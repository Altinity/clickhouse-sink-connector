package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseAutoCreateTable;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.converters.DebeziumConverter;
import com.altinity.clickhouse.sink.connector.metadata.TableMetaDataWriter;
import com.altinity.clickhouse.sink.connector.model.CdcRecordState;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.KafkaMetaData;
import com.clickhouse.client.ClickHouseCredentials;
import com.clickhouse.client.ClickHouseNode;
import com.google.common.io.BaseEncoding;
import io.debezium.time.*;
import io.debezium.time.Date;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Class that abstracts all functionality
 * related to interacting with Clickhouse DB.
 */
public class DbWriter extends BaseDbWriter {
    //ClickHouseNode server;
    private static final Logger log = LoggerFactory.getLogger(DbWriter.class);

    private final String tableName;
    // Map of column names to data types.
    private Map<String, String> columnNameToDataTypeMap = new LinkedHashMap<>();

    private final DBMetadata.TABLE_ENGINE engine;

    private final ClickHouseSinkConnectorConfig config;

    // CollapsingMergeTree
    private String signColumn = null;

    // ReplacingMergeTree
    private String versionColumn = null;

    // Delete column for ReplacingMergeTree
    private final String replacingMergeTreeDeleteColumn;

    public DbWriter(
            String hostName,
            Integer port,
            String database,
            String tableName,
            String userName,
            String password,
            ClickHouseSinkConnectorConfig config,
            ClickHouseStruct record
    )  {
        // Base class initiates connection using JDBC.
        super(hostName, port, database, userName, password, config);
        this.tableName = tableName;

        this.config = config;

        if (this.conn != null) {
            // Order of the column names and the data type has to match.
            this.columnNameToDataTypeMap = this.getColumnsDataTypesForTable(tableName);
        }

        DBMetadata metadata = new DBMetadata();
        MutablePair<DBMetadata.TABLE_ENGINE, String> response = metadata.getTableEngine(this.conn, database, tableName);
        this.engine = response.getLeft();

        //ToDO: Is this a reliable way of checking if the table exists already.
        if(this.engine == null) {
            if(this.config.getBoolean(ClickHouseSinkConnectorConfigVariables.AUTO_CREATE_TABLES)) {
                log.info("**** AUTO CREATE TABLE" + tableName);
                ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable();
                try {
                    act.createNewTable(record.getPrimaryKey(), tableName, record.getAfterModifiedFields().toArray(new Field[0]), this.conn);
                } catch (SQLException se) {
                    log.error("**** Error creating table ***" + tableName, se);
                }
            }
        }

        if(this.engine != null && this.engine.getEngine().equalsIgnoreCase(DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE.getEngine())) {
            this.versionColumn = response.getRight();
        } else if(this.engine != null && this.engine.getEngine().equalsIgnoreCase(DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE.getEngine())) {
            this.signColumn = response.getRight();
        }

        this.replacingMergeTreeDeleteColumn = this.config.getString(ClickHouseSinkConnectorConfigVariables.REPLACING_MERGE_TREE_DELETE_COLUMN);
    }

    /**
     * Function to check if the column is of DateTime64
     * from the column type(string name)
     *
     * @param columnType
     * @return true if its DateTime64, false otherwise.
     */
    public static boolean isColumnDateTime64(String columnType) {
        //ClickHouseDataType dt = ClickHouseDataType.of(columnType);
        //ToDo: Figure out a way to get the ClickHouseDataType
        // from column name.
        boolean result = false;
        if (columnType.contains("DateTime64")) {
            result = true;
        }
        return result;
    }

    /**
     * Function which has logic of choosing between before and after fields
     * based on CDC operation and Table Engine.
     *
     * @param engine
     * @return
     */
    public List<Field> getModifiedFieldsBasedOnTableEngine(DBMetadata.TABLE_ENGINE engine, ClickHouseStruct record) {
        List<Field> modifiedFields = null;
        if (record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.CREATE.getOperation())) {
            modifiedFields = record.getAfterModifiedFields();
        } else if (record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.DELETE.getOperation())) {
            modifiedFields = record.getBeforeModifiedFields();
        }

        return modifiedFields;
    }

    /**
     * Function
     *
     * @param operation
     * @return
     */
    public CdcRecordState getCdcSectionBasedOnOperation(ClickHouseConverter.CDC_OPERATION operation) {
        CdcRecordState state = CdcRecordState.CDC_RECORD_STATE_AFTER;

        if(operation == null || operation.getOperation() == null) {
            return state;
        }
        if (operation.getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.CREATE.getOperation()) ||
                operation.getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.READ.getOperation())) {
            state = CdcRecordState.CDC_RECORD_STATE_AFTER;
        } else if (operation.getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.DELETE.getOperation())) {
            state = CdcRecordState.CDC_RECORD_STATE_BEFORE;
        } else if (operation.getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.UPDATE.getOperation())) {
            state = CdcRecordState.CDC_RECORD_STATE_BOTH;
        }

        return state;
    }

    /**
     * Function to update the map of topic/partition to offset(max)
     * @param offsetToPartitionMap
     * @param partition
     * @param topic
     * @param offset
     */
    private void updatePartitionOffsetMap(Map<TopicPartition, Long> offsetToPartitionMap, int partition, String topic,
                                          long offset) {

        TopicPartition tp = new TopicPartition(topic, partition);

        // Check if record exists.
        if(!offsetToPartitionMap.containsKey(tp)) {
            // Record does not exist;
            offsetToPartitionMap.put(tp, offset);
        } else {
            // Record exists.
            // Update only if the current offset
            // is greater than the offset stored.
            long storedOffset = offsetToPartitionMap.get(tp);
            if(offset > storedOffset) {
                offsetToPartitionMap.put(tp, offset);
            }
        }
    }

    /**
     * Function to group the Query with records.
     *
     * @param records
     * @return
     */
    public Map<TopicPartition, Long> groupQueryWithRecords(ConcurrentLinkedQueue<ClickHouseStruct> records,
                                                                        Map<String, List<ClickHouseStruct>> queryToRecordsMap) {


        Map<TopicPartition, Long> partitionToOffsetMap = new HashMap<>();
        //HashMap<Integer, MutablePair<Long, Long>> partitionToOffsetMap = new HashMap<Integer, MutablePair<Long, Long>>();

        if (records.isEmpty()) {
            log.info("No Records to process");
            return partitionToOffsetMap;
        }

        long clickHouseInsertionTime = System.currentTimeMillis();
        // Co4 = {ClickHouseStruct@9220} de block to create a Map of Query -> list of records
        // so that all records belonging to the same  query
        // can be inserted as a batch.
        Iterator iterator = records.iterator();
        while (iterator.hasNext()) {
            ClickHouseStruct record = (ClickHouseStruct) iterator.next();

            updatePartitionOffsetMap(partitionToOffsetMap, record.getKafkaPartition(), record.getTopic(), record.getKafkaOffset());

            // Identify the min and max offsets of the bulk
            // that's inserted.
            int recordPartition = record.getKafkaPartition();


            if(CdcRecordState.CDC_RECORD_STATE_BEFORE == getCdcSectionBasedOnOperation(record.getCdcOperation())) {
                updateQueryToRecordsMap(record, record.getBeforeModifiedFields(), queryToRecordsMap);
            } else if(CdcRecordState.CDC_RECORD_STATE_AFTER == getCdcSectionBasedOnOperation(record.getCdcOperation())) {
                updateQueryToRecordsMap(record, record.getAfterModifiedFields(), queryToRecordsMap);
            } else if(CdcRecordState.CDC_RECORD_STATE_BOTH == getCdcSectionBasedOnOperation(record.getCdcOperation()))  {
                updateQueryToRecordsMap(record, record.getBeforeModifiedFields(), queryToRecordsMap);
                updateQueryToRecordsMap(record, record.getAfterModifiedFields(), queryToRecordsMap);
            } else {
                log.error("INVALID CDC RECORD STATE");
            }

            // Remove the record from shared records.
            iterator.remove();
        }
        return partitionToOffsetMap;
    }

    public void updateQueryToRecordsMap(ClickHouseStruct record, List<Field> modifiedFields,
                                        Map<String, List<ClickHouseStruct>> queryToRecordsMap) {
        String insertQueryTemplate = new QueryFormatter().getInsertQueryUsingInputFunction
                (this.tableName, modifiedFields, this.columnNameToDataTypeMap,
                        this.config.getBoolean(ClickHouseSinkConnectorConfigVariables.STORE_KAFKA_METADATA),
                        this.config.getBoolean(ClickHouseSinkConnectorConfigVariables.STORE_RAW_DATA),
                        this.config.getString(ClickHouseSinkConnectorConfigVariables.STORE_RAW_DATA_COLUMN),
                        this.signColumn, this.versionColumn, this.replacingMergeTreeDeleteColumn, this.engine);

        if (!queryToRecordsMap.containsKey(insertQueryTemplate)) {
            List<ClickHouseStruct> newList = new ArrayList<>();
            newList.add(record);
            queryToRecordsMap.put(insertQueryTemplate, newList);
        } else {
            List<ClickHouseStruct> recordsList = queryToRecordsMap.get(insertQueryTemplate);
            recordsList.add(record);
            queryToRecordsMap.put(insertQueryTemplate, recordsList);
        }
    }

    /**
     * Function that uses clickhouse-jdbc library
     * to insert records in bulk
     *
     * @param records Records to be inserted into clickhouse
     * @return Tuple of minimum and maximum kafka offset
     */
    public Map<TopicPartition, Long> insert(ConcurrentLinkedQueue<ClickHouseStruct> records) {

        Map<TopicPartition, Long> partitionToOffsetMap = new HashMap<TopicPartition, Long>();

//        BlockMetaData bmd = new BlockMetaData();
//        HashMap<Integer, MutablePair<Long, Long>> partitionToOffsetMap = new HashMap<Integer, MutablePair<Long, Long>>();

        if (records.isEmpty()) {
            log.info("No Records to process");
//            bmd.setPartitionToOffsetMap(partitionToOffsetMap);
            return partitionToOffsetMap;
        }

        Map<String, List<ClickHouseStruct>> queryToRecordsMap = new HashMap<>();

        // We are getting a subset of the records(Batch) to process.
        synchronized (records) {
            partitionToOffsetMap = groupQueryWithRecords(records, queryToRecordsMap);
        }
        addToPreparedStatementBatch(queryToRecordsMap);
        return partitionToOffsetMap;
    }

    /**
     * Function to iterate through records and add it to JDBC prepared statement
     * batch
     *
     * @param queryToRecordsMap
     */
    private void addToPreparedStatementBatch(Map<String, List<ClickHouseStruct>> queryToRecordsMap) {

        for (Map.Entry<String, List<ClickHouseStruct>> entry : queryToRecordsMap.entrySet()) {

            String insertQuery = entry.getKey();
            log.info("*** INSERT QUERY***" + insertQuery);
            // Create Hashmap of PreparedStatement(Query) -> Set of records
            // because the data will contain a mix of SQL statements(multiple columns)
            try (PreparedStatement ps = this.conn.prepareStatement(insertQuery)) {

                List<ClickHouseStruct> recordsList = entry.getValue();
                for (ClickHouseStruct record : recordsList) {
                    //List<Field> fields = record.getStruct().schema().fields();

                    //ToDO:
                    //insertPreparedStatement(ps, fields, record);

                    if(CdcRecordState.CDC_RECORD_STATE_BEFORE == getCdcSectionBasedOnOperation(record.getCdcOperation())) {
                        insertPreparedStatement(ps, record.getBeforeModifiedFields(), record, record.getBeforeStruct(), true);
                    } else if(CdcRecordState.CDC_RECORD_STATE_AFTER == getCdcSectionBasedOnOperation(record.getCdcOperation())) {
                        insertPreparedStatement(ps, record.getAfterModifiedFields(), record, record.getAfterStruct(), false);
                    } else if(CdcRecordState.CDC_RECORD_STATE_BOTH == getCdcSectionBasedOnOperation(record.getCdcOperation()))  {
                        if(this.engine.getEngine().equalsIgnoreCase(DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE.getEngine())) {
                            insertPreparedStatement(ps, record.getBeforeModifiedFields(), record, record.getBeforeStruct(), true);
                            ps.addBatch();
                        }
                        insertPreparedStatement(ps, record.getAfterModifiedFields(), record, record.getAfterStruct(), false);
                    } else {
                        log.error("INVALID CDC RECORD STATE");
                    }


                    // Append parameters to the query


                    ps.addBatch();
                    //records.remove(record);
                }


                // Issue the composed query: insert into mytable values(...)(...)...(...)
                // ToDo: The result of greater than or equal to zero means
                // the records were processed successfully.
                // but if any of the records were not processed successfully
                // How to we rollback or what action needs to be taken.
                int[] result = ps.executeBatch();

                // ToDo: Clear is not an atomic operation.
                //  It might delete the records that are inserted by the ingestion process.

            } catch (Exception e) {
                log.warn("insert Batch exception", e);
            }
        }
    }



    public void insertTopicOffsetMetadata(Map<TopicPartition, Long> topicPartitionToOffsetMap) {

//        try (PreparedStatement ps = this.conn.prepareStatement(insertQuery)) {
//
//        }
    }
    /**
     * Case-insensitive
     *
     * @param fields
     * @param colName
     * @return
     */
    private Field getFieldByColumnName(List<Field> fields, String colName) {
        // ToDo: Change it to a map so that multiple loops are avoided
        Field matchingField = null;
        for (Field f : fields) {
            if (f.name().equalsIgnoreCase(colName)) {
                matchingField = f;
                break;
            }
        }
        return matchingField;
    }

    /**
     * @param ps
     * @param fields
     * @param record
     */
    public void insertPreparedStatement(PreparedStatement ps, List<Field> fields,
                                        ClickHouseStruct record, Struct struct, boolean beforeSection) throws Exception {

        int index = 1;

        // Use this map's key natural ordering as the source of truth.
        //for (Map.Entry<String, String> entry : this.columnNameToDataTypeMap.entrySet()) {
        for (Field f : fields) {
            String colName = f.name();
            //String colName = entry.getKey();

            //ToDO: Setting null to a non-nullable field
            // will throw an error.
            // If the Received column is not a clickhouse column
            try {
                Object value = struct.get(colName);
                if (value == null) {
                    ps.setNull(index, Types.OTHER);
                    index++;
                    continue;
                }
            } catch (DataException e) {
                // Struct .get throws a DataException
                // if the field is not present.
                // If the record was not supplied, we need to set it as null.
                ps.setNull(index, Types.OTHER);
                index++;
                continue;
            }
            if (!this.columnNameToDataTypeMap.containsKey(colName)) {
                log.error(" ***** ERROR: Column:{} not found in ClickHouse", colName);
                continue;
            }
            //for (Map.Entry<String, String> entry : this.columnNameToDataTypeMap.entrySet()) {

            //ToDo: Map the Clickhouse types as a Enum.


            // Field f = getFieldByColumnName(fields, colName);
            Schema.Type type = f.schema().type();
            String schemaName = f.schema().name();
            Object value = struct.get(f);

            //TinyINT -> INT16 -> TinyInt
            boolean isFieldTinyInt = (type == Schema.INT16_SCHEMA.type());

            boolean isFieldTypeInt = (type == Schema.INT8_SCHEMA.type()) ||
                    (type == Schema.INT32_SCHEMA.type());

            boolean isFieldTypeFloat = (type == Schema.FLOAT32_SCHEMA.type()) ||
                    (type == Schema.FLOAT64_SCHEMA.type());


            // MySQL BigInt -> INT64
            boolean isFieldTypeBigInt = false;
            boolean isFieldTime = false;
            boolean isFieldDateTime = false;

            boolean isFieldTypeDecimal = false;

            // Decimal -> BigDecimal(JDBC)
            if (type == Schema.BYTES_SCHEMA.type() && (schemaName != null &&
                    schemaName.equalsIgnoreCase(Decimal.LOGICAL_NAME))) {
                isFieldTypeDecimal = true;
            }

            if (type == Schema.INT64_SCHEMA.type()) {
                // Time -> INT64 + io.debezium.time.MicroTime
                if (schemaName != null && schemaName.equalsIgnoreCase(MicroTime.SCHEMA_NAME)) {
                    isFieldTime = true;
                } else if ((schemaName != null && schemaName.equalsIgnoreCase(Timestamp.SCHEMA_NAME)) ||
                        (schemaName != null && schemaName.equalsIgnoreCase(MicroTimestamp.SCHEMA_NAME))) {
                    //DateTime -> INT64 + Timestamp(Debezium)
                    // MicroTimestamp ("yyyy-MM-dd HH:mm:ss")
                    isFieldDateTime = true;
                } else {
                    isFieldTypeBigInt = true;
                }
            }

            // Text columns
            if (type == Schema.Type.STRING) {
                if (schemaName != null && schemaName.equalsIgnoreCase(ZonedTimestamp.SCHEMA_NAME)) {
                    // MySQL(Timestamp) -> String, name(ZonedTimestamp) -> Clickhouse(DateTime)
                    ps.setString(index, DebeziumConverter.ZonedTimestampConverter.convert(value));

                } else {
                    ps.setString(index, (String) value);
                }
            } else if (isFieldTypeInt) {
                if (schemaName != null && schemaName.equalsIgnoreCase(Date.SCHEMA_NAME)) {
                    // Date field arrives as INT32 with schema name set to io.debezium.time.Date
                    ps.setDate(index, DebeziumConverter.DateConverter.convert(value));

                } else if (schemaName != null && schemaName.equalsIgnoreCase(Timestamp.SCHEMA_NAME)) {
                    ps.setTimestamp(index, (java.sql.Timestamp) value);
                } else {
                    ps.setInt(index, (Integer) value);
                }
            } else if (isFieldTypeFloat) {
                if (value instanceof Float) {
                    ps.setFloat(index, (Float) value);
                } else if (value instanceof Double) {
                    ps.setDouble(index, (Double) value);
                }
            } else if (type == Schema.BOOLEAN_SCHEMA.type()) {
                ps.setBoolean(index, (Boolean) value);
            } else if (isFieldTypeBigInt || isFieldTinyInt) {
                ps.setObject(index, value);
            } else if (isFieldDateTime || isFieldTime) {
                if (isFieldDateTime) {
                    if  (schemaName != null && schemaName.equalsIgnoreCase(MicroTimestamp.SCHEMA_NAME)) {
                        // Handle microtimestamp first
                        ps.setString(index, DebeziumConverter.MicroTimestampConverter.convert(value));
                    }
                    else if (value instanceof Long) {
                        ps.setString(index, DebeziumConverter.TimestampConverter.convert(value, isColumnDateTime64(colName)));
                    }
                } else if (isFieldTime) {
                    ps.setString(index, DebeziumConverter.MicroTimeConverter.convert(value));
                }
                // Convert this to string.
                // ps.setString(index, String.valueOf(value));
            } else if (isFieldTypeDecimal) {
                ps.setBigDecimal(index, (BigDecimal) value);
            } else if (type == Schema.Type.BYTES) {
                // Blob storage.
                if (value instanceof byte[]) {
                    String hexValue = new String((byte[]) value);
                    ps.setString(index, hexValue);
                } else if (value instanceof java.nio.ByteBuffer) {
                    ps.setString(index, BaseEncoding.base16().lowerCase().encode(((ByteBuffer) value).array()));
                }

            } else {
                log.error("Data Type not supported: {}", colName);
            }

            index++;

        }

        // Kafka metadata columns.
        for (KafkaMetaData metaDataColumn : KafkaMetaData.values()) {
            String metaDataColName = metaDataColumn.getColumn();
            if (this.config.getBoolean(ClickHouseSinkConnectorConfigVariables.STORE_KAFKA_METADATA)) {
                if (this.columnNameToDataTypeMap.containsKey(metaDataColName)) {
                    if (TableMetaDataWriter.addKafkaMetaData(metaDataColName, record, index, ps)) {
                        index++;
                    }
                }
            }
        }

        // Sign column.
        //String signColumn = this.config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_TABLE_SIGN_COLUMN);
        if(this.engine.getEngine() == DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE.getEngine() &&
        this.signColumn != null)
        if (this.columnNameToDataTypeMap.containsKey(signColumn)) {
            if (record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.DELETE.getOperation())) {
                ps.setInt(index, -1);
            } else if (record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.UPDATE.getOperation())){
                if(beforeSection == true) {
                    ps.setInt(index, - 1);
                } else {
                    ps.setInt(index, 1);
                }
            } else {
                ps.setInt(index, 1);
            }
            index++;
        }

        // Version column.
        //String versionColumn = this.config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_TABLE_VERSION_COLUMN);
        if(this.engine.getEngine() == DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE.getEngine() && this.versionColumn != null) {
            if (this.columnNameToDataTypeMap.containsKey(versionColumn)) {
                long currentTimeInMs = System.currentTimeMillis();
                //if (record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.UPDATE.getOperation()))
                {
                    ps.setLong(index, currentTimeInMs);
                    index++;
                }
            }
            // Sign column to mark deletes in ReplacingMergeTree
            if(this.replacingMergeTreeDeleteColumn != null && this.columnNameToDataTypeMap.containsKey(replacingMergeTreeDeleteColumn)) {
                if(record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.DELETE.getOperation())) {
                    ps.setInt(index, -1);
                } else {
                    ps.setInt(index, 1);
                }
                index++;
            }

        }

        // Store raw data in JSON form.
        if (this.config.getBoolean(ClickHouseSinkConnectorConfigVariables.STORE_RAW_DATA)) {
            String userProvidedColName = this.config.getString(ClickHouseSinkConnectorConfigVariables.STORE_RAW_DATA_COLUMN);
            if (this.columnNameToDataTypeMap.containsKey(userProvidedColName)) {

                TableMetaDataWriter.addRawData(struct, index, ps);
                index++;
            }
        }

    }

    /**
     * Function to retrieve Clickhouse http client Connection
     *
     * @return
     */
    private ClickHouseNode getHttpConnection() {
        ClickHouseCredentials credentials = ClickHouseCredentials.fromUserAndPassword("admin", "root");
        return ClickHouseNode.builder().credentials(credentials).database("test").port(8123).host("localhost").build();

    }

    /**
     * Function to insert records using Http Connection.
     */
    public void insertUsingHttpConnection() {

//        table = "test_hello2";
//        String insertQuery = MessageFormat.format("insert into {0} {1} values({2})",
//                table, "(id, message)", "1, 'hello'");
////        if(this.server != null) {
////            CompletableFuture<List<ClickHouseResponseSummary>> future = ClickHouseClient.send(this.server, insertQuery);
////            try {
////                future.get();
////            } catch (InterruptedException e) {
////                e.printStackTrace();
////            } catch (ExecutionException e) {
////                e.printStackTrace();
////            }
////        } else {
////            // Error .
////        }
    }
}

package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.common.Metrics;
import com.altinity.clickhouse.sink.connector.common.SnowFlakeId;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseDataTypeMapper;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseAlterTable;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseAutoCreateTable;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseCreateDatabase;
import com.altinity.clickhouse.sink.connector.metadata.TableMetaDataWriter;
import com.altinity.clickhouse.sink.connector.model.BlockMetaData;
import com.altinity.clickhouse.sink.connector.model.CdcRecordState;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.KafkaMetaData;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private DBMetadata.TABLE_ENGINE engine;

    private final ClickHouseSinkConnectorConfig config;

    // CollapsingMergeTree
    private String signColumn = null;

    // ReplacingMergeTree
    private String versionColumn = null;

    // Delete column for ReplacingMergeTree
    private String replacingMergeTreeDeleteColumn = null;

    /**
     * IMPORTANT: The logic to identify the new replacing mergetree
     * table which lets you specify the is_deleted column in
     * the CREATE TABLE DEFINITION and ClickHouse
     * will delete the rows where the is_deleted column is set to 1.
     */
    private boolean replacingMergeTreeWithIsDeletedColumn = false;


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

        try {
            if (this.conn != null) {
                // Order of the column names and the data type has to match.
                this.columnNameToDataTypeMap = this.getColumnsDataTypesForTable(tableName);
            }

            DBMetadata metadata = new DBMetadata();
            try {
                if (false == metadata.checkIfDatabaseExists(this.conn, database)) {
                    new ClickHouseCreateDatabase().createNewDatabase(this.conn, database);
                }
            } catch(Exception e) {
                log.error("Error creating Database", database);
            }
            MutablePair<DBMetadata.TABLE_ENGINE, String> response = metadata.getTableEngine(this.conn, database, tableName);
            this.engine = response.getLeft();

            long taskId = this.config.getLong(ClickHouseSinkConnectorConfigVariables.TASK_ID.toString());

            //ToDO: Is this a reliable way of checking if the table exists already.
            if (this.engine == null) {
                if (this.config.getBoolean(ClickHouseSinkConnectorConfigVariables.AUTO_CREATE_TABLES.toString())) {
                    log.info(String.format("**** Task(%s), AUTO CREATE TABLE (%s) *** ",taskId, tableName));
                    ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable();
                    try {
                        Field[] fields = null;
                        if(record.getAfterStruct() != null) {
                            fields = record.getAfterStruct().schema().fields().toArray(new Field[0]);
                        } else if(record.getBeforeStruct() != null) {
                            fields = record.getAfterStruct().schema().fields().toArray(new Field[0]);
                        }

                        act.createNewTable(record.getPrimaryKey(), tableName, fields, this.conn);
                        this.columnNameToDataTypeMap = this.getColumnsDataTypesForTable(tableName);
                        response = metadata.getTableEngine(this.conn, database, tableName);
                        this.engine = response.getLeft();
                    } catch (Exception e) {
                        log.error("**** Error creating table ***" + tableName, e);
                    }
                } else {
                    log.error("********* AUTO CREATE DISABLED, Table does not exist, please enable it by setting auto.create.tables=true");
                }
            }

            if (this.engine != null && this.engine.getEngine().equalsIgnoreCase(DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE.getEngine())) {
                String rmtColumns = response.getRight();
                if(rmtColumns != null && rmtColumns.contains(",")) {
                    // New RMT, with version and deleted column.
                    String[] rmtColumnArray = rmtColumns.split(",");
                    this.versionColumn = rmtColumnArray[0].trim();
                    this.replacingMergeTreeDeleteColumn = rmtColumnArray[1].trim();
                    replacingMergeTreeWithIsDeletedColumn = true;
                } else {
                    this.versionColumn = response.getRight();
                    this.replacingMergeTreeDeleteColumn = this.config.getString(ClickHouseSinkConnectorConfigVariables.REPLACING_MERGE_TREE_DELETE_COLUMN.toString());
                }

            } else if (this.engine != null && this.engine.getEngine().equalsIgnoreCase(com.altinity.clickhouse.sink.connector.db.DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE.getEngine())) {
                this.signColumn = response.getRight();
            }
        } catch(Exception e) {
            log.error("***** DBWriter error initializing ****", e);
        }
    }

    public boolean wasTableMetaDataRetrieved() {
        boolean result = true;

        if(this.engine == null || this.columnNameToDataTypeMap == null || this.columnNameToDataTypeMap.isEmpty()) {
            result = false;
        }

        return result;
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
     * Also this slices a chunk of records for processing
     * from the shared data structure(ConcurrentLinkedQueue<ClickHouseStruct>)
     * @param records
     * @return
     */
    public Map<TopicPartition, Long> groupQueryWithRecords(Collection<ClickHouseStruct> records,
                                                           Map<MutablePair<String, Map<String, Integer>>,
                                                                                List<ClickHouseStruct>> queryToRecordsMap) {


        Map<TopicPartition, Long> partitionToOffsetMap = new HashMap<>();
        //HashMap<Integer, MutablePair<Long, Long>> partitionToOffsetMap = new HashMap<Integer, MutablePair<Long, Long>>();

        if (records.isEmpty()) {
            log.debug("No Records to process");
            return partitionToOffsetMap;
        }

        long clickHouseInsertionTime = System.currentTimeMillis();
        // Co4 = {ClickHouseStruct@9220} de block to create a Map of Query -> list of records
        // so that all records belonging to the same  query
        // can be inserted as a batch.
        Iterator iterator = records.iterator();
        while (iterator.hasNext()) {
            ClickHouseStruct record = (ClickHouseStruct) iterator.next();

            if(record == null) {
                continue;
            }
            updatePartitionOffsetMap(partitionToOffsetMap, record.getKafkaPartition(), record.getTopic(), record.getKafkaOffset());


            boolean enableSchemaEvolution = this.config.getBoolean(ClickHouseSinkConnectorConfigVariables.ENABLE_SCHEMA_EVOLUTION.toString());

            boolean result = false;

            if(CdcRecordState.CDC_RECORD_STATE_BEFORE == getCdcSectionBasedOnOperation(record.getCdcOperation())) {
                result = updateQueryToRecordsMap(record, record.getBeforeModifiedFields(), queryToRecordsMap);
            } else if(CdcRecordState.CDC_RECORD_STATE_AFTER == getCdcSectionBasedOnOperation(record.getCdcOperation())) {
                if(enableSchemaEvolution) {
                    try {
                        alterTable(record.getAfterStruct().schema().fields());
                        this.columnNameToDataTypeMap = this.getColumnsDataTypesForTable(tableName);

                    } catch(Exception e) {
                        log.error("**** ERROR ALTER TABLE: " + tableName, e);
                    }
                }

                result = updateQueryToRecordsMap(record, record.getAfterModifiedFields(), queryToRecordsMap);
            } else if(CdcRecordState.CDC_RECORD_STATE_BOTH == getCdcSectionBasedOnOperation(record.getCdcOperation()))  {
                if(record.getBeforeModifiedFields() != null) {
                    result = updateQueryToRecordsMap(record, record.getBeforeModifiedFields(), queryToRecordsMap);
                }
                if(record.getAfterModifiedFields() != null) {
                    result = updateQueryToRecordsMap(record, record.getAfterModifiedFields(), queryToRecordsMap);
                }
            } else {
                log.error("INVALID CDC RECORD STATE");
            }

            // Remove the record from shared records.
            if(result) {
                iterator.remove();
            }
        }
        return partitionToOffsetMap;
    }

    public boolean updateQueryToRecordsMap(ClickHouseStruct record, List<Field> modifiedFields,
                                        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> queryToRecordsMap) {
        if(record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.TRUNCATE.getOperation())) {
            MutablePair<String, Map<String, Integer>> mp = new MutablePair<>();
            mp.setLeft(String.format("TRUNCATE TABLE %s", this.tableName));
            mp.setRight(new HashMap<String, Integer>());
            ArrayList<ClickHouseStruct> records = new ArrayList<>();
            records.add(record);
            queryToRecordsMap.put(mp, records);
            return true;
        }

        MutablePair<String, Map<String, Integer>>  response= new QueryFormatter().getInsertQueryUsingInputFunction
                (this.tableName, modifiedFields, this.columnNameToDataTypeMap,
                        this.config.getBoolean(ClickHouseSinkConnectorConfigVariables.STORE_KAFKA_METADATA.toString()),
                        this.config.getBoolean(ClickHouseSinkConnectorConfigVariables.STORE_RAW_DATA.toString()),
                        this.config.getString(ClickHouseSinkConnectorConfigVariables.STORE_RAW_DATA_COLUMN.toString()),
                        this.signColumn, this.versionColumn, this.replacingMergeTreeDeleteColumn, this.engine);



        String insertQueryTemplate = response.getKey();
        if(response.getKey() == null || response.getValue() == null) {
            log.error("********* QUERY or COLUMN TO INDEX MAP EMPTY");
            return false;
          //  this.columnNametoIndexMap = response.right;
        }

        MutablePair<String, Map<String, Integer>> mp = new MutablePair<>();
        mp.setLeft(insertQueryTemplate);
        mp.setRight(response.getValue());

        if (!queryToRecordsMap.containsKey(mp)) {
            List<ClickHouseStruct> newList = new ArrayList<>();
            newList.add(record);

            queryToRecordsMap.put(mp, newList);
        } else {
            List<ClickHouseStruct> recordsList = queryToRecordsMap.get(mp);
            recordsList.add(record);

            queryToRecordsMap.put(mp, recordsList);
        }

        return true;
    }

    /**
     *
     * @para
     * m modifiedFields
     */
    public void alterTable(List<Field> modifiedFields) {
        List<Field> missingFieldsInCH = new ArrayList<Field>();
        // Identify the columns that need to be added/removed in ClickHouse.
        for(Field f: modifiedFields) {
            String colName = f.name();

            if(this.columnNameToDataTypeMap.containsKey(colName) == false) {
                missingFieldsInCH.add(f);
            }
        }

        if(!missingFieldsInCH.isEmpty()) {
            log.info("***** ALTER TABLE ****");
            ClickHouseAlterTable cat = new ClickHouseAlterTable();
            Field[] missingFieldsArray = new Field[missingFieldsInCH.size()];
            missingFieldsInCH.toArray(missingFieldsArray);
            Map<String, String> colNameToDataTypeMap = cat.getColumnNameToCHDataTypeMapping(missingFieldsArray);

            if(!colNameToDataTypeMap.isEmpty()) {
                String alterTableQuery = cat.createAlterTableSyntax(this.tableName, colNameToDataTypeMap, ClickHouseAlterTable.ALTER_TABLE_OPERATION.ADD);
                log.info(" ***** ALTER TABLE QUERY **** " + alterTableQuery);

                try {
                    cat.runQuery(alterTableQuery, this.getConnection());
                } catch(Exception e) {
                    log.error(" **** ALTER TABLE EXCEPTION ", e);
                }
            }
        }
    }

    /**
     * Function that uses clickhouse-jdbc library
     * to insert records in bulk
     *
     * @param records Records to be inserted into clickhouse
     * @return Tuple of minimum and maximum kafka offset
     */
    public Map<TopicPartition, Long> insert(ConcurrentLinkedQueue<ClickHouseStruct> records,
                                            Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> queryToRecordsMap) {

        Map<TopicPartition, Long> partitionToOffsetMap = new HashMap<TopicPartition, Long>();

        if (records.isEmpty()) {
            log.debug("No Records to process");
            return partitionToOffsetMap;
        }

        // We are getting a subset of the records(Batch) to process.
        synchronized (records) {
            partitionToOffsetMap = groupQueryWithRecords(records, queryToRecordsMap);
        }
        return partitionToOffsetMap;
    }

    /**
     * Function to iterate through records and add it to JDBC prepared statement
     * batch
     *
     * @param queryToRecordsMap
     */
    public BlockMetaData addToPreparedStatementBatch(String topicName, Map<MutablePair<String, Map<String, Integer>>,
            List<ClickHouseStruct>> queryToRecordsMap, BlockMetaData bmd) throws SQLException {

        boolean success = false;

        Iterator<Map.Entry<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> iter = queryToRecordsMap.entrySet().iterator();
        while(iter.hasNext()) {
            Map.Entry<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> entry = iter.next();
            String insertQuery = entry.getKey().getKey();
            log.info("*** QUERY***" + insertQuery);
            // Create Hashmap of PreparedStatement(Query) -> Set of records
            // because the data will contain a mix of SQL statements(multiple columns)

            ArrayList<ClickHouseStruct> truncatedRecords = new ArrayList<>();
            try (PreparedStatement ps = this.conn.prepareStatement(insertQuery)) {

                List<ClickHouseStruct> recordsList = entry.getValue();
                for (ClickHouseStruct record : recordsList) {
                    try {
                        bmd.update(record);
                    } catch(Exception e) {
                        log.error("**** ERROR: updating Prometheus", e);
                    }

                    if(record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.TRUNCATE.getOperation())) {
                        truncatedRecords.add(record);
                        continue;
                    }

                    if(CdcRecordState.CDC_RECORD_STATE_BEFORE == getCdcSectionBasedOnOperation(record.getCdcOperation())) {
                        insertPreparedStatement(entry.getKey().right, ps, record.getBeforeModifiedFields(), record, record.getBeforeStruct(), true);
                    } else if(CdcRecordState.CDC_RECORD_STATE_AFTER == getCdcSectionBasedOnOperation(record.getCdcOperation())) {
                        insertPreparedStatement(entry.getKey().right, ps, record.getAfterModifiedFields(), record, record.getAfterStruct(), false);
                    } else if(CdcRecordState.CDC_RECORD_STATE_BOTH == getCdcSectionBasedOnOperation(record.getCdcOperation()))  {
                        if(this.engine != null && this.engine.getEngine().equalsIgnoreCase(DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE.getEngine())) {
                            insertPreparedStatement(entry.getKey().right, ps, record.getBeforeModifiedFields(), record, record.getBeforeStruct(), true);
                        }
                        insertPreparedStatement(entry.getKey().right, ps, record.getAfterModifiedFields(), record, record.getAfterStruct(), false);
                    } else {
                        log.error("INVALID CDC RECORD STATE");
                    }

                    ps.addBatch();
                }


                // Issue the composed query: insert into mytable values(...)(...)...(...)
                // ToDo: The result of greater than or equal to zero means
                // the records were processed successfully.
                // but if any of the records were not processed successfully
                // How to we rollback or what action needs to be taken.
                int[] result = ps.executeBatch();
                success = true;

                long taskId = this.config.getLong(ClickHouseSinkConnectorConfigVariables.TASK_ID.toString());
                log.info("*************** EXECUTED BATCH Successfully " + "Records: " + recordsList.size() + "************** task(" + taskId + ")"  + " Thread ID: " + Thread.currentThread().getName());

                // ToDo: Clear is not an atomic operation.
                //  It might delete the records that are inserted by the ingestion process.

            } catch (Exception e) {
                Metrics.updateErrorCounters(topicName, entry.getValue().size());
                log.error("******* ERROR inserting Batch *****************", e);
                success = false;
            }

            if(!truncatedRecords.isEmpty()) {

                PreparedStatement ps = this.conn.prepareStatement("TRUNCATE TABLE " + this.tableName);
                ps.execute();

                //this.conn.commit();
            }

            Metrics.updateCounters(topicName, entry.getValue().size());

            if(success) {
                iter.remove();
            }
        }

        return bmd;
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
    public void insertPreparedStatement(Map<String, Integer> columnNameToIndexMap, PreparedStatement ps, List<Field> fields,
                                        ClickHouseStruct record, Struct struct, boolean beforeSection) throws Exception {

       // int index = 1;
        // Use this map's key natural ordering as the source of truth.
        for (Map.Entry<String, String> entry : this.columnNameToDataTypeMap.entrySet()) {
        //for (Field f : fields) {
            String colName = entry.getKey();
            //String colName = f.name();

            if(colName == null) {
                continue;
            }
            if(columnNameToIndexMap == null) {
                log.error("Column Name to Index map error");
            }

                int index = -1;
            //int index = 1;
            if(true == columnNameToIndexMap.containsKey(colName)) {
                index = columnNameToIndexMap.get(colName);
            } else {
                log.error("***** Column index missing for column ****" + colName);
                continue;
            }

            //String colName = entry.getKey();

            //ToDO: Setting null to a non-nullable field
            // will throw an error.
            // If the Received column is not a clickhouse column
            try {
                Object value = struct.get(colName);
                if (value == null) {
                    ps.setNull(index, Types.OTHER);
                    continue;
                }
            } catch (DataException e) {
                // Struct .get throws a DataException
                // if the field is not present.
                // If the record was not supplied, we need to set it as null.
                ps.setNull(index, Types.OTHER);

                continue;
            }
            if (!this.columnNameToDataTypeMap.containsKey(colName)) {
                log.error(" ***** ERROR: Column:{} not found in ClickHouse", colName);
                continue;
            }
            //for (Map.Entry<String, String> entry : this.columnNameToDataTypeMap.entrySet()) {

            //ToDo: Map the Clickhouse types as a Enum.


            Field f = getFieldByColumnName(fields, colName);
            Schema.Type type = f.schema().type();
            String schemaName = f.schema().name();
            Object value = struct.get(f);

            if(false == ClickHouseDataTypeMapper.convert(type, schemaName, value, index, ps)) {
                log.error(String.format("**** DATA TYPE NOT HANDLED type(%s), name(%s), column name(%s)", type.toString(),
                        schemaName, colName));
            }
        }

        // Kafka metadata columns.
        for (KafkaMetaData metaDataColumn : KafkaMetaData.values()) {
            String metaDataColName = metaDataColumn.getColumn();
            if (this.config.getBoolean(ClickHouseSinkConnectorConfigVariables.STORE_KAFKA_METADATA.toString())) {
                if (this.columnNameToDataTypeMap.containsKey(metaDataColName)) {
                    if(columnNameToIndexMap != null && columnNameToIndexMap.containsKey(metaDataColName)) {
                        TableMetaDataWriter.addKafkaMetaData(metaDataColName, record, columnNameToIndexMap.get(metaDataColName), ps);
                    }
                }
            }
        }

        // Sign column.
        //String signColumn = this.config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_TABLE_SIGN_COLUMN);
        if(this.engine != null && this.engine.getEngine() == DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE.getEngine() &&
        this.signColumn != null)
        if (this.columnNameToDataTypeMap.containsKey(signColumn) && columnNameToIndexMap.containsKey(signColumn)) {
            int signColumnIndex = columnNameToIndexMap.get(signColumn);
            if (record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.DELETE.getOperation())) {
                ps.setInt(signColumnIndex, -1);
            } else if (record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.UPDATE.getOperation())){
                if(beforeSection == true) {
                    ps.setInt(signColumnIndex, - 1);
                } else {
                    ps.setInt(signColumnIndex, 1);
                }
            } else {
                ps.setInt(signColumnIndex, 1);
            }

        }

        // Version column.
        //String versionColumn = this.config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_TABLE_VERSION_COLUMN);
        if(this.engine != null && this.engine.getEngine() == DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE.getEngine() && this.versionColumn != null) {
            if (this.columnNameToDataTypeMap.containsKey(versionColumn)) {
                long currentTimeInMs = System.currentTimeMillis();
                //if (record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.UPDATE.getOperation()))
                {
                    //ps.setLong(columnNameToIndexMap.get(versionColumn), record.getTs_ms());
                    if(columnNameToIndexMap.containsKey(versionColumn)) {
                        if (record.getGtid() != -1) {
                            if(this.config.getBoolean(ClickHouseSinkConnectorConfigVariables.SNOWFLAKE_ID.toString())) {
                                ps.setLong(columnNameToIndexMap.get(versionColumn), SnowFlakeId.generate(record.getTs_ms(), record.getGtid()));
                            } else {
                                ps.setLong(columnNameToIndexMap.get(versionColumn), record.getGtid());
                            }
                        } else {
                            ps.setLong(columnNameToIndexMap.get(versionColumn), record.getTs_ms());
                        }
                    }
                }
            }
            // Sign column to mark deletes in ReplacingMergeTree
            if(this.replacingMergeTreeDeleteColumn != null && this.columnNameToDataTypeMap.containsKey(replacingMergeTreeDeleteColumn)) {
                if(columnNameToIndexMap.containsKey(replacingMergeTreeDeleteColumn)) {
                    if (record.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.DELETE.getOperation())) {
                        if(replacingMergeTreeWithIsDeletedColumn)
                            ps.setInt(columnNameToIndexMap.get(replacingMergeTreeDeleteColumn), 1);
                        else
                            ps.setInt(columnNameToIndexMap.get(replacingMergeTreeDeleteColumn), -1);
                    } else {
                        if(replacingMergeTreeWithIsDeletedColumn)
                            ps.setInt(columnNameToIndexMap.get(replacingMergeTreeDeleteColumn), 0);
                        else
                            ps.setInt(columnNameToIndexMap.get(replacingMergeTreeDeleteColumn), 1);
                    }
                }
            }
        }

        // Store raw data in JSON form.
        if (this.config.getBoolean(ClickHouseSinkConnectorConfigVariables.STORE_RAW_DATA.toString())) {
            String userProvidedColName = this.config.getString(ClickHouseSinkConnectorConfigVariables.STORE_RAW_DATA_COLUMN.toString());
            String rawDataColumnDataType = this.columnNameToDataTypeMap.get(userProvidedColName);
            if (this.columnNameToDataTypeMap.containsKey(userProvidedColName) &&  rawDataColumnDataType.contains("String")) {
                if(columnNameToIndexMap.containsKey(userProvidedColName)) {
                    TableMetaDataWriter.addRawData(struct, columnNameToIndexMap.get(userProvidedColName), ps);
                }
            }
        }
    }
}

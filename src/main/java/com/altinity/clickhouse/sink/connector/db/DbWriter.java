package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.converters.DebeziumConverter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.clickhouse.client.ClickHouseCredentials;
import com.clickhouse.client.ClickHouseNode;
import com.clickhouse.jdbc.ClickHouseConnection;
import com.clickhouse.jdbc.ClickHouseDataSource;
import com.google.common.io.BaseEncoding;
import io.debezium.time.Date;
import io.debezium.time.MicroTime;
import io.debezium.time.Timestamp;
import io.debezium.time.ZonedTimestamp;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.errors.DataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Class that abstracts all functionality
 * related to interacting with Clickhouse DB.
 */
public class DbWriter {
    //ClickHouseNode server;
    ClickHouseConnection conn;
    private static final Logger log = LoggerFactory.getLogger(DbWriter.class);

    private String tableName;
    // Map of column names to data types.
    private Map<String, String> columnNameToDataTypeMap = new LinkedHashMap<>();

    private ClickHouseSinkConnectorConfig config;

    public DbWriter(
            String hostName,
            Integer port,
            String database,
            String tableName,
            String userName,
            String password,
            ClickHouseSinkConnectorConfig config
    ) {
        this.tableName = tableName;

        this.config = config;

        String connectionUrl = getConnectionString(hostName, port, database);
        this.createConnection(connectionUrl, "Agent_1", userName, password);

        if (this.conn != null) {
            // Order of the column names and the data type has to match.
            this.columnNameToDataTypeMap = this.getColumnsDataTypesForTable(tableName);
        }
    }

    public String getConnectionString(String hostName, Integer port, String database) {
        return String.format("jdbc:clickhouse://%s:%s/%s", hostName, port, database);
    }

    /**
     * Function to create Connection using the JDBC Driver
     *
     * @param url        url with the JDBC format jdbc:ch://localhost/test
     * @param clientName Client Name
     * @param userName   UserName
     * @param password   Password
     */
    public void createConnection(String url, String clientName, String userName, String password) {
        try {
            Properties properties = new Properties();
            properties.setProperty("client_name", clientName);
            ClickHouseDataSource dataSource = new ClickHouseDataSource(url, properties);

            this.conn = dataSource.getConnection(userName, password);
        } catch (Exception e) {
            log.warn("Error creating SQL connection" + e);
        }
    }


    /**
     * Function to check if the column is of DateTime64
     * from the column type(string name)
     * @param columnType
     * @return true if its DateTime64, false otherwise.
     */
    public static boolean isColumnDateTime64(String columnType){
        //ClickHouseDataType dt = ClickHouseDataType.of(columnType);
        //ToDo: Figure out a way to get the ClickHouseDataType
        // from column name.
        boolean result = false;
        if(columnType.contains("DateTime64")){
            result = true;
        }
        return result;
    }
    /**
     * Function that uses the DatabaseMetaData JDBC functionality
     * to get the column name and column data type as key/value pair.
     */
    public Map<String, String> getColumnsDataTypesForTable(String tableName) {

        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        try {
            if(this.conn == null) {
                log.error("Error with DB connection");
                return result;
            }

            ResultSet columns = this.conn.getMetaData().getColumns(null, null,
                    tableName, null);
            while (columns.next()) {
                String columnName = columns.getString("COLUMN_NAME");
                String typeName = columns.getString("TYPE_NAME");

                Object dataType = columns.getString("DATA_TYPE");
                String columnSize = columns.getString("COLUMN_SIZE");
                String isNullable = columns.getString("IS_NULLABLE");
                String isAutoIncrement = columns.getString("IS_AUTOINCREMENT");

                result.put(columnName, typeName);
            }
        } catch (SQLException sq) {
            log.error("Exception retrieving Column Metadata", sq);
        }
        return result;
    }

    /**
     * Function that uses clickhouse-jdbc library
     * to insert records in bulk
     * @param records Records to be inserted into clickhouse
     * @return Tuple of minimum and maximum kafka offset
     */
    public HashMap<Integer, MutablePair<Long, Long>> insert(ConcurrentLinkedQueue<ClickHouseStruct> records) {

        HashMap<Integer, MutablePair<Long, Long>> partitionToOffsetMap = new HashMap<Integer, MutablePair<Long, Long>>();

        if (records.isEmpty()) {
            log.info("No Records to process");
            return partitionToOffsetMap;
        }

        // Get the first record to get length of columns
        //ToDo: This wont work where there are fields with different lengths.
        //ToDo: It will happens with alter table and add columns
        //String insertQueryTemplate = this.getInsertQuery(tableName, peekRecord.schema().fields().size());

        // Code block to create a Map of Query -> list of records
        // so that all records belonging to the same  query
        // can be inserted as a batch.
        Map<String, List<ClickHouseStruct>> queryToRecordsMap = new HashMap<String, List<ClickHouseStruct>>();
        Iterator iterator = records.iterator();
        while (iterator.hasNext()) {
            ClickHouseStruct record = (ClickHouseStruct) iterator.next();

            long minOffset = 0;
            long maxOffset = 0;

            // Identify the min and max offsets of the bulk
            // thats inserted.
            int recordPartition = record.getKafkaPartition();
            if(partitionToOffsetMap.containsKey(recordPartition)) {
                MutablePair<Long, Long> offsetsPair = partitionToOffsetMap.get(recordPartition);
                minOffset = offsetsPair.left;
                maxOffset = offsetsPair.right;
            }

            boolean offsetUpdated = false;
            if(record.getKafkaOffset() < minOffset) {
                minOffset = record.getKafkaOffset();
                offsetUpdated = true;
            }

            if(record.getKafkaOffset() > maxOffset) {
                maxOffset = record.getKafkaOffset();
                offsetUpdated = true;
            }

            if(true == offsetUpdated) {
                if(minOffset == 0) {
                    partitionToOffsetMap.put(recordPartition, new MutablePair<>(record.getKafkaOffset(), maxOffset));
                } else {
                    partitionToOffsetMap.put(recordPartition, new MutablePair<>(minOffset, maxOffset));
                }
            }

            String insertQueryTemplate = new QueryFormatter().getInsertQueryUsingInputFunction
                    (this.tableName, this.columnNameToDataTypeMap);

            if (false == queryToRecordsMap.containsKey(insertQueryTemplate)) {
                List<ClickHouseStruct> newList = new ArrayList<ClickHouseStruct>();
                newList.add(record);
                queryToRecordsMap.put(insertQueryTemplate, newList);
            } else {
                List<ClickHouseStruct> recordsList = queryToRecordsMap.get(insertQueryTemplate);
                recordsList.add(record);
                queryToRecordsMap.put(insertQueryTemplate, recordsList);
            }
        }

        for (Map.Entry<String, List<ClickHouseStruct>> entry : queryToRecordsMap.entrySet()) {

            String insertQuery = entry.getKey();
            // Create Hashmap of PreparedStatement(Query) -> Set of records
            // because the data will contain a mix of SQL statements(multiple columns)
            try (PreparedStatement ps = this.conn.prepareStatement(insertQuery)) {

                List<ClickHouseStruct> recordsList = entry.getValue();
                for (ClickHouseStruct record : recordsList) {
                    List<Field> fields = record.getStruct().schema().fields();

                    //ToDO:
                    insertPreparedStatement(ps, fields, record);
                    //insertPreparedStatement(ps, record.getModifiedFields(), record);
                    // Append parameters to the query
                    ps.addBatch();
                }


                // Issue the composed query: insert into mytable values(...)(...)...(...)
                // ToDo: The result of greater than or equal to zero means
                // the records were processed successfully.
                // but if any of the records were not processed successfully
                // How to we rollback or what action needs to be taken.
                int[] result = ps.executeBatch();

                // ToDo: Clear is not an atomic operation.
                //  It might delete the records that are inserted by the ingestion process.
                records.clear();
            } catch (Exception e) {
                log.warn("insert Batch exception", e);
            }
        }

        return partitionToOffsetMap;
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
    public void insertPreparedStatement(PreparedStatement ps, List<Field> fields, ClickHouseStruct record) throws Exception {

        int index = 1;

        // Use this map's key natural ordering as the source of truth.
        for (Map.Entry<String, String> entry : this.columnNameToDataTypeMap.entrySet()) {
        //for(Field f: fields) {

            //String colName = f.name();
            String colName = entry.getKey();

            // Kafka metdata columns.
            if (this.config.getBoolean(ClickHouseSinkConnectorConfigVariables.STORE_KAFKA_METADATA) == true) {
               if (true == ClickHouseTableMetaData.addKafkaMetaData(colName, record, index, ps)) {
                   index++;
                   continue;
               }
            }

            // Store raw data in JSON form.
            if(this.config.getBoolean(ClickHouseSinkConnectorConfigVariables.STORE_RAW_DATA) == true) {
                if(colName.equalsIgnoreCase(this.config.getString(ClickHouseSinkConnectorConfigVariables.STORE_RAW_DATA_COLUMN))) {
                    ClickHouseTableMetaData.addRawData(colName, record, index, ps);
                    index++;
                    continue;
                }
            }

            //ToDO: Setting null to a non-nullable field
            // will throw an error.
            // If the Received column is not a clickhouse column
            try {
                Object value = record.getStruct().get(colName);
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
            if (false == this.columnNameToDataTypeMap.containsKey(colName)) {
                log.error(" ***** ERROR: Column:{} not found in ClickHouse", colName);
                continue;
            }
            //for (Map.Entry<String, String> entry : this.columnNameToDataTypeMap.entrySet()) {

            //ToDo: Map the Clickhouse types as a Enum.


            Field f = getFieldByColumnName(fields, colName);
            Schema.Type type = f.schema().type();
            String schemaName = f.schema().name();
            Object value = record.getStruct().get(f);

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
                } else if (schemaName != null && schemaName.equalsIgnoreCase(Timestamp.SCHEMA_NAME)) {
                    //DateTime -> INT64 + Timestamp(Debezium)
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
                if (true == value instanceof Float) {
                    ps.setFloat(index, (Float) value);
                } else if (true == value instanceof Double) {
                    ps.setDouble(index, (Double) value);
                }
            } else if (type == Schema.BOOLEAN_SCHEMA.type()) {
                ps.setBoolean(index, (Boolean) value);
            } else if (isFieldTypeBigInt || isFieldTinyInt) {
                ps.setObject(index, value);
            } else if (isFieldDateTime || isFieldTime) {
                if (isFieldDateTime) {
                    if (value instanceof Long) {
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
                } else if(value instanceof java.nio.ByteBuffer) {
                    ps.setString(index, BaseEncoding.base16().lowerCase().encode(((ByteBuffer) value).array()));
                }

            } else {
                log.error("Data Type not supported: {}", colName);
            }

            index++;
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

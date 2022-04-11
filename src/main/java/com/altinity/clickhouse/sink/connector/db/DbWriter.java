package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.clickhouse.client.ClickHouseCredentials;
import com.clickhouse.client.ClickHouseDataType;
import com.clickhouse.client.ClickHouseNode;
import com.clickhouse.jdbc.ClickHouseConnection;
import com.clickhouse.jdbc.ClickHouseDataSource;
import io.debezium.time.Time;
import io.debezium.time.Timestamp;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;

import io.debezium.time.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

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
    String insertQueryUsingInputFunction;

    public DbWriter(String hostName, Integer port, String database, String tableName, String userName, String password) {
        this.tableName = tableName;

        String connectionUrl = getConnectionString(hostName, port, database);
        this.createConnection(connectionUrl, "Agent_1", userName, password);

        if (this.conn != null) {
            // Order of the column names and the data type has to match.
            this.columnNameToDataTypeMap = this.getColumnsDataTypesForTable(tableName);
            this.insertQueryUsingInputFunction = this.getInsertQueryUsingInputFunction(tableName);
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
     * Formatter for SQL 'Insert' query with placeholders for values
     * insert into <table name> values(?, ?, ?)
     *
     * @param tableName Table Name
     * @param numFields Number of fields with placeholders
     * @return
     */
    public String getInsertQuery(String tableName, int numFields) {
        StringBuffer insertQuery = new StringBuffer()
                .append("insert into ")
                .append(tableName)
                .append(" values(");
        for (int i = 0; i < numFields; i++) {
            insertQuery.append("?");
            if (i == numFields - 1) {
                insertQuery.append(")");
            } else {
                insertQuery.append(",");
            }
        }
        return insertQuery.toString();
    }

    /**
     * Function to construct an INSERT query using input functions.
     *
     * @param tableName Table Name
     * @return Insert query using Input function.
     */
    public String getInsertQueryUsingInputFunction(String tableName) {
        // "insert into mytable select col1, col2 from input('col1 String, col2 DateTime64(3), col3 Int32')"))

        StringBuffer colNamesDelimited = new StringBuffer();
        StringBuffer colNamesToDataTypes = new StringBuffer();

        for (Map.Entry<String, String> entry : this.columnNameToDataTypeMap.entrySet()) {
            colNamesDelimited.append(entry.getKey()).append(",");
            colNamesToDataTypes.append(entry.getKey()).append(" ").append(entry.getValue()).append(",");
        }

        //Remove terminating comma
        colNamesDelimited.deleteCharAt(colNamesDelimited.lastIndexOf(","));
        colNamesToDataTypes.deleteCharAt(colNamesToDataTypes.lastIndexOf(","));

        return String.format("insert into %s select %s from input('%s')", tableName, colNamesDelimited, colNamesToDataTypes);
    }

    /**
     * Function that uses the DatabaseMetaData JDBC functionality
     * to get the column name and column data type as key/value pair.
     */
    public Map<String, String> getColumnsDataTypesForTable(String tableName) {

        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        try {

            ResultSet columns = this.conn.getMetaData().getColumns(null, null,
                    tableName, null);
            while (columns.next()) {
                String columnName = columns.getString("COLUMN_NAME");
                String typeName = columns.getString("TYPE_NAME");

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
     * Creates INSERT statement and runs it over connection
     *
     * @param records
     */
    public void insert(ConcurrentLinkedQueue<Struct> records) {

        if (records.isEmpty()) {
            log.info("No Records to process");
            return;
        }

        // Get the first record to get length of columns
        //ToDo: This wont work where there are fields with different lengths.
        //ToDo: It will happens with alter table and add columns
        Struct peekRecord = records.peek();
        //String insertQueryTemplate = this.getInsertQuery(tableName, peekRecord.schema().fields().size());
        String insertQueryTemplate = this.getInsertQueryUsingInputFunction(this.tableName);
        try (PreparedStatement ps = this.conn.prepareStatement(insertQueryTemplate)) {

            Iterator iterator = records.iterator();
            while (iterator.hasNext()) {
                Struct record = (Struct) iterator.next();
                List<Field> fields = record.schema().fields();


                insertPreparedStatement(ps, fields, record);
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
            log.warn("insert Batch exception" + e);
        }
    }

    private Field getFieldByColumnName(List<Field> fields, String colName) {
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
    private void insertPreparedStatement(PreparedStatement ps, List<Field> fields, Struct record) throws SQLException {

        int index = 1;

        // Use this map's key natural ordering as the source of truth.
        for (Map.Entry<String, String> entry : this.columnNameToDataTypeMap.entrySet()) {

            // Column Name
            String colName = entry.getKey();

            Field field = getFieldByColumnName(fields, colName);

            Schema.Type type = field.schema().type();
            String schemaName = field.schema().name();
            Object value = record.get(field);

            //TinyINT -> INT16 -> TinyInt
            boolean isFieldTinyInt = (type == Schema.INT16_SCHEMA.type());

            boolean isFieldTypeInt = (type == Schema.INT8_SCHEMA.type()) ||
                    (type == Schema.INT32_SCHEMA.type());

            boolean isFieldTypeFloat = (type == Schema.FLOAT32_SCHEMA.type()) ||
                    (type == Schema.FLOAT64_SCHEMA.type());

            // DateTime -> INT64 + Timestamp(Debezium)
//                    boolean isFieldDateTime = (type == Schema.INT64_SCHEMA.type() &&
//                            schemaName.equalsIgnoreCase(Timestamp.SCHEMA_NAME));

            // MySQL BigInt -> INT64
            boolean isFieldTypeBigInt = (type == Schema.INT64_SCHEMA.type());

            // Text columns
            if (type == Schema.Type.STRING) {
                ps.setString(index, (String) value);
            } else if (isFieldTypeInt) {
                if (schemaName != null && schemaName.equalsIgnoreCase(Date.SCHEMA_NAME)) {
                    // Date field arrives as INT32 with schema name set to io.debezium.time.Date
                    long msSinceEpoch = TimeUnit.DAYS.toMillis((Integer) value);
                    java.util.Date date = new java.util.Date(msSinceEpoch);
                    java.sql.Date sqlDate = new java.sql.Date(date.getTime());
                    ps.setDate(index, sqlDate);

                } else if (schemaName != null && schemaName.equalsIgnoreCase(Timestamp.SCHEMA_NAME)) {
                    ps.setTimestamp(index, (java.sql.Timestamp) value);
                } else {
                    ps.setInt(index, (Integer) value);
                }
            } else if (isFieldTypeFloat) {
                ps.setFloat(index, (Float) value);
            } else if (type == Schema.BOOLEAN_SCHEMA.type()) {
                ps.setBoolean(index, (Boolean) value);
            } else if (isFieldTypeBigInt || isFieldTinyInt) {
                ps.setObject(index, value);
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

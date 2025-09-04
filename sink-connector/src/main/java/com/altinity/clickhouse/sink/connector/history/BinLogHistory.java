package com.altinity.clickhouse.sink.connector.history;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.QueryFormatter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class BinLogHistory {

    public static final String CREATE_TABLE = "CREATE TABLE";
    public static final String NULL = "NULL";
    public static final String NOT_NULL = "NOT NULL";
    public static final String ORDER_BY = "ORDER BY";
    public static final String ORDER_BY_TUPLE = "ORDER BY tuple()";
    public static final String ENGINE_MERGE_TREE = "ENGINE = MergeTree()";
    public static final String PARTITION_BY = " PARTITION BY toDate(`";
    public static final String TTL_PREFIX = " TTL toDate(`";
    public static final String TO_INTERVAL_DAY = "`) + toIntervalDay(";

    public static final String GTID_COLUMN = "gtid";
    public static final String GTID_COLUMN_DATA_TYPE = "String";
    public static final String DDL_COLUMN = "ddl";
    public static final String DDL_COLUMN_DATA_TYPE = "String";

    public static final String DATABASE_COLUMN = "database";
    public static final String DATABASE_COLUMN_DATA_TYPE = "String";
    public static final String TABLE_COLUMN = "table";
    public static final String TABLE_COLUMN_DATA_TYPE = "String";
    public static final String BEFORE_COLUMN = "before";
    public static final String AFTER_COLUMN = "after";
    public static final String RAW_COLUMN = "_raw";
    public static final String RAW_COLUMN_DATA_TYPE = "String";
    public static final String TIME_COLUMN = "_time";
    public static final String TIME_COLUMN_DATA_TYPE = "UInt64";
    public static final String IS_DELETED_COLUMN = "is_deleted";
    public static final String IS_DELETED_COLUMN_DATA_TYPE = "UInt8";
    public static final String OPERATION_COLUMN = "operation";
    public static final String OPERATION_COLUMN_DATA_TYPE = "String";
    public static final String VERSION_COLUMN = "_version";
    public static final String VERSION_COLUMN_DATA_TYPE = "UInt64";
    public static final String HOST_COLUMN = "host";
    public static final String HOST_COLUMN_DATA_TYPE = "String";
    public static final String LOGFILE_COLUMN = "logfile";
    public static final String LOGFILE_COLUMN_DATA_TYPE = "String";
    public static final String POSITION_COLUMN = "position";
    public static final String POSITION_COLUMN_DATA_TYPE = "UInt64";
    public static final String PRIMARY_HOST_COLUMN = "primary_host";
    public static final String PRIMARY_HOST_COLUMN_DATA_TYPE = "String";

    public static final Map<String, String> HISTORY_COLUMNS = new LinkedHashMap<String, String>() {{
        put(GTID_COLUMN, GTID_COLUMN_DATA_TYPE);
        put(DATABASE_COLUMN, DATABASE_COLUMN_DATA_TYPE);
        put(TABLE_COLUMN, TABLE_COLUMN_DATA_TYPE);
        put(DDL_COLUMN, DDL_COLUMN_DATA_TYPE);
        put(BEFORE_COLUMN, TABLE_COLUMN_DATA_TYPE);
        put(AFTER_COLUMN, TABLE_COLUMN_DATA_TYPE);
        put(RAW_COLUMN, RAW_COLUMN_DATA_TYPE);
        put(TIME_COLUMN, TIME_COLUMN_DATA_TYPE);
        put(IS_DELETED_COLUMN, IS_DELETED_COLUMN_DATA_TYPE);
        put(OPERATION_COLUMN, OPERATION_COLUMN_DATA_TYPE);
        put(VERSION_COLUMN, VERSION_COLUMN_DATA_TYPE);
        put(HOST_COLUMN, HOST_COLUMN_DATA_TYPE);
        put(LOGFILE_COLUMN, LOGFILE_COLUMN_DATA_TYPE);
        put(POSITION_COLUMN, POSITION_COLUMN_DATA_TYPE);
        put(PRIMARY_HOST_COLUMN, PRIMARY_HOST_COLUMN_DATA_TYPE);
    }};

    // get column to data type map
    public Map<String, String> getColumnToDataTypeMap() {
        return HISTORY_COLUMNS;
    }

    /**
     * Builds the CREATE TABLE SQL syntax for a history table,
     * adding CDC metadata columns for database, table, raw payload,
     * time, operation, host, logfile, position, and primary host.
     *
     * @param historyTableName name of the history table to create
     * @param databaseName name of the database in which the history table is created
     * @param ttlDays number of days for TTL retention
     * @return SQL statement string for creating the history table
     */
    public String createHistoryTableSyntax(
                                           String historyTableName,
                                           String databaseName,
                                           int ttlDays) {

        StringBuilder sb = new StringBuilder();
        sb.append(CREATE_TABLE)
                .append(' ').append(databaseName)
                .append(".`").append(historyTableName).append("`(");

        // Iterate through all history columns (LinkedHashMap preserves insertion order)
        String columnDefinitions = HISTORY_COLUMNS.entrySet().stream()
                .map(entry -> "`" + entry.getKey() + "` " + entry.getValue())
                .collect(Collectors.joining(","));
        sb.append(columnDefinitions);
        
        sb.append(") ").append(ENGINE_MERGE_TREE);

        // ORDER BY gtid
        sb.append(" ").append(ORDER_BY).append(" `").append(GTID_COLUMN).append("`");
        // Add partition by toDate(_time)
        sb.append(PARTITION_BY).append(TIME_COLUMN).append("`)");
        // Add TTL toDate(_time) + toIntervalDay(ttlDays)
        sb.append(TTL_PREFIX).append(TIME_COLUMN).append(TO_INTERVAL_DAY).append(ttlDays).append(")");
        sb.append(";");
        // Add tl_only_drop_parts=1
        //sb.append(" ttl_only_drop_parts=1");

        /**
         * partition by toDate(_time)
         * TTL toDate(_time) + toIntervalDay(30)
         * tl_only_drop_parts=1
         */
        return sb.toString();
    }

    /**
     * Adds records to the history table using QueryFormatter to generate the insert query.
     *
     * @param currentBatch list of ClickHouseStruct objects to insert into history table
     */
    public static void addRecordsToHistoryTable(String historyTableName, Connection conn, List<ClickHouseStruct> currentBatch) throws SQLException {
        if (currentBatch == null || currentBatch.isEmpty()) {
            return;
        }

        BinLogHistory binLogHistory = new BinLogHistory();
        QueryFormatter queryFormatter = new QueryFormatter();
        
        // Get the column to data type mapping
        Map<String, String> columnToDataTypeMap = binLogHistory.getColumnToDataTypeMap();
        
        // Generate insert query using QueryFormatter
        String insertQuery = queryFormatter.getInsertQueryUsingInputFunction(historyTableName, columnToDataTypeMap);
        
        binLogHistory.executeInsertWithStructs(conn, insertQuery, currentBatch);
    }

    /**
     * Creates and executes a PreparedStatement for inserting ClickHouseStruct data
     * into a history table using the input() function pattern.
     *
     * @param conn database connection
     * @param insertSql the SQL insert query with input() function
     * @param clickHouseStructs list of ClickHouseStruct objects to insert
     * @throws SQLException if database operation fails
     */
    public void executeInsertWithStructs(Connection conn, String insertSql, List<ClickHouseStruct> clickHouseStructs) throws SQLException {
        System.out.println("DEBUG: Insert SQL: " + insertSql);
        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            for (ClickHouseStruct struct : clickHouseStructs) {
                int paramIndex = 1;
                // Set values based on the HISTORY_COLUMNS mapping
                for (Map.Entry<String, String> entry : HISTORY_COLUMNS.entrySet()) {
                    String columnName = entry.getKey();
                    Object value = getValueFromStruct(struct, columnName);
                    System.out.println("DEBUG: Setting param " + paramIndex + " for column " + columnName + " to value: " + value);
                    ps.setObject(paramIndex++, value);
                }
                ps.addBatch();
            }
            
            ps.executeBatch();
        }
    }

    /**
     * Extracts the appropriate value from ClickHouseStruct based on column name.
     *
     * @param struct the ClickHouseStruct object
     * @param columnName name of the column to extract
     * @return the value for the specified column
     */
    private Object getValueFromStruct(ClickHouseStruct struct, String columnName) {
        switch (columnName) {
            case GTID_COLUMN:
                return struct.getGtid();
            case DATABASE_COLUMN:
                return struct.getDatabase();
            case TABLE_COLUMN:
                return struct.getTopic() != "" ?
                    struct.getTopic() : "";
            case DDL_COLUMN:
                return ""; // DDL might need special handling
            case BEFORE_COLUMN:
                return struct.beforeModifiedFieldsToJson();
            case AFTER_COLUMN:
                return struct.afterModifiedFieldsToJson();
            case RAW_COLUMN:
                return struct.sourceRecordToJson();
            case TIME_COLUMN:
                return struct.getTs_ms();
            case IS_DELETED_COLUMN:
                return struct.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.DELETE.getOperation()) ? 1 : 0;
            case OPERATION_COLUMN:
                return struct.getCdcOperation().toString();
            case VERSION_COLUMN:
                return struct.getKafkaOffset();
            case HOST_COLUMN:
                return Long.toString(struct.getServerId()); // Host might need special handling
            case LOGFILE_COLUMN:
                return struct.getFile();
            case POSITION_COLUMN:
                return struct.getPos();
            case PRIMARY_HOST_COLUMN:
                return Long.toString(struct.getServerId()); // Primary host might need special handling
            default:
                return null;
        }
    }
}

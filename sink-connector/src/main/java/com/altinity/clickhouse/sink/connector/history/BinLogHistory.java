package com.altinity.clickhouse.sink.connector.history;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.common.SnowFlakeId;
import com.altinity.clickhouse.sink.connector.converters.DebeziumConverter;
import com.altinity.clickhouse.sink.connector.metadata.DataTypeRange;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.QueryFormatter;
import com.clickhouse.data.ClickHouseDataType;
import org.apache.kafka.connect.source.SourceRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class BinLogHistory {

    public static final String CREATE_TABLE = "CREATE TABLE";
    public static final String IF_NOT_EXISTS = "IF NOT EXISTS";
    public static final String NULL = "NULL";
    public static final String NOT_NULL = "NOT NULL";
    public static final String ORDER_BY = "ORDER BY";
    public static final String ORDER_BY_TUPLE = "ORDER BY tuple()";
    public static final String ENGINE_REPLACING_MERGE_TREE = "ENGINE = ReplacingMergeTree(_version, is_deleted)";
    public static final String PARTITION_BY = " PARTITION BY toDate(`";
    public static final String TTL_PREFIX = " TTL toDate(`";
    public static final String TO_INTERVAL_DAY = "`) + toIntervalDay(";

    public static final String GTID_COLUMN = "gtid";
    public static final String GTID_COLUMN_DATA_TYPE = "String";
    public static final String DDL_COLUMN = "ddl";
    public static final String DDL_COLUMN_DATA_TYPE = "String";

    public static final String DATABASE_COLUMN = "database";
    public static final String DATABASE_COLUMN_DATA_TYPE = "LowCardinality(String)";
    public static final String TABLE_COLUMN = "table";
    public static final String TABLE_COLUMN_DATA_TYPE = "LowCardinality(String)";
    public static final String BEFORE_COLUMN = "before";
    public static final String AFTER_COLUMN = "after";
    public static final String BEFORE_AFTER_COLUMN_DATA_TYPE = "String";
    public static final String RAW_COLUMN = "_raw";
    public static final String RAW_COLUMN_DATA_TYPE = "String";
    public static final String TIME_COLUMN = "_time";
    public static final String TIME_COLUMN_DATA_TYPE = "DateTime";
    public static final String IS_DELETED_COLUMN = "is_deleted";
    public static final String IS_DELETED_COLUMN_DATA_TYPE = "UInt8";
    public static final String OPERATION_COLUMN = "_operation";
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
    public static final String SERVER_ID_COLUMN = "server_id";
    public static final String SERVER_ID_COLUMN_DATA_TYPE = "UInt64";
    public static final String ROW_COLUMN = "row";
    public static final String ROW_COLUMN_DATA_TYPE = "UInt64";
    public static final String SEQUENCE_COLUMN = "sequence";
    public static final String SEQUENCE_COLUMN_DATA_TYPE = "UInt64";

    public static final Map<String, String> HISTORY_COLUMNS = new LinkedHashMap<String, String>() {{
        put(GTID_COLUMN, GTID_COLUMN_DATA_TYPE);
        put(DATABASE_COLUMN, DATABASE_COLUMN_DATA_TYPE);
        put(TABLE_COLUMN, TABLE_COLUMN_DATA_TYPE);
        put(DDL_COLUMN, DDL_COLUMN_DATA_TYPE);
        put(BEFORE_COLUMN, BEFORE_AFTER_COLUMN_DATA_TYPE);
        put(AFTER_COLUMN, BEFORE_AFTER_COLUMN_DATA_TYPE);
        put(RAW_COLUMN, RAW_COLUMN_DATA_TYPE);
        put(TIME_COLUMN, TIME_COLUMN_DATA_TYPE);
        put(IS_DELETED_COLUMN, IS_DELETED_COLUMN_DATA_TYPE);
        put(OPERATION_COLUMN, OPERATION_COLUMN_DATA_TYPE);
        put(VERSION_COLUMN, VERSION_COLUMN_DATA_TYPE);
        put(HOST_COLUMN, HOST_COLUMN_DATA_TYPE);
        put(LOGFILE_COLUMN, LOGFILE_COLUMN_DATA_TYPE);
        put(POSITION_COLUMN, POSITION_COLUMN_DATA_TYPE);
        put(PRIMARY_HOST_COLUMN, PRIMARY_HOST_COLUMN_DATA_TYPE);
        put(SERVER_ID_COLUMN, SERVER_ID_COLUMN_DATA_TYPE);
        put(ROW_COLUMN, ROW_COLUMN_DATA_TYPE);
        put(SEQUENCE_COLUMN, SEQUENCE_COLUMN_DATA_TYPE);
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
     * @param serverTimeZone timezone to use for DateTime columns
     * @return SQL statement string for creating the history table
     */
    public String createHistoryTableSyntax(
                                           String historyTableName,
                                           String databaseName,
                                           int ttlDays,
                                           ZoneId serverTimeZone) {

        StringBuilder sb = new StringBuilder();
        sb.append(CREATE_TABLE).append(" ").append(IF_NOT_EXISTS)
                .append(' ').append(databaseName)
                .append(".`").append(historyTableName).append("`(");

        // Iterate through all history columns (LinkedHashMap preserves insertion order)
        // Special handling for TIME_COLUMN to add timezone
        String columnDefinitions = HISTORY_COLUMNS.entrySet().stream()
                .map(entry -> {
                    String dataType = entry.getValue();
                    // Add timezone to TIME_COLUMN
                    if (entry.getKey().equals(TIME_COLUMN) && serverTimeZone != null) {
                        dataType = "DateTime('" + serverTimeZone + "')";
                    }
                    return "`" + entry.getKey() + "` " + dataType;
                })
                .collect(Collectors.joining(","));
        sb.append(columnDefinitions);
        
        sb.append(") ").append(ENGINE_REPLACING_MERGE_TREE);

        // ORDER BY gtid
        sb.append(" ").append(ORDER_BY).append("(").append(SERVER_ID_COLUMN).append(",")
                .append(LOGFILE_COLUMN).append(",").append(POSITION_COLUMN).append(",")
                .append(SEQUENCE_COLUMN).append(",").append(TIME_COLUMN).append(")");
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
    public void addRecordsToHistoryTable(ClickHouseSinkConnectorConfig config, String historyTableName, Connection conn, 
    String DDL, List<ClickHouseStruct> currentBatch, String sourceTimeZone, String serverTimeZone) throws SQLException {
        if (currentBatch == null || currentBatch.isEmpty()) {
            return;
        }

        QueryFormatter queryFormatter = new QueryFormatter();

        // Get the column to data type mapping
        Map<String, String> columnToDataTypeMap = this.getColumnToDataTypeMap();

        // Generate insert query using QueryFormatter
        String insertQuery = queryFormatter.getInsertQueryUsingInputFunction(historyTableName, columnToDataTypeMap);

        this.executeInsertWithStructs(config, conn, insertQuery, DDL, currentBatch, sourceTimeZone, serverTimeZone);
    }

    /**
     * Creates and executes a PreparedStatement for inserting ClickHouseStruct data
     * into a history table using the input() function pattern.
     *
     * @param conn database connection
     * @param insertSql the SQL insert query with input() function  
     * @param clickHouseStructs list of ClickHouseStruct objects to insert
     * @param sourceTimeZone source timezone
     * @param serverTimeZone server timezone
     * @throws SQLException if database operation fails
     */
    public void executeInsertWithStructs(ClickHouseSinkConnectorConfig config, Connection conn, String insertSql, String DDL, 
    List<ClickHouseStruct> clickHouseStructs, String sourceTimeZone, String serverTimeZone) throws SQLException {
        // if serverTimeZone is empty, default to UTC.
        if(serverTimeZone == null || serverTimeZone.isEmpty()) {
            serverTimeZone = "UTC";
        }
        if(sourceTimeZone == null || sourceTimeZone.isEmpty()) {
            sourceTimeZone = "UTC";
        }
        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            for (ClickHouseStruct struct : clickHouseStructs) {
                int paramIndex = 1;
                // Set values based on the HISTORY_COLUMNS mapping
                for (Map.Entry<String, String> entry : HISTORY_COLUMNS.entrySet()) {
                    String columnName = entry.getKey();
                    // If the column name is DDL_COLUMN, set the value to the DDL
                    if (columnName.equals(DDL_COLUMN)) {
                        ps.setString(paramIndex++, DDL);

                    }   else if(columnName.equals(TIME_COLUMN)) {
                            ps.setString(paramIndex++, DebeziumConverter.TimestampConverter.convertWithoutTimeZoneAdjustment(
                                    struct.getTsSec() * 1000, ClickHouseDataType.DateTime,
                                        ZoneId.of(sourceTimeZone), ZoneId.of(serverTimeZone)));
                    } 
                    else {
                        Object value = getValueFromStruct(struct, columnName, config);
                        ps.setObject(paramIndex++, value);
                    }
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
    private Object getValueFromStruct(ClickHouseStruct struct, String columnName, ClickHouseSinkConnectorConfig config) {
        switch (columnName) {
            case GTID_COLUMN:
                return struct.getGtid();
            case DATABASE_COLUMN:
                return struct.getDatabase();
            case TABLE_COLUMN:
                if(struct.getTopic() != null) {
                    // table should be retrieved after the dot from end of the string.
                    // server53.db1.table1 -> table1
                    String[] parts = struct.getTopic().split("\\.");
                   // just return the last part of the array
                   // only if the array is not empty
                   if(parts.length > 0) {
                       String table = parts[parts.length - 1];
                       return table != null && !table.isEmpty() ? table : "";
                   } else
                       return "";
                } else
                    return "";
            case DDL_COLUMN:
                return ""; // DDL might need special handling
            case BEFORE_COLUMN:
                if(struct.beforeModifiedFieldsToJson() == null) {
                    return "";
                }
                return struct.beforeModifiedFieldsToJson();
            case AFTER_COLUMN:
                if(struct.afterModifiedFieldsToJson() == null) {
                    return "";
                }
                return struct.afterModifiedFieldsToJson();
            case RAW_COLUMN:
                if(struct.sourceRecordToJson() == null) {
                    return "";
                }
                return struct.sourceRecordToJson();
            case TIME_COLUMN:
                return struct.getTsSec();
            case IS_DELETED_COLUMN:
                if(struct.getCdcOperation() == null) {
                    return 0;
                }
                return struct.getCdcOperation().getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.DELETE.getOperation()) ? 1 : 0;
            case OPERATION_COLUMN:
                if(struct.getCdcOperation() == null) {
                    return "";
                }
                return struct.getCdcOperation().toString();
            case VERSION_COLUMN:
                Long version= SnowFlakeId.generate(struct.getTs_ms(), struct.getGtid(), false);
                return version;
            case HOST_COLUMN:
            case PRIMARY_HOST_COLUMN:
                // Get this value from database.hostname
                String databaseHostname = config.getString(ClickHouseSinkConnectorConfigVariables.DATABASE_HOSTNAME.toString());
                return databaseHostname; // Host might need special handling
            case LOGFILE_COLUMN:
                if(struct.getFile() == null) {
                    return "";
                }
                return struct.getFile();
            case POSITION_COLUMN:
                return struct.getPos();
            case SERVER_ID_COLUMN:
                return struct.getServerId();
            case ROW_COLUMN:
                return struct.getRow();
            case SEQUENCE_COLUMN:
                return struct.getSequenceNumber();
            default:
                return null;
        }
    }
}

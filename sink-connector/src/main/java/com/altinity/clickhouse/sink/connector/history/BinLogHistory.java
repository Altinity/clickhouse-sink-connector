package com.altinity.clickhouse.sink.connector.history;

import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.apache.kafka.connect.data.Field;
import com.altinity.clickhouse.sink.connector.config.SchemaOverrideConfig;

public class BinLogHistory {

    public static final String CREATE_TABLE = "CREATE TABLE";
    public static final String NULL = "NULL";
    public static final String NOT_NULL = "NOT NULL";
    public static final String ORDER_BY = "ORDER BY";
    public static final String ORDER_BY_TUPLE = "ORDER BY tuple()";
    
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

    /**
     * Builds the CREATE TABLE SQL syntax for a history table,
     * adding CDC metadata columns for database, table, raw payload,
     * time, operation, host, logfile, position, and primary host.
     *
     * @param primaryKey list of primary key column names
     * @param historyTableName name of the history table to create
     * @param databaseName name of the database in which the history table is created
     * @param fields array of Kafka Connect fields
     * @param columnToDataTypesMap map of column names to ClickHouse data types
     * @return SQL statement string for creating the history table
     */
    public String createHistoryTableSyntax(ArrayList<String> primaryKey,
                                           String historyTableName,
                                           String databaseName,
                                           Field[] fields,
                                           Map<String, String> columnToDataTypesMap,
                                           ClickHouseSinkConnectorConfig config) {

        SchemaOverrideConfig schemaConfig = new SchemaOverrideConfig();


        SchemaOverrideConfig.Table tableConfig = schemaConfig.getTableConfig(databaseName, historyTableName,config.originalsStrings());

        // Use the primaryKey from the tableConfig if it is not empty
        if (tableConfig != null && tableConfig.getPrimaryKey() != null && !tableConfig.getPrimaryKey().isEmpty()) {
            primaryKey = new ArrayList<>();
            primaryKey.add(tableConfig.getPrimaryKey());  // Replace with the primary key from tableConfig
        }

        StringBuilder sb = new StringBuilder();
        sb.append(CREATE_TABLE)
                .append(' ').append(databaseName)
                .append(".`").append(historyTableName).append("`(");

        for (Field f : fields) {
            String col = f.name();
            String dt = columnToDataTypesMap.get(col);
            sb.append('`').append(col).append("` ")
                    .append(dt)
                    .append(f.schema().isOptional() ? ' ' + NULL : ' ' + NOT_NULL)
                    .append(',');
        }
        sb.append('`').append(DATABASE_COLUMN).append("` ")
                .append(DATABASE_COLUMN_DATA_TYPE).append(',');
        sb.append('`').append(TABLE_COLUMN).append("` ")
                .append(TABLE_COLUMN_DATA_TYPE).append(',');
        sb.append('`').append(TABLE_COLUMN).append("` ")
                .append(TABLE_COLUMN_DATA_TYPE).append(',');
        sb.append('`').append(TABLE_COLUMN).append("` ")
                .append(TABLE_COLUMN_DATA_TYPE).append(',');

        sb.append('`').append(BEFORE_COLUMN).append("` ")
                .append(TABLE_COLUMN_DATA_TYPE).append(',');
        sb.append('`').append(AFTER_COLUMN).append("` ")
                .append(TABLE_COLUMN_DATA_TYPE).append(',');

        sb.append('`').append(RAW_COLUMN).append("` ")
                .append(RAW_COLUMN_DATA_TYPE).append(',');
        sb.append('`').append(TIME_COLUMN).append("` ")
                .append(TIME_COLUMN_DATA_TYPE).append(',');
        sb.append('`').append(IS_DELETED_COLUMN).append("` ")
                .append(IS_DELETED_COLUMN_DATA_TYPE).append(',');
        sb.append('`').append(OPERATION_COLUMN).append("` ")
                .append(OPERATION_COLUMN_DATA_TYPE).append(',');
        sb.append('`').append(VERSION_COLUMN).append("` ")
                .append(VERSION_COLUMN_DATA_TYPE).append(',');
        sb.append('`').append(HOST_COLUMN).append("` ")
                .append(HOST_COLUMN_DATA_TYPE).append(',');
        sb.append('`').append(LOGFILE_COLUMN).append("` ")
                .append(LOGFILE_COLUMN_DATA_TYPE).append(',');
        sb.append('`').append(POSITION_COLUMN).append("` ")
                .append(POSITION_COLUMN_DATA_TYPE).append(',');
        sb.append('`').append(PRIMARY_HOST_COLUMN).append("` ")
                .append(PRIMARY_HOST_COLUMN_DATA_TYPE);
        sb.append(") ENGINE = MergeTree()");

        if (tableConfig != null &&
                tableConfig.getPartitionBy() != null &&
                !tableConfig.getPartitionBy().isEmpty()) {
            sb.append(" PARTITION BY `")
                    .append(tableConfig.getPartitionBy())
                    .append("`");
        }

        sb.append(' ');
        if (primaryKey != null) {
            sb.append(ORDER_BY)
                    .append("(")
                    .append(primaryKey.stream()
                            .collect(Collectors.joining(",")))
                    .append(")");
        } else {
            sb.append(ORDER_BY_TUPLE);
        }

        if (tableConfig != null &&
                tableConfig.getSettings() != null &&
                !tableConfig.getSettings().isEmpty()) {
            sb.append(" SETTINGS ")
                    .append(tableConfig.getSettings());
        }
        return sb.toString();
    }
}

package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.config.SchemaOverrideConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.config.SchemaOverrideConfig;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.clickhouse.data.ClickHouseDataType;
import com.google.common.annotations.VisibleForTesting;
import org.apache.kafka.connect.data.Field;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;

import static com.altinity.clickhouse.sink.connector.db.ClickHouseDbConstants.*;

/**
 * Wraps all functionality related to creating tables from Kafka sink
 * records.
 *
 * <p>This class auto-generates the SQL for table creation and executes
 * the query to create a new table in ClickHouse.
 */
public class ClickHouseAutoCreateTable
        extends ClickHouseTableOperationsBase {

    /**
     * Logger instance for the ClickHouseAutoCreateTable class.
     */
    private static final Logger log = LogManager.getLogger(
            ClickHouseAutoCreateTable.class.getName());

    /**
     * Creates a new ClickHouse table using the provided fields and primary
     * key.
     *
     * <p>This method builds the CREATE TABLE query based on the provided
     * fields and configuration flags, logs the query, and executes it.
     *
     * @param primaryKey an ArrayList of primary key columns
     * @param tableName the name of the table to create
     * @param databaseName the name of the database in which the table is
     *                     to be created
     * @param fields an array of fields from the Kafka sink record
     * @param connection a JDBC Connection to the ClickHouse database
     * @param isNewReplacingMergeTree flag indicating the new engine type
     * @param useReplicatedReplacingMergeTree flag indicating use of a
     *                                        replicated engine
     * @param rmtDeleteColumn the column name for the delete flag; if null,
     *                        a default is used
     * @throws SQLException if a SQL exception occurs during table creation
     */
    public void createNewTable(ArrayList<String> primaryKey, String tableName,
                               String databaseName, Field[] fields,
                               Connection connection,
                               boolean isNewReplacingMergeTree,
                               boolean useReplicatedReplacingMergeTree,
                               String rmtDeleteColumn,
                               ClickHouseSinkConnectorConfig config)
            throws SQLException {
        Map<String, String> colNameToDataTypeMap =
                this.getColumnNameToCHDataTypeMapping(fields,config);
        String createTableQuery = this.createTableSyntax(primaryKey, tableName,
                databaseName, fields, colNameToDataTypeMap,
                isNewReplacingMergeTree, useReplicatedReplacingMergeTree,
                rmtDeleteColumn,config);
        log.info(String.format("**** AUTO CREATE TABLE for database(%s), "
                + "Query :%s)", databaseName, createTableQuery));
        // TODO: Run this before a session is created.
        DBMetadata metadata = new DBMetadata(config);
        metadata.executeSystemQuery(connection, createTableQuery);
    }

    /**
     * Generates the CREATE TABLE SQL syntax for ClickHouse.
     *
     * <p>The SQL is built based on the provided map of column names to data
     * types, along with the specified engine flags.
     *
     * <pre>
     * CREATE TABLE database.`table_name`
     *   ( `col1` data_type1, `col2` data_type2, ... )
     *   Engine=ReplacingMergeTree(version_column)
     *   PRIMARY KEY(col1) ORDER BY(col1)
     * </pre>
     *
     * @param primaryKey a list of primary key columns
     * @param tableName the name of the table to create
     * @param databaseName the name of the database
     * @param fields an array of Kafka Connect fields
     * @param columnToDataTypesMap a map of column names to ClickHouse
     *                             data types
     * @param isNewReplacingMergeTreeEngine flag for new engine type usage
     * @param useReplicatedReplacingMergeTree flag for using replicated engine
     * @param rmtDeleteColumn the deletion column name; if null or empty, a
     *                        default is used
     * @return a SQL string for creating the table
     */
    public String createTableSyntax(ArrayList<String> primaryKey,
                                    String tableName, String databaseName, Field[] fields,
                                    Map<String, String> columnToDataTypesMap,
                                    boolean isNewReplacingMergeTreeEngine,
                                    boolean useReplicatedReplacingMergeTree,
                                    String rmtDeleteColumn,
                                    ClickHouseSinkConnectorConfig config) {

        SchemaOverrideConfig schemaConfig = new SchemaOverrideConfig();

        // Get the schema configuration for the table "tr_live" in database "dbo"
        SchemaOverrideConfig.Table tableConfig = schemaConfig.getTableConfig(databaseName, tableName,config.originalsStrings());

        // Use the primaryKey from the tableConfig if it is not empty
        if (tableConfig != null && tableConfig.getPrimaryKey() != null && !tableConfig.getPrimaryKey().isEmpty()) {
            primaryKey = new ArrayList<>();
            primaryKey.add(tableConfig.getPrimaryKey());  // Replace with the primary key from tableConfig
        }

        StringBuilder createTableSyntax = new StringBuilder();

        createTableSyntax.append(CREATE_TABLE).append(" ")
                .append(databaseName).append(".")
                .append("`").append(tableName).append("`");
        if (useReplicatedReplacingMergeTree == true) {
            createTableSyntax.append(" ON CLUSTER `{cluster}` ");
        }

        createTableSyntax.append("(");

        for (Field f : fields) {
            String colName = f.name();
            String dataType = columnToDataTypesMap.get(colName);
            boolean isNull = false;
            if (f.schema().isOptional() == true) {
                isNull = true;
            }
            createTableSyntax.append("`").append(colName).append("`")
                    .append(" ").append(dataType);

            // Ignore setting NULL/NOT NULL for JSON and Array types.
            if (dataType != null
                    && (dataType.equalsIgnoreCase(ClickHouseDataType.JSON.name())
                    || dataType.contains(ClickHouseDataType.Array.name()))) {
                // Do not append null constraints.
            } else {
                if (isNull) {
                    createTableSyntax.append(" ").append(NULL);
                } else {
                    createTableSyntax.append(" ").append(NOT_NULL);
                }
            }
            createTableSyntax.append(",");
        }

        String isDeletedColumn = IS_DELETED_COLUMN;
        if (rmtDeleteColumn != null && !rmtDeleteColumn.isEmpty()) {
            isDeletedColumn = rmtDeleteColumn;
        }

        if (isNewReplacingMergeTreeEngine == true) {
            createTableSyntax.append("`").append(VERSION_COLUMN)
                    .append("` ").append(VERSION_COLUMN_DATA_TYPE)
                    .append(",");
            createTableSyntax.append("`").append(isDeletedColumn)
                    .append("` ").append(IS_DELETED_COLUMN_DATA_TYPE);
        } else {
            // Append sign and version columns.
            createTableSyntax.append("`").append(SIGN_COLUMN)
                    .append("` ").append(SIGN_COLUMN_DATA_TYPE)
                    .append(",");
            createTableSyntax.append("`").append(VERSION_COLUMN)
                    .append("` ").append(VERSION_COLUMN_DATA_TYPE);
        }
        createTableSyntax.append(")");
        createTableSyntax.append(" ");

        if (isNewReplacingMergeTreeEngine == true) {
            if (useReplicatedReplacingMergeTree == true) {
                createTableSyntax.append(String.format(
                        "Engine=ReplicatedReplacingMergeTree(%s, %s)",
                        VERSION_COLUMN, isDeletedColumn));
            } else {
                createTableSyntax.append(" Engine=ReplacingMergeTree(")
                        .append(VERSION_COLUMN).append(",")
                        .append(isDeletedColumn).append(")");
            }
        } else {
            if (useReplicatedReplacingMergeTree == true) {
                createTableSyntax.append(String.format(
                        "Engine=ReplicatedReplacingMergeTree(%s)",
                        VERSION_COLUMN));
            } else {
                createTableSyntax.append("ENGINE = ReplacingMergeTree(")
                        .append(VERSION_COLUMN).append(")");
            }
        }

        // Add PARTITION BY if it is present
        if (tableConfig != null && tableConfig.getPartitionBy() != null && !tableConfig.getPartitionBy().isEmpty()) {
            createTableSyntax.append(" PARTITION BY `").append(tableConfig.getPartitionBy()).append("`");
        }

        // Handle ORDER BY clause (primary key is part of ORDER BY in ClickHouse)
        createTableSyntax.append(" ");

        if (primaryKey != null
                && isPrimaryKeyColumnPresent(primaryKey, columnToDataTypesMap)) {
            createTableSyntax.append(PRIMARY_KEY).append("(");
            createTableSyntax.append(primaryKey.stream()
                    .map(Object::toString)
                    .collect(Collectors.joining(",")));
            createTableSyntax.append(") ");
            createTableSyntax.append(ORDER_BY).append("(");
            createTableSyntax.append(primaryKey.stream()
                    .map(Object::toString)
                    .collect(Collectors.joining(",")));
            createTableSyntax.append(")");
        } else {
            // TODO: Define a default ORDER BY clause.
            createTableSyntax.append(ORDER_BY_TUPLE);
        }

        // Add SETTINGS if they are provided (SETTINGS should be placed last)
        if (tableConfig != null && tableConfig.getSettings() != null && !tableConfig.getSettings().isEmpty()) {
            createTableSyntax.append(" SETTINGS ").append(tableConfig.getSettings());
        }

        return createTableSyntax.toString();
    }

    /**
     * Creates a history table with MergeTree engine, including
     * CDC metadata columns such as database, table, raw payload,
     * event time, operation type, host, logfile, position, and primary host.
     *
     * @param primaryKey list of primary key column names
     * @param historyTableName name of the history table to create
     * @param databaseName name of the database in which the history table is created
     * @param fields array of Kafka Connect fields
     * @param connection JDBC connection to the ClickHouse database
     * @throws SQLException if a SQL exception occurs during table creation
     */
    public void createHistoryTable(ArrayList<String> primaryKey,
                                   String historyTableName,
                                   String databaseName,
                                   Field[] fields,
                                   Connection connection,
                                   ClickHouseSinkConnectorConfig config)
            throws SQLException {
        Map<String, String> columnToDataTypesMap =
                this.getColumnNameToCHDataTypeMapping(fields, config);
        String sql = createHistoryTableSyntax(
                primaryKey, historyTableName,
                databaseName, fields, columnToDataTypesMap, config);
        log.info(String.format(
                "**** AUTO CREATE HISTORY TABLE for database(%s), Query :%s)",
                databaseName, sql));
        DBMetadata metadata = new DBMetadata(config);
        metadata.executeSystemQuery(connection, sql);
    }

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
        if (primaryKey != null &&
                isPrimaryKeyColumnPresent(primaryKey, columnToDataTypesMap)) {
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

    @VisibleForTesting
    boolean isPrimaryKeyColumnPresent(ArrayList<String> primaryKeys,
                                      Map<String, String> columnToDataTypesMap) {
        for (String primaryKey : primaryKeys) {
            if (!columnToDataTypesMap.containsKey(primaryKey)) {
                return false;
            }
        }
        return true;
    }
}

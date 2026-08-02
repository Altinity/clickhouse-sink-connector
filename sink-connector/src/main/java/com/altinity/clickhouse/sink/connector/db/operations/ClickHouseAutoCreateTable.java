package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.config.SchemaOverrideConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.history.BinLogHistory;
import com.clickhouse.data.ClickHouseDataType;
import com.google.common.annotations.VisibleForTesting;
import org.apache.kafka.connect.data.Field;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.ZoneId;
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
                .append("`").append(databaseName).append("`").append(".")
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
        

        // If Replication history is enabled, add the temporal columns
        // _valid_from, _valid_to, _operation, and is_deleted
        if (config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())) {
            // Add _valid_from column
            createTableSyntax.append("`").append(DELETED_FROM_TIME_COLUMN)
                    .append("` ").append("DateTime")
                    .append(",");

            // Add _valid_to column
            createTableSyntax.append("`").append(DELETED_TIME_COLUMN)
                    .append("` ").append(DELETED_TIME_COLUMN_DATA_TYPE)
                    .append(",");

            // Add operation column
            createTableSyntax.append("`").append(OPERATION_COLUMN)
                    .append("` ").append(OPERATION_COLUMN_DATA_TYPE)
                    .append(",");

            // Add is_deleted column for replication history
            createTableSyntax.append("`").append(isDeletedColumn)
                    .append("` ").append(IS_DELETED_COLUMN_DATA_TYPE)
                    .append(",");
        }

        boolean replicationHistoryEnabled = config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString());

        if (isNewReplacingMergeTreeEngine == true) {
            createTableSyntax.append("`").append(VERSION_COLUMN)
                    .append("` ").append(VERSION_COLUMN_DATA_TYPE);
            // Only add is_deleted if not already added by replication history
            if (!replicationHistoryEnabled) {
                createTableSyntax.append(",");
                createTableSyntax.append("`").append(isDeletedColumn)
                        .append("` ").append(IS_DELETED_COLUMN_DATA_TYPE);
            }
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

        // If Replication history is enabled, add the PARTITION BY toDate(deleted_time)
        if (config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())) {
            String partitionbyDateColumn = String.format(DELETED_TIME_COLUMN_TO_DATE, DELETED_TIME_COLUMN);
            createTableSyntax.append(" PARTITION BY ").append(partitionbyDateColumn);
        } else {

            // Add PARTITION BY if it is present
            if (tableConfig != null && tableConfig.getPartitionBy() != null && !tableConfig.getPartitionBy().isEmpty()) {
                createTableSyntax.append(" PARTITION BY `").append(tableConfig.getPartitionBy()).append("`");
            }
        }

        // Handle ORDER BY clause (primary key is part of ORDER BY in ClickHouse)
        createTableSyntax.append(" ");

        if (primaryKey != null
                && isPrimaryKeyColumnPresent(primaryKey, columnToDataTypesMap)) {
            createTableSyntax.append(PRIMARY_KEY).append("(");
            createTableSyntax.append(primaryKey.stream()
                    .map(pk -> "`" + pk + "`")
                    .collect(Collectors.joining(",")));
            createTableSyntax.append(") ");
            createTableSyntax.append(ORDER_BY).append("(");
            createTableSyntax.append(primaryKey.stream()
                    .map(pk -> "`" + pk + "`")
                    .collect(Collectors.joining(",")));
            if(config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())) {
                createTableSyntax.append(",`").append(DELETED_TIME_COLUMN).append("`");
            }
            createTableSyntax.append(")");
        } else {
            // TODO: Define a default ORDER BY clause.
            createTableSyntax.append(ORDER_BY_TUPLE);
        }

        // If Replication history is enabled, add the ORDER BY toDate(deleted_time) , Add TTL deleted_time + toIntervalDay(30)
        // TTL deleted_time + toIntervalDay(30)
            if(config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())) {
                createTableSyntax.append(" TTL `").append(DELETED_TIME_COLUMN).append("` + toIntervalDay(30)");
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
     * @param historyTableName name of the history table to create
     * @param databaseName name of the database in which the history table is created
     * @param connection JDBC connection to the ClickHouse database
     * @throws SQLException if a SQL exception occurs during table creation
     */
    public void createHistoryTable(
                                   String historyTableName,
                                   String databaseName,
                                   Connection connection,
                                   ClickHouseSinkConnectorConfig config)
            throws SQLException {
        // Get server timezone
        ZoneId serverTimeZone = getServerTimeZone(config, connection);
        
        String sql = new BinLogHistory().createHistoryTableSyntax(
                 historyTableName,
                databaseName,
                config.getInt(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_TTL.toString()),
                serverTimeZone);
        log.info(String.format(
                "**** AUTO CREATE HISTORY TABLE for database(%s), Query :%s)",
                databaseName, sql));
        DBMetadata metadata = new DBMetadata(config);
        metadata.executeSystemQuery(connection, sql);
    }
    
    /**
     * Gets the server timezone, first checking if user has provided one,
     * otherwise querying the ClickHouse server.
     *
     * @param config the connector configuration
     * @param connection the database connection
     * @return a ZoneId representing the server timezone
     */
    private ZoneId getServerTimeZone(ClickHouseSinkConnectorConfig config, Connection connection) {
        String userProvidedTimeZone = config.getString(
                ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATETIME_TIMEZONE.toString());
        
        ZoneId userProvidedTimeZoneId = null;
        if (userProvidedTimeZone != null && !userProvidedTimeZone.isEmpty()) {
            try {
                userProvidedTimeZoneId = ZoneId.of(userProvidedTimeZone);
            } catch (Exception e) {
                log.error("Error parsing user provided timezone", e);
            }
        }
        
        if (userProvidedTimeZoneId != null) {
            return userProvidedTimeZoneId;
        }
        return new DBMetadata(config).getServerTimeZone(connection);
    }

    public void createHistoryDatabase(String databaseName, Connection connection, ClickHouseSinkConnectorConfig config) throws SQLException {
        String sql = "CREATE DATABASE IF NOT EXISTS `" + databaseName.replace("`", "``") + "`";
        log.info(String.format(
                "**** AUTO CREATE HISTORY DATABASE for database(%s), Query :%s)",
                databaseName, sql));
        DBMetadata metadata = new DBMetadata(config);
        metadata.executeSystemQuery(connection, sql);
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

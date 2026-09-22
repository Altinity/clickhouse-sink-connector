package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.config.ColumnTypeOverrideConfig;
import com.altinity.clickhouse.sink.connector.config.SchemaOverrideConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseDataTypeMapper;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.KeylessTableWarning;
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
import java.util.List;
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
                this.getColumnNameToCHDataTypeMapping(fields, config,
                        databaseName, tableName);
        String createTableQuery = this.createTableSyntax(primaryKey, tableName,
                databaseName, fields, colNameToDataTypeMap,
                isNewReplacingMergeTree, useReplicatedReplacingMergeTree,
                rmtDeleteColumn,config);
        log.info(String.format("**** AUTO CREATE TABLE for database(%s), "
                + "Query :%s)", databaseName, createTableQuery));
        // TODO: Run this before a session is created.
        DBMetadata metadata = new DBMetadata(config);
        metadata.executeSystemQuery(connection, createTableQuery);

        // Reconcile column type overrides against the (possibly
        // pre-existing) table.  Direct override mismatches will throw
        // ColumnTypeOverrideMismatchException; alias drift is auto-fixed.
        ColumnTypeOverrideConfig overrideConfig =
                ColumnTypeOverrideConfig.fromProperties(
                        config.originalsStrings());
        if (overrideConfig.hasOverrides()) {
            ColumnTypeOverrideReconciler reconciler =
                    new ColumnTypeOverrideReconciler();
            try {
                reconciler.reconcile(connection, databaseName, tableName,
                        databaseName, overrideConfig);
            } catch (ColumnTypeOverrideMismatchException e) {
                throw e; // propagate — must halt the connector
            } catch (Exception e) {
                log.error("Error reconciling column type overrides for "
                        + "table {}.{}", databaseName, tableName, e);
            }
        }
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
     * <p>Without a usable primary key the sorting key is every source column
     * ({@code ORDER BY(`col1`,`col2`,...)}, plus {@code SETTINGS
     * allow_nullable_key=1} if any is Nullable); {@code ORDER BY tuple()} is
     * never emitted for ReplacingMergeTree (Spec 08.05 section 3.2).</p>
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

            // Wrap the data type in Nullable() if the source schema marks the
            // field as optional (i.e. nullable in PostgreSQL) and the type is
            // not already wrapped.  ClickHouse does NOT support Nullable()
            // around composite types such as Array, Map, Tuple or the geo
            // types (Point, Polygon, ...), so those must be left as-is
            // (ClickHouseDataTypeMapper.canBeNullable, Spec 07.06 section 3.1).
            // System/engine columns (_version, _sign, is_deleted) must stay
            // non-nullable because ClickHouse requires them as bare integer
            // types for ReplacingMergeTree / CollapsingMergeTree engines.
            // A SOURCE column named is_deleted is an ordinary column here (the
            // engine column is renamed _is_deleted below), so it is wrapped
            // like any other.
            if (f.schema().isOptional()
                    && ClickHouseDataTypeMapper.canBeNullable(dataType)
                    && !colName.equals(VERSION_COLUMN)
                    && !colName.equals(SIGN_COLUMN)) {
                dataType = "Nullable(" + dataType + ")";
            }

            createTableSyntax.append("`").append(colName).append("`")
                    .append(" ").append(dataType);
            createTableSyntax.append(",");
        }

        // Append ALIAS column definitions from ColumnTypeOverrideConfig.
        // ALIAS columns are virtual — computed on read, never stored, and
        // automatically skipped during INSERT by the existing
        // DBMetadata.getAliasAndMaterializedColumnsForTableAndDatabase()
        // mechanism which queries system.columns WHERE default_kind='ALIAS'.
        ColumnTypeOverrideConfig overrideConfig =
                ColumnTypeOverrideConfig.fromProperties(config.originalsStrings());
        List<ColumnTypeOverrideConfig.AliasOverrideEntry> aliasOverrides =
                overrideConfig.getAliasOverrides(databaseName, tableName);
        for (ColumnTypeOverrideConfig.AliasOverrideEntry entry : aliasOverrides) {
            createTableSyntax.append("`").append(entry.getAliasColumnName()).append("` ")
                    .append(entry.getAliasType())
                    .append(" ALIAS ")
                    .append(entry.getExpression());
            createTableSyntax.append(",");
            log.info("Adding ALIAS column '{}' ({} ALIAS {}) to table {}.{}",
                    entry.getAliasColumnName(), entry.getAliasType(),
                    entry.getExpression(), databaseName, tableName);
        }

        String isDeletedColumn = IS_DELETED_COLUMN;
        if (rmtDeleteColumn != null && !rmtDeleteColumn.isEmpty()) {
            isDeletedColumn = rmtDeleteColumn;
        }
        // A source table with its own column of that name keeps it as a
        // source column; the engine column is renamed, exactly as the DDL
        // translator does, instead of declaring the name twice -- which
        // ClickHouse rejects, after which every batch for the table failed
        // with "TABLE METADATA not retrieved" (Spec 08.05 section 3.1.1).
        for (Field f : fields) {
            if (f.name() != null && f.name().equalsIgnoreCase(isDeletedColumn)) {
                String renamed = "_" + isDeletedColumn;
                log.warn("Table {}.{}: the source has a column named `{}`; the ReplacingMergeTree "
                                + "delete-marker column is declared as `{}` instead",
                        databaseName, tableName, isDeletedColumn, renamed);
                isDeletedColumn = renamed;
                break;
            }
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

        // True only when the keyless fallback key (below) names a Nullable
        // column, which ClickHouse rejects (Code 44) unless allow_nullable_key
        // is enabled. The PK path never needs it: a MySQL PRIMARY KEY is NOT NULL.
        boolean nullableSortingKey = false;

        if (primaryKey != null && !primaryKey.isEmpty()
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
            if(config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())) {
                createTableSyntax.append(",`").append(DELETED_TIME_COLUMN).append("`");
            }
            createTableSyntax.append(")");
        } else {
            // No usable primary key: the record carries none (a keyless source
            // table) or names columns the table does not have.
            //
            // ORDER BY tuple() is NEVER emitted here. ReplacingMergeTree
            // deduplicates on the sorting key, and with an empty key every row
            // compares equal, so merges and FINAL collapse the whole table to
            // ONE row -- total, silent data loss (two distinct rows in, one
            // out, measured with clickhouse local). The sorting key is every
            // source column instead, which reproduces MySQL's own semantics for
            // a table without a declared identity: rows are distinguished by
            // value. The banner tells the operator to give the table a real
            // identity at the source; the schema-override primary_key is the
            // escape hatch in the meantime (Spec 08.05 section 3.2).
            List<String> keyColumns = keylessSortingKey(fields, columnToDataTypesMap, isDeletedColumn);
            if (keyColumns.isEmpty()) {
                throw new IllegalStateException(String.format(
                        "Cannot derive a sorting key for %s.%s: the record carries no primary key "
                                + "and no source column is present in the ClickHouse column map. "
                                + "Refusing to create a ReplacingMergeTree table with ORDER BY "
                                + "tuple(), which would collapse every row into one.",
                        databaseName, tableName));
            }
            for (String keyColumn : keyColumns) {
                if (isNullableColumn(keyColumn, fields, columnToDataTypesMap)) {
                    nullableSortingKey = true;
                }
            }
            log.error(KeylessTableWarning.banner(databaseName, tableName));
            log.warn("Table {}.{} has no usable primary key (record key: {}); using every source "
                            + "column as the ReplacingMergeTree sorting key so distinct rows stay "
                            + "distinct: {}. Rows identical in every column will still collapse, "
                            + "and a column added later is not part of this key.",
                    databaseName, tableName, primaryKey, keyColumns);

            createTableSyntax.append(ORDER_BY).append("(");
            createTableSyntax.append(keyColumns.stream()
                    .map(c -> "`" + c + "`")
                    .collect(Collectors.joining(",")));
            if(config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())) {
                createTableSyntax.append(",`").append(DELETED_TIME_COLUMN).append("`");
            }
            createTableSyntax.append(")");
        }

        // If Replication history is enabled, add the ORDER BY toDate(deleted_time) , Add TTL deleted_time + toIntervalDay(30)
        // TTL deleted_time + toIntervalDay(30)
            if(config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())) {
                createTableSyntax.append(" TTL `").append(DELETED_TIME_COLUMN).append("` + toIntervalDay(30)");
            }


        // Add SETTINGS if they are provided (SETTINGS should be placed last).
        // The keyless fallback key appends allow_nullable_key=1 when it names a
        // Nullable column -- added to the user's settings, never replacing
        // them, and not duplicated if the user already set it.
        String userSettings = null;
        if (tableConfig != null && tableConfig.getSettings() != null && !tableConfig.getSettings().isEmpty()) {
            userSettings = tableConfig.getSettings();
        }
        boolean appendNullableKey = nullableSortingKey
                && (userSettings == null || !userSettings.toLowerCase().contains(ALLOW_NULLABLE_KEY));
        if (userSettings != null || appendNullableKey) {
            createTableSyntax.append(" SETTINGS ");
            if (userSettings != null) {
                createTableSyntax.append(userSettings);
                if (appendNullableKey) {
                    createTableSyntax.append(",");
                }
            }
            if (appendNullableKey) {
                createTableSyntax.append(ALLOW_NULLABLE_KEY).append("=1");
            }
        }

        return createTableSyntax.toString();
    }

    /**
     * The sorting key for a table whose record carries no usable primary key:
     * every source column, in record-schema order, that exists in the column
     * map, excluding the columns the connector manages itself.
     *
     * @param fields the record's schema fields (source columns)
     * @param columnToDataTypesMap the ClickHouse column map
     * @param rmtDeleteColumn the configured ReplacingMergeTree delete column
     * @return the key columns, possibly empty
     */
    List<String> keylessSortingKey(Field[] fields, Map<String, String> columnToDataTypesMap,
                                   String rmtDeleteColumn) {
        List<String> keyColumns = new ArrayList<>();
        if (fields == null) {
            return keyColumns;
        }
        for (Field f : fields) {
            String colName = f.name();
            if (colName == null || !columnToDataTypesMap.containsKey(colName)) {
                continue;
            }
            if (isConnectorManagedColumn(colName, rmtDeleteColumn)) {
                continue;
            }
            keyColumns.add(colName);
        }
        return keyColumns;
    }

    /** Columns populated by the connector, never part of a source-derived key. */
    private static boolean isConnectorManagedColumn(String colName, String rmtDeleteColumn) {
        return colName.equalsIgnoreCase(VERSION_COLUMN)
                || colName.equalsIgnoreCase(SIGN_COLUMN)
                || colName.equalsIgnoreCase(IS_DELETED_COLUMN)
                || colName.equalsIgnoreCase(DELETED_TIME_COLUMN)
                || colName.equalsIgnoreCase(DELETED_FROM_TIME_COLUMN)
                || colName.equalsIgnoreCase(OPERATION_COLUMN)
                || (rmtDeleteColumn != null && colName.equalsIgnoreCase(rmtDeleteColumn));
    }

    /**
     * Whether the column is emitted as {@code Nullable(...)}: either the column
     * map already says so, or the schema field is optional and the column
     * definition loop above wraps it (same rule, kept in step with it).
     */
    private static boolean isNullableColumn(String colName, Field[] fields,
                                            Map<String, String> columnToDataTypesMap) {
        String dataType = columnToDataTypesMap.get(colName);
        if (dataType != null && dataType.startsWith("Nullable(")) {
            return true;
        }
        for (Field f : fields) {
            if (colName.equals(f.name())) {
                return f.schema().isOptional()
                        && ClickHouseDataTypeMapper.canBeNullable(dataType);
            }
        }
        return false;
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
        String sql = "CREATE DATABASE IF NOT EXISTS " + databaseName;
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

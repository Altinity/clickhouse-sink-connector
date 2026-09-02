package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.common.ConnectorType;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseAutoCreateTable;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import io.debezium.storage.jdbc.offset.JdbcOffsetBackingStoreConfig;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.connect.data.Field;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.debezium.storage.jdbc.offset.JdbcOffsetBackingStoreConfig.OFFSET_STORAGE_PREFIX;

/**
 * Class that abstracts all functionality related to interacting
 * with a ClickHouse database. It provides methods to create
 * a destination database, retrieve table metadata, and auto-create
 * tables if needed.
 */
public class DbWriter extends BaseDbWriter {

    /**
     * Logger for this class, handling logs and error messages.
     */
    private static final Logger log = LogManager.getLogger(
            DbWriter.class
    );

    /**
     * The target table name in ClickHouse.
     */
    private final String tableName;

    /**
     * A map holding column names and their respective data types.
     */
    private Map<String, String> columnNameToDataTypeMap = new LinkedHashMap<>();

    /**
     * The cache invalidation version this writer was built at. Compared against
     * {@link com.altinity.clickhouse.sink.connector.db.CacheInvalidationManager}
     * to detect when the writer is stale and must be rebuilt after a DDL.
     */
    @Getter
    @Setter
    private long cacheInvalidationVersion = 0;

    /**
     * The engine type of the target table in ClickHouse (e.g., MergeTree,
     * ReplacingMergeTree, CollapsingMergeTree).
     */
    @Getter
    @Setter
    private DBMetadata.TABLE_ENGINE engine;

    /**
     * The connector configuration.
     */
    private final ClickHouseSinkConnectorConfig config;

    /**
     * Sign column used if the engine is CollapsingMergeTree.
     */
    @Getter
    @Setter
    private String signColumn = null;

    /**
     * Version column used if the engine is ReplacingMergeTree.
     */
    @Getter
    @Setter
    private String versionColumn = null;

    /**
     * Delete column for ReplacingMergeTree (if engine is ReplacingMergeTree).
     */
    @Getter
    @Setter
    private String replacingMergeTreeDeleteColumn = null;

    /**
     * Indicates whether the ReplacingMergeTree engine supports
     * the is_deleted column (i.e., new ReplacingMergeTree).
     */
    @Getter
    @Setter
    private boolean replacingMergeTreeWithIsDeletedColumn = false;

    /**
     * Reusable DBMetadata instance for database operations.
     */
    private final DBMetadata dbMetadata;

    /**
     * Cached table engine response containing engine type and column info.
     */
    private MutablePair<DBMetadata.TABLE_ENGINE, String> tableEngineResponse;

    /**
     * The target table's sorting-key columns, in key order.
     *
     * <p>Cached alongside the engine because it is consulted on every UPDATE to
     * decide whether the row lands on a different sorting key and therefore
     * needs its previous position tombstoned. Empty when the table has an empty
     * sorting key ({@code ORDER BY tuple()}).</p>
     */
    @Getter
    private List<String> sortingKeyColumns = new ArrayList<>();

    /**
     * Constructor that sets up the DbWriter by initializing the database
     * connection, retrieving or creating tables, and determining the
     * engine type of the target table.
     *
     * @param hostName   The hostname of the ClickHouse server.
     * @param port       The port number of the ClickHouse server.
     * @param database   The name of the ClickHouse database.
     * @param tableName  The target table name.
     * @param userName   The username for authentication.
     * @param password   The password for authentication.
     * @param config     The sink connector configuration.
     * @param record     A {@link ClickHouseStruct} record object containing
     *                   schema information.
     * @param connection An existing connection to the ClickHouse server.
     */
    public DbWriter(
            String hostName,
            Integer port,
            String database,
            String tableName,
            String userName,
            String password,
            ClickHouseSinkConnectorConfig config,
            ClickHouseStruct record,
            Connection connection
    ) {
        super(hostName, port, database, userName, password, config, connection);
        this.tableName = tableName;
        this.config = config;
        this.dbMetadata = new DBMetadata(config);

        try {
            initializeColumnMetadata();
            ensureDatabasesExist();
            initializeTableEngine(hostName, record);
            configureEngineSpecificColumns();
        } catch (Exception e) {
            log.error("***** DBWriter error initializing ****", e);
        }
    }

    private void autoCreateTable(long taskId, String hostName,
                                 String database,
                                 String tableName,
                                 ClickHouseStruct record,
                                 boolean isNewReplacingMergeTreeEngine,
                                 boolean useOnCluster) {
        log.info(String.format(
                "**** Task(%s), AUTO CREATE TABLE (%s) Database(%s) *** ",
                taskId, tableName, database));

        ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable();
        try {
            Field[] fields = null;
            if (record.getAfterStruct() != null) {
                fields = record.getAfterStruct().schema().fields()
                        .toArray(new Field[0]);
            } else if (record.getBeforeStruct() != null) {
                fields = record.getBeforeStruct().schema().fields()
                        .toArray(new Field[0]);
            }

            String rmtDeleteColumn = this.config.getString(
                    ClickHouseSinkConnectorConfigVariables
                            .REPLACING_MERGE_TREE_DELETE_COLUMN
                            .toString());

            // Create a new table using the schema from record
            act.createNewTable(
                    record.getPrimaryKey(),
                    tableName,
                    database,
                    fields,
                    this.conn,
                    isNewReplacingMergeTreeEngine,
                    useOnCluster,
                    rmtDeleteColumn,
                    this.config
            );


        } catch (Exception e) {
            log.error(String.format(
                            "**** Error creating table(%s), database(%s) ***",
                            tableName, database),
                    e
            );
        }
    }

    /**
     * Initializes the column name to data type mapping for the table.
     * This retrieves metadata about existing columns if the table exists.
     */
    private void initializeColumnMetadata() {
        if (this.conn != null) {
            this.columnNameToDataTypeMap = dbMetadata.getColumnsDataTypesForTable(
                    tableName, this.conn, database);
        }
    }

    /**
     * Ensures that required databases exist in ClickHouse.
     * Creates the offset storage database (for non-Kafka connectors) and
     * the destination database if they don't exist.
     */
    private void ensureDatabasesExist() {
        boolean useOnCluster = this.config.getBoolean(
                ClickHouseSinkConnectorConfigVariables.AUTO_CREATE_TABLES_REPLICATED.toString());

        // For DBs that are not Kafka, create offset storage database if needed.
        if (ConnectorType.getConnectorType(config, log) != ConnectorType.KAFKA) {
            String offsetStorageDatabaseName = getOffsetStorageDatabaseName();
            if (offsetStorageDatabaseName != null) {
                createDestinationDatabase(offsetStorageDatabaseName, useOnCluster, this.config);
            }
        }
        // Create destination database if it doesn't exist
        createDestinationDatabase(database, useOnCluster, this.config);
    }

    /**
     * Initializes the table engine information.
     * If the table doesn't exist and auto-create is enabled, creates it.
     * Updates column metadata after table creation.
     *
     * @param hostName The hostname for logging purposes.
     * @param record   The record containing schema information for table creation.
     */
    private void initializeTableEngine(String hostName, ClickHouseStruct record) throws SQLException {
        // Retrieve table engine details
        this.tableEngineResponse = dbMetadata.getTableEngine(this.conn, database, tableName);
        this.engine = tableEngineResponse.getLeft();

        // If engine is null, the table does not exist yet
        if (this.engine == null) {
            boolean useOnCluster = this.config.getBoolean(
                    ClickHouseSinkConnectorConfigVariables.AUTO_CREATE_TABLES_REPLICATED.toString());

            if (this.config.getBoolean(
                    ClickHouseSinkConnectorConfigVariables.AUTO_CREATE_TABLES.toString())) {
                long taskId = this.config.getLong(
                        ClickHouseSinkConnectorConfigVariables.TASK_ID.toString());
                boolean isNewRMT = isNewReplacingMergeTreeEngine();

                autoCreateTable(taskId, hostName, database, tableName,
                        record, isNewRMT, useOnCluster);
            } else {
                log.error("********* AUTO CREATE DISABLED, Table does not "
                        + "exist, please enable it by setting "
                        + "auto.create.tables=true");
            }

            // Update local metadata after table creation
            this.columnNameToDataTypeMap = dbMetadata.getColumnsDataTypesForTable(
                    tableName, this.conn, database);
            this.tableEngineResponse = dbMetadata.getTableEngine(this.conn, database, tableName);
            this.engine = tableEngineResponse.getLeft();
        }

        // Read the sorting key once, here, rather than per batch on the write path.
        this.sortingKeyColumns = dbMetadata.getSortingKeyColumns(this.conn, database, tableName);
    }

    /**
     * Checks if the ClickHouse server supports the new ReplacingMergeTree
     * engine with the is_deleted column.
     *
     * @return true if new ReplacingMergeTree is supported, false otherwise.
     */
    private boolean isNewReplacingMergeTreeEngine() {
        try {
            String clickHouseVersion = dbMetadata.getClickHouseVersion(this.conn);
            return dbMetadata.checkIfNewReplacingMergeTree(clickHouseVersion);
        } catch (Exception e) {
            log.error("Error retrieving ClickHouse version");
            return false;
        }
    }

    /**
     * Configures engine-specific columns (version, sign, delete columns)
     * based on the table engine type.
     */
    private void configureEngineSpecificColumns() {
        if (this.engine == null || this.tableEngineResponse == null) {
            return;
        }

        String engineName = this.engine.getEngine();

        if (isReplacingMergeTreeEngine(engineName)) {
            configureReplacingMergeTreeColumns(tableEngineResponse.getRight());
        } else if (isCollapsingMergeTreeEngine(engineName)) {
            this.signColumn = tableEngineResponse.getRight();
        }
    }

    /**
     * Checks if the engine is a ReplacingMergeTree variant.
     */
    private boolean isReplacingMergeTreeEngine(String engineName) {
        return engineName.equalsIgnoreCase(
                DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE.getEngine())
                || engineName.equalsIgnoreCase(
                DBMetadata.TABLE_ENGINE.REPLICATED_REPLACING_MERGE_TREE.getEngine());
    }

    /**
     * Checks if the engine is a CollapsingMergeTree variant.
     */
    private boolean isCollapsingMergeTreeEngine(String engineName) {
        return engineName.equalsIgnoreCase(
                DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE.getEngine());
    }

    /**
     * Configures the version and delete columns for ReplacingMergeTree engines.
     *
     * @param rmtColumns The column specification from table engine response.
     */
    private void configureReplacingMergeTreeColumns(String rmtColumns) {
        if (rmtColumns != null && rmtColumns.contains(",")) {
            // The table uses the new RMT with version and deleted column
            String[] parts = rmtColumns.split(",");
            this.versionColumn = parts[0].trim();
            this.replacingMergeTreeDeleteColumn = parts[1].trim();
            this.replacingMergeTreeWithIsDeletedColumn = true;
        } else {
            this.versionColumn = rmtColumns;
            this.replacingMergeTreeDeleteColumn = this.config.getString(
                    ClickHouseSinkConnectorConfigVariables.REPLACING_MERGE_TREE_DELETE_COLUMN.toString());
        }
    }

    /**
     * Retrieves the offset storage database name from the connector
     * configuration, if it exists.
     *
     * <p>The property is a qualified table name, {@code database.table} --
     * exactly two parts. Every sample config in this repository ships
     * {@code altinity_sink_connector.replica_source_info}, and
     * {@link DebeziumJdbcStorageOperations} parses the same value with the
     * same two-part shape.</p>
     *
     * @return the offset storage database name, or {@code null} when the
     *         property is unset or is not a qualified {@code database.table}.
     */
    public String getOffsetStorageDatabaseName() {
        String offsetStorageTable = null;
        try {
            offsetStorageTable = config.getString(
                    OFFSET_STORAGE_PREFIX
                            + JdbcOffsetBackingStoreConfig.PROP_TABLE_NAME.name()
            );
        } catch (Exception e) {
            log.error("***** Error retrieving offset store configuration ****",
                    e);
        }
        return databaseOfOffsetTable(offsetStorageTable);
    }

    /**
     * Extracts the database from a qualified offset table name.
     *
     * <p>Package-private and static so the parsing can be tested without
     * standing up a ClickHouse connection -- {@link DbWriter}'s constructor
     * opens one, so a test that builds a writer just to read a config string
     * spends the full connection-retry budget first.</p>
     *
     * @param offsetStorageTable the configured {@code database.table} value.
     * @return the database segment, or {@code null} when the value is unset
     *         or carries no database.
     */
    static String databaseOfOffsetTable(String offsetStorageTable) {
        String propName = OFFSET_STORAGE_PREFIX
                + JdbcOffsetBackingStoreConfig.PROP_TABLE_NAME.name();
        if (offsetStorageTable == null || offsetStorageTable.isEmpty()) {
            log.warn("Skipping the offset storage database: '{}' is not set "
                    + "in the configuration.", propName);
            return null;
        }
        String[] parts = offsetStorageTable.split("\\.");
        // Two parts, not three. A correctly configured
        // 'altinity_sink_connector.replica_source_info' splits into exactly
        // two, so the previous '<= 2' rejected every valid value and this
        // method always returned null -- the offset storage database was
        // never created from here, and the operator was told the "query was
        // not provided" when it had been. Reported as #1379, where the
        // resulting WARN sent the reporter looking for a schema-history
        // misconfiguration that does not exist on the PostgreSQL path.
        if (parts.length < 2) {
            log.warn("Skipping the offset storage database: '{}' is '{}', "
                            + "which is not a qualified database.table name.",
                    propName, offsetStorageTable);
            return null;
        }

        return parts[0];
    }

    /**
     * Updates the column name to data type map for the table,
     * typically after the schema has changed or the table was
     * newly created. Also updates the table engine details.
     *
     * @throws SQLException If a database access error occurs.
     */
    public void updateColumnNameToDataTypeMap() throws SQLException {
        this.columnNameToDataTypeMap = dbMetadata.getColumnsDataTypesForTable(
                tableName, this.conn, database);
        this.tableEngineResponse = dbMetadata.getTableEngine(this.conn, database, tableName);
        this.engine = tableEngineResponse.getLeft();
        // Refresh the sorting key alongside the rest of the metadata. This is
        // the path taken when the table is created by a replicated DDL rather
        // than by auto-create, so leaving it stale here means the writer sees
        // an empty sorting key and silently skips the UPDATE tombstone.
        this.sortingKeyColumns = dbMetadata.getSortingKeyColumns(this.conn, database, tableName);
    }

    /**
     * Checks if table metadata (engine type and columns) was properly retrieved.
     *
     * @return true if metadata is retrieved; false otherwise.
     */
    public boolean wasTableMetaDataRetrieved() {
        boolean result = true;

        if (this.engine == null
                || this.columnNameToDataTypeMap == null
                || this.columnNameToDataTypeMap.isEmpty()) {
            result = false;
        }
        return result;
    }

    /**
     * Returns the map of column names to their data types.
     *
     * @return A map of column name to data type.
     */
    public Map<String, String> getColumnNameToDataTypeMap() {
        return this.columnNameToDataTypeMap;
    }

    /**
     * Gets the table name used by this writer.
     *
     * @return The table name as a String.
     */
    public String getTableName() {
        return this.tableName;
    }

    /**
     * Gets the database name used by this writer.
     *
     * @return The database name as a String.
     */
    public String getDatabaseName() {
        return this.database;
    }
}

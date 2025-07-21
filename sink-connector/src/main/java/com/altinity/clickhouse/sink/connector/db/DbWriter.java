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
import java.util.LinkedHashMap;
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
            ClickHouseSinkConnectorConfig.class
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

        try {
            if (this.conn != null) {
                // Retrieve column name to data type mapping for the table.
                this.columnNameToDataTypeMap =
                        new DBMetadata(config).getColumnsDataTypesForTable(
                                tableName,
                                this.conn,
                                database
                        );
            }

            DBMetadata metadata = new DBMetadata(config);

            // For DBs that are not Kafka, create offset storage database if needed.
            if (ConnectorType.getConnectorType(config, log)
                    != ConnectorType.KAFKA) {
                String offsetStorageDatabaseName = getOffsetStorageDatabaseName();
                if (offsetStorageDatabaseName != null) {
                    createDestinationDatabase(offsetStorageDatabaseName);
                }
            }
            // Create destination database if it doesn't exist
            createDestinationDatabase(database);

            // Retrieve table engine details
            MutablePair<DBMetadata.TABLE_ENGINE, String> response =
                    metadata.getTableEngine(this.conn, database, tableName);
            this.engine = response.getLeft();

            long taskId = this.config.getLong(
                    ClickHouseSinkConnectorConfigVariables.TASK_ID.toString());

            boolean isNewReplacingMergeTreeEngine = false;
            try {
                DBMetadata dbMetadata = new DBMetadata(config);
                String clickHouseVersion = dbMetadata.getClickHouseVersion(
                        this.conn);
                isNewReplacingMergeTreeEngine = dbMetadata
                        .checkIfNewReplacingMergeTree(clickHouseVersion);
            } catch (Exception e) {
                log.error("Error retrieving ClickHouse version");
            }

            // If engine is null, the table does not exist yet
            if (this.engine == null) {
                if (this.config.getBoolean(
                        ClickHouseSinkConnectorConfigVariables.AUTO_CREATE_TABLES
                                .toString())) {
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

                        boolean useReplicatedReplacingMergeTree = this.config
                                .getBoolean(
                                        ClickHouseSinkConnectorConfigVariables
                                                .AUTO_CREATE_TABLES_REPLICATED
                                                .toString());

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
                                useReplicatedReplacingMergeTree,
                                rmtDeleteColumn,
                                config
                        );
                    } catch (Exception e) {
                        log.error(String.format(
                                        "**** Error creating table(%s), database(%s) ***",
                                        tableName, database),
                                e
                        );
                    }
                } else {
                    log.error("********* AUTO CREATE DISABLED, Table does not "
                            + "exist, please enable it by setting "
                            + "auto.create.tables=true");
                }

                // Update local metadata
                this.columnNameToDataTypeMap = new DBMetadata(config)
                        .getColumnsDataTypesForTable(tableName, this.conn,
                                database);
                response = metadata.getTableEngine(this.conn, database,
                        tableName);
                this.engine = response.getLeft();
            }

            // If it's ReplacingMergeTree or ReplicatedReplacingMergeTree,
            // handle version columns
            if (this.engine != null
                    && (this.engine.getEngine().equalsIgnoreCase(
                    DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE.getEngine())
                    || this.engine.getEngine().equalsIgnoreCase(
                    DBMetadata.TABLE_ENGINE
                            .REPLICATED_REPLACING_MERGE_TREE.getEngine()))) {

                String rmtColumns = response.getRight();
                if (rmtColumns != null && rmtColumns.contains(",")) {
                    // The table uses the new RMT with version and deleted column
                    String[] rmtColumnArray = rmtColumns.split(",");
                    this.versionColumn = rmtColumnArray[0].trim();
                    this.replacingMergeTreeDeleteColumn = rmtColumnArray[1].trim();
                    replacingMergeTreeWithIsDeletedColumn = true;
                } else {
                    this.versionColumn = response.getRight();
                    this.replacingMergeTreeDeleteColumn = this.config
                            .getString(
                                    ClickHouseSinkConnectorConfigVariables
                                            .REPLACING_MERGE_TREE_DELETE_COLUMN.toString()
                            );
                }

            } else if (this.engine != null
                    && this.engine.getEngine().equalsIgnoreCase(
                    DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE
                            .getEngine())) {
                this.signColumn = response.getRight();
            }

        } catch (Exception e) {
            log.error("***** DBWriter error initializing ****", e);
        }
    }

    /**
     * Retrieves the offset storage database name from the connector
     * configuration, if it exists.
     *
     * @return The offset storage database name, or null if none is found.
     */
    public String getOffsetStorageDatabaseName() {
        String offsetSchemaHistoryTable = null;
        try {
            offsetSchemaHistoryTable = config.getString(
                    OFFSET_STORAGE_PREFIX
                            + JdbcOffsetBackingStoreConfig.PROP_TABLE_NAME.name()
            );
        } catch (Exception e) {
            log.error("***** Error retrieving offset store configuration ****",
                    e);
        }
        if (offsetSchemaHistoryTable == null
                || offsetSchemaHistoryTable.isEmpty()) {
            log.warn("Skipping creating offset schema history table as the "
                    + "query was not provided in configuration");
            return null;
        }
        String[] offsetStorageDatabaseNameArray = offsetSchemaHistoryTable.split(
                "\\.");
        if (offsetStorageDatabaseNameArray.length <= 2) {
            log.warn("Skipping creating offset schema history table as the "
                    + "query was not provided in configuration");
            return null;
        }
        String offsetStorageDatabaseName = offsetStorageDatabaseNameArray[0];
        String offsetStorageTableName = offsetStorageDatabaseNameArray[1];

        return offsetStorageDatabaseName;
    }

    /**
     * Updates the column name to data type map for the table,
     * typically after the schema has changed or the table was
     * newly created. Also updates the table engine details.
     *
     * @throws SQLException If a database access error occurs.
     */
    public void updateColumnNameToDataTypeMap() throws SQLException {
        this.columnNameToDataTypeMap = new DBMetadata(config)
                .getColumnsDataTypesForTable(
                        tableName, this.conn, database
                );
        MutablePair<DBMetadata.TABLE_ENGINE, String> response =
                new DBMetadata(config).getTableEngine(this.conn, database, tableName);
        this.engine = response.getLeft();
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

package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseAutoCreateTable;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseCreateDatabase;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.clickhouse.jdbc.ClickHouseConnection;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.connect.data.Field;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Class that abstracts all functionality
 * related to interacting with Clickhouse DB.
 */
public class DbWriter extends BaseDbWriter {
    //ClickHouseNode server;
    private static final Logger log = LogManager.getLogger(ClickHouseSinkConnectorConfig.class);

    private final String tableName;

    // Map of column names to data types.
    private Map<String, String> columnNameToDataTypeMap = new LinkedHashMap<>();

    @Getter
    @Setter
    private DBMetadata.TABLE_ENGINE engine;

    private final ClickHouseSinkConnectorConfig config;

    // CollapsingMergeTree
    @Getter
    @Setter
    private String signColumn = null;

    @Getter
    @Setter
    // ReplacingMergeTree
    private String versionColumn = null;

    @Getter
    @Setter
    // Delete column for ReplacingMergeTree
    private String replacingMergeTreeDeleteColumn = null;

    /**
     * IMPORTANT: The logic to identify the new replacing mergetree
     * table which lets you specify the is_deleted column in
     * the CREATE TABLE DEFINITION and ClickHouse
     * will delete the rows where the is_deleted column is set to 1.
     */
    @Getter
    @Setter
    private boolean replacingMergeTreeWithIsDeletedColumn = false;


    public DbWriter(
            String hostName,
            Integer port,
            String database,
            String tableName,
            String userName,
            String password,
            ClickHouseSinkConnectorConfig config,
            ClickHouseStruct record,
            ClickHouseConnection connection
    )  {
        // Base class initiates connection using JDBC.
        super(hostName, port, database, userName, password, config, connection);
        this.tableName = tableName;

        this.config = config;

        try {
            if (this.conn != null) {
                // Order of the column names and the data type has to match.
                this.columnNameToDataTypeMap = new DBMetadata().getColumnsDataTypesForTable(tableName, this.conn, database);
            }

            DBMetadata metadata = new DBMetadata();
            try {
                if (false == metadata.checkIfDatabaseExists(this.conn, database)) {
                    new ClickHouseCreateDatabase().createNewDatabase(this.conn, database);
                }
            } catch(Exception e) {
                log.error("Error creating Database: " + database);
            }
            MutablePair<DBMetadata.TABLE_ENGINE, String> response = metadata.getTableEngine(this.conn, database, tableName);
            this.engine = response.getLeft();

            long taskId = this.config.getLong(ClickHouseSinkConnectorConfigVariables.TASK_ID.toString());
            boolean isNewReplacingMergeTreeEngine = false;
            try {
                String clickHouseVersion = this.getClickHouseVersion();
                isNewReplacingMergeTreeEngine = new com.altinity.clickhouse.sink.connector.db.DBMetadata()
                        .checkIfNewReplacingMergeTree(clickHouseVersion);
            } catch (Exception e) {
                log.error("Error retrieving ClickHouse version");
            }
            //ToDO: Is this a reliable way of checking if the table exists already.
            if (this.engine == null) {
                if (this.config.getBoolean(ClickHouseSinkConnectorConfigVariables.AUTO_CREATE_TABLES.toString())) {
                    log.info(String.format("**** Task(%s), AUTO CREATE TABLE (%s) Database(%s) *** ",taskId, tableName,
                            database));
                    ClickHouseAutoCreateTable act = new ClickHouseAutoCreateTable();
                    try {
                        Field[] fields = null;
                        if(record.getAfterStruct() != null) {
                            fields = record.getAfterStruct().schema().fields().toArray(new Field[0]);
                        } else if(record.getBeforeStruct() != null) {
                            fields = record.getBeforeStruct().schema().fields().toArray(new Field[0]);
                        }
                        boolean useReplicatedReplacingMergeTree = this.config.getBoolean(
                                ClickHouseSinkConnectorConfigVariables.AUTO_CREATE_TABLES_REPLICATED.toString());
                        act.createNewTable(record.getPrimaryKey(), tableName, database, fields, this.conn,
                                isNewReplacingMergeTreeEngine, useReplicatedReplacingMergeTree);
                    } catch (Exception e) {
                        log.error(String.format("**** Error creating table(%s), database(%s) ***",tableName, database), e);
                    }
                } else {
                    log.error("********* AUTO CREATE DISABLED, Table does not exist, please enable it by setting auto.create.tables=true");
                }

                this.columnNameToDataTypeMap = new DBMetadata().getColumnsDataTypesForTable(tableName, this.conn, database);
                response = metadata.getTableEngine(this.conn, database, tableName);
                this.engine = response.getLeft();
            }

            if (this.engine != null &&
                    (this.engine.getEngine().equalsIgnoreCase(DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE.getEngine()) ||
                            this.engine.getEngine().equalsIgnoreCase(DBMetadata.TABLE_ENGINE.REPLICATED_REPLACING_MERGE_TREE.getEngine()))) {
                String rmtColumns = response.getRight();
                if(rmtColumns != null && rmtColumns.contains(",")) {
                    // New RMT, with version and deleted column.
                    String[] rmtColumnArray = rmtColumns.split(",");
                    this.versionColumn = rmtColumnArray[0].trim();
                    this.replacingMergeTreeDeleteColumn = rmtColumnArray[1].trim();
                    replacingMergeTreeWithIsDeletedColumn = true;
                } else {
                    this.versionColumn = response.getRight();
                    this.replacingMergeTreeDeleteColumn = this.config.getString(ClickHouseSinkConnectorConfigVariables.REPLACING_MERGE_TREE_DELETE_COLUMN.toString());
                }

            } else if (this.engine != null && this.engine.getEngine().equalsIgnoreCase(com.altinity.clickhouse.sink.connector.db.DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE.getEngine())) {
                this.signColumn = response.getRight();
            }
        } catch(Exception e) {
            log.error("***** DBWriter error initializing ****", e);
        }
    }

    public void updateColumnNameToDataTypeMap() throws SQLException {
        this.columnNameToDataTypeMap = new DBMetadata().getColumnsDataTypesForTable(tableName, this.conn, database);
        MutablePair<DBMetadata.TABLE_ENGINE, String> response = new DBMetadata().getTableEngine(this.conn, database, tableName);
        this.engine = response.getLeft();
    }

    public boolean wasTableMetaDataRetrieved() {
        boolean result = true;

        if(this.engine == null || this.columnNameToDataTypeMap == null || this.columnNameToDataTypeMap.isEmpty()) {
            result = false;
        }

        return result;
    }



    /**
     * Function to check if the column is of DateTime64
     * from the column type(string name)
     *
     * @param columnType
     * @return true if its DateTime64, false otherwise.
     */
    public static boolean isColumnDateTime64(String columnType) {
        //ClickHouseDataType dt = ClickHouseDataType.of(columnType);
        //ToDo: Figure out a way to get the ClickHouseDataType
        // from column name.
        boolean result = false;
        if (columnType.contains("DateTime64")) {
            result = true;
        }
        return result;
    }

    public Map<String, String> getColumnNameToDataTypeMap() {
        return this.columnNameToDataTypeMap;
    }

    public String getTableName() {
        return this.tableName;
    }

    public String getDatabaseName() {
        return this.database;
    }

}

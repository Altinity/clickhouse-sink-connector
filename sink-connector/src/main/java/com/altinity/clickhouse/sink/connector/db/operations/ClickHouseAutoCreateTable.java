package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.config.SchemaOverrideConfig;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.clickhouse.data.ClickHouseDataType;
import com.clickhouse.jdbc.ClickHouseConnection;
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
 * Class that wraps all functionality
 * related to creating tables
 * from kafka sink record.
 */
public class ClickHouseAutoCreateTable extends ClickHouseTableOperationsBase{


    private static final Logger log = LogManager.getLogger(ClickHouseAutoCreateTable.class.getName());

    public void createNewTable(ArrayList<String> primaryKey, String tableName, String databaseName, Field[] fields,
                               Connection connection, boolean isNewReplacingMergeTree,
                               boolean useReplicatedReplacingMergeTree, String rmtDeleteColumn) throws SQLException {
        Map<String, String> colNameToDataTypeMap = this.getColumnNameToCHDataTypeMapping(fields);
        String createTableQuery = this.createTableSyntax(primaryKey, tableName, databaseName, fields, colNameToDataTypeMap,
                isNewReplacingMergeTree, useReplicatedReplacingMergeTree, rmtDeleteColumn);
        log.info(String.format("**** AUTO CREATE TABLE for database(%s), Query :%s)", databaseName, createTableQuery));
        // ToDO: need to run it before a session is created.
        DBMetadata metadata = new DBMetadata();
        metadata.executeSystemQuery(connection, createTableQuery);
    }

    /**
     * Function to generate CREATE TABLE for ClickHouse.
     *
     * @param primaryKey
     * @param columnToDataTypesMap
     * @return CREATE TABLE query
     */
    public java.lang.String createTableSyntax(ArrayList<String> primaryKey, String tableName, String databaseName, Field[] fields,
                                              Map<String, String> columnToDataTypesMap,
                                              boolean isNewReplacingMergeTreeEngine,
                                              boolean useReplicatedReplacingMergeTree,
                                              String rmtDeleteColumn) {
        // Load the schema configuration from the YAML file
        SchemaOverrideConfig config = new SchemaOverrideConfig();
        String filePath = "config_schema_override.yml";  // File is inside src/main/resources
        try {
            config.loadTableConfigs(filePath);
        } catch (IOException e) {
            log.error("load schema override configs error:", e);
        }

        // Get the schema configuration for the table "tr_live" in database "dbo"
        SchemaOverrideConfig.Table tableConfig = config.getTableConfig(databaseName, tableName);

        // Use the primaryKey from the tableConfig if it is not empty
        if (tableConfig != null && tableConfig.getPrimaryKey() != null && !tableConfig.getPrimaryKey().isEmpty()) {
            primaryKey = new ArrayList<>();
            primaryKey.add(tableConfig.getPrimaryKey());  // Replace with the primary key from tableConfig
        }

        StringBuilder createTableSyntax = new StringBuilder();

        createTableSyntax.append(CREATE_TABLE).append(" ").append(databaseName).append(".").append("`").append(tableName).append("`");
        if(useReplicatedReplacingMergeTree == true) {
            createTableSyntax.append(" ON CLUSTER `{cluster}` ");
        }

        createTableSyntax.append("(");

        for(Field f: fields) {
            String colName = f.name();
            String dataType = columnToDataTypesMap.get(colName);
            boolean isNull = false;
            if(f.schema().isOptional() == true) {
                isNull = true;
            }
            createTableSyntax.append("`").append(colName).append("`").append(" ").append(dataType);

            // Ignore setting NULL OR not NULL for JSON and Array
            if(dataType != null &&
                    (dataType.equalsIgnoreCase(ClickHouseDataType.JSON.name()) ||
                            dataType.contains(ClickHouseDataType.Array.name()))) {
                // ignore adding nulls;
            } else {
                if (isNull) {
                    createTableSyntax.append(" ").append(NULL);
                } else {
                    createTableSyntax.append(" ").append(NOT_NULL);
                }
            }
            createTableSyntax.append(",");

        }

        // Handle the deletion column logic
        String isDeletedColumn = IS_DELETED_COLUMN;

        if(rmtDeleteColumn != null && !rmtDeleteColumn.isEmpty()) {
            isDeletedColumn = rmtDeleteColumn;
        }

        if(isNewReplacingMergeTreeEngine == true) {
            createTableSyntax.append("`").append(VERSION_COLUMN).append("` ").append(VERSION_COLUMN_DATA_TYPE).append(",");
            createTableSyntax.append("`").append(isDeletedColumn).append("` ").append(IS_DELETED_COLUMN_DATA_TYPE);
        } else {
            // Append sign and version columns
            createTableSyntax.append("`").append(SIGN_COLUMN).append("` ").append(SIGN_COLUMN_DATA_TYPE).append(",");
            createTableSyntax.append("`").append(VERSION_COLUMN).append("` ").append(VERSION_COLUMN_DATA_TYPE);
        }
        createTableSyntax.append(")");
        createTableSyntax.append(" ");

        if(isNewReplacingMergeTreeEngine == true ){
            if(useReplicatedReplacingMergeTree == true) {
                createTableSyntax.append(String.format("Engine=ReplicatedReplacingMergeTree(%s, %s)", VERSION_COLUMN, isDeletedColumn));
            } else
                createTableSyntax.append(" Engine=ReplacingMergeTree(").append(VERSION_COLUMN).append(",").append(isDeletedColumn).append(")");
        } else {
            if(useReplicatedReplacingMergeTree == true) {
                createTableSyntax.append(String.format("Engine=ReplicatedReplacingMergeTree(%s)", VERSION_COLUMN));
            } else
                createTableSyntax.append("ENGINE = ReplacingMergeTree(").append(VERSION_COLUMN).append(")");
        }
        createTableSyntax.append(" ");
        // Add PARTITION BY if it is present
        if (tableConfig != null && tableConfig.getPartitionBy() != null && !tableConfig.getPartitionBy().isEmpty()) {
            createTableSyntax.append(" PARTITION BY `").append(tableConfig.getPartitionBy()).append("`");
        }

        // Handle ORDER BY clause (primary key is part of ORDER BY in ClickHouse)
        createTableSyntax.append(" ");
        if(primaryKey != null && isPrimaryKeyColumnPresent(primaryKey, columnToDataTypesMap)) {
            createTableSyntax.append(PRIMARY_KEY).append("(");
            createTableSyntax.append(primaryKey.stream().map(Object::toString).collect(Collectors.joining(",")));
            createTableSyntax.append(") ");

            createTableSyntax.append(ORDER_BY).append("(");
            createTableSyntax.append(primaryKey.stream().map(Object::toString).collect(Collectors.joining(",")));
            createTableSyntax.append(")");
        } else {
            // ToDO:
            createTableSyntax.append(ORDER_BY_TUPLE);
        }

        // Add SETTINGS if they are provided (SETTINGS should be placed last)
        if (tableConfig != null && tableConfig.getSettings() != null && !tableConfig.getSettings().isEmpty()) {
            createTableSyntax.append(" SETTINGS ").append(tableConfig.getSettings());
        }

        return createTableSyntax.toString();
    }

    @VisibleForTesting
    boolean isPrimaryKeyColumnPresent(ArrayList<String> primaryKeys, Map<String, String> columnToDataTypesMap) {

        for(String primaryKey: primaryKeys) {
            if(!columnToDataTypesMap.containsKey(primaryKey)) {
                return false;
            }
        }
        return true;
    }
}

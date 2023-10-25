package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.clickhouse.data.ClickHouseDataType;
import com.clickhouse.jdbc.ClickHouseConnection;
import com.google.common.annotations.VisibleForTesting;
import org.apache.kafka.connect.data.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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


    private static final Logger log = LoggerFactory.getLogger(ClickHouseAutoCreateTable.class.getName());
    private final ClickHouseSinkConnectorConfig config;
    public static boolean supportsIsDeletedColumnRmt = false;
    public static boolean useReplication;
    public static boolean useReplacingMergeTreeIsDeletedColumn;
    public static String userDefinedDeleteColumnName;

    public ClickHouseAutoCreateTable(ClickHouseSinkConnectorConfig config, boolean newRmtSupport) {
        this.supportsIsDeletedColumnRmt = newRmtSupport;
        this.config = config;
        this.userDefinedDeleteColumnName = this.config.getString(ClickHouseSinkConnectorConfigVariables.REPLACING_MERGE_TREE_DELETE_COLUMN.toString());
        this.useReplacingMergeTreeIsDeletedColumn = this.config.getBoolean(ClickHouseSinkConnectorConfigVariables.USE_REPLACING_MERGE_TREE_IS_DELETED_COLUMN.toString());
        this.useReplication = this.config.getBoolean(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_REPLICATION.toString());
    }

    public void createNewTable(ArrayList<String> primaryKey, String tableName, Field[] fields, ClickHouseConnection connection) throws SQLException {
        Map<String, String> colNameToDataTypeMap = this.getColumnNameToCHDataTypeMapping(fields);
        String createTableQuery = this.createTableSyntax(primaryKey, tableName, fields, colNameToDataTypeMap);
        log.info("**** AUTO CREATE TABLE " + createTableQuery);
        // ToDO: need to run it before a session is created.
        this.runQuery(createTableQuery, connection);
    }

    /**
     * Function to generate CREATE TABLE for ClickHouse.
     *
     * @param primaryKey
     * @param columnToDataTypesMap
     * @return CREATE TABLE query
     */
    public java.lang.String createTableSyntax(ArrayList<String> primaryKey, String tableName, Field[] fields, Map<String, String> columnToDataTypesMap) {

        StringBuilder createTableSyntax = new StringBuilder();

        createTableSyntax.append(CREATE_TABLE).append(" ").append(tableName).append("(");

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
//        for(Map.Entry<String, String>  entry: columnToDataTypesMap.entrySet()) {
//            createTableSyntax.append("`").append(entry.getKey()).append("`").append(" ").append(entry.getValue()).append(",");
//        }
        //createTableSyntax.deleteCharAt(createTableSyntax.lastIndexOf(","));

        // Append sign and version columns
        String deleteColumnNameFinal = this.userDefinedDeleteColumnName;
        String deleteColumnTypeFinal = new String();
        // if want to use the is_deleted column behavior and it is supported
        if (this.supportsIsDeletedColumnRmt && this.useReplacingMergeTreeIsDeletedColumn) {
            // additional check for REPLACING_MERGE_TREE_DELETE_COLUMN parameter value
            // if it's null - set the default name from constant
            if (deleteColumnNameFinal == null) {
                deleteColumnNameFinal = IS_DELETED_COLUMN;
            }
            deleteColumnTypeFinal = IS_DELETED_COLUMN_DATA_TYPE;
        // else use the _sign column 
        } else {
            // also we can use REPLACING_MERGE_TREE_DELETE_COLUMN parameter value
            // for _sing column name
            if (deleteColumnNameFinal == null) {
                deleteColumnNameFinal = SIGN_COLUMN;
            }
            deleteColumnTypeFinal = SIGN_COLUMN_DATA_TYPE;
        }
        createTableSyntax.append("`").append(deleteColumnNameFinal).append("` ").append(deleteColumnTypeFinal).append(", ");
        createTableSyntax.append("`").append(VERSION_COLUMN).append("` ").append(VERSION_COLUMN_DATA_TYPE);
        createTableSyntax.append(")");
        createTableSyntax.append(" ");
        createTableSyntax.append("ENGINE = ");
        String replicatedEnginePrefix = new String();
        if (this.useReplication) {
            replicatedEnginePrefix = "Replicated";
        } else {
            replicatedEnginePrefix = "";
        }
        createTableSyntax.append(replicatedEnginePrefix).append("ReplacingMergeTree(");
        createTableSyntax.append(VERSION_COLUMN);
        if (this.supportsIsDeletedColumnRmt && this.useReplacingMergeTreeIsDeletedColumn) {
            createTableSyntax.append(",").append(deleteColumnNameFinal);
        }
        createTableSyntax.append(")");
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

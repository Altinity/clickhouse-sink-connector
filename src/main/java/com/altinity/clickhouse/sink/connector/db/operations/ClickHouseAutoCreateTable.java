package com.altinity.clickhouse.sink.connector.db.operations;

import com.clickhouse.jdbc.ClickHouseConnection;
import org.apache.kafka.connect.data.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Map;

/**
 * Class that wraps all functionality
 * related to creating tables
 * from kafka sink record.
 */
public class ClickHouseAutoCreateTable extends ClickHouseTableOperationsBase{

    private static final Logger log = LoggerFactory.getLogger(ClickHouseAutoCreateTable.class.getName());

    public void createNewTable(String primaryKey, String tableName, Field[] fields, ClickHouseConnection connection) throws SQLException {
        Map<String, String> colNameToDataTypeMap = this.getColumnNameToCHDataTypeMapping(fields);
        String createTableQuery = this.createTableSyntax(primaryKey, tableName, colNameToDataTypeMap);

        this.runQuery(createTableQuery, connection);
    }

    /**
     * Function to generate CREATE TABLE for ClickHouse.
     * @param primaryKey
     * @param columnToDataTypesMap
     * @return CREATE TABLE query
     */
    public String createTableSyntax(String primaryKey, String tableName, Map<String, String> columnToDataTypesMap) {

        StringBuilder createTableSyntax = new StringBuilder();

        createTableSyntax.append("CREATE TABLE").append(" ").append(tableName).append("(");

        for(Map.Entry<String, String>  entry: columnToDataTypesMap.entrySet()) {
            createTableSyntax.append("`").append(entry.getKey()).append("`").append(" ").append(entry.getValue()).append(",");
        }
        createTableSyntax.deleteCharAt(createTableSyntax.lastIndexOf(","));

        createTableSyntax.append(")");
        createTableSyntax.append(" ");
        createTableSyntax.append("ENGINE = MergeTree");
        createTableSyntax.append(" ");
        createTableSyntax.append("PRIMARY KEY ").append(primaryKey);
        createTableSyntax.append(" ");
        createTableSyntax.append("ORDER BY ").append(primaryKey);

        return createTableSyntax.toString();
    }
}

package com.altinity.clickhouse.sink.connector.db.operations;

import com.clickhouse.jdbc.ClickHouseConnection;
import org.apache.kafka.connect.data.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Class that wraps all functionality
 * related to creating tables
 * from kafka sink record.
 */
public class ClickHouseAutoCreateTable extends ClickHouseTableOperationsBase{

    private static final Logger log = LoggerFactory.getLogger(ClickHouseAutoCreateTable.class.getName());

    public void createNewTable(ArrayList<String> primaryKey, String tableName, Field[] fields, ClickHouseConnection connection) throws SQLException {
        Map<String, String> colNameToDataTypeMap = this.getColumnNameToCHDataTypeMapping(fields);
        String createTableQuery = this.createTableSyntax(primaryKey, tableName, fields, colNameToDataTypeMap);

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

        createTableSyntax.append("CREATE TABLE").append(" ").append(tableName).append("(");

        for(Field f: fields) {
            String colName = f.name();
            String dataType = columnToDataTypesMap.get(colName);
            boolean isNull = false;
            if(f.schema().isOptional() == true) {
                isNull = true;
            }
            createTableSyntax.append("`").append(colName).append("`").append(" ").append(dataType);
            if(isNull) {
                createTableSyntax.append(" NULL");
            } else {
                createTableSyntax.append(" NOT NULL");
            }
            createTableSyntax.append(",");

        }
//        for(Map.Entry<String, String>  entry: columnToDataTypesMap.entrySet()) {
//            createTableSyntax.append("`").append(entry.getKey()).append("`").append(" ").append(entry.getValue()).append(",");
//        }
        //createTableSyntax.deleteCharAt(createTableSyntax.lastIndexOf(","));

        // Append sign and version columns
        createTableSyntax.append("`sign` Int8").append(",");
        createTableSyntax.append("`ver` UInt64");

        createTableSyntax.append(")");
        createTableSyntax.append(" ");
        createTableSyntax.append("ENGINE = ReplacingMergeTree(ver)");
        createTableSyntax.append(" ");
        createTableSyntax.append("PRIMARY KEY(");

        createTableSyntax.append(primaryKey.stream().map(Object::toString).collect(Collectors.joining(",")));
        createTableSyntax.append(") ");
        createTableSyntax.append("ORDER BY(");
        createTableSyntax.append(primaryKey.stream().map(Object::toString).collect(Collectors.joining(",")));
        createTableSyntax.append(")");

       return createTableSyntax.toString();
    }
}

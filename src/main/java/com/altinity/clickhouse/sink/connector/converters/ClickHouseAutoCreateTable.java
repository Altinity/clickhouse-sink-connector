package com.altinity.clickhouse.sink.connector.converters;

import com.clickhouse.client.ClickHouseDataType;
import com.clickhouse.jdbc.ClickHouseConnection;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * Class that wraps all functionality
 * related to creating tables
 * from kafka sink record.
 */
public class ClickHouseAutoCreateTable {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseAutoCreateTable.class.getName());

    /**
     * Function to create clickhouse create table
     * syntax.
     * @param fields
     */
    public Map<String, String> getColumnNameToCHDataTypeMapping(Field[] fields) {

        ClickHouseDataTypeMapper mapper = new ClickHouseDataTypeMapper();
        
        Map<String, String> columnToDataTypesMap = new HashMap<>();
        
        for(Field f: fields) {
            String colName = f.name();

            Schema.Type type = f.schema().type();
            String schemaName = f.schema().name();

            // Input: 
            ClickHouseDataType dataType = mapper.getClickHouseDataType(type, schemaName);
            if(dataType != null) {
                columnToDataTypesMap.put(colName, dataType.name());
            }
//
//            if(columnToDataTypesMap.isEmpty() == false) {
//                String createTableQuery = this.createTableSyntax(primaryKey, tableName, columnToDataTypesMap);
//            }
        }

        return columnToDataTypesMap;
    }

    /**
     * Function to execute the create table query using the ClickHouse JDBC connection
     * @param createTableQuery
     * @param conn
     */
    public void runCreateTableQuery(String createTableQuery, ClickHouseConnection conn) throws SQLException {

        if(conn == null) {
            log.error("ClickHouse connection not created");
            return;
        }

        Statement stmt = conn.createStatement();
        stmt.executeQuery(createTableQuery);

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

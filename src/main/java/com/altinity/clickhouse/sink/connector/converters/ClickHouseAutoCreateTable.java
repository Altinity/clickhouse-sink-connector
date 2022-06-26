package com.altinity.clickhouse.sink.connector.converters;

import java.util.Map;

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;


public class ClickHouseAutoCreateTable {

    public void createClickHouseTableSyntax(Field[] fields) {

        ClickHouseDataTypeMapper mapper = new ClickHouseDataTypeMapper();
        
        Map<String, String> columnToDataTypesMap = new HashMap<String, String>();
        
        for(Field f: fields) {
            String colName = f.name();

            Schema.Type type = f.schema().type();
            String schemaName = f.schema().name();

            // Input: 
            ClickHouseDataType dataType = mapper.getClickHouseDataType(type, schemaName);
            if(dataType != null) {

            }
        }
    }

    /**
     * Function to generate CREATE TABLE for ClickHouse.
     * @param primaryKey
     * @param columnToDataTypesMap
     * @return
     */
    public String createTableSyntax(String primaryKey, Map<String, String> columnToDataTypesMap) {


        StringBuilder createTableSyntax = new StringBuilder();

        createTableSyntax.append("CREATE TABLE(");

        for(Map.Entry<String, String>  entry: columnToDataTypesMap.entrySet()) {
            createTableSyntax.append("`").append(entry.getKey()).append("`").append(" ").append(entry.getValue()).append(",");

        }

        createTableSyntax.append(")");

        createTableSyntax.append(" ENGINE = MergeTree");
        createTableSyntax.append("PRIMARY KEY ").append(primaryKey);
        createTableSyntax.append("ORDER BY ").append(primaryKey);

        return createTableSyntax.toString();
    }
}

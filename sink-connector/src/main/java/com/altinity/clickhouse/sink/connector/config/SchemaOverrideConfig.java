package com.altinity.clickhouse.sink.connector.config;

import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * SchemaOverrideConfig loads the schema configuration from a YAML file.
 * It allows querying the configuration based on database and table name.
 */
public class SchemaOverrideConfig {

    private Map<String, Map<String, Map<String, Table>>> databases;
    // private Map<String, Map<String, Table>> databases;
    /**
     * Loads the table configurations from the provided YAML file.
     * @param filePath the path to the YAML file in the classpath (resources)
     * @throws IOException if there is an error loading the YAML file
     */
    public void loadTableConfigs(String filePath) throws IOException {
        // Load the file from the classpath
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream(filePath);
        if (inputStream == null) {
            throw new IOException("File not found in classpath: " + filePath);
        }

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        // Load the entire structure of the YAML into the 'databases' map
        databases = mapper.readValue(inputStream, Map.class);
    }

    /**
     * Retrieves the schema configuration for a specific database and table.
     * @param databaseName the name of the database
     * @param tableName the name of the table
     * @return the schema configuration for the table, or null if not found
     */
    public Table getTableConfig(String databaseName, String tableName) {
        // Retrieve the configuration for the database
        Map<String, Map<String, Table>> databaseAll = databases.get("databases");

        // Get the tables map for the specified database
        Map<String, Table> tables = databaseAll.get(databaseName);
        /*if(tables==null){
            return null;
        }*/

        String jsonString=JSONObject.toJSONString(tables);

        JSONObject jsonObject = JSONObject.parseObject(jsonString);
        if(jsonObject==null){
            return null;
        }
        JSONObject tablesObject  = jsonObject.getJSONObject("tables");
        if(tablesObject==null){
            return null;
        }

        // Convert the JSONObject to a Map with String as key and Table as value
        Map<String, Map<String,Table>> tableMap = tablesObject.toJavaObject(Map.class);
        Map<String,Table> subTableMap=tableMap.get(tableName);
        JSONObject jsonObjectTable=(JSONObject) JSONObject.toJSON(subTableMap);
        if(jsonObjectTable==null){
            return null;
        }

        Table table=jsonObjectTable.toJavaObject(Table.class);

        return table;
    }

    /**
     * A nested class representing the schema configuration for each table.
     * It holds partitioning, primary key, and settings information.
     */
    public static class Table {
        private String partitionBy;
        private String primaryKey;
        private String settings;

        // Getter methods
        public String getPartitionBy() {
            return partitionBy;
        }

        public String getPrimaryKey() {
            return primaryKey;
        }

        public String getSettings() {
            return settings;
        }

        // Setter methods
        public void setPartitionBy(String partitionBy) {
            this.partitionBy = partitionBy;
        }

        public void setPrimaryKey(String primaryKey) {
            this.primaryKey = primaryKey;
        }

        public void setSettings(String settings) {
            this.settings = settings;
        }

        @Override
        public String toString() {
            return "partition_by: " + partitionBy + ", primary_key: " + primaryKey + ", settings: " + settings;
        }
    }
}
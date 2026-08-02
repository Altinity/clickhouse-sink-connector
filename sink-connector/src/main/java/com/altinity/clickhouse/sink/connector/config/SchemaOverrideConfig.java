package com.altinity.clickhouse.sink.connector.config;

import java.util.Map;

/**
 * It allows querying the configuration based on database and table name.
 */
public class SchemaOverrideConfig {


    public static Table getTableConfig(String databaseName, String tableName, Map<String, String> configMap) {

        // If the tableName contains a dot, remove everything before the first dot (including the dot itself)
        if (tableName.contains(".")) {
            tableName = tableName.substring(tableName.indexOf('.') + 1);
        }

        Table tableConfig = new Table();

        // Construct the prefix for matching entries
        String prefix = "databases." + databaseName + ".tables." + tableName + ".";

        for (Map.Entry<String, String> entry : configMap.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            // Only process keys that start with the correct prefix
            if (key.startsWith(prefix)) {
                // Get the suffix after the prefix
                String suffix = key.substring(prefix.length());

                // Match specific suffixes and set corresponding fields
                if (suffix.equals("partition_by")) {
                    tableConfig.setPartitionBy(value);
                } else if (suffix.equals("primary_key")) {
                    tableConfig.setPrimaryKey(value);
                } else if (suffix.equals("settings")) {
                    tableConfig.setSettings(value);
                }
            }
        }

        return tableConfig;
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

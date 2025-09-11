package com.altinity.clickhouse.sink.connector.config;

import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * The {@code DefaultColumnDataTypeMappingConfig} class is responsible for loading the
 * "default_column_datatype_mapping" section from a YAML configuration file and returning it as a Map.
 * <p>
 * This mapping is used to configure the default data type for columns in the ClickHouse sink connector.
 * The YAML file should reside in the <code>src/main/resources</code> directory and be named
 * <code>config_schema_override.yml</code>.
 * </p>
 */
public class DefaultColumnDataTypeMappingConfig {

    /**
     * Extracts a new map containing only entries with keys starting with
     * "default_column_datatype_mapping." prefix. The prefix is removed from
     * the keys in the new map.
     *
     * @param configMap the original configuration map
     * @return a new map with cleaned keys and corresponding values
     */
    public static Map<String, String> loadDefaultColumnDataTypeMapping(Map<String, String> configMap) {
        Map<String, String> extractedMap = new HashMap<>();

        String prefix = "default_column_datatype_mapping.";

        // Iterate through the entries of the original map
        for (Map.Entry<String, String> entry : configMap.entrySet()) {
            String key = entry.getKey();

            // Check if the key starts with the desired prefix
            if (key.startsWith(prefix)) {
                // Remove the prefix to get the new key
                String newKey = key.substring(prefix.length());
                // Put the new key and original value into the new map
                extractedMap.put(newKey, entry.getValue());
            }
        }

        return extractedMap;
    }
}


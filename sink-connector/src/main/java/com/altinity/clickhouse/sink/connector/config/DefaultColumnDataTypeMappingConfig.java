package com.altinity.clickhouse.sink.connector.config;

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
     * Loads the "default_column_datatype_mapping" section from config_schema_override.yml and returns it as a Map.
     * <p>
     * This method performs the following steps:
     * <ul>
     *   <li>Creates a new instance of the SnakeYAML {@code Yaml} class.</li>
     *   <li>Loads the YAML file ("config_schema_override.yml") from the classpath. Ensure that the file is placed
     *   under the <code>src/main/resources</code> directory so that it is available at runtime.</li>
     *   <li>Parses the entire YAML file into a generic {@code Map<String, Object>}.</li>
     *   <li>Extracts the value corresponding to the key "default_column_datatype_mapping".</li>
     *   <li>Casts the extracted object to {@code Map<String, String>} if it matches the expected format.</li>
     *   <li>If the file is not found or the extracted section is not of the expected format, a {@code RuntimeException}
     *   is thrown.</li>
     * </ul>
     *
     * @return a {@code Map<String, String>} that contains the default column data type mapping.
     * @throws RuntimeException if the YAML file is not found or the mapping is in an unexpected format.
     */
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


package com.altinity.clickhouse.sink.connector.config;

import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;
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
    public static Map<String, String> loadDefaultColumnDataTypeMapping() {
        // Create an instance of SnakeYAML.
        Yaml yaml = new Yaml();

        // Load the YAML file from the classpath.
        // The file "config_schema_override.yml" should reside in the src/main/resources directory.
        InputStream inputStream = DefaultColumnDataTypeMappingConfig.class.getClassLoader()
                .getResourceAsStream("config_schema_override.yml");
        if (inputStream == null) {
            throw new RuntimeException("Unable to find config_schema_override.yml file. Please check the resource path!");
        }

        // Parse the entire YAML file into a Map object.
        // The resulting map (yamlData) contains keys corresponding to the top-level keys in the YAML file.
        Map<String, Object> yamlData = yaml.load(inputStream);

        // Retrieve the object corresponding to the "default_column_datatype_mapping" key.
        // This object should be a Map that represents the mapping of column names to their respective data types.
        Object mappingObj = yamlData.get("default_column_datatype_mapping");
        if (mappingObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, String> mapping = (Map<String, String>) mappingObj;
            return mapping;
        } else {
            // If the object is not a Map, throw an exception indicating an unexpected YAML structure.
            throw new RuntimeException("The 'default_column_datatype_mapping' section is not in the expected format.");
        }
    }
}

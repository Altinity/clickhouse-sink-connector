package com.altinity.clickhouse.sink.connector.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.altinity.clickhouse.sink.connector.config.DefaultColumnDataTypeMappingConfig.loadDefaultColumnDataTypeMapping;

public class DefaultColumnDataTypeMappingConfigTest {

    @Test
    public void testDefaultColumnDataTypeMapping(){

        // Call the method to load the default column data type mapping.
        Map<String, String> mapping = loadDefaultColumnDataTypeMapping();

        // Print the contents of the mapping to the console.
        System.out.println("Contents of default_column_datatype_mapping:");
        mapping.forEach((key, value) -> System.out.println(key + ": " + value));
    }
}

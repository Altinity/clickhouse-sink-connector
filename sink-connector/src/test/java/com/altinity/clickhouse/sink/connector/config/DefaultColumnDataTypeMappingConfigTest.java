package com.altinity.clickhouse.sink.connector.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static com.altinity.clickhouse.sink.connector.config.DefaultColumnDataTypeMappingConfig.loadDefaultColumnDataTypeMapping;

public class DefaultColumnDataTypeMappingConfigTest {

    @Test
    public void testDefaultColumnDataTypeMapping(){

        Map<String, String> configMap = new HashMap<>();
        configMap.put("default_column_datatype_mapping.transaction_id", "String");
        configMap.put("default_column_datatype_mapping.gmt_time", "String");
        configMap.put("replication.history.enable", "true");

        // Extract the relevant mapping
        Map<String, String> resultMap = loadDefaultColumnDataTypeMapping(configMap);

        // Print the result
        System.out.println(resultMap); // Output: {transaction_id=String, gmt_time=String}
    }
}

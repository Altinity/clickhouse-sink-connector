package com.altinity.clickhouse.debezium.embedded.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;

import java.util.Properties;

public class ConfigLoaderTest {

    @Test
    @DisplayName("Unit test to validate loading of config.yml into the application")
    public void testLoad() {
        ConfigLoader loader = new ConfigLoader();
        Properties props = loader.load("config.yml");

        Assertions.assertNotNull(props);
    }


    @Test
    @DisplayName("Unit test to validate loading of nested entries in config.yml")
    public void testLoadNestedEntries() {
        ConfigLoader loader = new ConfigLoader();
        Properties props = loader.load("config.yml");

        int defaultColumnDataTypeMappingCount = 0;
        // iterate through the properties and check if the nested entries are loaded correctly
        // the nested entries have the prefix ClickHouseSinkConnectorConfigVariables.DEFAULT_COLUMN_DATATYPE_MAPPING
        for (Object key : props.keySet()) {
            if (key.toString().startsWith(ClickHouseSinkConnectorConfigVariables.DEFAULT_COLUMN_DATATYPE_MAPPING.toString())) {
                Assertions.assertNotNull(props.getProperty(key.toString()));
                defaultColumnDataTypeMappingCount++;
            }
        }

        Assertions.assertEquals(defaultColumnDataTypeMappingCount, 7);
    }
}

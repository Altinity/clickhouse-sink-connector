package com.altinity.clickhouse.debezium.embedded.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

public class ConfigLoaderTest {

    @Test
    @DisplayName("Unit test to validate loading of config.yml into the application")
    public void testLoad() {
        ConfigLoader loader = new ConfigLoader();
        Properties props = loader.load("config.yml");

        Assertions.assertNotNull(props);
        Assertions.assertFalse(props.isEmpty());
    }

    @Test
    @DisplayName("Load Boolean, Long, and Integer YAML values without ClassCastException")
    public void testLoadTypedValues() {
        ConfigLoader loader = new ConfigLoader();
        Properties props = loader.load("config-typed.yml");

        Assertions.assertEquals("true", props.getProperty("metrics.enable"));
        Assertions.assertEquals("5000", props.getProperty("offset.flush.interval.ms"));
        Assertions.assertEquals("3306", props.getProperty("database.port"));
        Assertions.assertEquals("test-connector", props.getProperty("name"));
    }

    @Test
    @DisplayName("Reject YAML with arbitrary Java object tags (CVE-2022-1471)")
    public void testRejectsMaliciousYamlTags() {
        ConfigLoader loader = new ConfigLoader();
        Assertions.assertThrows(Exception.class, () -> loader.load("config-malicious.yml"));
    }
}

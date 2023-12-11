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
    }
}

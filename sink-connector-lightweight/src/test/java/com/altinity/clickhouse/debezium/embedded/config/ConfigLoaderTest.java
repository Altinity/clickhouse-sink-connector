package com.altinity.clickhouse.debezium.embedded.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.FileNotFoundException;
import java.util.Properties;

public class ConfigLoaderTest {

    @Test
    public void testLoad() {
        ConfigLoader loader = new ConfigLoader();
        Properties props = loader.load();

        Assertions.assertNotNull(props);
    }
}

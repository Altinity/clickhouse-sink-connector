package com.altinity.clickhouse.debezium.embedded.config;


import org.junit.Assert;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.SetEnvironmentVariable;

import java.util.Properties;

public class AppConfigurationTest {

    @Test
    @Disabled
    @SetEnvironmentVariable(key = "database.hostname", value = "localhost")
    @SetEnvironmentVariable(key = "database.port", value = "8123")
    @SetEnvironmentVariable(key = "database.user", value = "root")
    @SetEnvironmentVariable(key = "database.pass", value = "password")
    @SetEnvironmentVariable(key = "clickhouse.server.url", value = "localhost")
    @SetEnvironmentVariable(key = "clickhouse.server.user", value = "root")
    @SetEnvironmentVariable(key = "clickhouse.server.password", value = "root")
    @SetEnvironmentVariable(key = "clickhouse.server.port", value = "8123")
    @SetEnvironmentVariable(key = "snapshot.mode", value = "schema_only")
    public void testParseConfiguration() throws Exception {

        EnvironmentConfigurationService environmentConfigurationService = new EnvironmentConfigurationService();
        Properties resultProperties = environmentConfigurationService.parse();

        Assert.assertTrue(resultProperties.size() != 0);

        Assert.assertTrue(resultProperties.getProperty("database.hostname").equalsIgnoreCase("localhost"));
        Assert.assertTrue(resultProperties.getProperty("database.port").equalsIgnoreCase("8123"));
        Assert.assertTrue(resultProperties.getProperty("clickhouse.server.url").equalsIgnoreCase("localhost"));

        Assert.assertTrue(resultProperties.getProperty("snapshot.mode").equalsIgnoreCase("schema_only"));
    }
}

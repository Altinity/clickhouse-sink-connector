package com.altinity.clickhouse.sink.connector.db;


import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.clickhouse.jdbc.ClickHouseConnection;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.annotation.JsonAppend;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class BaseDbWriterTest {
    @Test
    public void testSplitJdbcProperties() {
        String jdbcProperties = "max_buffer_size=1000000,socket_timeout=10000";
        Map props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.JDBC_PARAMETERS.toString(), jdbcProperties);
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(props);

        Connection conn = BaseDbWriter.createConnection(
                "localhost", BaseDbWriter.DATABASE_CLIENT_NAME,"default", "",BaseDbWriter.SYSTEM_DB, config);
        Properties properties = new BaseDbWriter(
                "localhost",
                8123,
                "default",
                "default",
                "",
                config, conn
        ).splitJdbcProperties(jdbcProperties);
        Assert.assertEquals(properties.getProperty("max_buffer_size"), "1000000");
        Assert.assertEquals(properties.getProperty("socket_timeout"), "10000");
    }

    @Test
    public void testSplitJdbcPropertiesWithSsl() {
        // Note: the parameters should not contain extra spaces.
        String jdbcProperties = "ssl=true,sslmode=none";
        Map props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.JDBC_PARAMETERS.toString(), jdbcProperties);
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(props);

        Connection conn = BaseDbWriter.createConnection(
                "localhost", BaseDbWriter.DATABASE_CLIENT_NAME,"default", "",BaseDbWriter.SYSTEM_DB, config);
        Properties properties = new BaseDbWriter(
                "localhost",
                8123,
                "default",
                "default",
                "",
                config, conn
        ).splitJdbcProperties(jdbcProperties);
        Assert.assertEquals(properties.getProperty("ssl"), "true");
        Assert.assertEquals(properties.getProperty("sslmode"), "none");
    }
}

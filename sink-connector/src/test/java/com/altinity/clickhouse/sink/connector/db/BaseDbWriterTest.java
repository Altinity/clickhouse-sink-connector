package com.altinity.clickhouse.sink.connector.db;


import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.clickhouse.jdbc.ClickHouseConnection;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.annotation.JsonAppend;

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

        ClickHouseConnection conn = BaseDbWriter.createConnection(
                "localhost", "client_1","default", "", config);
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
}

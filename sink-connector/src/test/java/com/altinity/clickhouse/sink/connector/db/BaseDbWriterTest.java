package com.altinity.clickhouse.sink.connector.db;


import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.util.Properties;

public class BaseDbWriterTest {
    @Test
    public void testSplitJdbcProperties() {
        String jdbcProperties = "max_buffer_size=1000000,socket_timeout=10000";
        Properties properties = new BaseDbWriter(
                "localhost",
                8123,
                "default",
                "default",
                "",
                null
        ).splitJdbcProperties(jdbcProperties);
        Assert.assertEquals(properties.getProperty("max_buffer_size"), "1000000");
        Assert.assertEquals(properties.getProperty("socket_timeout"), "10000");
    }
}

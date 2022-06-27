package com.altinity.clickhouse.sink.connector.converters;

import com.clickhouse.client.ClickHouseDataType;
import org.apache.kafka.connect.data.Schema;
import org.junit.Assert;
import org.junit.Test;

public class ClickHouseDataTypeMapperTest {

    @Test
    public void getClickHouseDataType() {
        ClickHouseDataType chDataType = ClickHouseDataTypeMapper.getClickHouseDataType(Schema.Type.INT16, null);

        Assert.assertTrue(chDataType.name().equalsIgnoreCase("INT16"));
    }
}

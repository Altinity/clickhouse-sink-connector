package com.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseDataTypeMapper;
import com.clickhouse.client.ClickHouseDataType;
import io.debezium.time.Date;
import io.debezium.time.Time;
import org.apache.kafka.connect.data.Schema;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

public class ClickHouseDataTypeMapperTest {

    @Test
    public void getClickHouseDataType() {
        ClickHouseDataType chDataType = ClickHouseDataTypeMapper.getClickHouseDataType(Schema.Type.INT16, null);
        Assert.assertTrue(chDataType.name().equalsIgnoreCase("INT16"));

        chDataType = ClickHouseDataTypeMapper.getClickHouseDataType(Schema.Type.INT32, null);
        Assert.assertTrue(chDataType.name().equalsIgnoreCase("INT32"));

        chDataType = ClickHouseDataTypeMapper.getClickHouseDataType(Schema.BYTES_SCHEMA.type(), null);
        Assert.assertTrue(chDataType.name().equalsIgnoreCase("String"));

        chDataType = ClickHouseDataTypeMapper.getClickHouseDataType(Schema.INT32_SCHEMA.type(), Time.SCHEMA_NAME);
        Assert.assertTrue(chDataType.name().equalsIgnoreCase("String"));

        chDataType = ClickHouseDataTypeMapper.getClickHouseDataType(Schema.INT32_SCHEMA.type(), Date.SCHEMA_NAME);
        Assert.assertTrue(chDataType.name().equalsIgnoreCase("Date32"));


    }

    @Test
    public void convert() {
        //Integer tests.
       // ClickHouseDataTypeMapper.convert(Schema.INT16_SCHEMA.type(), null, 244223232, 1, ps);


    }
}

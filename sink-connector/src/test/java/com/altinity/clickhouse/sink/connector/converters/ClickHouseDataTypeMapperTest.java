package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import com.clickhouse.data.ClickHouseDataType;
import com.clickhouse.jdbc.ClickHouseConnection;
import io.debezium.data.VariableScaleDecimal;
import io.debezium.time.Date;
import io.debezium.time.Time;
import org.apache.kafka.connect.data.Schema;
import org.junit.Assert;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.*;
import java.time.ZoneId;
import java.util.HashMap;

@Testcontainers
public class ClickHouseDataTypeMapperTest {

//    @Container
//    private ClickHouseContainer clickHouseContainer = new ClickHouseContainer("clickhouse/clickhouse-server:latest")
//            .withInitScript("./datatypes.sql");

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

        chDataType = ClickHouseDataTypeMapper.getClickHouseDataType(Schema.Type.STRUCT, VariableScaleDecimal.LOGICAL_NAME);
        Assert.assertTrue(chDataType.name().equalsIgnoreCase("Decimal"));

    }

    @Test
    public void getUnsignedClickHouseType() {
        Assert.assertEquals("UInt8",
                ClickHouseDataTypeMapper.getUnsignedClickHouseType("TINYINT UNSIGNED"));
        Assert.assertEquals("UInt16",
                ClickHouseDataTypeMapper.getUnsignedClickHouseType("SMALLINT UNSIGNED"));
        Assert.assertEquals("UInt32",
                ClickHouseDataTypeMapper.getUnsignedClickHouseType("MEDIUMINT UNSIGNED"));
        Assert.assertEquals("UInt32",
                ClickHouseDataTypeMapper.getUnsignedClickHouseType("INT UNSIGNED"));
        Assert.assertEquals("UInt32",
                ClickHouseDataTypeMapper.getUnsignedClickHouseType("INTEGER UNSIGNED"));
        Assert.assertEquals("UInt64",
                ClickHouseDataTypeMapper.getUnsignedClickHouseType("BIGINT UNSIGNED"));

        // Case-insensitive, display width and ZEROFILL suffix tolerated.
        Assert.assertEquals("UInt32",
                ClickHouseDataTypeMapper.getUnsignedClickHouseType("int(10) unsigned"));
        Assert.assertEquals("UInt32",
                ClickHouseDataTypeMapper.getUnsignedClickHouseType("int unsigned zerofill"));

        // Signed / unrelated types are not remapped.
        Assert.assertNull(ClickHouseDataTypeMapper.getUnsignedClickHouseType("INT"));
        Assert.assertNull(ClickHouseDataTypeMapper.getUnsignedClickHouseType("VARCHAR(255)"));
        Assert.assertNull(ClickHouseDataTypeMapper.getUnsignedClickHouseType(null));
    }

}

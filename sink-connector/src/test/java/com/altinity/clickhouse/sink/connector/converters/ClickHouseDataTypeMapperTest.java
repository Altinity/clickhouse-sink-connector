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

    @Test
    public void getUnsignedClickHouseTypeIsAFunctionOfTheSourceTypeAlone() {
        // Every spelling of one MySQL type must resolve to ONE ClickHouse
        // type. ZEROFILL implies UNSIGNED in MySQL, so all of these are
        // "smallint unsigned" and must all be UInt16 -- not UInt16 for some
        // columns and a wider signed type for others in the same database.
        String[] smallintUnsignedSpellings = {
            "SMALLINT UNSIGNED",
            "smallint unsigned",
            "SMALLINT(5) UNSIGNED",
            "SMALLINT UNSIGNED ZEROFILL",
            "SMALLINT(5) UNSIGNED ZEROFILL",
            "smallint unsigned zerofill",
            "  SMALLINT   UNSIGNED  ",
            "INT2 UNSIGNED",
        };
        for (String spelling : smallintUnsignedSpellings) {
            Assert.assertEquals(
                    "smallint unsigned must always map to UInt16: " + spelling,
                    "UInt16",
                    ClickHouseDataTypeMapper.getUnsignedClickHouseType(spelling));
        }

        // The other widths, including their MySQL aliases, are equally exact.
        Assert.assertEquals("UInt8",
                ClickHouseDataTypeMapper.getUnsignedClickHouseType("TINYINT UNSIGNED ZEROFILL"));
        Assert.assertEquals("UInt8",
                ClickHouseDataTypeMapper.getUnsignedClickHouseType("INT1 UNSIGNED"));
        Assert.assertEquals("UInt32",
                ClickHouseDataTypeMapper.getUnsignedClickHouseType("MEDIUMINT UNSIGNED ZEROFILL"));
        Assert.assertEquals("UInt32",
                ClickHouseDataTypeMapper.getUnsignedClickHouseType("INT3 UNSIGNED"));
        Assert.assertEquals("UInt32",
                ClickHouseDataTypeMapper.getUnsignedClickHouseType("MIDDLEINT UNSIGNED"));
        Assert.assertEquals("UInt32",
                ClickHouseDataTypeMapper.getUnsignedClickHouseType("INT4 UNSIGNED"));
        Assert.assertEquals("UInt64",
                ClickHouseDataTypeMapper.getUnsignedClickHouseType("BIGINT UNSIGNED ZEROFILL"));
        Assert.assertEquals("UInt64",
                ClickHouseDataTypeMapper.getUnsignedClickHouseType("INT8 UNSIGNED"));

        // Control: an explicit SIGNED must NOT be mistaken for unsigned, and
        // stripping "signed" must not strip the "signed" inside "unsigned".
        Assert.assertNull(
                ClickHouseDataTypeMapper.getUnsignedClickHouseType("SMALLINT SIGNED"));
        Assert.assertNull(
                ClickHouseDataTypeMapper.getUnsignedClickHouseType("BIGINT SIGNED"));
        Assert.assertNull(
                ClickHouseDataTypeMapper.getUnsignedClickHouseType("SMALLINT ZEROFILL"));
    }

    @Test
    public void normalizeMysqlTypeName() {
        Assert.assertEquals("smallint unsigned",
                ClickHouseDataTypeMapper.normalizeMysqlTypeName("SMALLINT(5) UNSIGNED ZEROFILL"));
        Assert.assertEquals("smallint",
                ClickHouseDataTypeMapper.normalizeMysqlTypeName("SMALLINT SIGNED"));
        Assert.assertEquals("varchar",
                ClickHouseDataTypeMapper.normalizeMysqlTypeName("VARCHAR(255)"));
        // "unsigned" survives the "signed" strip -- no word boundary inside it.
        Assert.assertEquals("bigint unsigned",
                ClickHouseDataTypeMapper.normalizeMysqlTypeName("BIGINT UNSIGNED"));
        Assert.assertNull(ClickHouseDataTypeMapper.normalizeMysqlTypeName(null));
    }

}

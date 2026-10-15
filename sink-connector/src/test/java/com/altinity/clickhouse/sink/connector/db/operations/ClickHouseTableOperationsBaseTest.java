package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseDataTypeMapper;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseTableOperationsBase;
import io.debezium.time.MicroTimestamp;
import io.debezium.time.Timestamp;
import io.debezium.time.ZonedTimestamp;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

public class ClickHouseTableOperationsBaseTest {

    @Test
    public void getColumnNameToCHDataTypeMappingTest() {
        ClickHouseTableOperationsBase base = new ClickHouseTableOperationsBase();

        Field[] fields = new Field[4];

        fields[0] =(new Field("totalAmount", 1, SchemaBuilder.type(Schema.BYTES_SCHEMA.type()).
                name(Decimal.LOGICAL_NAME).parameter(ClickHouseTableOperationsBase.SCALE, "2")
                .parameter(ClickHouseTableOperationsBase.PRECISION, "4").build()));
        fields[1] = (new Field("amount", 2, SchemaBuilder.type(Schema.BYTES_SCHEMA.type()).
                name(Decimal.LOGICAL_NAME).build()));

        // DateTime64(3)
        fields[2] = (new Field("date_milli", 3, SchemaBuilder.type(Schema.Type.INT64).
                name(Timestamp.SCHEMA_NAME).build()));
        // DateTime64(6)
        fields[3] = (new Field("date_micro", 4, SchemaBuilder.type(Schema.Type.INT64).
                name(MicroTimestamp.SCHEMA_NAME).build()));

        Map<String, String> result = base.getColumnNameToCHDataTypeMapping(fields,new ClickHouseSinkConnectorConfig(new HashMap<>()));

        Assert.assertTrue(result.get("totalAmount").equalsIgnoreCase("Decimal(4,2)"));
        // A DECIMAL without dimensions is DECIMAL(10,0) in MySQL and Decimal(10, 0)
        // on the DDL path; this assertion previously pinned Decimal(10,2), a
        // scale the source never had (Spec 08.05 section 3.1.1).
        Assert.assertEquals("Decimal(10,0)", result.get("amount"));

        // Without a propagated source type the widest precision the logical
        // type carries is declared.
        Assert.assertTrue(result.get("date_milli").equalsIgnoreCase("DateTime64(3, 'UTC')"));
        Assert.assertTrue(result.get("date_micro").equalsIgnoreCase("DateTime64(6, 'UTC')"));

    }

    private static Field sourceTyped(String name, int index, SchemaBuilder builder,
                                     String sourceType, Integer length) {
        builder.parameter(ClickHouseDataTypeMapper.DEBEZIUM_SOURCE_COLUMN_TYPE_PARAM, sourceType);
        if (length != null) {
            builder.parameter("__debezium.source.column.length", String.valueOf(length));
        }
        return new Field(name, index, builder.build());
    }

    /**
     * Spec 08.05 section 3.1.1: with Debezium's propagated source metadata the
     * record-schema path declares the type the DDL path declares.
     */
    @Test
    public void getColumnNameToCHDataTypeMappingSourceTypeParityTest() {
        Field[] fields = new Field[]{
                sourceTyped("tiny", 0, SchemaBuilder.int16(), "TINYINT", null),
                sourceTyped("tiny_null", 1, SchemaBuilder.int16().optional(), "TINYINT", null),
                sourceTyped("uint_null", 2, SchemaBuilder.int64().optional(), "INT UNSIGNED", null),
                sourceTyped("dt0", 3, SchemaBuilder.int64().name(Timestamp.SCHEMA_NAME), "DATETIME", null),
                sourceTyped("dt2", 4, SchemaBuilder.int64().name(Timestamp.SCHEMA_NAME), "DATETIME", 2),
                sourceTyped("dt6", 5, SchemaBuilder.int64().name(MicroTimestamp.SCHEMA_NAME), "DATETIME", 6),
                sourceTyped("ts0", 6, SchemaBuilder.string().name(ZonedTimestamp.SCHEMA_NAME), "TIMESTAMP", null),
                sourceTyped("ts6", 7, SchemaBuilder.string().name(ZonedTimestamp.SCHEMA_NAME).optional(), "TIMESTAMP", 6),
                sourceTyped("dec_scale_only", 8, Decimal.builder(3), "DECIMAL", null),
        };

        Map<String, String> result = new ClickHouseTableOperationsBase()
                .getColumnNameToCHDataTypeMapping(fields, new ClickHouseSinkConnectorConfig(new HashMap<>()));

        Assert.assertEquals("a signed TINYINT is Int8 on the DDL path; Int16 loses that agreement",
                "Int8", result.get("tiny"));
        Assert.assertEquals("Nullable(Int8)", result.get("tiny_null"));
        Assert.assertEquals("an optional unsigned column must be Nullable, or ADD COLUMN rejects the first NULL",
                "Nullable(UInt32)", result.get("uint_null"));
        Assert.assertEquals("DATETIME without a fraction is precision 0", "DateTime64(0, 'UTC')", result.get("dt0"));
        Assert.assertEquals("DateTime64(2, 'UTC')", result.get("dt2"));
        Assert.assertEquals("DateTime64(6, 'UTC')", result.get("dt6"));
        Assert.assertEquals("DateTime64(0, 'UTC')", result.get("ts0"));
        Assert.assertEquals("Nullable(DateTime64(6, 'UTC'))", result.get("ts6"));
        Assert.assertEquals("a missing precision defaults to max(10, scale)", "Decimal(10,3)", result.get("dec_scale_only"));
    }

    @Test
    public void getColumnNameToCHDataTypeMappingUnsignedTest() {
        ClickHouseTableOperationsBase base = new ClickHouseTableOperationsBase();

        Field[] fields = new Field[6];
        // MySQL unsigned integers are promoted to a wider signed Kafka Connect
        // type by Debezium; the original type is carried in the
        // __debezium.source.column.type schema parameter.
        fields[0] = unsignedField("tiny_col", 1, Schema.Type.INT16, "TINYINT UNSIGNED");
        fields[1] = unsignedField("small_col", 2, Schema.Type.INT32, "SMALLINT UNSIGNED");
        fields[2] = unsignedField("medium_col", 3, Schema.Type.INT32, "MEDIUMINT UNSIGNED");
        fields[3] = unsignedField("int_col", 4, Schema.Type.INT64, "INT UNSIGNED");
        fields[4] = unsignedField("big_col", 5, Schema.Type.INT64, "BIGINT UNSIGNED");
        // Signed column must keep its signed mapping.
        fields[5] = new Field("signed_int_col", 6, SchemaBuilder.type(Schema.Type.INT32).build());

        Map<String, String> result = base.getColumnNameToCHDataTypeMapping(
                fields, new ClickHouseSinkConnectorConfig(new HashMap<>()));

        Assert.assertEquals("UInt8", result.get("tiny_col"));
        Assert.assertEquals("UInt16", result.get("small_col"));
        Assert.assertEquals("UInt32", result.get("medium_col"));
        Assert.assertEquals("UInt32", result.get("int_col"));
        Assert.assertEquals("UInt64", result.get("big_col"));
        Assert.assertEquals("Int32", result.get("signed_int_col"));
    }

    @Test
    public void getColumnNameToCHDataTypeMappingUnsignedFallbackTest() {
        ClickHouseTableOperationsBase base = new ClickHouseTableOperationsBase();

        // No __debezium.source.column.type parameter -> falls back to the
        // signed mapping derived purely from the Kafka Connect schema type.
        Field[] fields = new Field[1];
        fields[0] = new Field("small_col", 1, SchemaBuilder.type(Schema.Type.INT32).build());

        Map<String, String> result = base.getColumnNameToCHDataTypeMapping(
                fields, new ClickHouseSinkConnectorConfig(new HashMap<>()));

        Assert.assertEquals("Int32", result.get("small_col"));
    }

    private static Field unsignedField(String name, int index, Schema.Type type,
                                       String sourceColumnType) {
        return new Field(name, index, SchemaBuilder.type(type)
                .parameter(ClickHouseDataTypeMapper.DEBEZIUM_SOURCE_COLUMN_TYPE_PARAM,
                        sourceColumnType)
                .build());
    }
}

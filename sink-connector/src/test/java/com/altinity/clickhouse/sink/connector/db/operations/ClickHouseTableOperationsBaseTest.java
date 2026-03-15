package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseDataTypeMapper;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseTableOperationsBase;
import io.debezium.time.MicroTimestamp;
import io.debezium.time.Timestamp;
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
        Assert.assertTrue(result.get("amount").equalsIgnoreCase("Decimal(10,2)"));

        Assert.assertTrue(result.get("date_milli").equalsIgnoreCase("DateTime64(3, 'UTC')"));
        Assert.assertTrue(result.get("date_micro").equalsIgnoreCase("DateTime64(6, 'UTC')"));

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

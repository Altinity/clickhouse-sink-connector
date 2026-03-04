package com.altinity.clickhouse.sink.connector.converters;

import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link ClickHouseDataTypeMapper#mapDebeziumSchemaToDDL(Schema)}.
 *
 * <p>This method is the centralized DDL-type mapping used by both MySQL DDL parsing
 * (via {@code DataTypeConverter}) and PostgreSQL schema reconciliation
 * (via {@code PostgresSchemaReconciler}).
 *
 * <p>No live database connection is needed – all tests exercise pure mapping logic.
 */
public class ClickHouseDataTypeMapperDDLTest {

    // ------------------------------------------------------------------
    // Primitive Kafka-Connect base types
    // ------------------------------------------------------------------

    @Test
    @DisplayName("INT8  → Nullable(Int8)")
    public void testInt8() {
        Schema s = SchemaBuilder.int8().optional().build();
        assertEquals("Nullable(Int8)", ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL(s));
    }

    @Test
    @DisplayName("INT16 → Nullable(Int16)")
    public void testInt16() {
        Schema s = SchemaBuilder.int16().optional().build();
        assertEquals("Nullable(Int16)", ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL(s));
    }

    @Test
    @DisplayName("INT32 → Nullable(Int32)")
    public void testInt32() {
        Schema s = SchemaBuilder.int32().optional().build();
        assertEquals("Nullable(Int32)", ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL(s));
    }

    @Test
    @DisplayName("INT64 → Nullable(Int64)")
    public void testInt64() {
        Schema s = SchemaBuilder.int64().optional().build();
        assertEquals("Nullable(Int64)", ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL(s));
    }

    @Test
    @DisplayName("FLOAT32 → Nullable(Float32)")
    public void testFloat32() {
        Schema s = SchemaBuilder.float32().optional().build();
        assertEquals("Nullable(Float32)", ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL(s));
    }

    @Test
    @DisplayName("FLOAT64 → Nullable(Float64)")
    public void testFloat64() {
        Schema s = SchemaBuilder.float64().optional().build();
        assertEquals("Nullable(Float64)", ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL(s));
    }

    @Test
    @DisplayName("BOOLEAN → Nullable(UInt8)")
    public void testBoolean() {
        Schema s = SchemaBuilder.bool().optional().build();
        assertEquals("Nullable(UInt8)", ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL(s));
    }

    @Test
    @DisplayName("STRING → Nullable(String)")
    public void testString() {
        Schema s = SchemaBuilder.string().optional().build();
        assertEquals("Nullable(String)", ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL(s));
    }

    @Test
    @DisplayName("BYTES → Nullable(String)")
    public void testBytes() {
        Schema s = SchemaBuilder.bytes().optional().build();
        assertEquals("Nullable(String)", ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL(s));
    }

    // ------------------------------------------------------------------
    // Debezium logical types (identified by schema name)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("io.debezium.time.MicroTimestamp → Nullable(DateTime64(6))")
    public void testMicroTimestamp() {
        Schema s = SchemaBuilder.int64()
                .name("io.debezium.time.MicroTimestamp")
                .optional()
                .build();
        assertEquals("Nullable(DateTime64(6))", ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL(s));
    }

    @Test
    @DisplayName("io.debezium.time.Timestamp → Nullable(DateTime64(3))")
    public void testTimestamp() {
        Schema s = SchemaBuilder.int64()
                .name("io.debezium.time.Timestamp")
                .optional()
                .build();
        assertEquals("Nullable(DateTime64(3))", ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL(s));
    }

    @Test
    @DisplayName("io.debezium.time.NanoTimestamp → Nullable(DateTime64(9))")
    public void testNanoTimestamp() {
        Schema s = SchemaBuilder.int64()
                .name("io.debezium.time.NanoTimestamp")
                .optional()
                .build();
        assertEquals("Nullable(DateTime64(9))", ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL(s));
    }

    @Test
    @DisplayName("io.debezium.time.ZonedTimestamp → Nullable(DateTime64(6, 'UTC'))")
    public void testZonedTimestamp() {
        Schema s = SchemaBuilder.string()
                .name("io.debezium.time.ZonedTimestamp")
                .optional()
                .build();
        assertEquals("Nullable(DateTime64(6, 'UTC'))", ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL(s));
    }

    @Test
    @DisplayName("io.debezium.time.Date → Nullable(Date32)")
    public void testDate() {
        Schema s = SchemaBuilder.int32()
                .name("io.debezium.time.Date")
                .optional()
                .build();
        assertEquals("Nullable(Date32)", ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL(s));
    }

    @Test
    @DisplayName("io.debezium.time.MicroTime → Nullable(Int64)")
    public void testMicroTime() {
        Schema s = SchemaBuilder.int64()
                .name("io.debezium.time.MicroTime")
                .optional()
                .build();
        assertEquals("Nullable(Int64)", ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL(s));
    }

    @Test
    @DisplayName("io.debezium.data.Uuid → Nullable(UUID)")
    public void testUuid() {
        Schema s = SchemaBuilder.string()
                .name("io.debezium.data.Uuid")
                .optional()
                .build();
        assertEquals("Nullable(UUID)", ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL(s));
    }

    @Test
    @DisplayName("io.debezium.data.Json → Nullable(String)")
    public void testJson() {
        Schema s = SchemaBuilder.string()
                .name("io.debezium.data.Json")
                .optional()
                .build();
        assertEquals("Nullable(String)", ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL(s));
    }

    @Test
    @DisplayName("io.debezium.data.Enum → Nullable(String)")
    public void testEnum() {
        Schema s = SchemaBuilder.string()
                .name("io.debezium.data.Enum")
                .optional()
                .build();
        assertEquals("Nullable(String)", ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL(s));
    }

    @Test
    @DisplayName("io.debezium.data.Bits → Nullable(String)")
    public void testBits() {
        Schema s = SchemaBuilder.bytes()
                .name("io.debezium.data.Bits")
                .optional()
                .build();
        assertEquals("Nullable(String)", ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL(s));
    }

    // ------------------------------------------------------------------
    // Kafka Connect Decimal logical type
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Decimal(precision=10, scale=2) → Nullable(Decimal(10, 2))")
    public void testDecimalWithPrecisionAndScale() {
        Schema s = Decimal.builder(2)
                .parameter("connect.decimal.precision", "10")
                .optional()
                .build();
        assertEquals("Nullable(Decimal(10, 2))", ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL(s));
    }

    @Test
    @DisplayName("Decimal with scale only → Nullable(Decimal(38, scale))")
    public void testDecimalScaleOnly() {
        Schema s = Decimal.builder(5)
                .optional()
                .build();
        // No precision parameter → defaults to 38
        assertEquals("Nullable(Decimal(38, 5))", ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL(s));
    }

    // ------------------------------------------------------------------
    // Edge cases
    // ------------------------------------------------------------------

    @Test
    @DisplayName("null schema → Nullable(String)")
    public void testNullSchema() {
        assertEquals("Nullable(String)", ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL(null));
    }

    @Test
    @DisplayName("Unknown logical type name falls through to base-type mapping")
    public void testUnknownLogicalTypeFallsThrough() {
        Schema s = SchemaBuilder.int64()
                .name("com.example.unknown.LogicalType")
                .optional()
                .build();
        assertEquals("Nullable(Int64)", ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL(s));
    }

    @Test
    @DisplayName("ARRAY base type → Nullable(String)")
    public void testArray() {
        Schema s = SchemaBuilder.array(Schema.STRING_SCHEMA).optional().build();
        assertEquals("Nullable(String)", ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL(s));
    }

    @Test
    @DisplayName("MAP base type → Nullable(String)")
    public void testMap() {
        Schema s = SchemaBuilder.map(Schema.STRING_SCHEMA, Schema.STRING_SCHEMA).optional().build();
        assertEquals("Nullable(String)", ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL(s));
    }

    @Test
    @DisplayName("STRUCT base type → Nullable(String)")
    public void testStruct() {
        Schema s = SchemaBuilder.struct()
                .field("dummy", Schema.STRING_SCHEMA)
                .optional()
                .build();
        assertEquals("Nullable(String)", ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL(s));
    }
}

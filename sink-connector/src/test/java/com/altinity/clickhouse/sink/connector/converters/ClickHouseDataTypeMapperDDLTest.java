package com.altinity.clickhouse.sink.connector.converters;

import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

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
    // Primitive Kafka-Connect base types (map-driven)
    // ------------------------------------------------------------------

    static Stream<Arguments> primitiveTypes() {
        return Stream.of(
                Arguments.of("INT8",    SchemaBuilder.int8().optional().build(),    "Nullable(Int8)"),
                Arguments.of("INT16",   SchemaBuilder.int16().optional().build(),   "Nullable(Int16)"),
                Arguments.of("INT32",   SchemaBuilder.int32().optional().build(),   "Nullable(Int32)"),
                Arguments.of("INT64",   SchemaBuilder.int64().optional().build(),   "Nullable(Int64)"),
                Arguments.of("FLOAT32", SchemaBuilder.float32().optional().build(), "Nullable(Float32)"),
                Arguments.of("FLOAT64", SchemaBuilder.float64().optional().build(), "Nullable(Float64)"),
                Arguments.of("BOOLEAN", SchemaBuilder.bool().optional().build(),    "Nullable(UInt8)"),
                Arguments.of("STRING",  SchemaBuilder.string().optional().build(),  "Nullable(String)"),
                Arguments.of("BYTES",   SchemaBuilder.bytes().optional().build(),   "Nullable(String)")
        );
    }

    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("primitiveTypes")
    @DisplayName("Primitive Kafka-Connect base types")
    public void testPrimitiveTypes(String label, Schema schema, String expectedCHType) {
        assertEquals(expectedCHType, ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL(schema));
    }

    // ------------------------------------------------------------------
    // Debezium logical types (map-driven)
    // ------------------------------------------------------------------

    static Stream<Arguments> debeziumLogicalTypes() {
        return Stream.of(
                Arguments.of("io.debezium.time.MicroTimestamp",
                        SchemaBuilder.int64().name("io.debezium.time.MicroTimestamp").optional().build(),
                        "Nullable(DateTime64(6))"),
                Arguments.of("io.debezium.time.Timestamp",
                        SchemaBuilder.int64().name("io.debezium.time.Timestamp").optional().build(),
                        "Nullable(DateTime64(3))"),
                Arguments.of("io.debezium.time.NanoTimestamp",
                        SchemaBuilder.int64().name("io.debezium.time.NanoTimestamp").optional().build(),
                        "Nullable(DateTime64(9))"),
                Arguments.of("io.debezium.time.ZonedTimestamp",
                        SchemaBuilder.string().name("io.debezium.time.ZonedTimestamp").optional().build(),
                        "Nullable(DateTime64(6, 'UTC'))"),
                Arguments.of("io.debezium.time.Date",
                        SchemaBuilder.int32().name("io.debezium.time.Date").optional().build(),
                        "Nullable(Date32)"),
                Arguments.of("io.debezium.time.MicroTime",
                        SchemaBuilder.int64().name("io.debezium.time.MicroTime").optional().build(),
                        "Nullable(Int64)"),
                Arguments.of("io.debezium.data.Uuid",
                        SchemaBuilder.string().name("io.debezium.data.Uuid").optional().build(),
                        "Nullable(UUID)"),
                Arguments.of("io.debezium.data.Json",
                        SchemaBuilder.string().name("io.debezium.data.Json").optional().build(),
                        "Nullable(String)"),
                Arguments.of("io.debezium.data.Enum",
                        SchemaBuilder.string().name("io.debezium.data.Enum").optional().build(),
                        "Nullable(String)"),
                Arguments.of("io.debezium.data.Bits",
                        SchemaBuilder.bytes().name("io.debezium.data.Bits").optional().build(),
                        "Nullable(String)")
        );
    }

    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("debeziumLogicalTypes")
    @DisplayName("Debezium logical types")
    public void testDebeziumLogicalTypes(String schemaName, Schema schema, String expectedCHType) {
        assertEquals(expectedCHType, ClickHouseDataTypeMapper.mapDebeziumSchemaToDDL(schema));
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

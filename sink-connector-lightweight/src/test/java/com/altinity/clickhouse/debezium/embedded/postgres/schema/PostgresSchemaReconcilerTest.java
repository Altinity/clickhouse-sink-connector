package com.altinity.clickhouse.debezium.embedded.postgres.schema;

import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link PostgresSchemaReconciler#mapDebeziumTypeToClickHouse(Schema)}.
 *
 * <p>The method is package-private, so these tests live in the same package as
 * the class under test.
 *
 * <p>No live database connection is needed – {@link PostgresSchemaReconciler} is
 * constructed with {@code null} for both {@code writer} and {@code config}; those
 * fields are only accessed inside {@link PostgresSchemaReconciler#addMissingColumns},
 * which is NOT called by these tests.
 */
public class PostgresSchemaReconcilerTest {

    /** Instance under test – writer/config are null because we only call mapDebeziumTypeToClickHouse. */
    private PostgresSchemaReconciler reconciler;

    @BeforeEach
    public void setUp() {
        reconciler = new PostgresSchemaReconciler(null, null);
    }

    // ------------------------------------------------------------------
    // Primitive Kafka-Connect base types
    // ------------------------------------------------------------------

    @Test
    @DisplayName("INT8  → Nullable(Int8)")
    public void testInt8() {
        Schema s = SchemaBuilder.int8().optional().build();
        assertEquals("Nullable(Int8)", reconciler.mapDebeziumTypeToClickHouse(s));
    }

    @Test
    @DisplayName("INT16 → Nullable(Int16)")
    public void testInt16() {
        Schema s = SchemaBuilder.int16().optional().build();
        assertEquals("Nullable(Int16)", reconciler.mapDebeziumTypeToClickHouse(s));
    }

    @Test
    @DisplayName("INT32 → Nullable(Int32)")
    public void testInt32() {
        Schema s = SchemaBuilder.int32().optional().build();
        assertEquals("Nullable(Int32)", reconciler.mapDebeziumTypeToClickHouse(s));
    }

    @Test
    @DisplayName("INT64 → Nullable(Int64)")
    public void testInt64() {
        Schema s = SchemaBuilder.int64().optional().build();
        assertEquals("Nullable(Int64)", reconciler.mapDebeziumTypeToClickHouse(s));
    }

    @Test
    @DisplayName("FLOAT32 → Nullable(Float32)")
    public void testFloat32() {
        Schema s = SchemaBuilder.float32().optional().build();
        assertEquals("Nullable(Float32)", reconciler.mapDebeziumTypeToClickHouse(s));
    }

    @Test
    @DisplayName("FLOAT64 → Nullable(Float64)")
    public void testFloat64() {
        Schema s = SchemaBuilder.float64().optional().build();
        assertEquals("Nullable(Float64)", reconciler.mapDebeziumTypeToClickHouse(s));
    }

    @Test
    @DisplayName("BOOLEAN → Nullable(UInt8)")
    public void testBoolean() {
        Schema s = SchemaBuilder.bool().optional().build();
        assertEquals("Nullable(UInt8)", reconciler.mapDebeziumTypeToClickHouse(s));
    }

    @Test
    @DisplayName("STRING → Nullable(String)")
    public void testString() {
        Schema s = SchemaBuilder.string().optional().build();
        assertEquals("Nullable(String)", reconciler.mapDebeziumTypeToClickHouse(s));
    }

    @Test
    @DisplayName("BYTES → Nullable(String)")
    public void testBytes() {
        Schema s = SchemaBuilder.bytes().optional().build();
        assertEquals("Nullable(String)", reconciler.mapDebeziumTypeToClickHouse(s));
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
        assertEquals("Nullable(DateTime64(6))", reconciler.mapDebeziumTypeToClickHouse(s));
    }

    @Test
    @DisplayName("io.debezium.time.Timestamp → Nullable(DateTime64(3))")
    public void testTimestamp() {
        Schema s = SchemaBuilder.int64()
                .name("io.debezium.time.Timestamp")
                .optional()
                .build();
        assertEquals("Nullable(DateTime64(3))", reconciler.mapDebeziumTypeToClickHouse(s));
    }

    @Test
    @DisplayName("io.debezium.time.NanoTimestamp → Nullable(DateTime64(9))")
    public void testNanoTimestamp() {
        Schema s = SchemaBuilder.int64()
                .name("io.debezium.time.NanoTimestamp")
                .optional()
                .build();
        assertEquals("Nullable(DateTime64(9))", reconciler.mapDebeziumTypeToClickHouse(s));
    }

    @Test
    @DisplayName("io.debezium.time.ZonedTimestamp → Nullable(DateTime64(6, 'UTC'))")
    public void testZonedTimestamp() {
        Schema s = SchemaBuilder.string()
                .name("io.debezium.time.ZonedTimestamp")
                .optional()
                .build();
        assertEquals("Nullable(DateTime64(6, 'UTC'))", reconciler.mapDebeziumTypeToClickHouse(s));
    }

    @Test
    @DisplayName("io.debezium.time.Date → Nullable(Date32)")
    public void testDate() {
        Schema s = SchemaBuilder.int32()
                .name("io.debezium.time.Date")
                .optional()
                .build();
        assertEquals("Nullable(Date32)", reconciler.mapDebeziumTypeToClickHouse(s));
    }

    @Test
    @DisplayName("io.debezium.time.MicroTime → Nullable(Int64)")
    public void testMicroTime() {
        Schema s = SchemaBuilder.int64()
                .name("io.debezium.time.MicroTime")
                .optional()
                .build();
        assertEquals("Nullable(Int64)", reconciler.mapDebeziumTypeToClickHouse(s));
    }

    @Test
    @DisplayName("io.debezium.data.Uuid → Nullable(UUID)")
    public void testUuid() {
        Schema s = SchemaBuilder.string()
                .name("io.debezium.data.Uuid")
                .optional()
                .build();
        assertEquals("Nullable(UUID)", reconciler.mapDebeziumTypeToClickHouse(s));
    }

    @Test
    @DisplayName("io.debezium.data.Json → Nullable(String)")
    public void testJson() {
        Schema s = SchemaBuilder.string()
                .name("io.debezium.data.Json")
                .optional()
                .build();
        assertEquals("Nullable(String)", reconciler.mapDebeziumTypeToClickHouse(s));
    }

    @Test
    @DisplayName("io.debezium.data.Enum → Nullable(String)")
    public void testEnum() {
        Schema s = SchemaBuilder.string()
                .name("io.debezium.data.Enum")
                .optional()
                .build();
        assertEquals("Nullable(String)", reconciler.mapDebeziumTypeToClickHouse(s));
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
        assertEquals("Nullable(Decimal(10, 2))", reconciler.mapDebeziumTypeToClickHouse(s));
    }

    @Test
    @DisplayName("Decimal with scale only → Nullable(Decimal(38, scale))")
    public void testDecimalScaleOnly() {
        Schema s = Decimal.builder(5)
                .optional()
                .build();
        // No precision parameter → defaults to 38
        assertEquals("Nullable(Decimal(38, 5))", reconciler.mapDebeziumTypeToClickHouse(s));
    }

    // ------------------------------------------------------------------
    // Edge cases
    // ------------------------------------------------------------------

    @Test
    @DisplayName("null schema → Nullable(String)")
    public void testNullSchema() {
        assertEquals("Nullable(String)", reconciler.mapDebeziumTypeToClickHouse(null));
    }

    @Test
    @DisplayName("Unknown logical type name falls through to base-type mapping")
    public void testUnknownLogicalTypeFallsThrough() {
        // An INT64 schema with an unknown logical type name should fall through to
        // the base-type switch and return Nullable(Int64).
        Schema s = SchemaBuilder.int64()
                .name("com.example.unknown.LogicalType")
                .optional()
                .build();
        // The switch falls through to the base-type block → INT64 → Nullable(Int64)
        assertEquals("Nullable(Int64)", reconciler.mapDebeziumTypeToClickHouse(s));
    }

    @Test
    @DisplayName("addMissingColumns with empty map is a no-op (does not throw NPE on null writer)")
    public void testAddMissingColumnsEmptyMapNoOp() {
        // writer is null, but an empty map must be handled before any JDBC access
        reconciler.addMissingColumns("mydb", "mytable", Map.of());
        // Reaches here → no exception
    }

    @Test
    @DisplayName("addMissingColumns with null map is a no-op (does not throw NPE on null writer)")
    public void testAddMissingColumnsNullMapNoOp() {
        reconciler.addMissingColumns("mydb", "mytable", null);
        // Reaches here → no exception
    }
}

package com.altinity.clickhouse.sink.connector.converters;

import com.clickhouse.data.ClickHouseDataType;
import io.debezium.data.*;
import io.debezium.data.Enum;
import io.debezium.data.EnumSet;
import io.debezium.data.geometry.Geometry;
import io.debezium.data.geometry.Point;
import io.debezium.time.Date;
import io.debezium.time.*;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Schema;
import org.junit.Assert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive tests for ClickHouseDataTypeMapper — Phase 8.
 * <p>
 * Tests all MySQL-to-ClickHouse data type mappings in dataTypesMap,
 * including the critical FLOAT64 → Float64 fix (was incorrectly Float32,
 * causing silent precision loss for MySQL DOUBLE values).
 * </p>
 */
public class ClickHouseDataTypeMapperTest {

    // =================================================================    // Integer types
    // =================================================================
    @Nested
    @DisplayName("Integer type mappings")
    class IntegerTypes {

        @Test
        @DisplayName("INT8 (MySQL TINYINT signed) → ClickHouse Int8")
        public void testInt8Mapping() {
            ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                    Schema.INT8_SCHEMA.type(), null);
            Assert.assertEquals("INT8 should map to Int8", ClickHouseDataType.Int8, result);
        }

        @Test
        @DisplayName("INT16 (MySQL SMALLINT) → ClickHouse Int16")
        public void testInt16Mapping() {
            ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                    Schema.INT16_SCHEMA.type(), null);
            Assert.assertEquals("INT16 should map to Int16", ClickHouseDataType.Int16, result);
        }

        @Test
        @DisplayName("INT32 (MySQL INT) → ClickHouse Int32")
        public void testInt32Mapping() {
            ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                    Schema.INT32_SCHEMA.type(), null);
            Assert.assertEquals("INT32 should map to Int32", ClickHouseDataType.Int32, result);
        }

        @Test
        @DisplayName("INT64 (MySQL BIGINT) → ClickHouse Int64")
        public void testInt64Mapping() {
            ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                    Schema.INT64_SCHEMA.type(), null);
            Assert.assertEquals("INT64 should map to Int64", ClickHouseDataType.Int64, result);
        }
    }

    // =========================================================    // Comprehensive data type mapping tests
    // =========================================================
    @Test
    public void testFloat32MapsToFloat32() {
        ClickHouseDataType dt = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.FLOAT32_SCHEMA.type(), null);
        Assert.assertEquals("FLOAT32 should map to Float32",
                ClickHouseDataType.Float32, dt);
    }

    @Test
    public void testFloat64MapsToFloat64() {
        // CRITICAL: This was previously mapped to Float32, causing
        // silent precision loss for MySQL DOUBLE values.
        ClickHouseDataType dt = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.FLOAT64_SCHEMA.type(), null);
        Assert.assertEquals("FLOAT64 must map to Float64 (not Float32)",
                ClickHouseDataType.Float64, dt);
    }

    @Test
    public void testInt8MapsToInt8() {
        ClickHouseDataType dt = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.INT8_SCHEMA.type(), null);
        Assert.assertEquals(ClickHouseDataType.Int8, dt);
    }

    @Test
    public void testInt64MapsToInt64() {
        ClickHouseDataType dt = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.INT64_SCHEMA.type(), null);
        Assert.assertEquals(ClickHouseDataType.Int64, dt);
    }

    @Test
    public void testStringMapsToString() {
        ClickHouseDataType dt = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.STRING_SCHEMA.type(), null);
        Assert.assertEquals(ClickHouseDataType.String, dt);
    }

    @Test
    public void testBooleanMapsToBool() {
        ClickHouseDataType dt = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.Type.BOOLEAN, null);
        Assert.assertEquals(ClickHouseDataType.Bool, dt);
    }

    @Test
    public void testDecimalMapsToDecimal() {
        ClickHouseDataType dt = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.BYTES_SCHEMA.type(),
                org.apache.kafka.connect.data.Decimal.LOGICAL_NAME);
        Assert.assertEquals(ClickHouseDataType.Decimal, dt);
    }

    @Test
    public void testTimestampMapsToDateTime64() {
        ClickHouseDataType dt = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.INT64_SCHEMA.type(),
                io.debezium.time.Timestamp.SCHEMA_NAME);
        Assert.assertEquals(ClickHouseDataType.DateTime64, dt);
    }

    @Test
    public void testMicroTimestampMapsToDateTime64() {
        ClickHouseDataType dt = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.INT64_SCHEMA.type(),
                io.debezium.time.MicroTimestamp.SCHEMA_NAME);
        Assert.assertEquals(ClickHouseDataType.DateTime64, dt);
    }

    @Test
    public void testMicroTimeMapsToString() {
        ClickHouseDataType dt = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.INT64_SCHEMA.type(),
                io.debezium.time.MicroTime.SCHEMA_NAME);
        Assert.assertEquals(ClickHouseDataType.String, dt);
    }

    @Test
    public void testZonedTimestampMapsToDateTime64() {
        ClickHouseDataType dt = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.Type.STRING,
                io.debezium.time.ZonedTimestamp.SCHEMA_NAME);
        Assert.assertEquals(ClickHouseDataType.DateTime64, dt);
    }

    @Test
    public void testEnumMapsToString() {
        ClickHouseDataType dt = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.Type.STRING,
                io.debezium.data.Enum.LOGICAL_NAME);
        Assert.assertEquals(ClickHouseDataType.String, dt);
    }

    @Test
    public void testEnumSetMapsToString() {
        ClickHouseDataType dt = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.STRING_SCHEMA.type(),
                io.debezium.data.EnumSet.LOGICAL_NAME);
        Assert.assertEquals(ClickHouseDataType.String, dt);
    }

    @Test
    public void testJsonMapsToString() {
        ClickHouseDataType dt = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.Type.STRING,
                io.debezium.data.Json.LOGICAL_NAME);
        Assert.assertEquals(ClickHouseDataType.String, dt);
    }

    @Test
    public void testYearMapsToInt32() {
        ClickHouseDataType dt = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.INT32_SCHEMA.type(),
                io.debezium.time.Year.SCHEMA_NAME);
        Assert.assertEquals(ClickHouseDataType.Int32, dt);
    }

    @Test
    public void testBitsMapsToString() {
        ClickHouseDataType dt = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.Type.BYTES,
                io.debezium.data.Bits.LOGICAL_NAME);
        Assert.assertEquals(ClickHouseDataType.String, dt);
    }

    @Test
    public void testGeometryMapsToPolygon() {
        ClickHouseDataType dt = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.Type.STRUCT,
                io.debezium.data.geometry.Geometry.LOGICAL_NAME);
        Assert.assertEquals(ClickHouseDataType.Polygon, dt);
    }

    @Test
    public void testPointMapsToPoint() {
        ClickHouseDataType dt = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.Type.STRUCT,
                io.debezium.data.geometry.Point.LOGICAL_NAME);
        Assert.assertEquals(ClickHouseDataType.Point, dt);
    }

    @Test
    public void testUuidMapsToUUID() {
        ClickHouseDataType dt = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.Type.STRING,
                io.debezium.data.Uuid.LOGICAL_NAME);
        Assert.assertEquals(ClickHouseDataType.UUID, dt);
    }

    @Test
    public void testBytesWithoutLogicalNameMapsToString() {
        // Raw BYTES (e.g., BLOB/BINARY) without logical name -> String
        ClickHouseDataType dt = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.Type.BYTES, null);
        Assert.assertEquals(ClickHouseDataType.String, dt);
    }

    @Test
    public void testUnknownTypeMappingReturnsNull() {
        // An unmapped type/name combination should return null
        ClickHouseDataType dt = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.Type.MAP, null);
        Assert.assertNull("Unmapped type should return null", dt);
    }

    // =================================================================    // Float types — includes the critical FLOAT64 fix
    // =================================================================
    @Nested
    @DisplayName("Float type mappings")
    class FloatTypes {

        @Test
        @DisplayName("FLOAT32 (MySQL FLOAT) → ClickHouse Float32")
        public void testFloat32Mapping() {
            ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                    Schema.FLOAT32_SCHEMA.type(), null);
            Assert.assertEquals("FLOAT32 should map to Float32", ClickHouseDataType.Float32, result);
        }

        @Test
        @DisplayName("FLOAT64 (MySQL DOUBLE) → ClickHouse Float64 [CRITICAL FIX — was Float32]")
        public void testFloat64MapsToFloat64NotFloat32() {
            // This is the critical bug fix from Phase 8.
            // MySQL DOUBLE is 8 bytes (~15 decimal digits precision).
            // It MUST map to ClickHouse Float64, not Float32 (4 bytes, ~7 digits).
            // The old mapping silently truncated precision on every DOUBLE column.
            ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                    Schema.FLOAT64_SCHEMA.type(), null);
            Assert.assertEquals(
                    "FLOAT64 must map to Float64 (not Float32) to preserve MySQL DOUBLE precision",
                    ClickHouseDataType.Float64, result);
            Assert.assertNotEquals(
                    "FLOAT64 must NOT map to Float32 — this was the original bug",
                    ClickHouseDataType.Float32, result);
        }
    }

    // =================================================================    // String types
    // =================================================================
    @Nested
    @DisplayName("String type mappings")
    class StringTypes {

        @Test
        @DisplayName("STRING → ClickHouse String")
        public void testStringMapping() {
            ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                    Schema.STRING_SCHEMA.type(), null);
            Assert.assertEquals("STRING should map to String", ClickHouseDataType.String, result);
        }

        @Test
        @DisplayName("STRING + Enum logical name → ClickHouse String")
        public void testEnumMapping() {
            ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                    Schema.Type.STRING, Enum.LOGICAL_NAME);
            Assert.assertEquals("Enum should map to String", ClickHouseDataType.String, result);
        }

        @Test
        @DisplayName("STRING + EnumSet logical name → ClickHouse String")
        public void testEnumSetMapping() {
            ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                    Schema.STRING_SCHEMA.type(), EnumSet.LOGICAL_NAME);
            Assert.assertEquals("EnumSet should map to String", ClickHouseDataType.String, result);
        }

        @Test
        @DisplayName("STRING + JSON logical name → ClickHouse String")
        public void testJsonMapping() {
            ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                    Schema.Type.STRING, Json.LOGICAL_NAME);
            Assert.assertEquals("JSON should map to String", ClickHouseDataType.String, result);
        }

        @Test
        @DisplayName("STRING + UUID logical name → ClickHouse UUID")
        public void testUuidMapping() {
            ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                    Schema.Type.STRING, Uuid.LOGICAL_NAME);
            Assert.assertEquals("UUID should map to UUID", ClickHouseDataType.UUID, result);
        }

        @Test
        @DisplayName("STRING + ZonedTime → ClickHouse String")
        public void testZonedTimeMapping() {
            ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                    Schema.Type.STRING, ZonedTime.SCHEMA_NAME);
            Assert.assertEquals("ZonedTime should map to String", ClickHouseDataType.String, result);
        }
    }

    // =================================================================    // Date and Time types
    // =================================================================
    @Nested
    @DisplayName("Date and Time type mappings")
    class DateTimeTypes {

        @Test
        @DisplayName("INT32 + Date schema → ClickHouse Date32")
        public void testDateMapping() {
            ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                    Schema.INT32_SCHEMA.type(), Date.SCHEMA_NAME);
            Assert.assertEquals("Date should map to Date32", ClickHouseDataType.Date32, result);
        }

        @Test
        @DisplayName("INT32 + Time schema → ClickHouse String")
        public void testTimeMapping() {
            ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                    Schema.INT32_SCHEMA.type(), Time.SCHEMA_NAME);
            Assert.assertEquals("Time should map to String", ClickHouseDataType.String, result);
        }

        @Test
        @DisplayName("INT64 + MicroTime schema → ClickHouse String")
        public void testMicroTimeMapping() {
            ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                    Schema.INT64_SCHEMA.type(), MicroTime.SCHEMA_NAME);
            Assert.assertEquals("MicroTime should map to String", ClickHouseDataType.String, result);
        }

        @Test
        @DisplayName("INT64 + Timestamp schema → ClickHouse DateTime64")
        public void testTimestampMapping() {
            ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                    Schema.INT64_SCHEMA.type(), Timestamp.SCHEMA_NAME);
            Assert.assertEquals("Timestamp should map to DateTime64", ClickHouseDataType.DateTime64, result);
        }

        @Test
        @DisplayName("INT64 + MicroTimestamp schema → ClickHouse DateTime64")
        public void testMicroTimestampMapping() {
            ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                    Schema.INT64_SCHEMA.type(), MicroTimestamp.SCHEMA_NAME);
            Assert.assertEquals("MicroTimestamp should map to DateTime64", ClickHouseDataType.DateTime64, result);
        }

        @Test
        @DisplayName("STRING + ZonedTimestamp schema → ClickHouse DateTime64")
        public void testZonedTimestampMapping() {
            ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                    Schema.Type.STRING, ZonedTimestamp.SCHEMA_NAME);
            Assert.assertEquals("ZonedTimestamp should map to DateTime64", ClickHouseDataType.DateTime64, result);
        }

        @Test
        @DisplayName("INT32 + Year schema → ClickHouse Int32")
        public void testYearMapping() {
            ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                    Schema.INT32_SCHEMA.type(), Year.SCHEMA_NAME);
            Assert.assertEquals("Year should map to Int32", ClickHouseDataType.Int32, result);
        }
    }

    // =================================================================    // Boolean type
    // =================================================================
    @Test
    @DisplayName("BOOLEAN → ClickHouse Bool")
    public void testBooleanMapping() {
        ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.Type.BOOLEAN, null);
        Assert.assertEquals("BOOLEAN should map to Bool", ClickHouseDataType.Bool, result);
    }

    // =================================================================    // BYTES types
    // =================================================================
    @Nested
    @DisplayName("BYTES type mappings")
    class BytesTypes {

        @Test
        @DisplayName("BYTES (raw, no schema) → ClickHouse String")
        public void testRawBytesMapping() {
            ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                    Schema.Type.BYTES, null);
            Assert.assertEquals("Raw BYTES should map to String", ClickHouseDataType.String, result);
        }

        @Test
        @DisplayName("BYTES + Decimal logical name → ClickHouse Decimal")
        public void testDecimalBytesMapping() {
            ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                    Schema.BYTES_SCHEMA.type(), Decimal.LOGICAL_NAME);
            Assert.assertEquals("Decimal BYTES should map to Decimal", ClickHouseDataType.Decimal, result);
        }

        @Test
        @DisplayName("BYTES + Bits logical name → ClickHouse String")
        public void testBitsMapping() {
            ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                    Schema.Type.BYTES, Bits.LOGICAL_NAME);
            Assert.assertEquals("Bits should map to String", ClickHouseDataType.String, result);
        }
    }

    // =================================================================    // Geometry/Point types
    // =================================================================
    @Nested
    @DisplayName("Geometry type mappings")
    class GeometryTypes {

        @Test
        @DisplayName("STRUCT + Geometry → ClickHouse Polygon")
        public void testGeometryMapping() {
            ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                    Schema.Type.STRUCT, Geometry.LOGICAL_NAME);
            Assert.assertEquals("Geometry should map to Polygon", ClickHouseDataType.Polygon, result);
        }

        @Test
        @DisplayName("STRUCT + Point → ClickHouse Point")
        public void testPointMapping() {
            ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                    Schema.Type.STRUCT, Point.LOGICAL_NAME);
            Assert.assertEquals("Point should map to Point", ClickHouseDataType.Point, result);
        }

        @Test
        @DisplayName("STRUCT + VariableScaleDecimal → ClickHouse Decimal")
        public void testVariableScaleDecimalMapping() {
            ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                    Schema.Type.STRUCT, VariableScaleDecimal.LOGICAL_NAME);
            Assert.assertEquals("VariableScaleDecimal should map to Decimal",
                    ClickHouseDataType.Decimal, result);
        }
    }

    // =================================================================    // Array type
    // =================================================================
    @Test
    @DisplayName("ARRAY + STRING → ClickHouse Array")
    public void testArrayMapping() {
        ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.Type.ARRAY, Schema.Type.STRING.name());
        Assert.assertEquals("Array should map to Array", ClickHouseDataType.Array, result);
    }

    // =================================================================    // Null/missing mappings
    // =================================================================
    @Nested
    @DisplayName("Null and unmapped type handling")
    class NullHandling {

        @Test
        @DisplayName("Unknown type with no schema name returns null")
        public void testUnknownTypeReturnsNull() {
            // MAP type is not in the dataTypesMap
            ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                    Schema.Type.MAP, null);
            Assert.assertNull("Unmapped type should return null", result);
        }

        @Test
        @DisplayName("Known type with wrong schema name returns null")
        public void testKnownTypeWithWrongSchemaReturnsNull() {
            // INT32 with a non-existent schema name
            ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                    Schema.INT32_SCHEMA.type(), "nonexistent.schema.name");
            Assert.assertNull("Known type with wrong schema should return null", result);
        }

        @Test
        @DisplayName("STRUCT type with null schema name returns null (no NPE)")
        public void testStructWithNullSchemaNameReturnsNull() {
            // This tests the NPE fix — STRUCT with null schemaName used to crash
            ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                    Schema.Type.STRUCT, null);
            Assert.assertNull("STRUCT with null schemaName should return null, not NPE", result);
        }
    }

    // =================================================================    // Regression: verify INT32 with null returns Int32 (not Date32 or Year)
    // =================================================================
    @Test
    @DisplayName("INT32 with null schema name returns Int32 (not Date32 or Year)")
    public void testInt32WithNullSchemaReturnsInt32NotDate() {
        // INT32 has multiple entries: null→Int32, Date→Date32, Time→String, Year→Int32
        // When schemaName is null, should match the null entry → Int32
        ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.INT32_SCHEMA.type(), null);
        Assert.assertEquals("INT32 + null should be Int32, not Date32",
                ClickHouseDataType.Int32, result);
    }

    // =================================================================    // Regression: verify INT64 with null returns Int64 (not DateTime64)
    // =================================================================
    @Test
    @DisplayName("INT64 with null schema name returns Int64 (not DateTime64)")
    public void testInt64WithNullSchemaReturnsInt64NotDatetime() {
        // INT64 has multiple entries: null→Int64, Timestamp→DateTime64, etc.
        ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.INT64_SCHEMA.type(), null);
        Assert.assertEquals("INT64 + null should be Int64, not DateTime64",
                ClickHouseDataType.Int64, result);
    }

    // =================================================================    // Case insensitivity of schema name lookups
    // =================================================================
    @Test
    @DisplayName("Schema name lookup should be case-insensitive")
    public void testSchemaNameCaseInsensitive() {
        // Use lowercase version of Date schema name
        ClickHouseDataType result = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.INT32_SCHEMA.type(), Date.SCHEMA_NAME.toLowerCase());
        Assert.assertEquals("Case-insensitive lookup should still find Date32",
                ClickHouseDataType.Date32, result);

        // Use uppercase version
        result = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.INT32_SCHEMA.type(), Date.SCHEMA_NAME.toUpperCase());
        Assert.assertEquals("Case-insensitive lookup should still find Date32",
                ClickHouseDataType.Date32, result);
    }
    @Test
    @DisplayName("FLOAT64 schema maps to ClickHouse Float64 (not Float32)")
    public void testFloat64MapsToFloat64WithDisplayName() {
        ClickHouseDataType chDataType = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.FLOAT64_SCHEMA.type(), null);
        Assert.assertEquals("Float64 schema must map to ClickHouse Float64",
                ClickHouseDataType.Float64, chDataType);
    }

    @Test
    @DisplayName("FLOAT32 schema maps to ClickHouse Float32 (regression guard)")
    public void testFloat32MapsToFloat32WithDisplayName() {
        ClickHouseDataType chDataType = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.FLOAT32_SCHEMA.type(), null);
        Assert.assertEquals("Float32 schema must map to ClickHouse Float32",
                ClickHouseDataType.Float32, chDataType);
    }

    @Test
    @DisplayName("INT64 schema maps to ClickHouse Int64")
    public void testInt64MapsToInt64WithDisplayName() {
        ClickHouseDataType chDataType = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.INT64_SCHEMA.type(), null);
        Assert.assertEquals("Int64 schema must map to ClickHouse Int64",
                ClickHouseDataType.Int64, chDataType);
    }

    @Test
    @DisplayName("STRING schema maps to ClickHouse String")
    public void testStringMapsToStringWithDisplayName() {
        ClickHouseDataType chDataType = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.STRING_SCHEMA.type(), null);
        Assert.assertEquals("String schema must map to ClickHouse String",
                ClickHouseDataType.String, chDataType);
    }

    @Test
    @DisplayName("BOOLEAN schema maps to ClickHouse Bool")
    public void testBooleanMapsToBoolWithDisplayName() {
        ClickHouseDataType chDataType = ClickHouseDataTypeMapper.getClickHouseDataType(
                Schema.BOOLEAN_SCHEMA.type(), null);
        Assert.assertEquals("Boolean schema must map to ClickHouse Bool",
                ClickHouseDataType.Bool, chDataType);
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

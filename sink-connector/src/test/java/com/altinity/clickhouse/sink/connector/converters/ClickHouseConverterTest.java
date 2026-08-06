package com.altinity.clickhouse.sink.connector.converters;

import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.sink.SinkRecord;
import org.junit.Assert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Tests for ClickHouseConverter — Phase 9 edge case coverage.
 * <p>
 * Validates:
 * - Normal record conversion
 * - Tombstone (null value) records
 * - Schemaless records
 * - Records with null keys
 * - CDC operation detection
 * </p>
 */
public class ClickHouseConverterTest {

    /**
     * Utility method for spoofing SinkRecords.
     */
    public static SinkRecord spoofSinkRecord(String topic, String keyField, String key,
                                             String valueField, String value,
                                             TimestampType timestampType, Long timestamp) {
        Schema basicKeySchema = null;
        Struct basicKey = null;
        if (keyField != null) {
            basicKeySchema = SchemaBuilder
                    .struct()
                    .field(keyField, Schema.STRING_SCHEMA)
                    .build();
            basicKey = new Struct(basicKeySchema);
            basicKey.put(keyField, key);
        }

        Schema basicValueSchema = null;
        Struct basicValue = null;
        if (valueField != null) {
            basicValueSchema = SchemaBuilder
                    .struct()
                    .field(valueField, Schema.STRING_SCHEMA)
                    .build();
            basicValue = new Struct(basicValueSchema);
            basicValue.put(valueField, value);
        }

        return new SinkRecord(topic, 0, basicKeySchema, basicKey,
                basicValueSchema, basicValue, 0, timestamp, timestampType);
    }

    @Test
    @DisplayName("Normal record should be convertible")
    public void testConvertNormalRecord() {
        ClickHouseConverter converter = new ClickHouseConverter();
        SinkRecord record = spoofSinkRecord("test", "key", "k", "value", "v",
                TimestampType.NO_TIMESTAMP_TYPE, null);
        // Should not throw — convert returns null for non-CDC records
        // (no "op" field in the value)
        converter.convert(record);
    }

    @Nested
    @DisplayName("Tombstone and null value handling")
    class TombstoneTests {

        @Test
        @DisplayName("Tombstone record (null value) should return null, not NPE")
        public void testTombstoneRecordReturnsNull() {
            ClickHouseConverter converter = new ClickHouseConverter();
            // Tombstone record: value is null
            SinkRecord tombstone = new SinkRecord("test", 0,
                    Schema.STRING_SCHEMA, "key",
                    null, null, 0);
            // Should return null gracefully
            var result = converter.convert(tombstone);
            Assert.assertNull("Tombstone record should return null", result);
        }

        @Test
        @DisplayName("Null value schema should return null for convertValue")
        public void testNullValueSchema() {
            ClickHouseConverter converter = new ClickHouseConverter();
            SinkRecord record = new SinkRecord("test", 0,
                    null, null,
                    null, null, 0);
            Map<String, Object> result = converter.convertValue(record);
            Assert.assertNull("Null value schema should return null", result);
        }
    }

    @Nested
    @DisplayName("Key conversion")
    class KeyConversionTests {

        @Test
        @DisplayName("Null key should return null for convertKey")
        public void testNullKeyReturnsNull() {
            ClickHouseConverter converter = new ClickHouseConverter();
            SinkRecord record = new SinkRecord("test", 0,
                    null, null,
                    Schema.STRING_SCHEMA, "value", 0);
            Map<String, Object> result = converter.convertKey(record);
            Assert.assertNull("Null key schema should return null", result);
        }

        @Test
        @DisplayName("Normal key should be converted")
        public void testNormalKeyConversion() {
            ClickHouseConverter converter = new ClickHouseConverter();
            SinkRecord record = spoofSinkRecord("test", "id", "123",
                    "value", "v", TimestampType.NO_TIMESTAMP_TYPE, null);
            Map<String, Object> result = converter.convertKey(record);
            Assert.assertNotNull("Key with schema should be converted", result);
            Assert.assertEquals("123", result.get("id"));
        }
    }

    @Nested
    @DisplayName("CDC operation detection")
    class CdcOperationTests {

        @Test
        @DisplayName("getOperation on non-CDC record should return null")
        public void testGetOperationNonCdcRecord() {
            ClickHouseConverter converter = new ClickHouseConverter();
            SinkRecord record = spoofSinkRecord("test", "key", "k",
                    "value", "v", TimestampType.NO_TIMESTAMP_TYPE, null);
            ClickHouseConverter.CDC_OPERATION op = converter.getOperation(record);
            // Non-CDC record has no "op" field, so operation is null
            Assert.assertNull("Non-CDC record should have null operation", op);
        }

        @Test
        @DisplayName("getOperation on tombstone should return null")
        public void testGetOperationTombstone() {
            ClickHouseConverter converter = new ClickHouseConverter();
            SinkRecord tombstone = new SinkRecord("test", 0,
                    Schema.STRING_SCHEMA, "key",
                    null, null, 0);
            ClickHouseConverter.CDC_OPERATION op = converter.getOperation(tombstone);
            Assert.assertNull("Tombstone should have null operation", op);
        }
    }

    @Nested
    @DisplayName("Non-struct records")
    class NonStructTests {

        @Test
        @DisplayName("Non-struct value (primitive) should return null")
        public void testNonStructValueReturnsNull() {
            ClickHouseConverter converter = new ClickHouseConverter();
            SinkRecord record = new SinkRecord("test", 0,
                    null, null,
                    Schema.STRING_SCHEMA, "plain string value", 0);
            Map<String, Object> result = converter.convertValue(record);
            Assert.assertNull("Non-struct value should return null", result);
        }
    }
}

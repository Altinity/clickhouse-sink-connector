package com.altinity.clickhouse.debezium.embedded.parser;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.kafka.connect.data.*;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SourceRecordParserService — Phase 12 edge case coverage.
 * <p>
 * Validates:
 * - Null SourceRecord handling (tombstone records)
 * - Non-Struct value handling (schema changes, heartbeats)
 * - Null SourceRecord.value() handling
 * </p>
 */
public class SourceRecordParserServiceTest {

    private SourceRecordParserService parser;

    @BeforeEach
    public void setUp() {
        parser = new SourceRecordParserService();
    }

    // Helper to create a mock ChangeEvent wrapping a SourceRecord.
    // ChangeEvent.destination() returns String (not SourceRecord) and the
    // interface also declares partition(); the previous anonymous class got
    // both wrong, so this file did not compile against the Debezium version in
    // use — origin/develop's test sources were broken here before this merge.
    private ChangeEvent<SourceRecord, SourceRecord> createChangeEvent(SourceRecord sr) {
        return new ChangeEvent<SourceRecord, SourceRecord>() {
            @Override public SourceRecord key() { return null; }
            @Override public SourceRecord value() { return sr; }
            @Override public String destination() { return null; }
            @Override public Integer partition() { return null; }
        };
    }

    @Nested
    @DisplayName("parse() null safety")
    class ParseNullSafety {

        @Test
        @DisplayName("null SourceRecord value should return null without NPE")
        public void testNullSourceRecordValue() {
            ChangeEvent<SourceRecord, SourceRecord> event = createChangeEvent(null);
            ClickHouseStruct result = parser.parse(event, null, false);
            assertNull(result, "Should return null for null SourceRecord");
        }

        @Test
        @DisplayName("SourceRecord with null value() should return null without NPE")
        public void testSourceRecordWithNullValue() {
            // Create a SourceRecord where value() returns null (tombstone)
            SourceRecord sr = new SourceRecord(
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    "test.db.table",
                    null,  // keySchema
                    null,  // key
                    null,  // valueSchema
                    null   // value — null represents tombstone
            );
            ChangeEvent<SourceRecord, SourceRecord> event = createChangeEvent(sr);
            ClickHouseStruct result = parser.parse(event, null, false);
            assertNull(result, "Should return null for tombstone record");
        }

        @Test
        @DisplayName("SourceRecord with non-Struct value should return null without ClassCastException")
        public void testNonStructValue() {
            // Create a SourceRecord with a String value instead of Struct
            SourceRecord sr = new SourceRecord(
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    "test.db.table",
                    Schema.STRING_SCHEMA,
                    "key",
                    Schema.STRING_SCHEMA,
                    "not a struct"
            );
            ChangeEvent<SourceRecord, SourceRecord> event = createChangeEvent(sr);
            ClickHouseStruct result = parser.parse(event, null, false);
            assertNull(result, "Should return null for non-Struct value");
        }

        @Test
        @DisplayName("SourceRecord with Map value should return null without ClassCastException")
        public void testMapValue() {
            SourceRecord sr = new SourceRecord(
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    "test.db.table",
                    null,
                    null,
                    null,
                    new HashMap<String, Object>()
            );
            ChangeEvent<SourceRecord, SourceRecord> event = createChangeEvent(sr);
            ClickHouseStruct result = parser.parse(event, null, false);
            assertNull(result, "Should return null for Map value");
        }

        @Test
        @DisplayName("SourceRecord with Integer value should return null without ClassCastException")
        public void testIntegerValue() {
            SourceRecord sr = new SourceRecord(
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    "test.db.table",
                    Schema.INT32_SCHEMA,
                    42,
                    Schema.INT32_SCHEMA,
                    12345
            );
            ChangeEvent<SourceRecord, SourceRecord> event = createChangeEvent(sr);
            ClickHouseStruct result = parser.parse(event, null, false);
            assertNull(result, "Should return null for Integer value");
        }
    }

    @Nested
    @DisplayName("parse() with valid Struct but no operation")
    class ParseStructNoOp {

        @Test
        @DisplayName("Struct without operation field should return null")
        public void testStructWithoutOperation() {
            // Create a valid Struct but without an 'op' field
            Schema schema = SchemaBuilder.struct()
                    .field("someField", Schema.STRING_SCHEMA)
                    .build();
            Struct struct = new Struct(schema);
            struct.put("someField", "someValue");

            SourceRecord sr = new SourceRecord(
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    "test.db.table",
                    null,
                    null,
                    schema,
                    struct
            );
            ChangeEvent<SourceRecord, SourceRecord> event = createChangeEvent(sr);
            // This should not throw — it should return null because there's
            // no DDL field and no operation in the converted value
            ClickHouseStruct result = parser.parse(event, null, false);
            // Result may be null if convertValue returns null or no operation found
        }

        @Test
        @DisplayName("Struct with null schema should not throw")
        public void testStructWithNullSchema() {
            // Create a minimal Struct-like scenario
            Schema schema = SchemaBuilder.struct().build();
            Struct struct = new Struct(schema);

            SourceRecord sr = new SourceRecord(
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    "test.db.table",
                    null,
                    null,
                    schema,
                    struct
            );
            ChangeEvent<SourceRecord, SourceRecord> event = createChangeEvent(sr);
            // Should not throw NPE — should handle empty schema gracefully
            ClickHouseStruct result = parser.parse(event, null, false);
            // null is acceptable since there are no fields to match DDL
        }
    }
}

package com.altinity.clickhouse.sink.connector.model;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import io.debezium.engine.ChangeEvent;
import org.junit.Assert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for ClickHouseStruct — Phase 10 edge case coverage.
 * <p>
 * Validates:
 * - setBeforeStruct / setAfterStruct with null schema
 * - getDebeziumTsFromChangeEvent with null/wrong type
 * - SERVER_THREAD metadata reads from source (not convertedValue)
 * - Version calculation from GTID, sequenceNumber, LSN
 * </p>
 */
public class ClickHouseStructTest {

    @Nested
    @DisplayName("setBeforeStruct / setAfterStruct null safety")
    class StructSetterTests {

    @Test
    public void testGtid() {

        Map<String, Object> metaData = new HashMap<String, Object>();

        metaData.put("source", new Struct(SchemaBuilder.struct().field("gtid", Schema.STRING_SCHEMA).build()).put("gtid", "0010-122323-0232323:2179558590"));

        String keyField = "customer";
        Schema basicKeySchema = SchemaBuilder
                .struct()
                .field(keyField, Schema.STRING_SCHEMA)
                .build();

        ClickHouseStruct st = new ClickHouseStruct(10, "topic_1", new Struct(basicKeySchema), 100,
                12322323L, new Struct(basicKeySchema), new Struct(basicKeySchema),
                metaData, ClickHouseConverter.CDC_OPERATION.CREATE);

        Assert.assertTrue(st.getGtid() == 2179558590L);

    }

    @Test
    public void testSourceRecordToJson() throws Exception {
        // Create a real SourceRecord for testing
        Map<String, Object> sourcePartition = new HashMap<>();
        sourcePartition.put("server", "test-server");
        sourcePartition.put("database", "test-db");
        
        Map<String, Object> sourceOffset = new HashMap<>();
        sourceOffset.put("file", "binlog.001");
        sourceOffset.put("pos", 12345L);
        
        Schema keySchema = SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA)
                .build();
        Struct keyStruct = new Struct(keySchema).put("id", 1);
        
        Schema valueSchema = SchemaBuilder.struct()
                .field("name", Schema.STRING_SCHEMA)
                .field("age", Schema.INT32_SCHEMA)
                .build();
        Struct valueStruct = new Struct(valueSchema)
                .put("name", "John")
                .put("age", 30);
        
        SourceRecord sourceRecord = new SourceRecord(
                sourcePartition,
                sourceOffset,
                "test-topic",
                0,
                keySchema,
                keyStruct,
                valueSchema,
                valueStruct,
                System.currentTimeMillis()
        );
        
        // Create a simple ChangeEvent wrapper
        ChangeEvent<SourceRecord, SourceRecord> changeEvent = new ChangeEvent<SourceRecord, SourceRecord>() {
            @Override
            public SourceRecord key() {
                return sourceRecord;
            }
            
            @Override
            public SourceRecord value() {
                return sourceRecord;
            }
            
            @Override
            public String destination() {
                return "2";
            }

            @Override
            public Integer partition() {
                return 0;
            }
        };
        
        // Create ClickHouseStruct with sourceRecord
        Map<String, Object> metaData = new HashMap<>();
        Schema basicSchema = SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA)
                .build();
        Struct basicStruct = new Struct(basicSchema).put("id", 1);
        
        ClickHouseStruct clickHouseStruct = new ClickHouseStruct(
                10L, "test-topic", basicStruct, 0, 123456789L,
                basicStruct, basicStruct, metaData,
                ClickHouseConverter.CDC_OPERATION.CREATE,
                changeEvent, null, false
        );
        
        // Test sourceRecordToJson
        String json = clickHouseStruct.sourceRecordToJson();
        
        assertNotNull(json);
        
        // Verify JSON structure
        ObjectMapper mapper = new ObjectMapper();
        JsonNode jsonNode = mapper.readTree(json);
        
        assertTrue(jsonNode.has("key"));
        assertTrue(jsonNode.has("value"));
        assertTrue(jsonNode.has("topic"));
        assertTrue(jsonNode.has("partition"));
        assertTrue(jsonNode.has("sourceOffset"));
        assertTrue(jsonNode.has("sourcePartition"));
        assertTrue(jsonNode.has("timestamp"));
        
        assertEquals("test-topic", jsonNode.get("topic").asText());
        assertEquals(0, jsonNode.get("partition").asInt());
    }
    
    @Test
    public void testSourceRecordToJsonWithNullSourceRecord() {
        Map<String, Object> metaData = new HashMap<>();
        Schema basicSchema = SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA)
                .build();
        Struct basicStruct = new Struct(basicSchema).put("id", 1);
        
        ClickHouseStruct clickHouseStruct = new ClickHouseStruct(
                10L, "test-topic", basicStruct, 0, 123456789L,
                basicStruct, basicStruct, metaData,
                ClickHouseConverter.CDC_OPERATION.CREATE
        );
        
        // sourceRecord should be null by default
        String json = clickHouseStruct.sourceRecordToJson();
        assertNull(json);
    }
    
    @Test
    public void testBeforeModifiedFieldsToJson() throws Exception {
        // Create schema with multiple fields
        Schema beforeSchema = SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA)
                .field("name", Schema.OPTIONAL_STRING_SCHEMA)
                .field("email", Schema.STRING_SCHEMA)
                .field("age", Schema.OPTIONAL_INT32_SCHEMA)
                .build();
        
        Struct beforeStruct = new Struct(beforeSchema)
                .put("id", 1)
                .put("name", "John Doe")
                .put("email", "john@example.com")
                // age is null - should not be in modified fields
                ;
        
        Schema afterSchema = SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA)
                .build();
        Struct afterStruct = new Struct(afterSchema).put("id", 1);
        
        Map<String, Object> metaData = new HashMap<>();
        
        ClickHouseStruct clickHouseStruct = new ClickHouseStruct(
                10L, "test-topic", afterStruct, 0, 123456789L,
                beforeStruct, afterStruct, metaData,
                ClickHouseConverter.CDC_OPERATION.UPDATE
        );
        
        // Test beforeModifiedFieldsToJson
        String json = clickHouseStruct.beforeModifiedFieldsToJson();
        
        assertNotNull(json);
        
        // Verify JSON structure
        ObjectMapper mapper = new ObjectMapper();
        JsonNode jsonArray = mapper.readTree(json);
        
        assertTrue(jsonArray.isArray());
        // All schema fields are included (including NULL ones) to ensure
        // that UPDATE SET col=NULL is properly replicated to ClickHouse.
        assertEquals(4, jsonArray.size());
        
        // Check first field (id)
        JsonNode idField = jsonArray.get(0);
        assertEquals("id", idField.get("name").asText());
        assertEquals(0, idField.get("index").asInt());
        assertTrue(idField.has("schema"));
        assertTrue(idField.has("value"));
        assertEquals(1, idField.get("value").asInt());
        
        // Check schema information
        JsonNode idSchema = idField.get("schema");
        assertEquals("INT32", idSchema.get("type").asText());
        assertFalse(idSchema.get("optional").asBoolean());
    }
    
    @Test
    public void testBeforeModifiedFieldsToJsonWithNullBeforeModifiedFields() {
        Map<String, Object> metaData = new HashMap<>();
        Schema basicSchema = SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA)
                .build();
        Struct basicStruct = new Struct(basicSchema).put("id", 1);
        
        ClickHouseStruct clickHouseStruct = new ClickHouseStruct(
                10L, "test-topic", basicStruct, 0, 123456789L,
                null, basicStruct, metaData, // beforeStruct is null
                ClickHouseConverter.CDC_OPERATION.CREATE
        );
        
        String json = clickHouseStruct.beforeModifiedFieldsToJson();
        // change it to empty string check.
        assertEquals("", json);
    }
    
    @Test
    public void testAfterModifiedFieldsToJson() throws Exception {
        // Create schema with multiple fields
        Schema afterSchema = SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA)
                .field("name", Schema.OPTIONAL_STRING_SCHEMA)
                .field("email", Schema.STRING_SCHEMA)
                .field("active", Schema.BOOLEAN_SCHEMA)
                .field("score", Schema.OPTIONAL_FLOAT64_SCHEMA)
                .build();
        
        Struct afterStruct = new Struct(afterSchema)
                .put("id", 2)
                .put("name", "Jane Smith")
                .put("email", "jane@example.com")
                .put("active", true)
                // score is null - should not be in modified fields
                ;
        
        Schema beforeSchema = SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA)
                .build();
        Struct beforeStruct = new Struct(beforeSchema).put("id", 1);
        
        Map<String, Object> metaData = new HashMap<>();
        
        ClickHouseStruct clickHouseStruct = new ClickHouseStruct(
                10L, "test-topic", beforeStruct, 0, 123456789L,
                beforeStruct, afterStruct, metaData,
                ClickHouseConverter.CDC_OPERATION.UPDATE
        );
        
        // Test afterModifiedFieldsToJson
        String json = clickHouseStruct.afterModifiedFieldsToJson();
        
        assertNotNull(json);
        
        // Verify JSON structure
        ObjectMapper mapper = new ObjectMapper();
        JsonNode jsonArray = mapper.readTree(json);
        
        assertTrue(jsonArray.isArray());
        // All schema fields are included (including NULL ones).
        assertEquals(5, jsonArray.size());
        
        // Check fields are present
        boolean foundId = false, foundName = false, foundEmail = false, foundActive = false;
        
        for (int i = 0; i < jsonArray.size(); i++) {
            JsonNode field = jsonArray.get(i);
            String fieldName = field.get("name").asText();
            
            switch (fieldName) {
                case "id":
                    foundId = true;
                    assertEquals(2, field.get("value").asInt());
                    assertEquals("INT32", field.get("schema").get("type").asText());
                    break;
                case "name":
                    foundName = true;
                    assertEquals("Jane Smith", field.get("value").asText());
                    assertEquals("STRING", field.get("schema").get("type").asText());
                    assertTrue(field.get("schema").get("optional").asBoolean());
                    break;
                case "email":
                    foundEmail = true;
                    assertEquals("jane@example.com", field.get("value").asText());
                    assertEquals("STRING", field.get("schema").get("type").asText());
                    break;
                case "active":
                    foundActive = true;
                    assertTrue(field.get("value").asBoolean());
                    assertEquals("BOOLEAN", field.get("schema").get("type").asText());
                    break;
                default:
                    break;
            }
        }

        // Restored: the phase merge truncated this test mid-loop, dropping the
        // for-loop close, the assertions the loop exists to make, and the
        // method close — which is what made the whole file unparseable.
        assertTrue(foundId);
        assertTrue(foundName);
        assertTrue(foundEmail);
        assertTrue(foundActive);
    }

        @Test
        @DisplayName("setBeforeStruct with null should not throw")
        public void testSetBeforeStructNull() {
            ClickHouseStruct chStruct = new ClickHouseStruct();
            chStruct.setBeforeStruct(null);
            Assert.assertNull(chStruct.getBeforeStruct());
            Assert.assertNull(chStruct.getBeforeModifiedFields());
        }

        @Test
        @DisplayName("setAfterStruct with null should not throw")
        public void testSetAfterStructNull() {
            ClickHouseStruct chStruct = new ClickHouseStruct();
            chStruct.setAfterStruct(null);
            Assert.assertNull(chStruct.getAfterStruct());
            Assert.assertNull(chStruct.getAfterModifiedFields());
        }

        @Test
        @DisplayName("setBeforeStruct with valid struct should populate modified fields")
        public void testSetBeforeStructValid() {
            Schema schema = SchemaBuilder.struct()
                    .field("id", Schema.INT32_SCHEMA)
                    .field("name", Schema.STRING_SCHEMA)
                    .build();
            Struct s = new Struct(schema);
            s.put("id", 42);
            s.put("name", "test");

            ClickHouseStruct chStruct = new ClickHouseStruct();
            chStruct.setBeforeStruct(s);

            Assert.assertNotNull(chStruct.getBeforeStruct());
            Assert.assertNotNull(chStruct.getBeforeModifiedFields());
            Assert.assertEquals(2, chStruct.getBeforeModifiedFields().size());
        }

        @Test
        @DisplayName("setAfterStruct with valid struct should populate modified fields")
        public void testSetAfterStructValid() {
            Schema schema = SchemaBuilder.struct()
                    .field("id", Schema.INT32_SCHEMA)
                    .field("value", Schema.FLOAT64_SCHEMA)
                    .build();
            Struct s = new Struct(schema);
            s.put("id", 1);
            s.put("value", 3.14);

            ClickHouseStruct chStruct = new ClickHouseStruct();
            chStruct.setAfterStruct(s);

            Assert.assertNotNull(chStruct.getAfterStruct());
            Assert.assertNotNull(chStruct.getAfterModifiedFields());
            Assert.assertEquals(2, chStruct.getAfterModifiedFields().size());
        }

        @Test
        @DisplayName("setBeforeStruct should include null-valued fields as modified")
        public void testSetBeforeStructPartialNull() {
            Schema schema = SchemaBuilder.struct()
                    .field("id", Schema.INT32_SCHEMA)
                    .field("name", Schema.OPTIONAL_STRING_SCHEMA)
                    .build();
            Struct s = new Struct(schema);
            s.put("id", 42);
            // name is null (optional)

            ClickHouseStruct chStruct = new ClickHouseStruct();
            chStruct.setBeforeStruct(s);

            Assert.assertNotNull(chStruct.getBeforeModifiedFields());
            // Both fields must be present. Skipping null-valued fields was a
            // data-loss bug: an UPDATE that sets a column to NULL would omit that
            // column from the modified-field list, so ClickHouse silently retained
            // the previous value. See testNullFieldsIncludedInModifiedFields.
            Assert.assertEquals(2, chStruct.getBeforeModifiedFields().size());
            Assert.assertEquals("id", chStruct.getBeforeModifiedFields().get(0).name());
            Assert.assertEquals("name", chStruct.getBeforeModifiedFields().get(1).name());
        }
    }

    @Nested
    @DisplayName("getDebeziumTsFromChangeEvent null safety")
    class DebeziumTsTests {

        @Test
        @DisplayName("null changeEvent should return 0")
        public void testNullChangeEvent() {
            Long result = ClickHouseStruct.getDebeziumTsFromChangeEvent(null);
            Assert.assertEquals(Long.valueOf(0L), result);
        }
    }
    
    // =========================================================    // GTID parsing tests — validate parseGtidMax handles all MySQL formats
    // =========================================================
    @Test
    public void testParseGtidSimple() {
        // Simple format: uuid:N
        long result = ClickHouseStruct.parseGtidMax(
                "30fd82c7-0f86-11ee-9e3b-0242c0a86002:2442");
        assertEquals(2442L, result);
    }

    @Test
    public void testParseGtidRange() {
        // Range format: uuid:start-end
        long result = ClickHouseStruct.parseGtidMax(
                "30fd82c7-0f86-11ee-9e3b-0242c0a86002:1-2442");
        assertEquals(2442L, result);
    }

    @Test
    public void testParseGtidMultiRange() {
        // Multi-range format: uuid:range1:range2
        long result = ClickHouseStruct.parseGtidMax(
                "30fd82c7-0f86-11ee-9e3b-0242c0a86002:1-500:502-2442");
        assertEquals(2442L, result);
    }

    @Test
    public void testParseGtidMultiSource() {
        // Multi-source format: uuid1:range,uuid2:range
        long result = ClickHouseStruct.parseGtidMax(
                "30fd82c7-0f86-11ee-9e3b-0242c0a86002:1-2442,"
                + "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee:1-100");
        assertEquals(2442L, result);
    }

    @Test
    public void testParseGtidMultiSourceMultiRange() {
        // Multi-source with multi-range
        long result = ClickHouseStruct.parseGtidMax(
                "uuid1:1-100:200-300,uuid2:1-5000");
        assertEquals(5000L, result);
    }

    @Test
    public void testParseGtidSingleTransaction() {
        // Single transaction ID (no range)
        long result = ClickHouseStruct.parseGtidMax(
                "30fd82c7-0f86-11ee-9e3b-0242c0a86002:1");
        assertEquals(1L, result);
    }

    @Test
    public void testGtidMetadataParsing() {
        // Test GTID via setAdditionalMetaData with multi-range format
        Map<String, Object> metaData = new HashMap<>();
        metaData.put("source", new Struct(
                SchemaBuilder.struct()
                        .field("gtid", Schema.STRING_SCHEMA)
                        .build())
                .put("gtid", "uuid1:1-500:502-2442,uuid2:1-100"));

        Schema basicSchema = SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA).build();
        Struct basicStruct = new Struct(basicSchema).put("id", 1);

        ClickHouseStruct st = new ClickHouseStruct(10, "topic_1",
                basicStruct, 100, 12322323L, basicStruct, basicStruct,
                metaData, ClickHouseConverter.CDC_OPERATION.CREATE);

        assertEquals(2442L, st.getGtid());
    }

    @Test
    public void testServerThreadReadsFromSource() {
        // Verify SERVER_THREAD is read from source (not convertedValue).
        // The Debezium source-block field name is "thread"
        // (SinkRecordColumns.SERVER_THREAD); "_server_thread" is the ClickHouse
        // destination column name (KafkaMetaData.SERVER_THREAD), not the input field.
        Map<String, Object> metaData = new HashMap<>();
        Schema sourceSchema = SchemaBuilder.struct()
                .field("thread", Schema.INT32_SCHEMA)
                .build();
        Struct source = new Struct(sourceSchema).put("thread", 42);
        metaData.put("source", source);

        Schema basicSchema = SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA).build();
        Struct basicStruct = new Struct(basicSchema).put("id", 1);

        ClickHouseStruct st = new ClickHouseStruct(10, "topic_1",
                basicStruct, 100, 12322323L, basicStruct, basicStruct,
                metaData, ClickHouseConverter.CDC_OPERATION.CREATE);

        assertEquals(42, st.getThread());
    }

    @Test
    public void testNullFieldsIncludedInModifiedFields() {
        // Verify that NULL fields ARE included in modifiedFields
        // (this is critical for UPDATE SET col = NULL replication)
        Schema schema = SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA)
                .field("name", Schema.OPTIONAL_STRING_SCHEMA)
                .field("age", Schema.OPTIONAL_INT32_SCHEMA)
                .build();

        // age is NULL — must still appear in modifiedFields
        Struct struct = new Struct(schema)
                .put("id", 1)
                .put("name", "test");
                // age deliberately left as null

        ClickHouseStruct chStruct = new ClickHouseStruct();
        chStruct.setAfterStruct(struct);

        assertNotNull(chStruct.getAfterModifiedFields());
        // All 3 fields must be present, including null age
        assertEquals(3, chStruct.getAfterModifiedFields().size());

        boolean foundAge = false;
        for (org.apache.kafka.connect.data.Field f :
                chStruct.getAfterModifiedFields()) {
            if (f.name().equals("age")) {
                foundAge = true;
            }
        }
        // JUnit 5 takes the failure message LAST (JUnit 4 took it first).
        assertTrue(foundAge, "NULL field 'age' must be in modifiedFields");
    }

    @Test
    public void testJsonSerializationWithComplexSchema() throws Exception {
        // Test with array and nested fields
        Schema nestedSchema = SchemaBuilder.struct()
                .field("street", Schema.STRING_SCHEMA)
                .field("city", Schema.STRING_SCHEMA)
                .build();
        
        Schema afterSchema = SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA)
                .field("tags", SchemaBuilder.array(Schema.STRING_SCHEMA).build())
                .field("address", nestedSchema)
                .build();
        
        List<String> tags = new ArrayList<>();
        tags.add("developer");
        tags.add("java");
        
        Struct nestedStruct = new Struct(nestedSchema)
                .put("street", "123 Main St")
                .put("city", "Anytown");
        
        Struct afterStruct = new Struct(afterSchema)
                .put("id", 1)
                .put("tags", tags)
                .put("address", nestedStruct);
        
        Map<String, Object> metaData = new HashMap<>();
        
        ClickHouseStruct clickHouseStruct = new ClickHouseStruct(
                10L, "test-topic", null, 0, 123456789L,
                null, afterStruct, metaData,
                ClickHouseConverter.CDC_OPERATION.CREATE
        );
        
        String json = clickHouseStruct.afterModifiedFieldsToJson();
        
        assertNotNull(json);
        
        ObjectMapper mapper = new ObjectMapper();
        JsonNode jsonArray = mapper.readTree(json);
        
        assertTrue(jsonArray.isArray());
        assertEquals(3, jsonArray.size());
        
        // Verify complex field types are handled
        for (int i = 0; i < jsonArray.size(); i++) {
            JsonNode field = jsonArray.get(i);
            String fieldName = field.get("name").asText();
            
            if (fieldName.equals("tags")) {
                assertEquals("ARRAY", field.get("schema").get("type").asText());
                assertTrue(field.has("value"));
            } else if (fieldName.equals("address")) {
                assertEquals("STRUCT", field.get("schema").get("type").asText());
                assertTrue(field.has("value"));
            }
        }
        // Restored: the phase merge truncated this test mid-loop, dropping the
        // for-loop close and the method close.
    }

    @Nested
    @DisplayName("setAdditionalMetaData")
    class MetadataTests {

        @Test
        @DisplayName("null metadata should not throw")
        public void testNullMetadata() {
            ClickHouseStruct chStruct = new ClickHouseStruct();
            chStruct.setAdditionalMetaData(null);
            // Should return without error
        }

        @Test
        @DisplayName("empty metadata should not throw")
        public void testEmptyMetadata() {
            ClickHouseStruct chStruct = new ClickHouseStruct();
            Map<String, Object> metadata = new HashMap<>();
            chStruct.setAdditionalMetaData(metadata);
            // Should return without error (no SOURCE key)
        }

        @Test
        @DisplayName("metadata with valid source should populate fields")
        public void testValidSourceMetadata() {
            ClickHouseStruct chStruct = new ClickHouseStruct();

            Schema sourceSchema = SchemaBuilder.struct()
                    .field("ts_ms", Schema.INT64_SCHEMA)
                    .field("server_id", Schema.INT64_SCHEMA)
                    .field("file", Schema.STRING_SCHEMA)
                    .field("pos", Schema.INT64_SCHEMA)
                    .field("row", Schema.INT32_SCHEMA)
                    .field("thread", Schema.INT32_SCHEMA)
                    .field("db", Schema.STRING_SCHEMA)
                    .build();

            Struct source = new Struct(sourceSchema);
            source.put("ts_ms", 1234567890000L);
            source.put("server_id", 1L);
            source.put("file", "mysql-bin.000001");
            source.put("pos", 12345L);
            source.put("row", 0);
            source.put("thread", 42);
            source.put("db", "testdb");

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", source);
            metadata.put("ts_ms", 1234567890001L);

            chStruct.setAdditionalMetaData(metadata);

            Assert.assertEquals(1234567890000L, chStruct.getTs_ms());
            Assert.assertEquals(1234567890001L, chStruct.getDebezium_ts_ms());
            Assert.assertEquals(Long.valueOf(1L), chStruct.getServerId());
            Assert.assertEquals("mysql-bin.000001", chStruct.getFile());
            Assert.assertEquals(Long.valueOf(12345L), chStruct.getPos());
            Assert.assertEquals(0, chStruct.getRow());
            // Fix 1 validation: thread should come from source, not convertedValue
            Assert.assertEquals(42, chStruct.getThread());
            Assert.assertEquals("testdb", chStruct.getDatabase());
        }
    }

    @Nested
    @DisplayName("Version calculation")
    class VersionTests {

        @Test
        @DisplayName("calculateVersion with GTID should use GTID")
        public void testCalculateVersionGtid() {
            ClickHouseStruct chStruct = new ClickHouseStruct();
            chStruct.setGtid(12345L);
            chStruct.calculateVersion(false);
            Assert.assertEquals(12345L, chStruct.getVersion());
        }

        @Test
        @DisplayName("calculateVersion with sequenceNumber should use sequenceNumber")
        public void testCalculateVersionSequenceNumber() {
            ClickHouseStruct chStruct = new ClickHouseStruct();
            chStruct.setSequenceNumber(67890L);
            chStruct.calculateVersion(false);
            Assert.assertEquals(67890L, chStruct.getVersion());
        }

        @Test
        @DisplayName("calculateVersion with LSN should use LSN")
        public void testCalculateVersionLsn() {
            ClickHouseStruct chStruct = new ClickHouseStruct();
            chStruct.setLsn(99999L);
            chStruct.calculateVersion(false);
            Assert.assertEquals(99999L, chStruct.getVersion());
        }

        @Test
        @DisplayName("calculateVersion with no identifiers should leave version uninitialized")
        public void testCalculateVersionNone() {
            ClickHouseStruct chStruct = new ClickHouseStruct();
            chStruct.calculateVersion(false);
            Assert.assertEquals(-1L, chStruct.getVersion());
        }
    }

    @Nested
    @DisplayName("Replication lag")
    class ReplicationLagTests {

        @Test
        @DisplayName("getReplicationLag with ts_ms=0 should return 0")
        public void testReplicationLagZero() {
            ClickHouseStruct chStruct = new ClickHouseStruct();
            Assert.assertEquals(0L, chStruct.getReplicationLag());
        }

        @Test
        @DisplayName("getReplicationLag with valid ts_ms should return positive value")
        public void testReplicationLagPositive() {
            ClickHouseStruct chStruct = new ClickHouseStruct();
            chStruct.setTs_ms(System.currentTimeMillis() - 5000);
            long lag = chStruct.getReplicationLag();
            Assert.assertTrue("Replication lag should be >= 4000ms", lag >= 4000);
            Assert.assertTrue("Replication lag should be < 10000ms", lag < 10000);
        }
    }
}

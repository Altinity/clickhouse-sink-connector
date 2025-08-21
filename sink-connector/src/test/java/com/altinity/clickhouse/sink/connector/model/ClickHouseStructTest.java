package com.altinity.clickhouse.sink.connector.model;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.debezium.engine.ChangeEvent;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ClickHouseStructTest {

    ClickHouseStruct st;

    @Test
    public void testGtid() {

        Map<String, Object> metaData = new HashMap<String, Object>();

        metaData.put("source", new Struct(SchemaBuilder.struct().field("gtid", Schema.STRING_SCHEMA).build()).put("gtid", "0010-122323-0232323:2179558590"));

        String keyField = "customer";
        Schema basicKeySchema = SchemaBuilder
                .struct()
                .field(keyField, Schema.STRING_SCHEMA)
                .build();

        st = new ClickHouseStruct(10, "topic_1", new Struct(basicKeySchema), 100,
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
        assertEquals(3, jsonArray.size()); // Only non-null fields should be included
        
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
        assertNull(json);
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
        assertEquals(4, jsonArray.size()); // Only non-null fields should be included
        
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
            }
        }
        
        assertTrue(foundId);
        assertTrue(foundName);
        assertTrue(foundEmail);
        assertTrue(foundActive);
    }
    
    @Test
    public void testAfterModifiedFieldsToJsonWithNullAfterModifiedFields() {
        Map<String, Object> metaData = new HashMap<>();
        Schema basicSchema = SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA)
                .build();
        Struct basicStruct = new Struct(basicSchema).put("id", 1);
        
        ClickHouseStruct clickHouseStruct = new ClickHouseStruct(
                10L, "test-topic", basicStruct, 0, 123456789L,
                basicStruct, null, metaData, // afterStruct is null
                ClickHouseConverter.CDC_OPERATION.DELETE
        );
        
        String json = clickHouseStruct.afterModifiedFieldsToJson();
        assertNull(json);
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
    }
}

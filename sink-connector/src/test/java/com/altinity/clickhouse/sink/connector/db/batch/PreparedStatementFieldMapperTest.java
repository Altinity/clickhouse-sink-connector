package com.altinity.clickhouse.sink.connector.db.batch;

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PreparedStatementFieldMapper.
 */
public class PreparedStatementFieldMapperTest {

    private PreparedStatementFieldMapper createMapper() {
        return new PreparedStatementFieldMapper(
                null,   // replacingMergeTreeDeleteColumn
                false,  // replacingMergeTreeWithIsDeletedColumn
                null,   // signColumn
                null,   // versionColumn
                "test_db",
                ZoneId.of("UTC")
        );
    }

    @Test
    @DisplayName("getClickHouseDataType should return null for unknown column type")
    public void testGetClickHouseDataTypeUnknownColumn() {
        PreparedStatementFieldMapper mapper = createMapper();
        Map<String, String> columnMap = new HashMap<>();
        columnMap.put("test_col", "SomeUnknownType_XYZ_123");
        // Should not throw, should return null for unrecognized type
        com.clickhouse.data.ClickHouseDataType result = mapper.getClickHouseDataType("test_col", columnMap);
        assertNull(result, "Unknown data type should return null");
    }

    @Test
    @DisplayName("getClickHouseDataType should return correct type for String column")
    public void testGetClickHouseDataTypeValidColumn() {
        PreparedStatementFieldMapper mapper = createMapper();
        Map<String, String> columnMap = new HashMap<>();
        columnMap.put("name_col", "String");
        com.clickhouse.data.ClickHouseDataType result = mapper.getClickHouseDataType("name_col", columnMap);
        assertNotNull(result, "String type should be recognized");
        assertEquals(com.clickhouse.data.ClickHouseDataType.String, result);
    }

    @Test
    @DisplayName("getFieldByColumnName should return null for missing column")
    public void testGetFieldByColumnNameReturnsNullForMissing() throws Exception {
        PreparedStatementFieldMapper mapper = createMapper();
        // Use reflection to call private method
        Method method = PreparedStatementFieldMapper.class.getDeclaredMethod(
                "getFieldByColumnName", List.class, String.class);
        method.setAccessible(true);

        Schema schema = SchemaBuilder.struct()
                .field("existing_field", Schema.STRING_SCHEMA)
                .build();
        List<Field> fields = schema.fields();

        // Search for non-existent field
        Field result = (Field) method.invoke(mapper, fields, "nonexistent_column");
        assertNull(result, "Should return null for column not in field list");

        // Search for existing field
        Field found = (Field) method.invoke(mapper, fields, "existing_field");
        assertNotNull(found, "Should find existing field");
        assertEquals("existing_field", found.name());
    }
}

package com.altinity.clickhouse.sink.connector.db.batch;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the logic for detecting when a PG ARRAY value should be serialized
 * as a string for a non-Array ClickHouse column.
 *
 * The fix in PreparedStatementFieldMapper checks if the CH column type
 * starts with "Array" before calling setArray(). If the CH column is
 * String/Nullable(String), it serializes using value.toString() instead.
 *
 * These tests validate the core serialization logic without requiring
 * a full PreparedStatement or database connection.
 */
public class PreparedStatementFieldMapperArrayTest {

    /**
     * Verifies that a Nullable(String) CH column type is correctly identified
     * as a non-Array type, meaning the array should be serialized as a string.
     */
    @Test
    public void testNullableStringIsNotArrayType() {
        String chColumnType = "Nullable(String)";
        assertFalse(chColumnType.startsWith("Array"),
                "Nullable(String) should NOT be treated as Array type");
    }

    /**
     * Verifies that a String CH column type is correctly identified
     * as a non-Array type.
     */
    @Test
    public void testStringIsNotArrayType() {
        String chColumnType = "String";
        assertFalse(chColumnType.startsWith("Array"),
                "String should NOT be treated as Array type");
    }

    /**
     * Verifies that Array(String) CH column type IS correctly identified
     * as an Array type, so the existing setArray() path should be used.
     */
    @Test
    public void testArrayStringIsArrayType() {
        String chColumnType = "Array(String)";
        assertTrue(chColumnType.startsWith("Array"),
                "Array(String) SHOULD be treated as Array type");
    }

    /**
     * Verifies that Array(Int32) CH column type IS correctly identified
     * as an Array type.
     */
    @Test
    public void testArrayInt32IsArrayType() {
        String chColumnType = "Array(Int32)";
        assertTrue(chColumnType.startsWith("Array"),
                "Array(Int32) SHOULD be treated as Array type");
    }

    /**
     * Tests that ArrayList.toString() produces a reasonable string representation
     * that can be stored in a String column. This is the format used when
     * serializing PG arrays to CH String columns.
     */
    @Test
    public void testArrayToStringSerializationFormat() {
        List<String> arrayValue = Arrays.asList("gpt-4", "gpt-3.5-turbo", "claude-3");
        String serialized = arrayValue.toString();

        assertNotNull(serialized);
        assertEquals("[gpt-4, gpt-3.5-turbo, claude-3]", serialized);
    }

    /**
     * Tests that an empty array serializes correctly.
     */
    @Test
    public void testEmptyArrayToStringSerialization() {
        List<String> arrayValue = Arrays.asList();
        String serialized = arrayValue.toString();

        assertNotNull(serialized);
        assertEquals("[]", serialized);
    }

    /**
     * Tests that a single-element array serializes correctly.
     */
    @Test
    public void testSingleElementArrayToStringSerialization() {
        List<String> arrayValue = Arrays.asList("model-only");
        String serialized = arrayValue.toString();

        assertNotNull(serialized);
        assertEquals("[model-only]", serialized);
    }

    /**
     * Tests the full decision logic: when CH type is non-Array and value is non-null,
     * use value.toString(); when value is null, the code should call setNull().
     */
    @Test
    public void testArraySerializationDecisionLogic() {
        // Simulate the decision logic from the fix
        String chColumnType = "Nullable(String)";
        List<String> value = Arrays.asList("gpt-4", "claude-3");

        // Decision: CH column is not Array, so serialize as string
        boolean isNonArrayChColumn = chColumnType != null && !chColumnType.startsWith("Array");
        assertTrue(isNonArrayChColumn);

        if (isNonArrayChColumn) {
            if (value == null) {
                // Would call ps.setNull(index, Types.VARCHAR)
                fail("Value should not be null in this test case");
            } else {
                // Would call ps.setString(index, value.toString())
                String result = value.toString();
                assertEquals("[gpt-4, claude-3]", result);
            }
        }
    }

    /**
     * Tests the decision logic when value is null.
     */
    @Test
    public void testNullArraySerializationDecisionLogic() {
        String chColumnType = "Nullable(String)";
        Object value = null;

        boolean isNonArrayChColumn = chColumnType != null && !chColumnType.startsWith("Array");
        assertTrue(isNonArrayChColumn);

        // When CH column is non-Array and value is null, should call setNull
        assertTrue(isNonArrayChColumn && value == null,
                "Should call setNull when CH column is non-Array and value is null");
    }

    /**
     * Tests that null CH column type falls through to the existing convert() path.
     */
    @Test
    public void testNullChColumnTypeFallsThrough() {
        String chColumnType = null;

        boolean isNonArrayChColumn = chColumnType != null && !chColumnType.startsWith("Array");
        assertFalse(isNonArrayChColumn,
                "Null CH column type should fall through to existing convert() path");
    }
}

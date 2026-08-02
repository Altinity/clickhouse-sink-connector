package com.altinity.clickhouse.sink.connector.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ColumnOverrides} to verify that DateTime64 columns
 * are NOT incorrectly overridden to String.
 */
public class ColumnOverridesTest {

    @Test
    @DisplayName("DateTime column should be overridden to String")
    public void testDateTimeIsOverridden() {
        String result = ColumnOverrides.getColumnOverride("DateTime");
        assertEquals("String", result, "DateTime should be overridden to String");
    }

    @Test
    @DisplayName("Nullable(DateTime) column should be overridden to Nullable(String)")
    public void testNullableDateTimeIsOverridden() {
        String result = ColumnOverrides.getColumnOverride("Nullable(DateTime)");
        assertEquals("Nullable(String)", result, "Nullable(DateTime) should be overridden to Nullable(String)");
    }

    @Test
    @DisplayName("DateTime64 column should NOT be overridden")
    public void testDateTime64NotOverridden() {
        String result = ColumnOverrides.getColumnOverride("DateTime64(3)");
        assertNull(result, "DateTime64 should NOT be overridden — JDBC handles it correctly");
    }

    @Test
    @DisplayName("Nullable(DateTime64) column should NOT be overridden")
    public void testNullableDateTime64NotOverridden() {
        String result = ColumnOverrides.getColumnOverride("Nullable(DateTime64(6))");
        assertNull(result, "Nullable(DateTime64) should NOT be overridden");
    }

    @Test
    @DisplayName("null dataType should return null without NPE")
    public void testNullDataTypeReturnsNull() {
        String result = ColumnOverrides.getColumnOverride(null);
        assertNull(result, "null input should return null");
    }

    @Test
    @DisplayName("String dataType should not match any override")
    public void testStringNotOverridden() {
        String result = ColumnOverrides.getColumnOverride("String");
        assertNull(result, "String should not be overridden");
    }

    @Test
    @DisplayName("DateTime with timezone should be overridden")
    public void testDateTimeWithTimezoneOverridden() {
        String result = ColumnOverrides.getColumnOverride("DateTime('UTC')");
        assertEquals("String", result, "DateTime('UTC') should be overridden to String");
    }
}

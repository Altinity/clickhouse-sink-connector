package com.altinity.clickhouse.sink.connector.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional tests for {@link Utils} utility methods.
 */
public class UtilsAdditionalTest {

    @Test
    @DisplayName("parseTopicToTableMap should return empty map for null input")
    public void testParseTopicToTableMapNull() throws Exception {
        Map<String, String> result = Utils.parseTopicToTableMap(null);
        assertNotNull(result, "Should return empty map, not null");
        assertTrue(result.isEmpty(), "Should return empty map for null input");
    }

    @Test
    @DisplayName("parseTopicToTableMap should return empty map for empty input")
    public void testParseTopicToTableMapEmpty() throws Exception {
        Map<String, String> result = Utils.parseTopicToTableMap("");
        assertNotNull(result, "Should return empty map, not null");
        assertTrue(result.isEmpty(), "Should return empty map for empty input");
    }

    @Test
    @DisplayName("parseTopicToTableMap should parse valid input")
    public void testParseTopicToTableMapValid() throws Exception {
        Map<String, String> result = Utils.parseTopicToTableMap("topic1:table1,topic2:table2");
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("table1", result.get("topic1"));
        assertEquals("table2", result.get("topic2"));
    }

    @Test
    @DisplayName("isValidTable should reject null/empty table names")
    public void testIsValidTableNullEmpty() {
        assertFalse(Utils.isValidTable(null), "null should be invalid");
        assertFalse(Utils.isValidTable(""), "empty should be invalid");
    }

    @Test
    @DisplayName("isValidTable should reject table names starting with digits")
    public void testIsValidTableStartsWithDigit() {
        assertFalse(Utils.isValidTable("1table"), "Table starting with digit should be invalid");
    }

    @Test
    @DisplayName("isValidTable should accept valid table names")
    public void testIsValidTableValid() {
        assertTrue(Utils.isValidTable("my_table"), "Valid table name should pass");
        assertTrue(Utils.isValidTable("_private"), "Table starting with underscore should pass");
        assertTrue(Utils.isValidTable("Table123"), "Alphanumeric table should pass");
    }

    @Test
    @DisplayName("isValidTable should reject table names over 63 chars")
    public void testIsValidTableTooLong() {
        String longName = "a".repeat(64);
        assertFalse(Utils.isValidTable(longName), "Table name > 63 chars should be invalid");
    }

    @Test
    @DisplayName("getTableNameFromTopic returns null for fewer than 3 segments")
    public void testGetTableNameFromTopicFewSegments() {
        assertNull(Utils.getTableNameFromTopic("simple_topic"),
                "Should return null for topic without 3 segments");
        assertNull(Utils.getTableNameFromTopic("two.parts"),
                "Should return null for topic with only 2 segments");
    }

    @Test
    @DisplayName("getTableNameFromTopic extracts last segment")
    public void testGetTableNameFromTopicValid() {
        assertEquals("orders", Utils.getTableNameFromTopic("server.mydb.orders"));
        assertEquals("users", Utils.getTableNameFromTopic("host.db.schema.users"));
    }
}

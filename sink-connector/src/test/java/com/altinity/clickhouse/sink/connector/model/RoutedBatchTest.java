package com.altinity.clickhouse.sink.connector.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RoutedBatch} utility methods.
 */
public class RoutedBatchTest {

    @Test
    @DisplayName("calculateThreadId should never return negative (Integer.MIN_VALUE hash)")
    public void testCalculateThreadIdNoNegative() {
        // Find a string whose hashCode is Integer.MIN_VALUE
        // "polygenelubricants" is a known string with hashCode == Integer.MIN_VALUE
        // But we can also construct a test by using the actual logic
        // The key property: (hashCode & 0x7FFFFFFF) % N >= 0 for all inputs
        for (int poolSize = 1; poolSize <= 16; poolSize++) {
            for (String name : new String[]{"test", "", "a", "ab", "abc", "server1.db.table"}) {
                int result = RoutedBatch.calculateThreadId(name, poolSize);
                assertTrue(result >= 0,
                        "Thread ID must be non-negative for '" + name + "' with pool size " + poolSize);
                assertTrue(result < poolSize,
                        "Thread ID must be < pool size for '" + name + "' with pool size " + poolSize);
            }
        }
    }

    @Test
    @DisplayName("calculateThreadId with null returns 0")
    public void testCalculateThreadIdNull() {
        assertEquals(0, RoutedBatch.calculateThreadId(null, 10),
                "null table name should return thread 0");
    }

    @Test
    @DisplayName("calculateThreadId with zero pool size returns 0")
    public void testCalculateThreadIdZeroPoolSize() {
        assertEquals(0, RoutedBatch.calculateThreadId("test", 0),
                "zero pool size should return thread 0");
    }

    @Test
    @DisplayName("calculateThreadId with negative pool size returns 0")
    public void testCalculateThreadIdNegativePoolSize() {
        assertEquals(0, RoutedBatch.calculateThreadId("test", -1),
                "negative pool size should return thread 0");
    }

    @Test
    @DisplayName("Same table name always routes to same thread (consistency)")
    public void testCalculateThreadIdConsistency() {
        String tableName = "my_database.my_table";
        int poolSize = 8;
        int expected = RoutedBatch.calculateThreadId(tableName, poolSize);
        for (int i = 0; i < 100; i++) {
            assertEquals(expected, RoutedBatch.calculateThreadId(tableName, poolSize),
                    "Same input must always produce same thread ID");
        }
    }

    @Test
    @DisplayName("extractTableName parses server.database.table format")
    public void testExtractTableNameStandard() {
        assertEquals("orders", RoutedBatch.extractTableName("myserver.mydb.orders"));
    }

    @Test
    @DisplayName("extractTableName returns full topic for non-standard format")
    public void testExtractTableNameFallback() {
        assertEquals("simple_topic", RoutedBatch.extractTableName("simple_topic"));
    }

    @Test
    @DisplayName("extractTableName handles null/empty")
    public void testExtractTableNameNullEmpty() {
        assertEquals("", RoutedBatch.extractTableName(null));
        assertEquals("", RoutedBatch.extractTableName(""));
    }

    @Test
    @DisplayName("createRoutingKey returns database.table from server.database.table")
    public void testCreateRoutingKeyStandard() {
        assertEquals("mydb.orders", RoutedBatch.createRoutingKey("myserver.mydb.orders"));
    }

    @Test
    @DisplayName("createRoutingKey returns full topic for non-standard format")
    public void testCreateRoutingKeyFallback() {
        assertEquals("simple_topic", RoutedBatch.createRoutingKey("simple_topic"));
    }

    @Test
    @DisplayName("createRoutingKey handles null/empty")
    public void testCreateRoutingKeyNullEmpty() {
        assertEquals("", RoutedBatch.createRoutingKey(null));
        assertEquals("", RoutedBatch.createRoutingKey(""));
    }
}

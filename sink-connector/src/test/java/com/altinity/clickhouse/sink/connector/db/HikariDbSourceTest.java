package com.altinity.clickhouse.sink.connector.db;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unit tests for HikariDbSource — Phase 7 thread safety and NPE fixes.
 * <p>
 * These tests verify:
 * - ConcurrentHashMap is used for instance and connectionPool maps (thread safety)
 * - initiateNewConnectionIfClosed throws SQLException for unknown database (NPE fix)
 * - printConnectionInfo does not throw NPE when pool is not initialized
 * - close() handles empty pool gracefully
 * </p>
 */
public class HikariDbSourceTest {

    @Test
    @DisplayName("Instance map should use ConcurrentHashMap for thread safety")
    public void testInstanceMapIsConcurrentHashMap() throws Exception {
        Field instanceField = HikariDbSource.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        Object instanceMap = instanceField.get(null);

        Assertions.assertNotNull(instanceMap, "instance map should not be null");
        Assertions.assertInstanceOf(ConcurrentHashMap.class, instanceMap,
                "instance map should be ConcurrentHashMap for thread safety, not HashMap");
    }

    @Test
    @DisplayName("ConnectionPool map should use ConcurrentHashMap for thread safety")
    public void testConnectionPoolMapIsConcurrentHashMap() throws Exception {
        Field poolField = HikariDbSource.class.getDeclaredField("connectionPool");
        poolField.setAccessible(true);
        Object poolMap = poolField.get(null);

        Assertions.assertNotNull(poolMap, "connectionPool map should not be null");
        Assertions.assertInstanceOf(ConcurrentHashMap.class, poolMap,
                "connectionPool map should be ConcurrentHashMap for thread safety, not HashMap");
    }

    @Test
    @DisplayName("initiateNewConnectionIfClosed should throw SQLException for unknown database instead of NPE")
    public void testInitiateNewConnectionReturnsNullForUnknownDatabase() {
        // Must fail with a diagnosable checked SQLException, not a bare NPE
        // (the previous behaviour dereferenced a null HikariDataSource).
        // Returning null would be worse than throwing: callers in DBMetadata
        // assign the result straight back into `conn` inside retry loops, so a
        // null would resurface later as an NPE with no link to the real cause,
        // or make a query silently report that a database does not exist.
        String unknownDb = "nonexistent_database_" + System.currentTimeMillis();

        SQLException e = Assertions.assertThrows(SQLException.class,
                () -> HikariDbSource.initiateNewConnectionIfClosed(unknownDb),
                "initiateNewConnectionIfClosed should throw SQLException when no pool exists");

        Assertions.assertTrue(e.getMessage().contains(unknownDb),
                "Exception message should name the database that has no pool");
    }

    @Test
    @DisplayName("printConnectionInfo should not throw NPE when pool is empty")
    public void testPrintConnectionInfoEmptyPool() {
        // Should not throw any exception even with no pools registered
        Assertions.assertDoesNotThrow(
                () -> HikariDbSource.printConnectionInfo(),
                "printConnectionInfo should not throw when pool is empty");
    }

    @Test
    @DisplayName("getInstance(String) should return null for unregistered database")
    public void testGetInstanceReturnsNullForUnregistered() {
        Assertions.assertNull(
                HikariDbSource.getInstance("unregistered_db_" + System.currentTimeMillis()),
                "getInstance should return null for unregistered database");
    }

    @Test
    @DisplayName("close() should not throw when pool is empty")
    public void testCloseEmptyPool() {
        // close() on empty pool should be a no-op, not an error
        Assertions.assertDoesNotThrow(
                () -> HikariDbSource.close(),
                "close() should handle empty pool gracefully");
    }

    @Test
    @DisplayName("closeDatabaseConnection should not throw for non-existent database")
    public void testCloseDatabaseConnectionNonExistent() {
        // Should be a no-op — no exception
        Assertions.assertDoesNotThrow(
                () -> HikariDbSource.closeDatabaseConnection(
                        "does_not_exist_" + System.currentTimeMillis()),
                "closeDatabaseConnection should not throw for non-existent database");
    }
}

package com.altinity.clickhouse.sink.connector.db;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton class that manages cache invalidation signals between DDL execution
 * and batch processing threads.
 * 
 * When a DDL statement is executed (e.g., ALTER TABLE ADD/DROP COLUMN), this manager
 * is notified so that cached DbWriter instances can be invalidated and refreshed
 * with the new schema.
 * 
 * This class is thread-safe and uses a lock-free concurrent set.
 */
public class CacheInvalidationManager {

    private static final Logger log = LogManager.getLogger(CacheInvalidationManager.class);

    /**
     * Singleton instance.
     */
    private static final CacheInvalidationManager INSTANCE = new CacheInvalidationManager();

    /**
     * Thread-safe set of table names (in format "database.table") that need cache invalidation.
     */
    private final Set<String> tablesToInvalidate = ConcurrentHashMap.newKeySet();

    /**
     * Private constructor to enforce singleton pattern.
     */
    private CacheInvalidationManager() {
    }

    /**
     * Returns the singleton instance.
     * 
     * @return The CacheInvalidationManager instance.
     */
    public static CacheInvalidationManager getInstance() {
        return INSTANCE;
    }

    /**
     * Marks a table for cache invalidation. This should be called after a DDL
     * statement is successfully executed.
     * 
     * @param tableName The fully qualified table name in format "database.table".
     */
    public void invalidateTable(String tableName) {
        if (tableName != null && !tableName.isEmpty()) {
            tablesToInvalidate.add(tableName);
            log.info("Marked table {} for cache invalidation after DDL", tableName);
        }
    }

    /**
     * Checks if a table needs cache invalidation and removes it from the set
     * if it does. This is an atomic check-and-remove operation.
     * 
     * @param tableName The fully qualified table name in format "database.table".
     * @return true if the table was marked for invalidation (and has been removed
     *         from the set), false otherwise.
     */
    public boolean shouldInvalidate(String tableName) {
        if (tableName == null || tableName.isEmpty()) {
            return false;
        }
        boolean removed = tablesToInvalidate.remove(tableName);
        if (removed) {
            log.info("Cache invalidation triggered for table {}", tableName);
        }
        return removed;
    }

    /**
     * Clears all pending invalidations. Useful for testing.
     */
    public void clearAll() {
        tablesToInvalidate.clear();
    }

    /**
     * Returns the number of tables pending invalidation. Useful for testing.
     * 
     * @return The number of tables pending invalidation.
     */
    public int pendingInvalidations() {
        return tablesToInvalidate.size();
    }
}

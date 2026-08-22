package com.altinity.clickhouse.sink.connector.db;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton class that manages cache invalidation signals between DDL execution
 * and batch processing threads.
 * 
 * When a DDL statement is executed (e.g., ALTER TABLE ADD/DROP COLUMN), this manager
 * is notified so that cached DbWriter instances can be invalidated and refreshed
 * with the new schema.
 * 
 * Invalidation is tracked with a per-table monotonically increasing version
 * counter rather than a remove-on-read signal. Each cached DbWriter records the
 * version it was built at; a consumer rebuilds its writer whenever that version
 * is stale relative to the current table version. Because the version map is a
 * shared singleton but every batch-processing thread keeps its own writer cache,
 * this lets every thread independently detect and rebuild a stale writer without
 * any shared writer state or races.
 * 
 * This class is thread-safe and uses a lock-free concurrent map.
 */
public class CacheInvalidationManager {

    private static final Logger log = LogManager.getLogger(CacheInvalidationManager.class);

    /**
     * Singleton instance.
     */
    private static final CacheInvalidationManager INSTANCE = new CacheInvalidationManager();

    /**
     * Thread-safe map of table names (in format "database.table") to their current
     * invalidation version. Absent tables are treated as version 0.
     */
    private final Map<String, Long> tableVersions = new ConcurrentHashMap<>();

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
     * Marks a table for cache invalidation by bumping its version. This should be
     * called after a DDL statement is successfully executed. The version is
     * monotonically increasing, so any cached DbWriter built at an older version
     * will be rebuilt on its next access.
     * 
     * @param tableName The fully qualified table name in format "database.table".
     */
    public void invalidateTable(String tableName) {
        if (tableName != null && !tableName.isEmpty()) {
            long version = tableVersions.merge(tableName, 1L, Long::sum);
            log.info("Marked table {} for cache invalidation after DDL (version {})",
                    tableName, version);
        }
    }

    /**
     * Returns the current invalidation version for a table. Tables that have never
     * been invalidated are treated as version 0.
     * 
     * @param tableName The fully qualified table name in format "database.table".
     * @return The current invalidation version, or 0 if the table has never been
     *         invalidated.
     */
    public long getVersion(String tableName) {
        if (tableName == null || tableName.isEmpty()) {
            return 0L;
        }
        return tableVersions.getOrDefault(tableName, 0L);
    }

    /**
     * Clears all invalidation versions. Useful for testing.
     */
    public void clearAll() {
        tableVersions.clear();
    }

    /**
     * Returns the number of tables that have a tracked invalidation version.
     * Useful for testing.
     * 
     * @return The number of tables with a tracked invalidation version.
     */
    public int pendingInvalidations() {
        return tableVersions.size();
    }
}

package com.altinity.clickhouse.sink.connector.db;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Singleton class that manages cache invalidation signals between DDL execution
 * and batch processing threads.
 *
 * When a DDL statement is executed (e.g., ALTER TABLE ADD/DROP COLUMN), this manager
 * is notified so that cached DbWriter instances can be invalidated and refreshed
 * with the new schema.
 *
 * <p>In addition to explicit DDL-triggered invalidation, this manager enforces
 * a <b>time-to-live (TTL)</b> on the per-table schema cache. Keeping a cache
 * entry forever is unsafe: if a schema change is ever missed (a DDL that is
 * filtered, a connector that was down during the ALTER, or a metadata-cache bug
 * like the Altinity 2.8.0 issue), the connector would keep inserting against a
 * stale schema indefinitely. With a TTL, any such drift self-heals within at
 * most one TTL window because the cache is rebuilt from {@code system.columns}.
 * The TTL defaults to one hour.</p>
 *
 * <p>This class is thread-safe.</p>
 */
public class CacheInvalidationManager {

    private static final Logger log = LogManager.getLogger(CacheInvalidationManager.class);

    /** Default cache TTL: rebuild every table's schema cache at least hourly. */
    public static final long DEFAULT_CACHE_TTL_MS = 60L * 60L * 1000L;

    /**
     * Singleton instance.
     */
    private static final CacheInvalidationManager INSTANCE = new CacheInvalidationManager();

    /**
     * Monotonic DDL generation per table, keyed by "database.table".
     *
     * A plain Set with a remove-on-read check was not usable here: the connector runs
     * a pool of worker threads, each holding its OWN topicToDbWriterMap cache. The
     * first thread to observe the invalidation consumed it, so every other thread
     * kept serving a stale DbWriter (and therefore a stale column list) forever.
     * A generation counter lets every cache holder invalidate independently.
     */
    private final Map<String, AtomicLong> tableGenerations = new ConcurrentHashMap<>();

    /**
     * Per-table timestamp (epoch ms) of the last cache build, for TTL expiry.
     * Keyed by fully qualified table name ("database.table").
     */
    private final Map<String, Long> lastBuildEpochMs = new ConcurrentHashMap<>();

    /** Cache TTL in milliseconds; entries older than this are forced to rebuild. */
    private volatile long cacheTtlMs = DEFAULT_CACHE_TTL_MS;

    /**
     * Private constructor to enforce singleton pattern.
     */
    private CacheInvalidationManager() {
    }

    /**
     * Sets the cache TTL. A value &lt;= 0 disables TTL-based expiry (DDL-triggered
     * invalidation still applies).
     *
     * @param ttlMs TTL in milliseconds.
     */
    public void setCacheTtlMs(long ttlMs) {
        this.cacheTtlMs = ttlMs;
        log.info("Schema cache TTL set to {}ms", ttlMs);
    }

    /** Returns the current cache TTL in milliseconds. */
    public long getCacheTtlMs() {
        return cacheTtlMs;
    }

    /**
     * Records that a table's schema cache was just (re)built. Resets the TTL
     * clock for that table. Call this whenever a fresh {@code DbWriter} /
     * column map is constructed for the table.
     *
     * @param tableName fully qualified table name ("database.table").
     */
    public void markCacheBuilt(String tableName) {
        if (tableName != null && !tableName.isEmpty()) {
            lastBuildEpochMs.put(tableName, System.currentTimeMillis());
        }
    }

    /**
     * Returns true if the table's cache has exceeded its TTL and must be
     * rebuilt. Tables with no recorded build time are treated as expired so the
     * first access establishes a fresh, timestamped entry.
     *
     * @param tableName fully qualified table name ("database.table").
     * @return true if the cache is stale per the TTL policy.
     */
    public boolean isCacheExpired(String tableName) {
        if (tableName == null || tableName.isEmpty()) {
            return false;
        }
        if (cacheTtlMs <= 0) {
            return false;
        }
        Long built = lastBuildEpochMs.get(tableName);
        if (built == null) {
            return true;
        }
        boolean expired = (System.currentTimeMillis() - built) >= cacheTtlMs;
        if (expired) {
            log.info("Schema cache for {} exceeded TTL ({}ms); forcing rebuild", tableName, cacheTtlMs);
        }
        return expired;
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
            long generation = tableGenerations
                    .computeIfAbsent(tableName, k -> new AtomicLong())
                    .incrementAndGet();
            log.info("Marked table {} for cache invalidation after DDL (generation {})",
                    tableName, generation);
        }
    }

    /**
     * Returns the current DDL generation for a table. A cache holder records the
     * generation it built its entry at and rebuilds whenever the value changes.
     *
     * @param tableName The fully qualified table name in format "database.table".
     * @return The current generation, or 0 if the table has never had a DDL applied.
     */
    public long currentGeneration(String tableName) {
        if (tableName == null || tableName.isEmpty()) {
            return 0L;
        }
        AtomicLong generation = tableGenerations.get(tableName);
        return generation == null ? 0L : generation.get();
    }

    /**
     * Clears all pending invalidations. Useful for testing.
     */
    public void clearAll() {
        // tablesToInvalidate (a remove-on-read Set) no longer exists: it was
        // replaced by the tableGenerations counter, because the Set let the
        // FIRST worker thread consume an invalidation while every other thread
        // kept serving a stale DbWriter. Clear the generation map plus this
        // PR's TTL bookkeeping.
        tableGenerations.clear();
        lastBuildEpochMs.clear();
    }

    /**
     * Returns the number of tables that have had at least one DDL applied.
     * Useful for testing.
     *
     * @return The number of tracked tables.
     */
    public int pendingInvalidations() {
        return tableGenerations.size();
    }
}

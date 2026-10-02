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
     * Columns proven, by a fresh metadata read, NOT to be part of a table's
     * writable column map -- keyed by "database.table", then by lower-cased
     * column name, valued with the table version the proof was taken at.
     *
     * <p>Not every column carried by a CDC record is expected to appear in the
     * cached column map. {@code DBMetadata#getColumnsDataTypesForTable}
     * deliberately drops columns whose {@code default_kind} is
     * {@code MATERIALIZED} or {@code ALIAS}, because ClickHouse computes those
     * and refuses an INSERT that binds them. A source column that was made
     * MATERIALIZED on the ClickHouse side is therefore absent from the map
     * permanently and correctly -- it is not evidence of a stale cache, and
     * re-reading metadata will never make it appear.</p>
     *
     * <p>Recording that proof is what stops the re-read from repeating per
     * record. The version is stored alongside so a later genuine DDL (which
     * bumps the version) discards the proof and the column is probed once
     * more -- covering the case where a MATERIALIZED column is redefined as
     * an ordinary one.</p>
     */
    private final Map<String, Map<String, Long>> columnsProvenAbsent =
            new ConcurrentHashMap<>();

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
        // The epoch is folded in so that invalidateAll() reaches tables that
        // have no entry of their own. A per-table map alone cannot express
        // "everything is stale": a table never individually invalidated would
        // read version 0 before and after the sweep and keep its cached schema.
        return globalEpoch.get() + tableVersions.getOrDefault(tableName, 0L);
    }

    /**
     * Invalidates every cached schema, including those of tables that have
     * never been invalidated individually.
     *
     * <p>This is the fail-safe for a DDL whose affected table cannot be
     * determined -- an unparsed statement, an unresolvable table name, or an
     * error while working it out. In those cases the connector knows the
     * schema changed but not where, and leaving caches in service lets writers
     * bind against a table shape that no longer exists. That produces rows
     * which insert successfully with wrong contents and identical row counts,
     * which is undetectable without a value-level checksum.</p>
     *
     * <p>Bumping a monotonic epoch invalidates every writer at once without
     * having to enumerate them, and costs one metadata re-read per active
     * table on its next batch.</p>
     */
    public void invalidateAll() {
        long epoch = globalEpoch.incrementAndGet();
        log.warn("Invalidating ALL cached schemas after a DDL whose affected table could "
                + "not be determined (epoch {}). Every writer will re-read its metadata "
                + "before its next write.", epoch);
    }

    /**
     * Monotonic counter bumped by {@link #invalidateAll()}.
     *
     * <p>Added to every per-table version, so one increment makes every cached
     * writer -- including those for tables absent from the version map --
     * observe a version change and rebuild.</p>
     */
    private final java.util.concurrent.atomic.AtomicLong globalEpoch =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Records that a fresh metadata read proved {@code columnName} is not part
     * of {@code tableName}'s writable column map at the current table version.
     *
     * <p>Called after a re-read that did not resolve the apparent staleness.
     * The only way that happens is a column ClickHouse owns -- MATERIALIZED or
     * ALIAS -- which {@code getColumnsDataTypesForTable} filters out by design.
     * Without this record the caller re-reads metadata for the same column on
     * every subsequent record forever.</p>
     *
     * @param tableName  fully qualified table name ("database.table").
     * @param columnName the column proven absent; compared case-insensitively.
     */
    public void markColumnProvenAbsent(String tableName, String columnName) {
        if (tableName == null || tableName.isEmpty()
                || columnName == null || columnName.isEmpty()) {
            return;
        }
        columnsProvenAbsent
                .computeIfAbsent(tableName, k -> new ConcurrentHashMap<>())
                .put(columnName.toLowerCase(), getVersion(tableName));
    }

    /**
     * Returns true if {@code columnName} was already proven absent from
     * {@code tableName} at the table's CURRENT version.
     *
     * <p>A proof taken at an older version is ignored (and discarded), because
     * a DDL since then may have turned the column into an ordinary one that a
     * re-read would now find.</p>
     *
     * @param tableName  fully qualified table name ("database.table").
     * @param columnName the column to test; compared case-insensitively.
     * @return true when a re-read for this column would be pointless.
     */
    public boolean isColumnProvenAbsent(String tableName, String columnName) {
        if (tableName == null || tableName.isEmpty()
                || columnName == null || columnName.isEmpty()) {
            return false;
        }
        Map<String, Long> proven = columnsProvenAbsent.get(tableName);
        if (proven == null) {
            return false;
        }
        Long provenAt = proven.get(columnName.toLowerCase());
        if (provenAt == null) {
            return false;
        }
        // longValue() is explicit on purpose: `provenAt == getVersion(...)`
        // would read as a reference comparison even though it unboxes here,
        // and a later refactor returning a boxed Long would silently make it
        // one -- which fails above the Long cache and reinstates the storm.
        if (provenAt.longValue() == getVersion(tableName)) {
            return true;
        }
        // Stale proof: a DDL landed after it was taken. Drop it so the column
        // is probed once against the new schema.
        proven.remove(columnName.toLowerCase());
        return false;
    }

    /**
     * Clears all invalidation versions. Useful for testing.
     */
    public void clearAll() {
        tableVersions.clear();
        columnsProvenAbsent.clear();
        globalEpoch.set(0L);
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

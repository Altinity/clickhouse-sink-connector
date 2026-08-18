package com.altinity.clickhouse.sink.connector.db;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton that freezes replication (INSERT processing) for a single table
 * while a DDL schema change is being applied and reconciled.
 *
 * <p><b>Why this exists.</b> The INSERT path builds the column list for an
 * INSERT statement by iterating the <i>cached</i> ClickHouse column map
 * ({@code columnNameToDataTypeMap}), not the source event fields
 * (see {@code PreparedStatementFieldMapper#insertPreparedStatement}). If a
 * DDL adds a column on the source and the batch thread inserts a row using a
 * stale cache that predates the {@code ALTER TABLE}, the new column is
 * silently omitted from the INSERT and ClickHouse stores the column's DEFAULT
 * (NULL / 0) instead of the real source value. This is silent data loss
 * (GitHub issue #1222: a column added by {@code ADD COLUMN} replicated as
 * NULL for rows written during the window).</p>
 *
 * <p>The DDL waiter alone (PR #1321) is necessary but not sufficient: it makes
 * the DDL thread wait for {@code system.columns} visibility, but it does not
 * stop other batch threads from inserting against the stale cache during the
 * window between {@code ALTER TABLE} execution and the cache rebuild. This
 * manager closes that window by freezing the table:</p>
 *
 * <ol>
 *   <li>DDL thread calls {@link #freeze(String)} <b>before</b> executing the
 *       {@code ALTER TABLE}.</li>
 *   <li>DDL thread executes the ALTER, waits for {@code system.columns}
 *       visibility, rebuilds the cache, and validates source-vs-destination
 *       schema integrity.</li>
 *   <li>DDL thread calls {@link #unfreeze(String)} only once both sides and the
 *       cache hold the schema derived from the source.</li>
 *   <li>Any insert thread calls {@link #awaitUnfrozen(String, long)} before
 *       processing a batch for that table; it blocks while the table is
 *       frozen.</li>
 * </ol>
 *
 * <p>The freeze is <b>per table</b>, so DDL on one table never stalls inserts
 * to unrelated tables. The implementation uses one monitor object per table
 * with a {@code frozen} flag and {@code wait()/notifyAll()} — the DDL thread
 * and the insert threads are always distinct threads, so no lock reentrancy is
 * required.</p>
 *
 * <p>This class is thread-safe.</p>
 */
public class TableReplicationFreezeManager {

    private static final Logger log =
            LogManager.getLogger(TableReplicationFreezeManager.class);

    private static final TableReplicationFreezeManager INSTANCE =
            new TableReplicationFreezeManager();

    /**
     * Per-table freeze state. The value object is its own monitor.
     * Keyed by fully qualified table name ("database.table").
     */
    private final Map<String, FreezeState> freezeStates =
            new ConcurrentHashMap<>();

    private TableReplicationFreezeManager() {
    }

    public static TableReplicationFreezeManager getInstance() {
        return INSTANCE;
    }

    /**
     * Per-table monitor + freeze flag. The instance itself is the lock.
     */
    private static final class FreezeState {
        private boolean frozen = false;
    }

    private FreezeState stateFor(String fullyQualifiedTableName) {
        return freezeStates.computeIfAbsent(
                fullyQualifiedTableName, k -> new FreezeState());
    }

    /**
     * Freezes replication for a table. Must be paired with a later
     * {@link #unfreeze(String)} call in a {@code finally} block so the table
     * is never left frozen forever if reconciliation throws.
     *
     * @param fullyQualifiedTableName table in "database.table" form.
     */
    public void freeze(String fullyQualifiedTableName) {
        if (fullyQualifiedTableName == null || fullyQualifiedTableName.isEmpty()) {
            return;
        }
        FreezeState state = stateFor(fullyQualifiedTableName);
        synchronized (state) {
            state.frozen = true;
        }
        log.info("Replication FROZEN for table {} (DDL schema change in progress)",
                fullyQualifiedTableName);
    }

    /**
     * Unfreezes replication for a table and wakes any waiting insert threads.
     *
     * @param fullyQualifiedTableName table in "database.table" form.
     */
    public void unfreeze(String fullyQualifiedTableName) {
        if (fullyQualifiedTableName == null || fullyQualifiedTableName.isEmpty()) {
            return;
        }
        FreezeState state = stateFor(fullyQualifiedTableName);
        synchronized (state) {
            state.frozen = false;
            state.notifyAll();
        }
        log.info("Replication UNFROZEN for table {} (schema reconciled on both sides + cache)",
                fullyQualifiedTableName);
    }

    /**
     * Returns true if the table is currently frozen.
     */
    public boolean isFrozen(String fullyQualifiedTableName) {
        if (fullyQualifiedTableName == null || fullyQualifiedTableName.isEmpty()) {
            return false;
        }
        FreezeState state = freezeStates.get(fullyQualifiedTableName);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            return state.frozen;
        }
    }

    /**
     * Blocks the calling (insert) thread while the table is frozen, up to
     * {@code timeoutMs}. Returns when the table is unfrozen or the timeout
     * elapses.
     *
     * <p>Fails <b>safe</b>: if the timeout elapses while still frozen, this
     * logs a loud warning and returns {@code false}. The caller should treat
     * {@code false} as "do not insert this batch yet" and retry, rather than
     * inserting against a possibly-stale cache. Returning (rather than
     * throwing) keeps the batch loop alive; the loud log surfaces a stuck
     * freeze for operators.</p>
     *
     * @param fullyQualifiedTableName table in "database.table" form.
     * @param timeoutMs               max time to wait, in milliseconds.
     * @return {@code true} if the table is unfrozen (safe to insert);
     *         {@code false} if the timeout elapsed while still frozen.
     */
    public boolean awaitUnfrozen(String fullyQualifiedTableName, long timeoutMs) {
        if (fullyQualifiedTableName == null || fullyQualifiedTableName.isEmpty()) {
            return true;
        }
        FreezeState state = freezeStates.get(fullyQualifiedTableName);
        if (state == null) {
            return true;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (state) {
            while (state.frozen) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    log.warn("Timeout ({}ms) waiting for table {} to unfreeze; "
                                    + "insert batch deferred to avoid writing against a stale "
                                    + "schema cache. A DDL reconciliation may be stuck.",
                            timeoutMs, fullyQualifiedTableName);
                    return false;
                }
                try {
                    state.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted while waiting for table {} to unfreeze",
                            fullyQualifiedTableName);
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Clears all freeze state. Test-only.
     */
    public void clearAll() {
        freezeStates.clear();
    }
}

package com.altinity.clickhouse.sink.connector.db.batch;

/**
 * Raised when the connector detects that a cached schema no longer matches the
 * source metadata it was derived from, in a way that would silently drop a
 * column value on write.
 *
 * <p><b>Why this is an exception and not a log line.</b> The condition it
 * reports -- a column present in both the ClickHouse table and the incoming
 * record, but absent from the generated INSERT's parameter map -- produces a
 * row that inserts successfully with the wrong contents. The affected column
 * receives the ClickHouse DEFAULT (0 / '' / 1970-01-01) instead of its real
 * value, row counts stay identical on both sides, and no batch fails. Nothing
 * in the pipeline notices.</p>
 *
 * <p>When the condition was only logged, every affected write still succeeded,
 * so row counts could not reveal the divergence. A value-level comparison
 * against the source was the only check that found it.</p>
 *
 * <p>Throwing instead routes the batch into the existing retry path, which
 * rebuilds the writer against freshly read metadata. A retry that reads the
 * current schema will bind the column correctly; a retry that cannot is a real
 * failure that an operator must see. Either outcome is strictly better than a
 * successful write of wrong data.</p>
 *
 * <p>Unchecked so it can propagate through the batch lambda in
 * {@code PreparedStatementExecutor} without widening signatures across the
 * write path.</p>
 */
public class StaleSchemaCacheException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message description naming the column, database and table, so the
     *                operator can identify the affected data without reading
     *                the code.
     */
    public StaleSchemaCacheException(String message) {
        super(message);
    }

    /**
     * @param message description naming the column, database and table.
     * @param cause   the metadata read or classification failure that prevented
     *                the staleness from being resolved. Its ClickHouse error code
     *                decides whether the batch is retried or stopped.
     */
    public StaleSchemaCacheException(String message, Throwable cause) {
        super(message, cause);
    }
}

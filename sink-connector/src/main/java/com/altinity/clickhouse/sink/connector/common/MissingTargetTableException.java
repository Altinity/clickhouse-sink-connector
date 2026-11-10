package com.altinity.clickhouse.sink.connector.common;

/**
 * Raised when the target table a batch is addressed to is <em>proven</em>
 * absent from ClickHouse, so the batch can never be written until a human (or
 * auto-create) makes the table exist.
 *
 * <p>An unresolved column map is ambiguous: it is produced both by a missing
 * table and by a metadata read that has not settled. Only the second is worth
 * retrying. Retrying the first converts a precise, actionable error into an
 * unattributed stall -- the handoff FIFO fills, the hard cap trips, and the
 * engine stops with a message that names neither the table nor the reason --
 * which is exactly what happened on 2026-11-09, when a dropped table made a
 * connector stop every 13 minutes for hours (spec 08.07).</p>
 *
 * <p>Absence is only ever concluded from a live catalog read that positively
 * answers "no such table" (see {@code DbWriter.isTargetTableAbsent}), never
 * from an empty column map or an unreachable server. That distinction is what
 * keeps a dropped table, a broken connection and a transient metadata read
 * from sharing one diagnosis.</p>
 *
 * <p>Registered in {@code ClickHouseErrorClassifier}'s terminal types, so the
 * engine fails once with this cause instead of restarting into the same wall.
 * It deliberately does not extend {@code BatchPersistenceException}: nothing in
 * the tree catches that parent, and it is thrown from BOTH execution engines,
 * only one of which owns that nested type (spec 08.07 section 3.5). The
 * classifier matches the subtype anywhere in the cause chain, so the writer's
 * rethrow still classifies correctly after it wraps an unrelated failure --
 * see the {@code catch} in {@code ClickHouseBatchWriter.persistRecords}.</p>
 */
public class MissingTargetTableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message description naming the database, the table and the two
     *                legitimate resolutions.
     */
    public MissingTargetTableException(String message) {
        super(message);
    }
}

package com.altinity.clickhouse.debezium.embedded.cdc;

/**
 * Thrown when a DDL change event cannot be applied to ClickHouse and the
 * pipeline must therefore STOP rather than advance past it.
 *
 * <p><b>Why this type exists.</b> {@code processEveryChangeRecord} ends in a
 * catch-all ({@code catch (Exception e) { log.error("Exception processing
 * record", e); }}) whose job is to keep a single malformed DML record from
 * killing the stream. A DDL failure is the opposite situation: if the schema
 * change is skipped but the stream continues, every subsequent row is written
 * against a ClickHouse schema that no longer matches MySQL — a silent,
 * count-clean divergence, exactly what the DDL barrier exists to prevent.
 * A plain {@link RuntimeException} raised on the DDL path is swallowed by that
 * catch-all; this dedicated type is re-thrown ahead of it so the failure
 * propagates out of the Debezium {@code handleBatch} consumer and halts the
 * engine.</p>
 *
 * <p>Halting is the loud, recoverable outcome (Invariant I9, Loud Failure):
 * offsets are NOT committed past the unapplied DDL, so on restart Debezium
 * re-delivers the DDL from the last committed position and the connector
 * retries it from a consistent point. That is the genuine retry the drain
 * abort was always meant to enable — the previous behaviour logged the abort
 * and then let the row stream march on, which was neither a retry nor safe.</p>
 */
public class DDLReplicationException extends RuntimeException {

    public DDLReplicationException(String message, Throwable cause) {
        super(message, cause);
    }
}

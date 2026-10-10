package com.altinity.clickhouse.debezium.embedded.cdc;

/**
 * Thrown when a ROW change event (a record whose value carries an {@code op}
 * field) cannot be turned into a {@code ClickHouseStruct} — the parser returned
 * {@code null} or threw — and the pipeline must therefore STOP rather than
 * advance past it (spec 01.06 §3.1, spec 10.04 §3.3).
 *
 * <p><b>Why this type exists.</b> {@code processEveryChangeRecord} ends in a
 * catch-all whose job used to be described as "keep one malformed DML record
 * from killing the stream". For a row record that is exactly the wrong
 * outcome: the record produced no row, so it was remembered as the batch's
 * {@code lastControlRecord} and — once the pipeline was quiescent — its offset
 * was committed like a heartbeat's. The durable source position then moved
 * past a row that never reached ClickHouse; a restart did not redeliver it,
 * and row counts on the two sides disagreed by one with nothing in the log
 * louder than a WARN. Like {@link DDLReplicationException}, this type is
 * re-thrown ahead of that catch-all so the failure leaves the Debezium
 * {@code handleBatch} consumer and halts the engine.</p>
 *
 * <p>Halting is the loud, recoverable outcome (Invariant I9): the offset is
 * NOT committed past the record, so on restart Debezium redelivers it from the
 * last committed position. If the record is genuinely unconvertible the
 * connector stops again on it, every time, until the cause — a converter gap,
 * a source configuration such as {@code binlog_row_image != FULL}, a corrupt
 * event — is fixed. That is the intended behaviour: a replication engine that
 * cannot represent a source row must say so, not skip it.</p>
 *
 * <p>Records that carry no row BY CONTRACT — heartbeats, transaction
 * markers, Debezium tombstones (null value) — are classified by
 * {@code DebeziumChangeEventCapture.isControlRecord} BEFORE this rule applies
 * and are never raised as this exception.</p>
 */
public class RecordReplicationException extends RuntimeException {

    public RecordReplicationException(String message) {
        super(message);
    }

    public RecordReplicationException(String message, Throwable cause) {
        super(message, cause);
    }
}

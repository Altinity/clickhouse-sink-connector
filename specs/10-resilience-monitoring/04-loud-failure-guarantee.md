# Spec 10.04: Loud Failure Guarantee & Anti-Swallowing Protocol

## 1. Executive Summary & Purpose
Specifies the non-negotiable policy that unrecoverable replication errors must terminate execution loudly and immediately, strictly prohibiting error swallowing.

---

## 2. Codebase Mapping on 2.11.0
- **System Constitution**: Invariant I9
- **Error Classifier**: `ClickHouseErrorClassifier`
- **DDL failure type**: `DDLReplicationException` (`...embedded/cdc/DDLReplicationException.java`), re-thrown ahead of the catch-all in `DebeziumChangeEventCapture#processEveryChangeRecord`

---

## 3. Operational Specification

### 3.1 Anti-Swallowing Rule
Under no circumstances may a worker catch a `SQLException` or `DataConversionException` and simply log a warning while allowing the batch to proceed.
```java
// STRICTLY FORBIDDEN ANTI-PATTERN:
try {
    ps.executeBatch();
} catch (SQLException e) {
    logger.warn("Batch failed, skipping records: " + e.getMessage()); // VIOLATION!
}
```

### 3.2 Mandatory Crash Contract
When an unrecoverable failure occurs:
1. Roll back the local JDBC transaction.
2. Log full stack trace, binlog coordinates, and table name at `FATAL` level.
3. Throw an unchecked runtime exception to terminate the JVM process or task thread.
4. Leaves `replica_source_info` intact at the last known good commit.

### 3.3 DDL Path Anti-Swallowing (DDLReplicationException)

The DDL path has its own catch-all in
`DebeziumChangeEventCapture#processEveryChangeRecord`
(`catch (Exception e) { log.error("Exception processing record", e); }`) whose
legitimate purpose is to keep one malformed DML record from killing the stream.
A DDL failure absorbed by that catch is a §3.1 violation with a worse blast
radius: the schema change is lost, offsets advance past it, and every later row
diverges silently from MySQL.

Therefore a DDL that cannot be applied — a `drainBeforeDDL()` abort, or retry
exhaustion in `performDDLOperation()` — is raised as `DDLReplicationException`
and re-thrown **ahead of** the generic catch, so it leaves the Debezium
`handleBatch` consumer and halts the engine. The offset is not committed past
the DDL, so a restart re-delivers it — the same loud-but-recoverable contract as
§3.2. This mirrors `ClickHouseBatchRunnable`'s FATAL rethrow, which stops the
scheduled executor rather than retrying a doomed batch forever.

### 3.4 Worker death must reach the engine (no silent stall)
A `RuntimeException` thrown from a `scheduleAtFixedRate` task only cancels
that task's future; the executor logs nothing and calls nobody. A FATAL
rethrow in `ClickHouseBatchRunnable#run` therefore used to leave the process
alive with one worker dead, its queue filling, and offsets frozen — a stall
with no error after the first one. `DebeziumChangeEventCapture` retains the
workers' `ScheduledFuture`s and checks them at the top of every
`handleChangeEventBatch` (`failIfWorkerDied`); a done future is re-raised as
a `RuntimeException` carrying the worker's cause, which stops the engine
through its completion callback (spec 03.01 §3.3).

---

### 3.5 The grouping stage never drops a record
A record that reaches `GroupInsertQueryWithBatchRecords` is either grouped
into a statement or fails the batch with an exception that names the record.
The grouper used to answer `false` for a record it could not build a
statement for, and the caller kept only the last record's answer — a
per-record skip with the offset still advancing, the same silence as §3.1
with a row-shaped victim (in practice the missing-image case died earlier
with an opaque `NullPointerException`, which told the operator nothing).
Spec 04.01 §3.3 states the exceptions; an empty query map is likewise refused
by the executor instead of being retried forever.

---

## 4. Invariants Preserved
- **Invariant I9 (Loud Failure / Zero Silence)**: Guarantees that data divergence is never masked by silent error suppression.

---

## 5. Verification Criteria
- `DdlFailureLoudTest.ddlFailurePropagatesInsteadOfBeingSwallowed()` — §3.3: a DDL failure escapes the catch-all as `DDLReplicationException`.
- `ClickHouseErrorClassifierTest.testIsFatal()`, `ClickHouseErrorClassifierTest.testClassifyFatal()` — the FATAL set that triggers the rethrow.
- `ClickHouseBatchWriterMissingTableTest` — a missing target table fails the batch loudly instead of being skipped.
- `WorkerDeathIsLoudTest.deadWorkerFailsTheNextBatchLoudly()`
- `GroupInsertQueryWithBatchRecordsTest.deleteWithoutBeforeImageFailsLoudly()`, `PreparedStatementExecutorNoSilentDropTest.emptyQueryMapIsRefusedNotRetried()` — §3.5.

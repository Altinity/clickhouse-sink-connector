# Spec 10.04: Loud Failure Guarantee & Anti-Swallowing Protocol

## 1. Executive Summary & Purpose
Specifies the non-negotiable policy that unrecoverable replication errors must terminate execution loudly and immediately, strictly prohibiting error swallowing.

---

## 2. Codebase Mapping on 2.11.0
- **System Constitution**: Invariant I9
- **Error Classifier**: `ClickHouseErrorClassifier`
- **DDL failure type**: `DDLReplicationException` (`...embedded/cdc/DDLReplicationException.java`), re-thrown ahead of the catch-all in `DebeziumChangeEventCapture#processEveryChangeRecord`
- **Row failure type**: `RecordReplicationException` (`...embedded/cdc/RecordReplicationException.java`), re-thrown ahead of the same catch-all

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

### 3.3 Record-path anti-swallowing: `DDLReplicationException` and `RecordReplicationException`

`DebeziumChangeEventCapture#processEveryChangeRecord` ends in a catch-all
(`catch (Exception e) { log.error("Exception processing record", e); }`). It
used to be described as keeping "one malformed DML record from killing the
stream". That description was the defect: a record absorbed there returns
`null`, and the batch loop treated every null as a control record whose offset
it committed once the pipeline was quiescent — so the "malformed" row was not
merely skipped, its offset was durably acknowledged and a restart never
redelivered it (spec 01.06 §3.1). There is no record for which continuing past
it is correct:

- **DDL** — a schema change that cannot be applied (a `drainBeforeDDL()` abort,
  or retry exhaustion in `performDDLOperation()`) is raised as
  `DDLReplicationException`. Absorbing it loses the schema change, advances the
  offset past it, and every later row diverges silently from MySQL.
- **Row** — a record whose value carries an `op` field but for which the parser
  returned `null` or threw is raised as `RecordReplicationException`. Absorbing
  it loses that row with the offset advanced past it.

Both types are re-thrown **ahead of** the generic catch, so they leave the
Debezium `handleBatch` consumer and halt the engine. The offset is not committed
past the failed record, so a restart redelivers it — the same
loud-but-recoverable contract as §3.2. This mirrors `ClickHouseBatchRunnable`'s
FATAL rethrow, which stops the scheduled executor rather than retrying a doomed
batch forever. The catch-all that remains is reached only by exceptions raised
BEFORE classification (a record whose schema cannot even be read); for a
non-control record that path still ends in `RecordReplicationException`, thrown
by `handleChangeEventBatch` when the null reaches it (spec 01.06 §3.1 rule 3).

Records that carry no row by contract — heartbeats, transaction markers,
Debezium tombstones — are classified as control records before this rule
applies and are never raised (spec 01.06 §3.1 table).

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

## 4. Invariants Preserved
- **Invariant I9 (Loud Failure / Zero Silence)**: Guarantees that data divergence is never masked by silent error suppression.

---

## 5. Verification Criteria
- `DdlFailureLoudTest.ddlFailurePropagatesInsteadOfBeingSwallowed()` — §3.3: a DDL failure escapes the catch-all as `DDLReplicationException`.
- `UnparseableRowRecordIsTerminalTest.insertWhoseParserThrowsIsTerminal()`, `UnparseableRowRecordIsTerminalTest.updateWhoseParserReturnsNullIsTerminal()` — §3.3: a row record whose parse throws / returns null escapes the catch-all as `RecordReplicationException`; nothing is acknowledged.
- `NullParsedRowRecordIsTerminalTest.unconvertibleRowRecordIsTerminal()` — §3.3 at the `processEveryChangeRecord` seam (inverted from the former NullParsedRecordSkipTest, which asserted the skip).
- `Replication.Snapshot.unparsed_row_halts`, `Replication.Snapshot.unparsed_row_never_committed` — the row half of §3.3 in the offset-commit model.
- `ClickHouseErrorClassifierTest.testIsFatal()`, `ClickHouseErrorClassifierTest.testClassifyFatal()` — the FATAL set that triggers the rethrow.
- `ClickHouseBatchWriterMissingTableTest` — a missing target table fails the batch loudly instead of being skipped.
- `WorkerDeathIsLoudTest.deadWorkerFailsTheNextBatchLoudly()`

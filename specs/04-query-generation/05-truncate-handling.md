# Spec 04.05: TRUNCATE Table Event Handling

## 1. Executive Summary & Purpose
Specifies the translation and execution of upstream MySQL TRUNCATE operations (`op == 't'`) on ClickHouse target tables.

---

## 2. Codebase Mapping on 2.11.0
- **Grouping**: `GroupInsertQueryWithBatchRecords.groupQueryWithRecords` / `updateQueryToRecordsMap` in `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/GroupInsertQueryWithBatchRecords.java` — emits an ordered list of segments; a TRUNCATE event is a segment of its own (spec 04.01 §3.1).
- **Execution**: `PreparedStatementExecutor.addToPreparedStatementBatch` in `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementExecutor.java` — runs the segments in order and issues the truncate for a TRUNCATE segment against its own (target) database.
- **Statement**: `DBMetadata.truncateTable(Connection conn, String databaseName, String tableName)` in `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DBMetadata.java`
- **Formal model**: `formal_specs/lean/Replication/BatchOrder.lean`

The TRUNCATE this path issues is MySQL's own statement, already executed at the source and delivered as a replicated binlog change event (`op == 't'`). It is never issued on the connector's initiative.

---

## 3. Operational Specification

### 3.1 Grouping: a TRUNCATE is a segment of its own
`groupQueryWithRecords` walks the batch in binlog order and emits an **ordered list of segments** (`List<Map<template, records>>`, spec 04.01 §3.1):
- a row event is added to the current segment under its INSERT template;
- a TRUNCATE event (`op == 't'`, no row images) closes the current segment, is emitted as a segment holding only its marker group (key text `TRUNCATE TABLE \`table\``, an empty parameter map, the one record), and a fresh segment starts behind it.

Two TRUNCATEs in one batch are therefore two segments (`Replication.BatchOrder.every_truncate_is_its_own_segment`). Under the previous single `HashMap` they collapsed onto one equal key, and whether the truncate iterated before or after the INSERT template of the same batch depended on the hash of the table name: `[INSERT r1, TRUNCATE, INSERT r2]` either resurrected `r1` or lost `r2` — formally `Replication.BatchOrder.truncate_first_resurrects_rows` and `Replication.BatchOrder.truncate_last_loses_rows`.

### 3.2 Execution: segments in order, against the target database
`addToPreparedStatementBatch` executes the segments strictly in order; within a segment each template is one prepared-statement batch (spec 03.06). A marker group — exactly one record whose operation is TRUNCATE — is executed as follows:
1. `metadata.truncateTable(conn, databaseName, tableName)` issues ``TRUNCATE TABLE `<database>`.`<table>` `` where `databaseName` is the **executor's** database: the writer's resolved target database (spec 03.04, `D_final`). It is never the source database carried by the record (`record.getDatabase()`), which under `clickhouse.database.override.map` is not the table's database at all.
2. `truncateTable` attempts the statement up to `DBMetadata.MAX_RETRIES` (10) times, reconnecting between attempts when pooling is enabled, and **throws `SQLException` (cause: the last refusal) once every attempt has failed** — it never returns normally without having executed the truncate. The executor rethrows that as `RuntimeException("TRUNCATE failed for db.table")`, so the batch fails and is not acknowledged. (Previously the method fell out of its retry loop silently: the batch continued, reported success and was acknowledged while the pre-truncate rows survived in ClickHouse.) `DBMetadata.getPreparedStatement` follows the same rule — after `MAX_RETRIES` failed attempts it throws `SQLException` naming the statement instead of returning `null`.
3. The marker key text is never executed as SQL. A TRUNCATE record found inside an INSERT template's record list is an `IllegalStateException` (impossible by construction; binding it as a row would write garbage).

Because every row grouped before the TRUNCATE sits in an earlier segment whose `executeBatch()` completed before the truncate ran, and every row grouped after it in a later segment, the ClickHouse state after the batch equals the source state after the same events in binlog order: `Replication.BatchOrder.segments_match_source`, `Replication.BatchOrder.segmented_batch_converges`.

There is **no schema-cache invalidation step** on this path: a TRUNCATE does not change the table's shape, and the code does not call `CacheInvalidationManager`.

---

## 4. Invariants Preserved
- **Invariant I1 (Log Sequence Monotonicity) / Relational Parity**: the truncate runs at its binlog position — the replica loses exactly the pre-truncate state and keeps the rows that follow, regardless of the table name's hash.
- **Invariant I9 (Loud Failure)**: a truncate ClickHouse keeps refusing fails the batch; a TRUNCATE inside an INSERT group is refused.
- **Deterministic Routing (spec 03.04)**: the statement names the resolved target database.

---

## 5. Verification Criteria
- `PreparedStatementExecutorTruncateTest.truncateIsAppliedAtItsBinlogPositionForBothHashOrders()` — §3.1/§3.2: for two table names chosen so that the pre-fix `HashMap` iterated the TRUNCATE first for one and last for the other, a recording connection observes `INSERT(r1), FLUSH, TRUNCATE, INSERT(r2), FLUSH`.
- `PreparedStatementExecutorTruncateTest.truncateTargetsTheExecutorDatabaseNotTheSourceDatabase()` — §3.2 step 1: the statement names the executor's target database, not the record's source database.
- `PreparedStatementExecutorTruncateTest.twoTruncatesInOneBatchAreBothApplied()` — §3.1: two TRUNCATEs in one batch both run, each at its position.
- `PreparedStatementExecutorTruncateTest.truncateRefusedByClickHouseFailsTheBatch()` — §3.2 step 2: with a connection that refuses the qualified `TRUNCATE TABLE` statement, `addToPreparedStatementBatch` throws (root cause: the refusal) and never answers `true`.
- `PreparedStatementExecutorTruncateTest.aTruncateSegmentIsNotReRunWhenTheFollowingInsertSegmentFailsThenRetries()` — spec 03.06 §3.5 / FM-04.05-4: a TRUNCATE segment followed by a segment that fails and is retried does not re-execute the TRUNCATE; only the still-unapplied insert is resent.
- `Replication.BatchOrder.segments_match_source`, `Replication.BatchOrder.segmented_batch_converges`, `Replication.BatchOrder.every_truncate_is_its_own_segment`, `Replication.BatchOrder.truncate_last_loses_rows`, `Replication.BatchOrder.truncate_first_resurrects_rows` — the formal model (`lake build`, zero `sorry`).
- `DBMetadataStatementFailureTest.truncateFailureIsRethrownAfterRetries()`, `DBMetadataStatementFailureTest.preparedStatementFailureIsRethrownNotNull()` — `truncateTable` throws after `MAX_RETRIES` refused attempts; `getPreparedStatement` throws instead of returning `null`.
- `TruncateTableIT.testIsDeleted()` — a TRUNCATE on MySQL empties the ClickHouse table.
- `TruncateTableIT.testRowsInsertedAfterTruncateSurvive()` — rows following the TRUNCATE in the same batch are retained.

---

## 6. Failure Modes & Recovery

Recovery posture: a replicated TRUNCATE is applied at its binlog position or the batch fails (§3.2); a refused TRUNCATE is retried with the batch and heals once ClickHouse accepts it, a TRUNCATE of a missing table is terminal. Because the statement is destructive, its blast radius is the whole target table, including rows that did not come from the truncated source table. Procedures P-FIX-TYPE / P-SKIP / P-RESYNC and the retry-vs-stop rule are defined in spec 07.01 §6.

- **FM-04.05-1 ClickHouse refuses the TRUNCATE transiently**
  - **Trigger**: ClickHouse unreachable, restarting, a `ReplicatedMergeTree` table read-only after Keeper loss (`Code: 242`), a socket timeout on a large table.
  - **Behaviour**: `DBMetadata.truncateTable` tries `MAX_RETRIES` (10) times back to back — no delay between attempts, reconnecting when pooling is enabled — then throws `SQLException` with the last refusal as cause; `PreparedStatementExecutor.addToPreparedStatementBatch` rethrows `RuntimeException("Truncation failed for <db>.<t>")`. The runnable classifies by the cause's code: 242, 999, timeouts and codeless connection errors are RETRIABLE/UNKNOWN, so the batch is retained and retried with backoff (500 ms doubling to 30 s) until ClickHouse accepts it. A TRUNCATE that completed server-side while the client timed out is simply repeated on retry (idempotent on the then-empty table; the rows behind it in the batch are in later segments and not yet written).
  - **Detection**: 10 × ERROR `*** Error: Truncate table statement error, retry attempt (<n>/10) failed` with stack traces in well under a second, then ERROR `ClickHouseBatchRunnable exception - Task(<id>)` (`TRUNCATE TABLE <db>.<t> failed on all 10 attempts; the replicated TRUNCATE was NOT applied and this batch must not be acknowledged.`) and WARN `Retriable ClickHouse error (Code: <n>, Category: <c>)` every ≤ 30 s; no metric (the TRUNCATE branch does not call `Metrics.updateErrorCounters`); no exit.
  - **Blast radius**: the worker's tables stop and offsets freeze until ClickHouse accepts; the pre-TRUNCATE segments of the batch are re-inserted on each retry (they are truncated again by the retry, so the end state is correct); nothing lost.
  - **Recovery**: self-heals when ClickHouse accepts the statement (Keeper back, server up); no operator step.
  - **RTO**: outage + ≤ 30 s backoff + re-apply of the batch; unmeasured.
  - **Test**: `DBMetadataStatementFailureTest.truncateFailureIsRethrownAfterRetries()`, `PreparedStatementExecutorTruncateTest.truncateRefusedByClickHouseFailsTheBatch()`.

- **FM-04.05-2 TRUNCATE of a table that does not exist on ClickHouse**
  - **Trigger**: the source table was never replicated to ClickHouse (created with `sql_log_bin=0`, filtered, or dropped out of band on ClickHouse) and a TRUNCATE of it arrives.
  - **Behaviour**: every attempt fails with `Code: 60 UNKNOWN_TABLE`; 60 is in `FATAL_ERROR_CODES`, so the worker dies, the engine stops on the next source batch and the process exits 3. The batch is not acknowledged.
  - **Detection**: the 10 ERROR attempt lines, ERROR `FATAL ClickHouse error (Code: 60) -- this batch will never succeed.`, FATAL `Replication is STOPPED: ...`, exit 3 within ≤ 5 s; systemd restarts every 30 s and gives up after 5 starts in 300 s.
  - **Blast radius**: the whole connector stops; nothing lost.
  - **Recovery**: create the table on ClickHouse (`sink-connector-client ddl_translate` of the source `SHOW CREATE TABLE`, or enable `auto.create.tables`) and restart; the TRUNCATE then empties the new table, and `ch-mysql-resync` (spec 11.04) back-fills it if earlier rows of it were never replicated.
  - **RTO**: create + restart ≈ 1–2 min + re-apply of the in-flight transaction (+ resync if the table must be back-filled); unmeasured.
  - **Test**: `ClickHouseErrorClassifierTest.testClassifyWrappedCause()` (a wrapped `Code: 60` is FATAL), `TerminalFailureExitTest.fatalErrorCodeIsNotRetried()`.

- **FM-04.05-3 TRUNCATE of a target table shared by two source databases**
  - **Trigger**: `clickhouse.database.override.map` maps two source databases to the same target database (e.g. `shard1:app,shard2:app`) and both hold a table of the same name; one source truncates its table.
  - **Behaviour**: `Utils.parseSourceToDestinationDatabaseMap` rejects a duplicated source name but not a duplicated destination, so the map is accepted; `DBMetadata.truncateTable` truncates the resolved target table, which also holds the other source's rows.
  - **Detection**: none. DEFECT.
  - **Blast radius**: data loss on the replica for every row of the other source database's table; the loss persists (no later event re-sends those rows).
  - **Recovery**: P-RESYNC the other source database's table (`ch-mysql-resync`, spec 11.04), then remove the N:1 mapping.
  - **RTO**: resync proportional to the table size; unmeasured.
  - **Test**: `DatabaseOverrideMapFanInTest.fanInToOneTargetDatabaseIsRefused()` (disabled, fails on 2.11.0), `DatabaseOverrideMapFanInTest.oneToOneMapIsAccepted()` (the correct half).
  - **DEFECT**: an N:1 database mapping is accepted although a replicated TRUNCATE on one source (and, not verified here, a replicated DROP TABLE) destroys the other source's replica rows.

- **FM-04.05-4 Batch fails after the TRUNCATE was applied**
  - **Trigger**: a later segment of the same batch (rows inserted after the TRUNCATE) fails.
  - **Behaviour**: since spec 03.06 §3.5, the TRUNCATE's own execution marks its record `appliedToClickHouse` the instant it returns; the batch is retained and re-executed from the first segment NOT yet marked applied, so the TRUNCATE is **not** re-executed. The pre-TRUNCATE rows (an earlier segment, already marked applied by their own `executeBatch()`, spec 03.06 §3.2) and the TRUNCATE itself are excluded from the retry's regrouping; only the later, unapplied segment is resent. Readers never see the table re-emptied by a retry: once the TRUNCATE returns, nothing in this path touches the table's existing rows again. History mode applies a bulk-close record (spec 12.03 §3.4 Gap G-12.03-6) instead of a TRUNCATE statement; the bulk-close record is marked the same way and is likewise excluded from a retry (spec 12.03 §7 FM-12.03-1).
  - **Detection**: the later segment's ERROR and the retry WARN; no detection needed for the TRUNCATE itself, since it is not repeated.
  - **Blast radius**: none from the TRUNCATE on retry (it does not run again); the later segment's own failure mode applies to its rows (spec 03.06 §6 FM-03.06-2, FM-03.06-3).
  - **Recovery**: self-heals on the successful retry of the remaining segment; none needed for the TRUNCATE.
  - **RTO**: ≤ 30 s backoff per attempt + the cause of the later failure; unmeasured.
  - **Test**: `PreparedStatementExecutorTruncateTest.truncateIsAppliedAtItsBinlogPositionForBothHashOrders()` pins the order within one execution; `PreparedStatementExecutorTruncateTest.aTruncateSegmentIsNotReRunWhenTheFollowingInsertSegmentFailsThenRetries()` pins that a retry after the later segment fails does not re-execute the TRUNCATE and sends only the still-unapplied insert.

Summary: 4 failure modes, 1 DEFECT, 0 GAP.

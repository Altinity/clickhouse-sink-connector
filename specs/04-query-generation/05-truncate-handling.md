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
- `Replication.BatchOrder.segments_match_source`, `Replication.BatchOrder.segmented_batch_converges`, `Replication.BatchOrder.every_truncate_is_its_own_segment`, `Replication.BatchOrder.truncate_last_loses_rows`, `Replication.BatchOrder.truncate_first_resurrects_rows` — the formal model (`lake build`, zero `sorry`).
- `DBMetadataStatementFailureTest.truncateFailureIsRethrownAfterRetries()`, `DBMetadataStatementFailureTest.preparedStatementFailureIsRethrownNotNull()` — `truncateTable` throws after `MAX_RETRIES` refused attempts; `getPreparedStatement` throws instead of returning `null`.
- `TruncateTableIT.testIsDeleted()` — a TRUNCATE on MySQL empties the ClickHouse table.
- `TruncateTableIT.testRowsInsertedAfterTruncateSurvive()` — rows following the TRUNCATE in the same batch are retained.

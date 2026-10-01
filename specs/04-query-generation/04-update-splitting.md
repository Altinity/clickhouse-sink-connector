# Spec 04.04: UPDATE Event Handling & Before/After Image Processing

## 1. Executive Summary & Purpose
Specifies the handling of MySQL UPDATE operations (`op == 'u'`) across standard replication mode and replication history mode.

---

## 2. Codebase Mapping on 2.11.0
- **Grouping**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/GroupInsertQueryWithBatchRecords.java`
- **Binding**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementExecutor.java` (`executePreparedStatement`) and `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementFieldMapper.java`
- **History mode**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/ReplicationHistoryHandler.java`, enabled by `replication.history.enable` (`ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE`)

---

## 3. Operational Specification

### 3.1 Standard Mode Update Handling
- In standard replication mode, an UPDATE event carries both a `before` struct and an `after` struct.
- The record is grouped **once** (spec 04.01 §3.2): exactly one entry in the
  record list of the template built from its `after` image. The `before`
  image shares that image's schema and is bound through the same parameter
  map by `PreparedStatementExecutor`. It used to be appended twice — once
  per image, both resolving to the same template — so every UPDATE was bound
  and written twice: 2× write amplification on `ReplacingMergeTree`, and on
  `CollapsingMergeTree` two `+1` rows and no `-1` row.
- **ReplacingMergeTree**: if the UPDATE does not relocate the sorting key, the `after` image is processed as a standard insert with `is_deleted = 0` and the record's `_version`. If the UPDATE alters any sorting key column (`updateRelocatesSortingKey`, spec 05.01), the two-phase tombstone protocol (specs 05.02 and 05.03) binds an old-key tombstone before the new-key insert in the same batch.
- **CollapsingMergeTree**: the `before` image is bound with `sign = -1` and
  staged with `ps.addBatch()`, then the `after` image is bound with
  `sign = +1` and staged — two rows per UPDATE, cancel row first (spec 05.04
  §3.1).

### 3.2 Replication History Mode (`binlog_history`)
When `replication.history.enable = true`:
- The connector maintains SCD Type 2 bitemporal history in the configured history tables.
- Both `before` and `after` images are preserved with audit columns:
  - `_operation = 'U'` (the single-letter code of `CDC_OPERATION.getOperation()`)
  - `_valid_from`, `_valid_to`
  - `is_deleted`
- The statement is one `INSERT ... SELECT ... UNION ALL ...` with **two** `SELECT`s — close the visible open row at the **before-image** key (`_valid_to = ts`), then insert the after image at `(key, sentinel)` — and **three** when the UPDATE changes a primary-key column: the third is a delete marker at the old key's open sorting key. Every row of one event carries the record's **one** standard version (no `V+1`, no separate history version). The staged INSERTs of the batch are flushed before the statement runs, as before a DELETE. The deleted copy of the before image that the previous revision re-inserted no longer exists. The statement text, the DELETE and TRUNCATE / DROP TABLE counterparts, the theorems and the resolved gaps are specified in **Spec 12.03**; the table shape in **Spec 12.02**; the mode matrix in **Spec 12.01**.

---

## 4. Invariants Preserved
- **Invariant I3 (ReplacingMergeTree Convergence)**: Latest update images strictly supersede earlier versions for the same primary key.

---

## 5. Verification Criteria
- `GroupInsertQueryHistoryMultiRowTest.standardModeGroupsEachUpdateOnce()`, `GroupInsertQueryHistoryMultiRowTest.standardModeGroupsOneUpdateUnderOneTemplateOnce()` — §3.1: an UPDATE contributes exactly one entry to its template's record list (20 UPDATEs → 20 grouped records, one UPDATE → one template with a list of size 1).
- `GroupInsertQueryHistoryMultiRowTest.historyModeStillEmitsOneRowPerUpdate()`, `GroupInsertQueryHistoryMultiRowTest.recordsAfterTheFirstUpdateSurviveInHistoryMode()`.
- `PreparedStatementExecutorCollapsingSignTest.testUpdateStagesCancelRowThenLiveRow()` — §3.1 CollapsingMergeTree: the sign values staged by `addBatch()` for one UPDATE are `[-1, +1]`, the `-1` row carrying the before image.
- `ReplicationHistoryHandlerTest` — history-column population; `ReplicationHistoryHandlerTest.compositePrimaryKeyClosesOnlyTheMatchingRow()` — the previous history row is closed by every primary-key column, not only the first (spec 02.01 §3.5 a).
- `BinLogHistoryIT` — end to end history mode.

---

## 6. Failure Modes & Recovery

Recovery posture: an UPDATE is one grouped record whose images are bound through one template; a missing image is refused rather than written from the wrong image, and on `ReplacingMergeTree` a replayed UPDATE converges by version. The replay of an UPDATE is not idempotent on `CollapsingMergeTree` and in replication-history mode. Procedures P-FIX-TYPE / P-SKIP / P-RESYNC and the retry-vs-stop rule are defined in spec 07.01 §6.

- **FM-04.04-1 UPDATE without its after image**
  - **Trigger**: an UPDATE event whose `after` image is missing — a malformed Kafka-mode event, or a source whose `binlog_row_image` changed after the start-time check (spec 10.04 §3.6).
  - **Behaviour**: `GroupInsertQueryWithBatchRecords.updateQueryToRecordsMap` throws `IllegalStateException` (`... carries no after image, so its row cannot be built ...`) instead of writing the before image as a live row (§3.1, spec 04.01 §3.3); UNKNOWN, retried forever — spec 04.01 §6 FM-04.01-1.
  - **Detection**: ERROR `ClickHouseBatchRunnable exception - Task(<id>)` with that message naming topic, partition and offset; WARN `Retriable ... Category: UNKNOWN` every ≤ 30 s; no metric, no exit.
  - **Blast radius**: the worker's tables stop, offsets freeze, the next DDL drain waits forever; nothing written with stale values.
  - **Recovery**: restore `binlog_row_image=FULL`; P-SKIP the transaction and P-RESYNC its tables.
  - **RTO**: unbounded until an operator acts; then P-SKIP + resync; unmeasured.
  - **Test**: `GroupInsertQueryWithBatchRecordsTest.updateWithoutAfterImageFailsLoudly()`; classification: `PoisonValueClassificationTest.groupingRefusalIsFatal()` (disabled, fails on 2.11.0).
  - **DEFECT**: as FM-04.01-1 — retried forever instead of stopping the engine.

- **FM-04.04-2 UPDATE replayed on a non-idempotent engine**
  - **Trigger**: a batch containing UPDATEs is written partly and retried (spec 04.01 §6 FM-04.01-3), or written and redelivered after a crash before its offset was committed (spec 09.01).
  - **Behaviour**: `ReplacingMergeTree`: the replayed after image (and relocation tombstone) carry the same `_version`, so the replica converges on merge and under `FINAL`. `CollapsingMergeTree`: `PreparedStatementExecutor.executePreparedStatement` stages a second `-1`/`+1` pair per replayed UPDATE (§3.1), and two cancel rows for one live row do not collapse to the source state (spec 05.04). History mode: the SCD2 `INSERT ... SELECT` is executed again (spec 12.03).
  - **Detection**: none for the duplication (the batch reports success on the retry). DEFECT.
  - **Blast radius**: `CollapsingMergeTree` / history tables: wrong row counts or duplicated history for the replayed keys; `ReplacingMergeTree`: transient duplicates in non-`FINAL` reads only.
  - **Recovery**: P-RESYNC the affected `CollapsingMergeTree` / history tables (value-reconciled, spec 11.04).
  - **RTO**: resync proportional to the table size; unmeasured.
  - **Test**: `PreparedStatementExecutorCollapsingSignTest.testUpdateStagesCancelRowThenLiveRow()` pins the `[-1, +1]` staging of one UPDATE; GAP: a replay test asserting the collapsed state after the same batch is written twice.
  - **DEFECT**: at-least-once delivery (spec 02.04) is only safe on `ReplacingMergeTree`; the other engines this spec supports diverge on replay with no signal.

Summary: 2 failure modes, 2 DEFECT, 1 GAP.

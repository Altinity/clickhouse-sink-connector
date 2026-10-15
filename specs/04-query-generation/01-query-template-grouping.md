# Spec 04.01: Query Template Grouping & Cache Keying

## 1. Executive Summary & Purpose
Specifies how incoming records within a batch are partitioned into buckets sharing an identical parameterized INSERT statement, allowing JDBC prepared statement reuse.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/GroupInsertQueryWithBatchRecords.java`
- **Methods**: `public void groupQueryWithRecords(...)`, `public void updateQueryToRecordsMap(...)`, `private Map<String, String> refreshIfRecordHasUnknownColumn(...)` (spec 08.03); constructed as `GroupInsertQueryWithBatchRecords(versionColumn, signColumn, deleteColumn)` with the writer's resolved engine columns (spec 04.02 §3.1)
- **Executor entry point**: `PreparedStatementExecutor.addToPreparedStatementBatch(...)` in `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementExecutor.java` (spec 03.06)
- **Query construction**: `QueryFormatter.getInsertQueryUsingInputFunction(...)` in `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/QueryFormatter.java` (spec 04.02)

---

## 3. Operational Specification

### 3.1 Template Key Generation
Because schema evolution or sparse CDC records can produce differing column subsets within a single batch, records cannot all be bound to a single query.
- For each record, `QueryFormatter.getInsertQueryUsingInputFunction` computes the target column list from the record's fields and the ClickHouse column map and returns a `MutablePair<String, Map<String, Integer>>`: the SQL text and the column-name to 1-based parameter index map.
- The SQL text has the shape
  ```sql
  INSERT INTO db.table(col1, col2, ..., _version, is_deleted) VALUES (?, ?, ..., ?, ?)
  ```
- The batch is grouped into an **ordered list of segments**, `List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> querySegments` (there is no `QueryTemplate` class; the pair is the key). Within a segment the templates may execute in any order; a replicated TRUNCATE event closes the current segment, forms a segment of its own, and a new segment starts behind it, so the executor applies it at its binlog position (spec 04.05 §3.1). A batch without a TRUNCATE is exactly one segment.
- All records in a bucket share a single `PreparedStatement`, minimizing SQL parsing overhead on ClickHouse.

### 3.2 One record, one entry
Every record contributes exactly one entry to exactly one bucket. In
particular an UPDATE (which carries a `before` and an `after` image) is
grouped once, under the template built from its `after` image; the executor
binds both images through that template's parameter map (spec 04.04 §3.1).
Appending the record once per image — the previous behaviour — put the same
record twice in the same list, so it was bound and written twice.

### 3.3 No record is silently dropped
`groupQueryWithRecords` and `updateQueryToRecordsMap` return `void`: a record
is either grouped or the batch fails. There is no `false` return and no
per-record skip, because a skipped record is written nowhere while the
batch's offset still advances past it (Invariant I9). Concretely,
`updateQueryToRecordsMap` throws `IllegalStateException` when:
- the record carries no image for the section its operation binds — a DELETE
  with no `before` image, or an INSERT / snapshot read / UPDATE with no
  `after` image. (Previously the `return false` guard for this case was
  unreachable: the null template was dereferenced first, so the worker saw an
  opaque `NullPointerException` and retried the batch forever with no hint of
  which record or image was missing. An UPDATE lacking its after image was
  worse — it was grouped by its before image alone and written as a LIVE row
  holding the pre-update values.)
- no ClickHouse column metadata is available (`columnNameToDataTypeMap` is
  `null` or empty), which previously surfaced as a `NullPointerException`
  from inside the formatter;
- the formatter returns no template or no parameter map (the former
  `return false`, which would have skipped the record).

A record whose CDC state is not recognised is likewise an exception, never a
"RECORD DROPPED" log line. Because nothing returns `false`,
`groupQueryWithRecords` no longer reports the outcome of the LAST record
only: every record's outcome is the batch's outcome.

`PreparedStatementExecutor.addToPreparedStatementBatch` refuses an empty
query map with `IllegalStateException` rather than returning `false`. A
`false` there made the worker keep the batch and retry it on every tick
(spec 03.03 §3.1, backoff of spec 10.02) although it could never produce a
statement; a batch that groups into nothing is a defect to surface, not a
transient to wait out. The same holds inside the per-record loop: a record
whose state binds neither image is refused, not staged with whatever
parameters the previous row left behind.

---

## 4. Invariants Preserved
- **Prepared Statement Parameter Parity**: parameter placeholders exactly match the column set of every record in the bucket.

---

## 5. Verification Criteria
- `DbWriterTest.testGroupRecords()` — records with differing column sets group into distinct templates.
- `GroupInsertQueryHistoryMultiRowTest.standardModeGroupsEachUpdateOnce()`, `GroupInsertQueryHistoryMultiRowTest.standardModeGroupsOneUpdateUnderOneTemplateOnce()`, `GroupInsertQueryHistoryMultiRowTest.allRowsOfAMultiRowUpdateAreGroupedInHistoryMode()` — §3.2: one entry per record in both modes.
- `GroupInsertQueryWithBatchRecordsTest.deleteWithoutBeforeImageFailsLoudly()`, `GroupInsertQueryWithBatchRecordsTest.updateWithoutAfterImageFailsLoudly()`, `GroupInsertQueryWithBatchRecordsTest.missingColumnMapFailsLoudly()` — §3.3: a record that cannot be grouped fails the batch with `IllegalStateException` and nothing is grouped.
- `PreparedStatementExecutorNoSilentDropTest.emptyQueryMapIsRefusedNotRetried()` — §3.3: an empty query map is refused with `IllegalStateException`, never answered with `false`.

---

## 6. Failure Modes & Recovery

Recovery posture: grouping never drops a record — a record that cannot be grouped fails the whole batch (§3.3) — and the batch is retained and retried by its worker. What 2.11.0 does not do is tell a deterministic refusal from a transient one: every grouping refusal is an `IllegalStateException` without a ClickHouse code, so it is retried forever. Execution of the grouped segments is not atomic: a batch that fails part-way is re-executed from its first segment. Procedures P-FIX-TYPE / P-SKIP / P-RESYNC and the retry-vs-stop rule are defined in spec 07.01 §6.

- **FM-04.01-1 A record that can never be grouped**
  - **Trigger**: a record lacking the image its operation binds (a DELETE without `before`, an INSERT/UPDATE without `after` — e.g. `binlog_row_image` changed on the source after the start-time check of spec 10.04 §3.6, or a malformed Kafka-mode event), a record with no recognised CDC state, or a batch that groups into nothing.
  - **Behaviour**: `GroupInsertQueryWithBatchRecords.updateQueryToRecordsMap` / `groupQueryWithRecords` throw `IllegalStateException` naming the operation, topic, partition and offset (§3.3); `PreparedStatementExecutor.addToPreparedStatementBatch` throws the same for an empty segment list. `ClickHouseErrorClassifier.classify` returns UNKNOWN, so `ClickHouseBatchRunnable.run` retries the same batch forever.
  - **Detection**: ERROR `ClickHouseBatchRunnable exception - Task(<id>)` with e.g. `Record(operation=d, topic=<t>, partition=<p>, offset=<o>) on table <t> carries no before image, so its row cannot be built. A DELETE event must carry the before image (MySQL binlog_row_image=FULL). Refusing to drop the record (Spec 04.01 section 3.3).` and WARN `Retriable ClickHouse error (Code: -1, Category: UNKNOWN) -- the same batch will be retried in <ms> ms` every ≤ 30 s; no metric (the failure precedes the executor, so `clickhouse.sink.topics.error.records` does not move); no exit.
  - **Blast radius**: every table hashed to that worker stops; offsets freeze behind the batch; the next DDL drain waits on it forever (spec 06.01). Nothing is written for the batch; no loss.
  - **Recovery**: no retry can succeed (the event is in the binlog as it is). Restore `binlog_row_image=FULL` on the source, then P-SKIP the offending transaction and P-RESYNC the tables it touched.
  - **RTO**: unbounded until an operator acts; then P-SKIP + resync proportional to the tables' size; unmeasured.
  - **Test**: `GroupInsertQueryWithBatchRecordsTest.deleteWithoutBeforeImageFailsLoudly()`, `GroupInsertQueryWithBatchRecordsTest.updateWithoutAfterImageFailsLoudly()`, `PreparedStatementExecutorNoSilentDropTest.emptyQueryMapIsRefusedNotRetried()` (the refusals); `PoisonValueClassificationTest.groupingRefusalIsFatal()` (disabled, fails on 2.11.0: UNKNOWN, so the refusal IS retried).
  - **DEFECT**: the refusal is loud in the log but never terminal and not counted; the worker retries a record that can never be grouped forever, stalling its tables and every later DDL.

- **FM-04.01-2 No ClickHouse column metadata for the table**
  - **Trigger**: the target table does not exist on ClickHouse (not created, dropped out of band, `auto.create.tables=false`), or its metadata cannot be read (ClickHouse unreachable, privileges revoked).
  - **Behaviour**: `ClickHouseBatchRunnable.processBatchRecords` finds `wasTableMetaDataRetrieved()` false, re-reads once, and returns `false`; the runnable keeps the batch and retries it with backoff (500 ms doubling to 30 s). If the map is empty at grouping time, `updateQueryToRecordsMap` throws `IllegalStateException` instead (same retry). Self-heals as soon as the metadata is readable.
  - **Detection**: ERROR `*** TABLE METADATA not retrieved for Database(<db>), table(<t>), retrying on next attempt` and WARN `Batch not written to ClickHouse; retrying the same batch in <ms> ms (consecutive failures: <k>)` every ≤ 30 s; no exit.
  - **Blast radius**: the worker's tables stop and offsets freeze; nothing lost.
  - **Recovery**: create the table (enable `auto.create.tables`, or translate the source DDL with `sink-connector-client ddl_translate` and run it) or restore connectivity/grants; the next retry picks it up — no restart needed.
  - **RTO**: fix + ≤ 30 s backoff + re-apply of the in-flight batch; unmeasured.
  - **Test**: `GroupInsertQueryWithBatchRecordsTest.missingColumnMapFailsLoudly()`.

- **FM-04.01-3 Batch fails after some segments were written (partial application)**
  - **Trigger**: a batch groups into several templates or segments (pre/post-ALTER records, a TRUNCATE, several chunks of `buffer.max.records` / `buffer.max.bytes`); a later statement fails (ClickHouse timeout, `TOO_MANY_PARTS`, a poison value in a later chunk) after earlier ones committed.
  - **Behaviour**: `PreparedStatementExecutor.addToPreparedStatementBatch` executes segments, templates and chunks one `executeBatch()` at a time with no transaction; on failure the whole batch is retained and re-executed from its first segment, so the rows already committed are inserted again with the same `_version`.
  - **Detection**: the failing statement's ERROR `******* ERROR inserting Batch Database(<db>), Table(<t>)` and the retry WARN; the duplication itself: none.
  - **Blast radius**: `ReplacingMergeTree` tables converge (same sorting key and version dedup on merge and under `FINAL`; non-`FINAL` reads see the duplicate until a merge). `CollapsingMergeTree` tables get a second `-1`/`+1` pair per replayed UPDATE, which does not collapse correctly (spec 05.04); replication-history (SCD2) statements are re-executed (spec 12.03).
  - **Recovery**: none needed for `ReplacingMergeTree`; for `CollapsingMergeTree` and history tables, P-RESYNC the tables written by the partially applied batch.
  - **RTO**: `ReplacingMergeTree`: the retry itself (≤ 30 s); others: resync; unmeasured.
  - **Test**: GAP: a test that re-executes a batch after a mid-batch `executeBatch()` failure and asserts the resulting rows are idempotent for `ReplacingMergeTree` and flags the non-idempotent engines.
  - **DEFECT**: replay after a partial write is not idempotent for `CollapsingMergeTree` and history tables, and nothing records which statements of a failed batch already committed.

Summary: 3 failure modes, 2 DEFECT, 1 GAP.

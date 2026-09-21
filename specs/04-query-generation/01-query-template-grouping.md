# Spec 04.01: Query Template Grouping & Cache Keying

## 1. Executive Summary & Purpose
Specifies how incoming records within a batch are partitioned into buckets sharing an identical parameterized INSERT statement, allowing JDBC prepared statement reuse.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/GroupInsertQueryWithBatchRecords.java`
- **Methods**: `public void groupQueryWithRecords(...)`, `public void updateQueryToRecordsMap(...)`, `private Map<String, String> refreshIfRecordHasUnknownColumn(...)` (spec 08.03)
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

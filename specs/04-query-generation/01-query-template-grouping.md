# Spec 04.01: Query Template Grouping & Cache Keying

## 1. Executive Summary & Purpose
Specifies how incoming records within a batch are partitioned into buckets sharing an identical parameterized INSERT statement, allowing JDBC prepared statement reuse.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/GroupInsertQueryWithBatchRecords.java`
- **Methods**: `public boolean groupQueryWithRecords(...)`, `public boolean updateQueryToRecordsMap(...)`, `private Map<String, String> refreshIfRecordHasUnknownColumn(...)` (spec 08.03)
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
- The batch is grouped into `Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> queryToRecordsMap` (there is no `QueryTemplate` class; the pair is the key).
- All records in a bucket share a single `PreparedStatement`, minimizing SQL parsing overhead on ClickHouse.

### 3.2 One record, one entry
Every record contributes exactly one entry to exactly one bucket. In
particular an UPDATE (which carries a `before` and an `after` image) is
grouped once, under the template built from its `after` image; the executor
binds both images through that template's parameter map (spec 04.04 §3.1).
Appending the record once per image — the previous behaviour — put the same
record twice in the same list, so it was bound and written twice.

---

## 4. Invariants Preserved
- **Prepared Statement Parameter Parity**: parameter placeholders exactly match the column set of every record in the bucket.

---

## 5. Verification Criteria
- `DbWriterTest.testGroupRecords()` — records with differing column sets group into distinct templates.
- `GroupInsertQueryHistoryMultiRowTest.standardModeGroupsEachUpdateOnce()`, `GroupInsertQueryHistoryMultiRowTest.standardModeGroupsOneUpdateUnderOneTemplateOnce()`, `GroupInsertQueryHistoryMultiRowTest.allRowsOfAMultiRowUpdateAreGroupedInHistoryMode()` — §3.2: one entry per record in both modes.

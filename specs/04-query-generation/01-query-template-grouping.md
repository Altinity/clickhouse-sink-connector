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

---

## 4. Invariants Preserved
- **Prepared Statement Parameter Parity**: parameter placeholders exactly match the column set of every record in the bucket.

---

## 5. Verification Criteria
- `DbWriterTest.testGroupRecords()` — records with differing column sets group into distinct templates.
- `GroupInsertQueryHistoryMultiRowTest.standardModeStillSplitsUpdateIntoBeforeAndAfter()`, `GroupInsertQueryHistoryMultiRowTest.allRowsOfAMultiRowUpdateAreGroupedInHistoryMode()` — grouping across update images in both modes.

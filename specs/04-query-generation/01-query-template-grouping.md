# Spec 04.01: Query Template Grouping & Cache Keying

## 1. Executive Summary & Purpose
Specifies how incoming records within a batch are partitioned into buckets sharing identical SQL insert templates (`QueryTemplate`), allowing efficient JDBC prepared statement reuse.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/operations.GroupInsertQueryWithBatchRecords.java`
- **Method**: `groupQueryWithRecords()`

---

## 3. Operational Specification

### 3.1 Template Key Generation
Because schema evolution or sparse CDC records can produce differing column subsets within a single batch, records cannot all be bound to a single query.
- For each record, `QueryFormatter.createColumns()` calculates the sorted list of target columns.
- A `QueryTemplate` is constructed:
  ```sql
  INSERT INTO db.table (col1, col2, ..., _version, is_deleted) VALUES (?, ?, ..., ?, ?)
  ```
- The batch is grouped into a map: `Map<QueryTemplate, List<ClickHouseStruct>> queryToRecordsMap`.
- All records in a bucket share a single `PreparedStatement`, minimizing SQL parsing overhead on ClickHouse.

---

## 4. Invariants Preserved
- **Prepared Statement Parameter Parity**: Guarantees that parameter placeholders exactly match the schema fields of every record in the bucket.

---

## 5. Verification Criteria
- `GroupInsertQueryWithBatchRecordsTest.testGroupQueryWithRecords()`

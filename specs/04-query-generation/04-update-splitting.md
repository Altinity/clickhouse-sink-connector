# Spec 04.04: UPDATE Event Handling & Before/After Image Processing

## 1. Executive Summary & Purpose
Specifies the handling of MySQL UPDATE operations (`op == 'u'`) across standard replication mode and replication history mode.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/operations/GroupInsertQueryWithBatchRecords.java`

---

## 3. Operational Specification

### 3.1 Standard Mode Update Handling
- In standard replication mode, an UPDATE event carries both a `before` struct and an `after` struct.
- If the UPDATE does not relocate the sorting key, the `after` image is processed as a standard insert with `is_deleted = 0` and the new monotonic `_version`.
- If the UPDATE alters any sorting key column, the two-phase tombstone protocol (Spec 004) splits the event into an old-key tombstone and a new-key insert.

### 3.2 Replication History Mode (`binlog_history`)
When `replication.history.enable = true`:
- The connector maintains SCD Type 2 bitemporal history in `binlog_history` tables.
- Both `before` and `after` images are preserved with audit columns:
  - `_operation = 'u'`
  - `_valid_from`, `_valid_to`
  - `is_deleted`

---

## 4. Invariants Preserved
- **Invariant I3 (ReplacingMergeTree Convergence)**: Latest update images strictly supersede earlier versions for the same primary key.

---

## 5. Verification Criteria
- `GroupInsertQueryWithBatchRecordsTest.testUpdateHandling()`
- `BinLogHistoryIT`

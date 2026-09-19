# Spec 04.05: TRUNCATE Table Event Handling

## 1. Executive Summary & Purpose
Specifies the translation and execution of upstream MySQL TRUNCATE operations (`op == 't'`) on ClickHouse target tables.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/operations/GroupInsertQueryWithBatchRecords.java`

---

## 3. Operational Specification

When a TRUNCATE event is received:
1. Preceding DML records for the table are flushed and committed.
2. The connector constructs a ClickHouse DDL command:
   ```sql
   TRUNCATE TABLE `db`.`table`
   ```
3. The command is executed via JDBC connection.
4. Schema caches for the table are invalidated to ensure fresh metadata state.

---

## 4. Invariants Preserved
- **Relational Parity**: Truncating the source MySQL table empties the ClickHouse replica table completely.

---

## 5. Verification Criteria
- `TruncateTableIT`: Verifies that `TRUNCATE TABLE` on MySQL results in 0 rows in ClickHouse.

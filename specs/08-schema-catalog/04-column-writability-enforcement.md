# Spec 08.04: Column Writability Enforcement & MATERIALIZED to DEFAULT Alteration

## 1. Executive Summary & Purpose
Specifies the automatic conversion of ClickHouse `MATERIALIZED` columns to `DEFAULT` columns when the upstream MySQL source table defines values for them.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/operations/GroupInsertQueryWithBatchRecords.java`
- **Method**: `boolean enforceSourceColumnIsWritable(...)`

---

## 3. Operational Specification

### 3.1 Detection of Shadowed Columns
1. Record carries column $C$, but ClickHouse metadata reports `default_kind == "MATERIALIZED"`.
2. ClickHouse rejects explicit writes to `MATERIALIZED` columns.
3. Under the Prime Directive, MySQL data is authoritative and must be stored.

### 3.2 Automated Schema Remediation
`enforceSourceColumnIsWritable()` constructs and executes:
```sql
ALTER TABLE `db`.`table` MODIFY COLUMN `col` type DEFAULT (default_expression)
```
- Modifies column kind in ClickHouse metadata without rewriting existing data parts.
- Invalidates local schema cache: `CacheInvalidationManager.getInstance().invalidateTable(tableKey)`.
- Subsequent `INSERT` statements now successfully bind MySQL values directly into the column.

---

## 4. Invariants Preserved
- **Invariant I6 (Column Authority & Shadowing Prohibition)**: ClickHouse computed expressions never discard or shadow values supplied by MySQL.

---

## 5. Verification Criteria
- `GroupInsertQueryWithBatchRecordsTest.testEnforceSourceColumnIsWritable()`
- `MaterializedColumnConversionIT`

# Spec 08.04: Column Writability Enforcement & MATERIALIZED to DEFAULT Alteration

## 1. Executive Summary & Purpose
Specifies what the writer does when the incoming record carries a source column
that the ClickHouse table cannot currently store: a `MATERIALIZED` column is
converted to `DEFAULT`; a column that is simply **absent** is added (schema
evolution on) or fails the batch loudly (schema evolution off). Only an `ALIAS`
column is ever ignored.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/GroupInsertQueryWithBatchRecords.java`
- **Methods**: `refreshIfRecordHasUnknownColumn(...)`, `boolean enforceSourceColumnIsWritable(...)`
- **Evolution path reused**: `db/operations/ClickHouseAlterTable.alterTable(...)` (`ALTER TABLE ... ADD COLUMN`)
- **Failure type**: `db/batch/MissingTargetColumnException` (unchecked)
- **Config knob**: `schema.evolution` (`ENABLE_SCHEMA_EVOLUTION`, default `false`)

---

## 3. Operational Specification

### 3.1 Classification of a column the record carries but the writable map lacks
After a fresh metadata read still does not produce column $C$, its
`system.columns.default_kind` decides:

| `default_kind` | Meaning | Action |
|---|---|---|
| `ALIAS` | not stored; nothing can diverge | ignore; `markColumnProvenAbsent` so the probe is not repeated per record |
| `MATERIALIZED` | stored, ClickHouse-computed; shadows the source value | §3.2 convert to `DEFAULT`, re-read, bind the source value |
| `null` (no `system.columns` row: the column does not exist in ClickHouse) or `""` (a stored column the writable map still lacks) | the replica is incomplete | §3.3 |
| `null` because the kind could not be read at all | unknown | §3.3 as well: with evolution on the `ADD COLUMN` is attempted; otherwise the batch fails and the retry re-probes. Never proven-absent. |

A non-`ALIAS` miss is **never** recorded as proven-absent: doing so made the
writer drop the column's value on every subsequent record after a single WARN,
i.e. it treated a missing replica column like an `ALIAS` forever.

### 3.2 MATERIALIZED: Automated Schema Remediation
`enforceSourceColumnIsWritable()` constructs and executes:
```sql
ALTER TABLE `db`.`table` MODIFY COLUMN `col` type DEFAULT (default_expression)
```
- Modifies column kind in ClickHouse metadata without rewriting existing data parts.
- Invalidates local schema cache: `CacheInvalidationManager.getInstance().invalidateTable(tableKey)`.
- Subsequent `INSERT` statements now successfully bind MySQL values directly into the column.

### 3.3 Absent column: add it, or fail the batch
The prime directive: "If a column exists in MySQL but not usefully in
ClickHouse, the ClickHouse side is wrong or incomplete. Fix the ClickHouse
side; never a log line only."

1. **`schema.evolution=true`**: run `ClickHouseAlterTable.alterTable()` for the
   record's schema (it issues `ALTER TABLE ... ADD COLUMN` for every field the
   writable map lacks, typed by `getColumnNameToCHDataTypeMapping`), then
   re-read the column map. If the re-read now contains $C$, bump the table
   version (`invalidateTable`) and build the INSERT from the fresh map so
   **this** batch binds the value. If it still does not, fall through to 2.
2. **Otherwise**: throw `MissingTargetColumnException` naming the database,
   table, column and the `schema.evolution` knob. The batch fails and is
   retried/surfaced; nothing is written with the column silently omitted.
   Rows are never dropped and never written incomplete.

---

## 4. Invariants Preserved
- **Invariant I6 (Column Authority & Shadowing Prohibition)**: ClickHouse computed expressions never discard or shadow values supplied by MySQL; a missing replica column is added, not ignored.
- **Invariant I9 (Loud Failure)**: when the replica cannot be corrected automatically the batch fails; it does not succeed with a dropped column.

---

## 5. Verification Criteria
- `GroupInsertQueryWithBatchRecordsTest.missingPlainColumnFailsBatch()` —
  cached map lacks `note`, `default_kind` reads `""`, `schema.evolution` off:
  `MissingTargetColumnException` (pre-fix code omits the column and marks it
  proven-absent).
- `GroupInsertQueryWithBatchRecordsTest.missingPlainColumnIsAddedWhenSchemaEvolutionEnabled()`
  — same setup with `schema.evolution=true`: an `ALTER TABLE ... ADD COLUMN
  \`note\`` is issued and the INSERT built for the batch contains `note`.
- `GroupInsertQueryWithBatchRecordsTest.aliasColumnIsStillIgnoredAndProvenAbsent()`
  — `default_kind == ALIAS` keeps the pre-existing behaviour.
- `UnwritableColumnReportingTest` — MATERIALIZED → DEFAULT conversion DDL.

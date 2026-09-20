# Spec 08.03: Metadata Query Storm Prevention & Proven-Absent Tracking

## 1. Executive Summary & Purpose
Specifies the proven-absent column tracking mechanism that prevents unbounded `system.columns` queries when incoming records carry fields not present in ClickHouse's writable column map.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/CacheInvalidationManager.java`
- **Methods**:
  - `markColumnProvenAbsent(String tableName, String columnName)`
  - `isColumnProvenAbsent(String tableName, String columnName)`
  - (there is no `clearProvenAbsentColumns`; proofs expire through the version stamp — §3.2 step 5 — and `clearAll()` exists for tests)
- **Caller**: `GroupInsertQueryWithBatchRecords.refreshIfRecordHasUnknownColumn(...)` in `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/GroupInsertQueryWithBatchRecords.java`

---

## 3. Operational Specification

### 3.1 The Query Storm Defect
In high-throughput replication, if an incoming event carries a column that does not exist in the writable column map:
- Without tracking, every record triggers an expensive `system.columns` re-read.
- At thousands of events per second this generates millions of metadata queries per hour, exhausting ClickHouse connections and stalling replication. The only column that legitimately stays absent after a re-read is one ClickHouse owns (`MATERIALIZED` or `ALIAS`), which `getColumnsDataTypesForTable` filters out by design.

### 3.2 Proven-Absent Protocol
1. `refreshIfRecordHasUnknownColumn` encounters a record field missing from the cached column map.
2. It checks `isColumnProvenAbsent(tableKey, column)` (case-insensitive). If true, it returns immediately without issuing SQL.
3. Otherwise it re-reads the metadata once.
4. If the column is still absent from the writable map **and is an `ALIAS`**
   (`default_kind = 'ALIAS'`, i.e. ClickHouse owns it and no re-read will ever
   produce it), it calls `markColumnProvenAbsent(tableKey, column)`, which
   stores the column with the table's **current** `getVersion(tableKey)`. A
   `MATERIALIZED` column is converted instead (and fails the batch if the
   conversion fails), and a column that does not exist at all is added or
   fails the batch (Spec 08.04 §3.1/§3.3) — neither is ever marked
   proven-absent, in any outcome, because that would silently drop its value
   on every later record. `markColumnProvenAbsent` is reserved for `ALIAS`.
5. The proof is honoured only while `getVersion(tableKey)` still equals the stored version: a later `invalidateTable(tableKey)` or `invalidateAll()` changes the version, `isColumnProvenAbsent` then discards the stale proof and returns false, and the column is probed once against the new schema.
6. `null`/empty table or column names are inert for both methods.

---

## 4. Invariants Preserved
- **System Stability**: Protects ClickHouse connection pools from metadata query storms.
- **No stale absence**: a column that becomes writable after a DDL is re-probed exactly once, so the proof can never hide a real column.

---

## 5. Verification Criteria
- `CacheInvalidationProvenAbsentTest.testUnprobedColumnIsNotProvenAbsent()`, `CacheInvalidationProvenAbsentTest.testProvenAbsentColumnIsRemembered()`, `CacheInvalidationProvenAbsentTest.testProvenAbsentIsCaseInsensitive()`, `CacheInvalidationProvenAbsentTest.testProvenAbsentIsScopedToItsTable()`, `CacheInvalidationProvenAbsentTest.testDdlInvalidatesTheProof()`, `CacheInvalidationProvenAbsentTest.testInvalidateAllInvalidatesTheProof()`, `CacheInvalidationProvenAbsentTest.testProofRetakenAfterDdlSticks()`, `CacheInvalidationProvenAbsentTest.testNullAndEmptyInputsAreInert()`.
- Verification: a benchmark asserting zero additional `system.columns` queries under a sustained stream of records with extra fields is not yet covered by an automated test (gap).

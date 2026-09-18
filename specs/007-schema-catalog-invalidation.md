# Specification 007: Schema Catalog, Metadata Caching & Invalidation

## 1. Executive Summary & Purpose

To achieve high-throughput streaming insertion, the connector maintains an in-memory catalog of target ClickHouse tables, including column names, data types, sorting keys, and table engines. Because ClickHouse metadata queries (`system.columns`) can be expensive under heavy load, metadata must be cached aggressively while remaining responsive to DDL alterations and dynamic column evolution.

The schema catalog subsystem coordinates metadata caching, multi-epoch cache invalidation, column writability enforcement (converting `MATERIALIZED` to `DEFAULT`), and protection against metadata query storms.

---

## 2. Codebase Mapping on 2.11.0

- **Primary Classes**:
  - `com.altinity.clickhouse.sink.connector.db.DBMetadata` (`sink-connector/...`)
  - `com.altinity.clickhouse.sink.connector.db.DbWriter` (`sink-connector/...`)
  - `com.altinity.clickhouse.sink.connector.db.operations.CacheInvalidationManager` (`sink-connector/...`)
  - `com.altinity.clickhouse.sink.connector.db.operations.ClickHouseAutoCreateTable` (`sink-connector/...`)
  - `com.altinity.clickhouse.sink.connector.db.operations.GroupInsertQueryWithBatchRecords` (`sink-connector/...`)
- **Key Methods**:
  - `CacheInvalidationManager.invalidateTable(tableKey)`
  - `CacheInvalidationManager.isColumnProvenAbsent(tableKey, column)`
  - `CacheInvalidationManager.markColumnProvenAbsent(tableKey, column)`
  - `GroupInsertQueryWithBatchRecords.refreshIfRecordHasUnknownColumn()`
  - `GroupInsertQueryWithBatchRecords.enforceSourceColumnIsWritable()`

---

## 3. Metadata Cache Architecture & Multi-Epoch Invalidation

```
+-----------------------------------------------------------------------------------+
|                        SCHEMA CACHE & INVALIDATION FLOW                           |
+-----------------------------------------------------------------------------------+
|                                                                                   |
|  Worker Thread requests DbWriter for Table T:                                     |
|        |                                                                          |
|        v                                                                          |
|  Check cached DbWriter version vs. CacheInvalidationManager:                      |
|  cached.version == CacheInvalidationManager.getVersion(tableKey)                  |
|  AND cached.epoch == CacheInvalidationManager.getGlobalEpoch()                    |
|        |                                                                          |
|        +--- [MATCH]: Use cached DbWriter (Zero DB Queries)                        |
|        |                                                                          |
|        +--- [STALE]: Invalidate and Re-read Schema:                               |
|               1. Query `system.columns` for target table                          |
|               2. Populate `columnNameToDataTypeMap`                              |
|               3. Query sorting keys via `DBMetadata.getSortingKeyColumns()`       |
|               4. Detect table engine (ReplacingMergeTree, etc.)                   |
|               5. Clear proven-absent column set for table                         |
|               6. Update cached DbWriter with latest version and epoch             |
|                                                                                   |
+-----------------------------------------------------------------------------------+
```

---

## 4. Protection Against Metadata Query Storms

In earlier releases, incoming records carrying columns that did not exist in ClickHouse triggered an uncached `system.columns` query for every single record, causing millions of queries per hour and exhausting connection pools.

### The Proven-Absent Protocol:
1. When `refreshIfRecordHasUnknownColumn` encounters a field present in the record schema but missing from the local column cache:
2. It first checks `CacheInvalidationManager.isColumnProvenAbsent(tableKey, columnName)`.
3. If the column was already proven absent at the current table version, the database query is skipped immediately.
4. If not proven absent, the connector queries `system.columns`:
   - If the column now exists, the table version is bumped, refreshing all workers.
   - If the column remains absent, `markColumnProvenAbsent(tableKey, columnName)` records the absence for the duration of the current schema version.

---

## 5. Column Writability Enforcement (The MATERIALIZED Rule)

Under the Prime Directive, if MySQL supplies data for a column, that data must be written to ClickHouse.
If ClickHouse defines a column as `MATERIALIZED`:
- ClickHouse rejects explicit INSERTs with an exception.
- `enforceSourceColumnIsWritable()` detects this condition by inspecting `default_kind`:
  $$\text{default\_kind} == \text{"MATERIALIZED"}$$
- The connector automatically emits an ALTER command:
  ```sql
  ALTER TABLE db.table MODIFY COLUMN col type DEFAULT (default_expression)
  ```
- This converts the column from read-only `MATERIALIZED` to writable `DEFAULT`, allowing MySQL source values to be inserted while preserving derivation when source values are absent.

---

## 6. Invariants Preserved

1. **Source Authority Enforcement (Invariant I6)**:
   ClickHouse computed columns never silently shadow or block MySQL data.
2. **Deterministic Refresh across All Workers**:
   Incrementing table versions in `CacheInvalidationManager` forces all scheduled worker threads to discard their stale schema copies on their next execution tick.

---

## 7. Verification Criteria

- **Unit Tests**:
  `CacheInvalidationManagerTest`, `GroupInsertQueryWithBatchRecordsTest`.
- **Integration Tests**:
  `MaterializedColumnConversionIT`, `MetadataCacheStormPreventionIT`.
- **Formal Verification**:
  Corresponds to schema authority and column mapping invariants in `formal_specs/lean/`.

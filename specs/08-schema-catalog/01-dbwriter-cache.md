# Spec 08.01: DbWriter Schema Cache & Metadata Resolution

## 1. Executive Summary & Purpose
Specifies the in-memory caching of ClickHouse table schemas inside `DbWriter`, including column data types, table engines, and sorting key lists.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Sources**:
  - `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DbWriter.java`
  - `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DBMetadata.java`
- **Fields** (`DbWriter`):
  - `private Map<String, String> columnNameToDataTypeMap` (a `LinkedHashMap`)
  - `private List<String> sortingKeyColumns`
  - `private DBMetadata.TABLE_ENGINE engine`
- **Metadata readers** (`DBMetadata`): `getColumnsDataTypesForTable(...)`, `getSortingKeyColumns(Connection conn, String database, String tableName)`, engine detection from `SHOW CREATE TABLE` / `system.tables`

---

## 3. Operational Specification

### 3.1 Metadata Querying
On initialization or cache eviction:
1. Queries ClickHouse `system.columns` for the writable column map (`name`, `type`; ClickHouse-owned `MATERIALIZED` / `ALIAS` columns are filtered out of the writable map by design — spec 08.03).
2. Reads the sorting key via `DBMetadata.getSortingKeyColumns`, which selects `name` from `system.columns` where `is_in_sorting_key = 1` ordered by `position` (it does not parse `system.tables.sorting_key`, whose rendered expression may contain functions).
3. Resolves the table engine (`REPLACING_MERGE_TREE`, `REPLICATED_REPLACING_MERGE_TREE`, `COLLAPSING_MERGE_TREE`, ...).
4. The owning writer thread records the `CacheInvalidationManager.getVersion(tableKey)` it built against and rebuilds the `DbWriter` when that version changes (spec 08.02).

---

## 4. Invariants Preserved
- **Schema Parity**: Ensures query generation uses current ClickHouse physical column types.

---

## 5. Verification Criteria
- `DbWriterTest.testGetColumnsDataTypesForTable()`, `DbWriterTest.testGetEngineTypeUsingSystemTables()` (`testGetEngineType` is `@Disabled`, spec 11.03 §6).
- `DBMetadataTest` — sorting-key extraction via `is_in_sorting_key`.
- `StaleSchemaCacheIT`, `AlterTableDropColumnCacheIT` — the cache is rebuilt after a schema change.

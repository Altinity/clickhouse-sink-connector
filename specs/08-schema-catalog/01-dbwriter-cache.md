# Spec 08.01: DbWriter Schema Cache & Metadata Resolution

## 1. Executive Summary & Purpose
Specifies the in-memory caching of ClickHouse table schemas inside `DbWriter`, including column data types, table engines, and sorting key lists.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Sources**:
  - `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DbWriter.java`
  - `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DBMetadata.java`
- **Fields**:
  - `Map<String, String> columnNameToDataTypeMap`
  - `List<String> sortingKeyColumns`
  - `DBMetadata.TABLE_ENGINE engine`

---

## 3. Operational Specification

### 3.1 Metadata Querying
On initialization or cache eviction:
1. Queries ClickHouse `system.columns` for `name`, `type`, `default_kind`, and `default_expression`.
2. Identifies sorting key columns via `DBMetadata.getSortingKeyColumns()` querying `system.tables.sorting_key`.
3. Resolves table engine (`REPLACING_MERGE_TREE`, `REPLICATED_REPLACING_MERGE_TREE`, or `COLLAPSING_MERGE_TREE`).
4. Associates the cached `DbWriter` with the active version in `CacheInvalidationManager`.

---

## 4. Invariants Preserved
- **Schema Parity**: Ensures query generation uses current ClickHouse physical column types.

---

## 5. Verification Criteria
- `DbWriterTest.testSchemaResolution()`

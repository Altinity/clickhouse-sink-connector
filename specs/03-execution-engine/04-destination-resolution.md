# Spec 03.04: Destination Database & Table Name Resolution

## 1. Executive Summary & Purpose
Specifies the transformation pipeline resolving raw MySQL source database and table names into canonical target ClickHouse database and table identifiers.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Sources**:
  - `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchRunnable.java`
  - `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchWriter.java`
- **Method**: `getDbWriterForTable()`

---

## 3. Transformation Pipeline Specification

Given a source database $D_{\text{src}}$ and table $T_{\text{src}}$:

```
Source Database: D_src
       |
       v
1. Check `clickhouse.database.override.map`:
   If D_src exists in map -> D_mapped = map.get(D_src)
   Else -> D_mapped = D_src
       |
       v
2. Apply `clickhouse.common.database.prefix`:
   If prefix is defined -> D_prefixed = prefix + D_mapped
   Else -> D_prefixed = D_mapped
       |
       v
3. Apply `clickhouse.database.schema.suffix`:
   If suffix is defined -> D_final = format(suffix, D_prefixed)
   Else -> D_final = D_prefixed
       |
       v
4. Target Table:
   T_final = table.name.mapping.getOrDefault(T_src, T_src)
```

### 3.1 Fully Qualified Table Identifier
The canonical key used for cache indexing and metadata tracking is:
$$\text{tableKey} = D_{\text{final}} + "." + T_{\text{final}}$$

### 3.2 Every statement names $D_{\text{final}}$
The `PreparedStatementExecutor` is constructed with the writer's resolved database (`DbWriter.getDatabaseName()` $= D_{\text{final}}$) and every statement it issues names that database. In particular the ``TRUNCATE TABLE `db`.`table` `` issued for a replicated TRUNCATE event (spec 04.05 §3.2) uses the executor's database, never the source database the record carries (`ClickHouseStruct.getDatabase()` $= D_{\text{src}}$): under `clickhouse.database.override.map` the two differ and the source name is not the table's database at all.

---

## 4. Invariants Preserved
- **Deterministic Routing**: Every worker thread resolves identical destination coordinates for the same source record.

---

## 5. Verification Criteria
- `ClickHouseBatchWriterDatabaseResolutionTest`: Validates that overrides, prefixes, and suffixes produce identical outputs in both writers.
- `PreparedStatementExecutorTruncateTest.truncateTargetsTheExecutorDatabaseNotTheSourceDatabase()` — §3.2: the replicated TRUNCATE names the executor's target database.

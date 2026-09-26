# Spec 08.02: Multi-Epoch Cache Invalidation & Version Counters

## 1. Executive Summary & Purpose
Specifies the global coordination singleton (`CacheInvalidationManager`) that invalidates stale metadata across concurrent worker threads using monotonic per-table version counters and a global epoch.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/CacheInvalidationManager.java`
- **Fields**:
  - `private final Map<String, Long> tableVersions` (a `ConcurrentHashMap`)
  - `private final AtomicLong globalEpoch`
  - `private final Map<String, Map<String, Long>> columnsProvenAbsent` (spec 08.03)
- **Methods**: `getInstance()`, `invalidateTable(String tableName)`, `getVersion(String tableName)`, `invalidateAll()`, `clearAll()` (tests), `pendingInvalidations()` (tests)

---

## 3. Operational Specification

### 3.1 Table-Scoped Invalidation (`invalidateTable`)
When a DDL has been applied to table `tableName` (`"database.table"`):
1. `tableVersions.merge(tableName, 1L, Long::sum)` increments the table's version (a table never invalidated has no entry and reads as 0).
2. Nothing else is cleared: proven-absent column proofs (spec 08.03) are stamped with the version they were taken at and are discarded lazily by `isColumnProvenAbsent` when the current version no longer matches.
3. A worker whose cached version differs from `getVersion(tableName)` discards its `DbWriter` and re-reads ClickHouse metadata before its next write.

### 3.2 Version read (`getVersion`)
`getVersion(tableName) = globalEpoch.get() + tableVersions.getOrDefault(tableName, 0L)` (0 for a `null`/empty name). The epoch is folded in so that a global invalidation reaches tables that have no entry of their own.

### 3.3 Global Epoch Invalidation (`invalidateAll`)
When the table affected by a DDL cannot be determined (unparsed statement, unresolvable table name, or an error while working it out):
1. `globalEpoch.incrementAndGet()`.
2. Because every `getVersion` result includes the epoch, every cached writer — including those for tables absent from `tableVersions` — observes a version change and rebuilds, at the cost of one metadata re-read per active table on its next batch.

---

## 4. Invariants Preserved
- **Deterministic Cache Convergence**: after any schema modification every writer re-reads metadata before its next write; a DDL whose target is unknown invalidates everything rather than nothing.

---

## 5. Verification Criteria
- `CacheInvalidationProvenAbsentTest.testDdlInvalidatesTheProof()`, `CacheInvalidationProvenAbsentTest.testInvalidateAllInvalidatesTheProof()`, `CacheInvalidationProvenAbsentTest.testProofRetakenAfterDdlSticks()` — `invalidateTable` / `invalidateAll` move `getVersion`.
- `StaleSchemaCacheIT`, `AlterTableDropColumnCacheIT`, `AlterTableDropColumnDatabaseOverrideCacheIT` — writers rebuild after DDL.
- Verification: a dedicated unit test of the version counter arithmetic (`merge`/epoch fold) independent of proven-absent tracking is not yet covered by an automated test (gap).

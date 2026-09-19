# Spec 08.02: Multi-Epoch Cache Invalidation & Version Counters

## 1. Executive Summary & Purpose
Specifies the global coordination singleton (`CacheInvalidationManager`) that invalidates stale metadata across concurrent worker threads using monotonic version counters and epochs.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/operations/CacheInvalidationManager.java`
- **Fields**:
  - `ConcurrentHashMap<String, Long> tableVersions`
  - `AtomicLong globalEpoch`

---

## 3. Operational Specification

### 3.1 Table-Scoped Invalidation (`invalidateTable`)
When a DDL event or column conversion occurs on table `tableKey`:
1. `tableVersions.compute(tableKey, (k, v) -> v == null ? 1L : v + 1)` increments the table version.
2. Clears proven-absent column tracking for `tableKey`.
3. Worker threads checking `cachedVersion != manager.getVersion(tableKey)` discard their local `DbWriter` and re-read ClickHouse metadata.

### 3.2 Global Epoch Invalidation (`invalidateAll`)
When DDL target resolution is ambiguous (e.g. unparseable SQL):
1. `globalEpoch.incrementAndGet()` increments the global epoch counter.
2. Forces all worker threads across all tables to refresh their metadata caches.

---

## 4. Invariants Preserved
- **Deterministic Cache Convergence**: Stale schema caches are evicted immediately following any schema modification.

---

## 5. Verification Criteria
- `CacheInvalidationManagerTest.testTableAndGlobalInvalidation()`

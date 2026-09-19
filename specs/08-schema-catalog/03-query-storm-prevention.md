# Spec 08.03: Metadata Query Storm Prevention & Proven-Absent Tracking

## 1. Executive Summary & Purpose
Specifies the proven-absent column tracking mechanism that prevents unbounded `system.columns` queries when incoming records carry fields not present in ClickHouse.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/operations/CacheInvalidationManager.java`
- **Methods**:
  - `isColumnProvenAbsent(String tableKey, String column)`
  - `markColumnProvenAbsent(String tableKey, String column)`
  - `clearProvenAbsentColumns(String tableKey)`

---

## 3. Operational Specification

### 3.1 The Query Storm Defect
In high-throughput replication, if an incoming event carries a column that does not exist in ClickHouse:
- Without tracking, every record triggers an expensive `SELECT name, type FROM system.columns` query.
- At 3,000 events/sec, this generates millions of metadata queries per hour, exhausting ClickHouse connections and stalling replication.

### 3.2 Proven-Absent Protocol
1. When `refreshIfRecordHasUnknownColumn` encounters a missing column:
2. Checks `isColumnProvenAbsent(tableKey, column)`. If true, returns immediately without issuing SQL.
3. If not proven absent, issues a single `system.columns` check.
4. If the column is still absent in ClickHouse, calls `markColumnProvenAbsent(tableKey, column)`.
5. The absence remains cached until `invalidateTable(tableKey)` clears the set upon a future DDL event.

---

## 4. Invariants Preserved
- **System Stability**: Protects ClickHouse connection pools from metadata query storms.

---

## 5. Verification Criteria
- `CacheInvalidationManagerTest.testProvenAbsentTracking()`
- Benchmark verifying zero additional `system.columns` queries when inserting records with extra fields.

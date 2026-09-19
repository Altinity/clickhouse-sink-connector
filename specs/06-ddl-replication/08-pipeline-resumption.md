# Spec 06.08: DDL Execution, Cache Invalidation & Pipeline Resumption

## 1. Executive Summary & Purpose
Specifies the final phase of DDL replication: executing the translated DDL on ClickHouse, invalidating metadata caches, committing the DDL offset, and unparking worker threads.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
- **Method**: `performDDLOperation()`

---

## 3. Operational Specification

When DDL translation yields one or more ClickHouse SQL statements:
1. **Execution**:
   The statement is executed via `systemDbConnection.createStatement().execute(clickHouseQuery)`.
2. **Table Key Resolution**:
   Resolves affected table names from the AST or source event topic.
3. **Cache Invalidation**:
   - `CacheInvalidationManager.getInstance().invalidateTable(tableKey)` increments the table version counter.
   - Clears proven-absent column sets for the affected table.
   - If table resolution is ambiguous, calls `invalidateAll()` to bump `globalEpoch`.
4. **Offset Acknowledgment**:
   `committer.markProcessed(record)` acknowledges the DDL event offset.
5. **Worker Resumption**:
   Calls `executor.resume()`, setting `isPaused = false` and waking parked workers.
6. Post-DDL records now proceed against the newly altered table schema.

---

## 4. Invariants Preserved
- **Invariant I5 (DDL Barrier Quiescence)**: Cache invalidation takes effect before any post-DDL rows begin query formulation.

---

## 5. Verification Criteria
- `DebeziumChangeEventCaptureTest.testPerformDDLOperationAndResume()`

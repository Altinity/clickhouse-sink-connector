# Specification 003: ClickHouse Batch Writer & Execution Engine

## 1. Executive Summary & Purpose

The ClickHouse batch writing subsystem dequeues parsed CDC structs, groups them by target table and query template, applies destination routing and schema rules, and dispatches them via JDBC batch statements to ClickHouse. The subsystem supports two execution modes:
1. **Multi-Threaded Scheduled Mode (`ClickHouseBatchRunnable`)**: Uses `ClickHouseBatchExecutor` with scheduled worker threads to flush batches concurrently.
2. **Single-Threaded Inline Mode (`ClickHouseBatchWriter`)**: Executes flushes synchronously on the capture thread when `threadPoolSize == 1`.

---

## 2. Codebase Mapping on 2.11.0

- **Primary Classes**:
  - `com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchRunnable` (`sink-connector/...`)
  - `com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchWriter` (`sink-connector/...`)
  - `com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchExecutor` (`sink-connector/...`)
  - `com.altinity.clickhouse.sink.connector.db.operations.GroupInsertQueryWithBatchRecords` (`sink-connector/...`)
  - `com.altinity.clickhouse.sink.connector.db.operations.QueryFormatter` (`sink-connector/...`)
  - `com.altinity.clickhouse.sink.connector.db.PreparedStatementExecutor` (`sink-connector/...`)
- **Key Methods**:
  - `ClickHouseBatchRunnable.processBatch()`
  - `ClickHouseBatchWriter.processBatch()`
  - `GroupInsertQueryWithBatchRecords.groupQueryWithRecords()`
  - `PreparedStatementExecutor.insertBatch()`

---

## 3. Operational Workflow

```
+-----------------------------------------------------------------------------------+
|                        CLICKHOUSE BATCH EXECUTION                                 |
+-----------------------------------------------------------------------------------+
|                                                                                   |
|  records.poll(buffer.flush.time)                                                  |
|        |                                                                          |
|        v                                                                          |
|  DebeziumOffsetManagement.addToBatchTimestamps(batch)                             |
|        |                                                                          |
|        v                                                                          |
|  Group records by topic: Map<String, List<ClickHouseStruct>>                      |
|        |                                                                          |
|        +---> For each topic:                                                      |
|                 |                                                                 |
|                 +---> Resolve target database & table:                            |
|                 |     Apply override maps, common prefixes, and schema suffixes.  |
|                 |                                                                 |
|                 +---> Obtain or refresh DbWriter:                                 |
|                 |     Check CacheInvalidationManager.getVersion(tableKey).        |
|                 |     If stale: re-read schema, rebuild column mapping.           |
|                 |                                                                 |
|                 +---> Formulate batch queries:                                    |
|                 |     GroupInsertQueryWithBatchRecords.groupQueryWithRecords()    |
|                 |     - Check unknown columns (refreshIfRecordHasUnknownColumn)   |
|                 |     - Convert MATERIALIZED columns to DEFAULT if source defined |
|                 |     - Group by (INSERT, UPDATE, DELETE)                         |
|                 |                                                                 |
|                 +---> Execute JDBC batches:                                       |
|                 |     flushRecordsToClickHouse()                                  |
|                 |     - QueryFormatter: INSERT INTO db.table (cols) VALUES (?,?,?)|
|                 |     - PreparedStatementFieldMapper: bind parameters & engine col|
|                 |     - PreparedStatementExecutor: executeBatch()                 |
|                 |                                                                 |
|                 +---> Acknowledge batch completion:                               |
|                       checkIfBatchCanBeCommitted(batch)                           |
|                                                                                   |
+-----------------------------------------------------------------------------------+
```

---

## 4. Destination Database & Table Resolution

To guarantee deterministic destination routing across both `ClickHouseBatchRunnable` and `ClickHouseBatchWriter` (unified in PR #1458):
1. **Source Database Extraction**: The database name is parsed from `record.getDatabase()`.
2. **Destination Override Map**: `clickhouse.database.override.map` applies explicit source-to-target remapping (e.g. `mysql_prod:ch_prod`).
3. **Database Prefix**: `clickhouse.common.database.prefix` prepends a namespace prefix if defined.
4. **Schema Template Suffix**: `clickhouse.database.schema.suffix` formats the final database name.
5. **Table Mapping**: `table.name.mapping` or raw topic names define the ClickHouse target table.

---

## 5. JDBC Batch Execution & HikariCP Pooling

- **Connection Management**:
  Connections are managed via `HikariCP` connection pool or raw JDBC connections. `DbWriter` maintains thread-confined connections to prevent concurrent statement execution on a single JDBC handle.
- **Batch Size Controls**:
  `buffer.max.records` (default 10,000) caps the number of records accumulated in a single `PreparedStatement.addBatch()` sequence.
- **Batch Flush Timing**:
  `buffer.flush.time` (default 1,000ms) enforces maximum latency before in-flight batches are flushed regardless of batch size.

---

## 6. Invariants Preserved

1. **Transactional Batch Atomicity (Invariant I3)**:
   All records contained within a single JDBC batch are committed atomically in ClickHouse, ensuring that intermediate states are not partially visible.
2. **Deterministic Destination Consistency**:
   Both multi-threaded and single-threaded execution modes resolve identical database and table targets using the unified override rules.

---

## 7. Failure Modes & Retries

- **Retriable Database Failures**:
  Network disconnections, socket timeouts, and ClickHouse cluster recovery trigger `ClickHouseErrorClassifier`. The batch is rolled back and re-queued for the next scheduled tick.
- **Fatal Schema / Type Errors**:
  If ClickHouse rejects an insert due to missing columns or unconvertible types, the task halts and logs the exact error, preventing data corruption or silent drops.

---

## 7. Verification Criteria

- **Unit Tests**:
  `ClickHouseBatchWriterDatabaseResolutionTest`, `GroupInsertQueryWithBatchRecordsTest`.
- **Integration Tests**:
  `BatchInsertIT`, `HikariConnectionPoolIT`.
- **Formal Verification**:
  Corresponds to `Replication.ClickHouse.CHTable` and batch application in `formal_specs/lean/`.

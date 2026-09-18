# Specification 005: DDL Interception, Translation & Barrier Synchronization

## 1. Executive Summary & Purpose

The DDL replication subsystem intercepts schema alteration events in the upstream MySQL binlog stream, translates them from MySQL syntax into equivalent ClickHouse DDL commands, and executes them with strict barrier synchronization. The barrier protocol guarantees that all pre-DDL data records are durably committed under the old schema before the DDL executes, and that all post-DDL records are processed under the new schema, completely preventing schema inversion bugs.

---

## 2. Codebase Mapping on 2.11.0

- **Primary Classes**:
  - `com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture` (`sink-connector-lightweight/...`)
  - `com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchExecutor` (`sink-connector/...`)
  - `com.altinity.clickhouse.debezium.embedded.ddl.parser.MySqlDDLParserService` (`sink-connector-lightweight/...`)
  - `com.altinity.clickhouse.debezium.embedded.ddl.parser.MySqlDDLParserListenerImpl` (`sink-connector-lightweight/...`)
  - `com.altinity.clickhouse.sink.connector.db.operations.CacheInvalidationManager` (`sink-connector/...`)
- **Key Methods**:
  - `DebeziumChangeEventCapture.drainBeforeDDL()`
  - `DebeziumChangeEventCapture.performDDLOperation()`
  - `ClickHouseBatchExecutor.pause()`, `awaitQuiescent()`, `resume()`
  - `MySqlDDLParserService.parseSql()`

---

## 3. The DDL Barrier Protocol

DDL events represent synchronous schema transitions in an asynchronous, pipelined replication architecture. The connector enforces a 5-step barrier protocol:

```
+-----------------------------------------------------------------------------------+
|                            DDL BARRIER PROTOCOL                                   |
+-----------------------------------------------------------------------------------+
|                                                                                   |
|  1. Flush In-Flight Batch:                                                        |
|     - All DML records accumulated prior to the DDL record in the current batch    |
|       are immediately dispatched to `records` handoff queue.                      |
|                                                                                   |
|  2. Queue Drain Phase:                                                            |
|     - CDC thread spins while `records` queue is not empty (up to 60s timeout).    |
|     - Worker threads continue processing and flushing preceding rows.             |
|                                                                                   |
|  3. Pause Worker Execution:                                                       |
|     - Calls `ClickHouseBatchExecutor.pause()`.                                    |
|     - Internal monitor gate sets `isPaused = true`.                               |
|     - Worker threads arriving at `beforeExecute()` park on the gate monitor.      |
|                                                                                   |
|  4. Await Quiescence:                                                             |
|     - Calls `ClickHouseBatchExecutor.awaitQuiescent(timeout)`.                    |
|     - Spins until `activeBatches.get() == 0`.                                     |
|                                                                                   |
|  5. DDL Translation & Execution:                                                  |
|     - ANTLR parser translates MySQL DDL to ClickHouse SQL.                        |
|     - Executes translated DDL on ClickHouse via system database connection.       |
|     - `CacheInvalidationManager.invalidateTable(tableKey)` increments version.    |
|                                                                                   |
|  6. Resume Pipeline:                                                              |
|     - DDL offset is acknowledged via `DebeziumOffsetManagement`.                  |
|     - Calls `ClickHouseBatchExecutor.resume()`, unparking all worker threads.     |
|                                                                                   |
+-----------------------------------------------------------------------------------+
```

---

## 4. DDL Translation Rules & ClickHouse Specifics

The ANTLR4-based parser (`MySqlDDLParserListenerImpl`) translates MySQL DDL into ClickHouse-compatible DDL while adhering to the following hard rules:

### 4.1 Generated Columns Mapping
- MySQL `GENERATED ALWAYS AS (expr) STORED/VIRTUAL` must map to ClickHouse `DEFAULT (expr)`, **never** `MATERIALIZED`.
- **Reason**: ClickHouse `MATERIALIZED` columns reject explicit INSERTs. Mapping to `DEFAULT` allows ClickHouse to compute default values when the column is omitted, while still accepting source values when provided.

### 4.2 Nullability & `NOT NULL` Alterations (PR #1455 / #1457 / #1459)
- `ALTER TABLE ... MODIFY COLUMN col type NOT NULL`:
  In ClickHouse, converting an existing `Nullable` column to non-Nullable requires providing a default expression or rewriting all parts; otherwise, ClickHouse aborts with `Code: 36`.
  - **Rule**: If a column was originally created as `Nullable`, a subsequent `MODIFY COLUMN ... NOT NULL` keeps the ClickHouse column as `Nullable(type)`. This prevents stream-stalling DDL rejections while allowing source values to be stored.
  - `ADD COLUMN ... NOT NULL` on a newly created column is safely honored.

### 4.3 Primary Key Alterations
- `ALTER TABLE ... ADD PRIMARY KEY (...)`:
  ClickHouse `ReplacingMergeTree` primary and sorting keys are immutable after table creation (`Code: 524`).
  - **Rule**: The parser suppresses `ADD PRIMARY KEY` clauses on existing tables, avoiding syntax errors. For keyless tables, Generated Invisible Primary Keys (GIPK) or auto-create configuration are recommended.

---

## 5. Invariants Preserved

1. **Zero Schema Inversion (Invariant I5)**:
   Pre-DDL data records are guaranteed to be inserted before the table schema is altered. Post-DDL records are guaranteed to encounter the updated ClickHouse table.
2. **Deterministic Cache Invalidation**:
   `CacheInvalidationManager.getInstance().invalidateTable(tableKey)` guarantees that all cached `DbWriter` column maps are dropped, forcing a refresh on next query formulation.

---

## 6. Verification Criteria

- **Unit Tests**:
  `MySqlDDLParserListenerImplTest` (114 test cases covering ADD, DROP, MODIFY, RENAME, CHANGE).
- **Integration Tests**:
  `AlterTableModifyColumnIT`, `AlterTableAddColumnIT`, `AlterTableRenameColumnIT`.
- **Formal Verification**:
  Corresponds to DDL schema transition barriers and cache epochs in `formal_specs/lean/`.

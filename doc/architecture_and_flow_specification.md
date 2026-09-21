# Architecture & End-to-End Flow Specification: ClickHouse Sink Connector (2.11.0)

## Executive Summary

This document provides a comprehensive, ground-truth architectural specification and method-by-method operational mapping of the `clickhouse-sink-connector` on branch `2.11.0`. It covers both operating runtimes:
1. **Embedded Lightweight Mode (`sink-connector-lightweight`)**: In-process Debezium engine streaming MySQL/PostgreSQL binlog/WAL directly to ClickHouse without Kafka brokers.
2. **Kafka Connect Sink Mode (`sink-connector`)**: Standard Kafka Connect `SinkTask` consuming from Kafka partitions and writing batches to ClickHouse.

The purpose of this specification is to establish a rigorous, idea-level mapping of all control paths, data pipelines, state machines, concurrency models, and failure domains. This mapping serves as the foundation for identifying structural incoherencies, race conditions, and design complexity traps that have led to recurring operational defects, and provides the architectural blueprint required for a coherent system redesign.

---

## 1. Prime Invariant & Architectural Topology

### 1.1 The Prime Directive
The connector is a **replication engine** from transactional source databases (MySQL, PostgreSQL) to an analytical columnar store (ClickHouse).
1. **The source database is the absolute source of truth.** The source data defines what the data is. The connector never assumes the source is in error.
2. **ClickHouse is the replica and must conform to the source.** The replica schema, columns, types, nullability, and row state exist exclusively to mirror the source.
3. **Every divergence must be resolved by correcting ClickHouse to match the source.** Divergences are never resolved by ignoring, modifying, or reinterpreting source events.
4. **The connector is an active enforcer, not merely a passive reporter.** When ClickHouse schema or column definitions prevent storing source data (e.g. `MATERIALIZED` column shadowing source values), the connector automatically alters the ClickHouse schema to enable ingestion.

### 1.2 Architectural Dualism

```
+----------------------------------------------------------------------------------------------------+
|                                    OPERATING TOPOLOGY MODES                                        |
+----------------------------------------------------------------------------------------------------+
|                                                                                                    |
|  [MODE A: Embedded Lightweight Standalone]                   [MODE B: Kafka Connect Plugin]        |
|                                                                                                    |
|   +--------------------------+                                 +--------------------------+        |
|   |   MySQL / PostgreSQL     |                                 |   Kafka Topic Partitions |        |
|   +------------+-------------+                                 +------------+-------------+        |
|                |                                                            |                      |
|                v (Binlog / WAL)                                             v (SinkRecord)         |
|   +--------------------------+                                 +--------------------------+        |
|   | Debezium Embedded Engine |                                 |  ClickHouseSinkTask      |        |
|   +------------+-------------+                                 +------------+-------------+        |
|                |                                                            |                      |
|                v (ChangeEvent)                                              v (put())              |
|   +--------------------------+                                 +--------------------------+        |
|   | DebeziumChangeEvent     |                                 | DeDuplicator (Optional)  |        |
|   | Capture                  |                                 +------------+-------------+        |
|   +------------+-------------+                                              |                      |
|                |                                                            v                      |
|                +-----------------------------+------------------------------+                      |
|                                              |                                                     |
|                                              v (ClickHouseStruct batches)                          |
|                             +--------------------------------+                                     |
|                             | LinkedBlockingQueue (Handoff)  |                                     |
|                             +----------------+---------------+                                     |
|                                              |                                                     |
|                                              v (poll())                                            |
|                             +--------------------------------+                                     |
|                             | ClickHouseBatchExecutor        |                                     |
|                             | (ScheduledThreadPoolExecutor)  |                                     |
|                             +----------------+---------------+                                     |
|                                              |                                                     |
|                                              v (Task Threads)                                      |
|                             +--------------------------------+                                     |
|                             | ClickHouseBatchRunnable        |                                     |
|                             | / ClickHouseBatchWriter        |                                     |
|                             +----------------+---------------+                                     |
|                                              |                                                     |
|                                              v (JDBC PreparedStatement Batch)                      |
|                             +--------------------------------+                                     |
|                             | ClickHouse Cluster / Instance  |                                     |
|                             | (ReplacingMergeTree Engines)   |                                     |
|                             +--------------------------------+                                     |
+----------------------------------------------------------------------------------------------------+
```

---

## 2. Core Component Directory & Responsibilities

| Component | Module | Responsibilities |
|---|---|---|
| `ClickHouseDebeziumEmbeddedApplication` | `lightweight` | Main entry point for standalone service; loads properties and YAML; initializes Guice dependency injection (`AppInjector`); coordinates lifecycle. |
| `DebeziumChangeEventCapture` | `lightweight` | Embedded Debezium engine harness; manages CDC loop, monotonic sequence numbers, DDL interception, pre-DDL pipeline draining, and control-record offset commits. |
| `ClickHouseSinkTask` | `sink-connector` | Kafka Connect sink task entry point; manages partition assignment, consumes `SinkRecord` batches, invokes deduplication, tracks durable offsets in `preCommit()`. |
| `ClickHouseBatchExecutor` | `sink-connector` | Subclass of `ScheduledThreadPoolExecutor`; introduces thread-safe gate monitor with `pause()`, `resume()`, and `awaitQuiescent()` for DDL barrier synchronization. |
| `ClickHouseBatchRunnable` | `sink-connector` | Worker thread execution loop for multi-threaded mode; drains queues, manages database overrides, builds `DbWriter`, formats queries, and executes JDBC flushes. |
| `ClickHouseBatchWriter` | `sink-connector` | Single-threaded synchronous writer (used when `threadPoolSize == 1`); processes batches inline without thread dispatch. |
| `DbWriter` | `sink-connector` | Encapsulates JDBC connection, ClickHouse metadata cache (column name to type mapping), auto-table creation, and table engine resolution. |
| `GroupInsertQueryWithBatchRecords` | `sink-connector` | Groups incoming records by target query template; performs schema staleness checks (`refreshIfRecordHasUnknownColumn`); enforces column writability; handles UPDATE splitting. |
| `QueryFormatter` | `sink-connector` | Constructs parameterized ClickHouse `INSERT INTO table(cols) VALUES (?,?,...)` queries; builds column-to-index parameter maps. |
| `PreparedStatementFieldMapper` | `sink-connector` | Maps Kafka Connect struct fields to JDBC statement parameter indices; converts data types; populates engine columns (`_version`, `_sign`, `is_deleted`). |
| `PreparedStatementExecutor` | `sink-connector` | Dispatches record batches into JDBC `PreparedStatement`; detects sorting key mutations on UPDATEs and synthesizes delete tombstones; triggers `executeBatch()`. |
| `ClickHouseDataTypeMapper` | `sink-connector` | Central type conversion matrix; maps Debezium/Kafka Connect types to ClickHouse types for DDL generation and statement binding. |
| `CacheInvalidationManager` | `sink-connector` | Global singleton managing monotonic invalidation version counters per table and global invalidation epochs; tracks columns proven absent. |
| `DebeziumOffsetManagement` | `sink-connector` | Tracks in-flight batches; serializes offset updates through Debezium `RecordCommitter` under `OFFSET_COMMIT_LOCK`; checks pipeline quiescence. |
| `PostgresSchemaChangeDetector` | `lightweight` | Detects schema drift in PostgreSQL CDC by comparing Debezium event schemas against ClickHouse `system.columns` using TTL-cached metadata. |
| `PostgresSchemaReconciler` | `lightweight` | Issues automated `ALTER TABLE ... ADD COLUMN` statements on ClickHouse when PostgreSQL schema drift is detected. |
| `DDLParserFactory` / Services | `lightweight` | ANTLR4-based DDL parsers (`MySQLDDLParserService`, `PostgreSQLDDLParserService`) that translate source DDL into ClickHouse DDL. |
| `BinLogHistory` | `sink-connector` | Handles bitemporal SCD Type 2 history logging (`_valid_from`, `_valid_to`, `_operation`, `is_deleted`) in `binlog_history` tables. |

---

## 3. End-to-End Execution Flow (Detail to the Tee)

### 3.1 Phase 1: Initialization & Engine Startup

1. **Bootstrap & Property Resolution**:
   - `ClickHouseDebeziumEmbeddedApplication.main()` is invoked.
   - Configuration is loaded from `config.properties`, YAML files, or environment variables (`EnvironmentConfigurationService`).
   - `SinkConnectorLightWeightConfig` and `ClickHouseSinkConnectorConfig` validate settings (database overrides, credentials, thread pool size).
2. **Source Preflight Validation**:
   - For MySQL sources, `KeylessTablePreflight.checkKeylessTables()` queries source metadata (`information_schema.tables`, `innodb_indexes`) to identify tables lacking primary keys. Warns that keyless tables will collapse to a single row under ClickHouse `ReplacingMergeTree` unless generated invisible primary keys (GIPK) are enabled.
3. **Target Metadata & Storage Initialization**:
   - `DebeziumJdbcStorageOperations.createSchemaHistoryTable()` creates the schema history table in ClickHouse.
   - `DebeziumJdbcStorageOperations.createViewForShowReplicaStatus()` sets up the replica status monitoring view.
   - If replication history mode is enabled (`replication.history.enable=true`), `ClickHouseAutoCreateTable.createHistoryDatabase()` and `createHistoryTable()` ensure the `binlog_history` database and table exist.
4. **Execution Harness Setup**:
   - A `LinkedBlockingQueue[List[ClickHouseStruct]] records` is instantiated with capacity `max.queue.size` (default unbounded).
   - If `threadPoolSize exceeds 1`, a `ClickHouseBatchExecutor` is initialized with scheduled worker threads running `ClickHouseBatchRunnable` at a fixed rate (`buffer.flush.time`).
   - If `threadPoolSize == 1`, `singleThreadedWriter` (`ClickHouseBatchWriter`) is initialized to execute synchronously on the CDC thread.
5. **Debezium Engine Instantiation**:
   - `DebeziumEngine.create(Connect.class)` builds the capture engine.
   - Connector class is set (`io.debezium.connector.mysql.MySqlConnector` or `io.debezium.connector.postgresql.PostgresConnector`).
   - Offset backing store is configured (`DebeziumOffsetStorage` or `JdbcOffsetBackingStore` storing offsets in ClickHouse `replica_source_info` with `ORDER BY offset_key`).
   - Engine is dispatched on a dedicated single-threaded executor (`singleThreadDebeziumEventExecutor`).

---

### 3.2 Phase 2: Ingestion Loop & CDC Event Processing

```
Debezium Event Engine
       |
       v (List of ChangeEvent)
handleChangeEventBatch()
       |
       +---> For each record:
       |        |
       |        +---> Extract source timestamp: ClickHouseStruct.getSourceTsFromChangeEvent()
       |        +---> Extract log coordinates: ClickHouseStruct.getSourcePositionFromChangeEvent()
       |        +---> Calculate sequence: nextSequenceNumber(recordTs, position)
       |        |
       |        +---> Check if DDL: isDDLRecord(record)
       |        |        |
       |        |        +--- [YES]:
       |        |        |      1. Flush pending rows: appendToRecords(batch)
       |        |        |      2. drainBeforeDDL() (Queue drain -> Pause -> Await Quiescent)
       |        |        |      3. Parse & Execute DDL on ClickHouse
       |        |        |      4. Invalidate caches: CacheInvalidationManager.invalidateTable()
       |        |        |      5. Acknowledge DDL offset
       |        |        |      6. Resume executor: executor.resume()
       |        |        |
       |        |        +--- [NO]:
       |        |               1. Postgres drift check: checkAndReconcile()
       |        |               2. Parse struct: debeziumRecordParserService.parse()
       |        |               3. If DML: assign sequenceNumber, add to batch
       |        |               4. If Control/Heartbeat (null struct): track as lastControlRecord
       |        |
       +---> Post-loop:
                1. If batch has rows: appendToRecords(batch)
                2. If lastControlRecord != null: commitControlRecordOffset() (if pipeline quiescent)
```

#### Step 2.1: Monotonic Sequence Number & Version Calculation
The connector must assign each change event a strictly monotonic 64-bit `_version` for ClickHouse `ReplacingMergeTree`.
- **Method**: `DebeziumChangeEventCapture.nextSequenceNumber(long recordTs, SourcePosition position)`:
  - `recordTs` is the source commit timestamp (`source.ts_ms`).
  - `SourcePosition` tracks binary log coordinates:
    - MySQL: `(filePrefix, fileSequence, position, row)`.
    - PostgreSQL: `lsn`.
  - **First Delivery vs. Redelivery Logic**:
    - If `position ranks above sequenceHighWaterPosition`, the event is a **first delivery** (new commit).
    - In MySQL, `source.ts_ms` is the *statement execution timestamp*, not the commit timestamp. A long transaction committing after short transactions carries an older `source.ts_ms`. To maintain commit monotonicity:
      - `effectiveTs` is clamped to the high-water floor: `if (effectiveTs is less than sequenceMaxSourceTs) effectiveTs = sequenceMaxSourceTs`.
      - `sequenceMaxSourceTs` is updated to `effectiveTs`.
    - If `position ranks at or below sequenceHighWaterPosition`, the event is an **offset rewind / redelivery**. The redelivery-stable assignment is preserved without advancing the high-water mark.
  - **Intra-second Sequence Counter**:
    - `diff = (effectiveTs - sequenceAnchorTs) / 1000`.
    - If `diff exceeds 1` (more than 1 second advance): `sequenceAnchorTs = effectiveTs`, `sequenceNumber = SEQUENCE_START (1,000,000,000)`.
    - Else: `sequenceNumber++`.
  - **Resulting Version**: `_version = effectiveTs * 1_000_000L + sequenceNumber`.

#### Step 2.2: DDL Interception & Pre-DDL Inversion Prevention
If an event carries a DDL schema change:
1. **Flush Buffered Rows**:
   - Any DML records accumulated in `batch` prior to the DDL record were captured against the *pre-DDL schema*.
   - They must be dispatched immediately: `appendToRecords(batch, config)` and `batch.clear()`. This prevents schema inversion where pre-DDL records are executed against a post-DDL table.
2. **Execute DDL Drain Protocol (`drainBeforeDDL()`)**:
   - **Step A (Queue Drain)**: The thread spins while `records != null && !records.isEmpty()`, sleeping 50ms per iteration up to `DDL_DRAIN_TIMEOUT_MS` (60,000ms). Workers continue executing.
   - **Step B (Pause Workers)**: Calls `executor.pause()`. The monitor lock inside `ClickHouseBatchExecutor` sets `isPaused = true`, causing workers arriving at `beforeExecute()` to park.
   - **Step C (Await In-Flight Quiescence)**: Calls `executor.awaitQuiescent(remainingTimeout)`. Spins until `activeBatches.get() == 0`.
3. **Parse and Execute DDL (`performDDLOperation()`)**:
   - DDL is checked against ignore regexes (`checkIfDDLNeedsToBeIgnored`).
   - `DDLParserFactory.getParser()` selects `MySQLDDLParserService` or `PostgreSQLDDLParserService`.
   - The parser generates equivalent ClickHouse SQL (`clickHouseQuery`).
   - Generated columns in MySQL (`GENERATED ALWAYS AS ...`) are mapped to ClickHouse `DEFAULT (expr)`, NOT `MATERIALIZED`, ensuring the column can store source values while deriving default values when omitted.
   - Query is executed via JDBC on `systemDbConnection`.
4. **Cache Invalidation**:
   - Affected table names are resolved from DDL text or event topic.
   - `CacheInvalidationManager.getInstance().invalidateTable(tableKey)` increments the table version counter.
   - If the table name cannot be parsed, `invalidateAll()` increments `globalEpoch` to invalidate all tables.
   - If PostgreSQL, `PostgresSchemaChangeDetector.invalidateCache(tableKey)` clears the schema drift cache.
5. **Resume Pipeline**:
   - Offset is acknowledged via `DebeziumOffsetManagement.acknowledgeRecords()`.
   - `executor.resume()` unlocks the gate and unparks worker threads.

#### Step 2.3: PostgreSQL Schema Drift Detection
For PostgreSQL sources (which do not emit DDL statements over logical replication):
- On every DML record, `pgConfig.getSchemaChangeDetector().checkAndReconcile(sr, dmlTable, dmlDatabase)` is called.
- Compares the `SourceRecord` Kafka Connect `Schema` fields against ClickHouse `system.columns` (cached with TTL).
- If new fields exist in the source record, `PostgresSchemaReconciler.addMissingColumns()` executes `ALTER TABLE db.table ADD COLUMN IF NOT EXISTS col type` on ClickHouse before row insertion proceeds.

#### Step 2.4: Control Record & Heartbeat Offset Commit
- When Debezium completes an initial snapshot, the completion state (`snapshot=false`) is emitted on a subsequent **heartbeat record** (which produces a null `ClickHouseStruct`).
- `commitControlRecordOffset()` checks `isPipelineQuiescent()`:
  - Confirms `records.isEmpty()`, `routedRecords.isEmpty()`, and `DebeziumOffsetManagement.hasUnwrittenBatches() == false`.
  - If quiescent, calls `DebeziumOffsetManagement.acknowledgeRecords()` under `OFFSET_COMMIT_LOCK`. This commits the snapshot offset to ClickHouse `replica_source_info`, preventing snapshot loops on restart.

---

### 3.3 Phase 3: Worker Batch Execution & ClickHouse Insertion

```
ClickHouseBatchRunnable / ClickHouseBatchWriter
       |
       v (poll batch from records queue)
processBatch()
       |
       +---> (the batch was registered with its handoff sequence by the
       |      Debezium thread at handoff: DebeziumOffsetManagement.registerHandoff)
       |
       +---> Partition records by topic: topicToRecordsMap
       |
       +---> For each topic:
                |
                +---> Resolve Table & Database (apply overrides, prefixes, templates)
                |
                +---> Get or Rebuild DbWriter: getDbWriterForTable()
                |        |
                |        +---> Check CacheInvalidationManager.getVersion()
                |        +---> If stale: re-read schema, rebuild columnNameToDataTypeMap
                |        +---> If table missing & auto.create.tables: autoCreateTable()
                |
                +---> Formulate Queries: GroupInsertQueryWithBatchRecords
                |        |
                |        +---> Staleness check: refreshIfRecordHasUnknownColumn()
                |        |        +---> If unknown col found: re-read system.columns
                |        |        +---> If MATERIALIZED: enforceSourceColumnIsWritable()
                |        |        +---> If unresolvable: markColumnProvenAbsent()
                |        |
                |        +---> Partition by CDC Operation:
                |                 +---> INSERT: After-image fields
                |                 +---> UPDATE: Before-image + After-image fields
                |                 +---> DELETE: Before-image with delete flag
                |                 +---> TRUNCATE: TRUNCATE TABLE
                |
                +---> Execute Batches: flushRecordsToClickHouse()
                |        |
                |        +---> QueryFormatter builds: INSERT INTO table(cols) VALUES (?,?,...)
                |        +---> PreparedStatementExecutor creates JDBC PreparedStatement
                |        +---> PreparedStatementFieldMapper binds types via ClickHouseDataTypeMapper
                |        |        +---> Detect PK relocation: insertTombstonePreparedStatement()
                |        |        +---> Bind _version, _sign, is_deleted
                |        +---> ps.executeBatch()
                |
                +---> Drop the written batch (currentBatch = null; never re-executed)
                +---> Report it written: checkIfBatchCanBeCommitted()
                         |
                         +---> Unit complete and head of the handoff FIFO?
                                  yes: acknowledge in binlog order under OFFSET_COMMIT_LOCK:
                                       recordCommitter.markProcessed() per row,
                                       recordCommitter.markBatchFinished() once;
                                       then drain younger parked units in sequence order
                                  no:  park it (completedUnits) until older units are acknowledged
```

#### Step 3.1: Batch Dequeue & Tracking
- Worker threads poll batches from their own routed queue (`runWithHashRouting`, `thread.pool.size > 1`) or from `records` (`runLegacyMode`).
- Nothing is registered on pick-up: the Debezium thread registered the batch with its handoff sequence (`DebeziumOffsetManagement.registerHandoff`) before enqueueing it, so it counts as unwritten from the instant of handoff (spec 09.01).

#### Step 3.2: Topic Partitioning & Destination Resolution
- The batch is grouped by Kafka topic: `Map[String, List[ClickHouseStruct]] topicToRecordsMap`.
- Database name is resolved:
  - Source database extracted from record.
  - `clickhouse.database.override.map` applies destination remapping.
  - `clickhouse.common.database.prefix` applies database prefixing.
  - `clickhouse.database.schema.suffix` applies schema template formatting.

#### Step 3.3: DbWriter Lifecycle & Schema Invalidation
- `getDbWriterForTable()` checks the cached `DbWriter` version against `CacheInvalidationManager.getInstance().getVersion(tableKey)`.
- If the table was invalidated by a DDL event:
  - Old `DbWriter` is discarded.
  - Fresh `DbWriter` is instantiated, querying ClickHouse `system.columns` to populate `columnNameToDataTypeMap`.
  - Table engine is detected (`TABLE_ENGINE.REPLACING_MERGE_TREE`, `REPLICATED_REPLACING_MERGE_TREE`, or `COLLAPSING_MERGE_TREE`).
  - Sorting keys are fetched: `DBMetadata.getSortingKeyColumns()`.

#### Step 3.4: Query Template Grouping & Column Enforcement
In `GroupInsertQueryWithBatchRecords.groupQueryWithRecords()`:
1. **Cache Staleness & Unknown Column Probing (`refreshIfRecordHasUnknownColumn`)**:
   - Inspects incoming record schema fields against `cached` ClickHouse columns.
   - If a field is missing from `cached`:
     - Checks `CacheInvalidationManager.isColumnProvenAbsent()`: if proven absent at current version, skips re-reading (prevents metadata query storms).
     - If not proven absent: re-queries `system.columns`.
     - If column appeared: invalidates table version so all workers refresh.
     - If column did NOT appear (e.g. ClickHouse `MATERIALIZED` column):
       - Invokes `enforceSourceColumnIsWritable()`:
         - Inspects `default_kind`. If `ALIAS`, ignored (query-time only).
         - If `MATERIALIZED`, ClickHouse derives it locally and rejects INSERTs. The connector enforces source authority by executing:
           `ALTER TABLE db.table MODIFY COLUMN col (type) DEFAULT (expr)`
         - Re-reads metadata and invalidates cache if successful.
       - If enforcement fails or is skipped, records `markColumnProvenAbsent(tableKey, col)`.
2. **Field Membership Filtering (`QueryFormatter.createColumns`)**:
   - Columns omitted from the record schema (e.g. pre-ALTER records) are excluded from the `INSERT` column list so ClickHouse applies the column `DEFAULT` instead of binding `NULL`.
   - Columns present in the record schema but holding `null` values are **retained** in the `INSERT` list so an explicit `NULL` is bound, preventing ClickHouse from falling back to column defaults.
3. **CDC Operation Handling**:
   - **INSERT (`c`, `r`)**: Dispatches `after` image to `updateQueryToRecordsMap`.
   - **UPDATE (`u`)**:
     - Standard Mode: Grouped once, under the after-image template; the executor binds the before and/or after image as the engine requires (Spec 04.04 §3.1).
     - Replication History Mode: Preserves single after-image record with temporal metadata.
   - **DELETE (`d`)**: Dispatches `before` image with delete flag.
   - A record whose required image is missing, or for which no column metadata is available, fails the batch with `IllegalStateException`; nothing is dropped (Spec 04.01 §3.3).
   - **TRUNCATE (`t`)**: Directly generates `TRUNCATE TABLE \`table\``.

#### Step 3.5: Statement Parameter Binding & Execution
In `PreparedStatementExecutor.insertBatch()` and `PreparedStatementFieldMapper.insertPreparedStatement()`:
1. **Primary/Sorting Key Relocation on UPDATE**:
   - If an UPDATE modifies a column that is part of the ClickHouse `ORDER BY` sorting key:
     - `updateRelocatesSortingKey()` detects the mismatch between `beforeStruct` and `afterStruct`.
     - An explicit tombstone row is inserted for the *old* sorting key:
       `insertTombstonePreparedStatement()` binds the before-image values with `is_deleted = 1` and `_version = record.getVersion()` (the same version as the after-image; a same-transaction relocation shares its version with the row it retires, and ClickHouse's equal-version tie resolves to the later-inserted row, i.e. the tombstone).
     - The live after-image row is then inserted for the *new* sorting key with `is_deleted = 0` and `_version = record.getVersion()`.
2. **Type Conversion (`ClickHouseDataTypeMapper.convert`)**:
   - Binds values to `PreparedStatement` parameter positions:
     - Integers: `INT8` (`Byte`), `INT16` (`Short`), `INT32` (`Integer`), `INT64` (`Long`). Uses `Number.intValue()` to prevent class-cast failures on tinyints.
     - Floats/Doubles: `Float32`, `Float64` (converted to `BigDecimal`).
     - Decimals: Scaled `BigDecimal` mapped to `Decimal(p, s)`.
     - Temporals:
       - `MicroTimestamp` / `Timestamp`: Formatted with source/server timezone conversion.
       - `ZonedTimestamp`: Converted using UTC/server zone mapping.
       - `Date` / `Date32`: Converted from epoch days.
       - `MicroTime`: Converted to microseconds since midnight (`Int64`).
     - Strings / JSON: Mapped to ClickHouse `String` or `JSON`.
     - Byte Arrays / Bits: Reverses byte order for Debezium `io.debezium.data.Bits` to match big-endian ClickHouse `Bit` representations.
     - Geometry / Spatial: Uses JTS `WKBReader` to parse WKB polygons and binds to ClickHouse Geo types.
     - Arrays: If ClickHouse target is non-Array, serializes to JSON string; otherwise binds array elements.
3. **System Column Binding**:
   - `_version`: Bound from `record.getVersion()` (`rejectUnderivableVersion` throws if `-1`).
   - `is_deleted`: Bound to `1` for DELETE / tombstones, `0` for live rows.
   - `_sign`: Bound to `-1` for deletes, `1` for inserts (CollapsingMergeTree).
4. **JDBC Execution & Error Classification**:
   - `ps.addBatch()` accumulates records up to `buffer.max.records`.
   - `ps.executeBatch()` flushes to ClickHouse.
   - Errors are passed to `ClickHouseErrorClassifier`:
     - `FATAL` (syntax errors, illegal column types): Batch is discarded and task stops.
     - `UNKNOWN` / `RETRIABLE` (network timeouts, connection loss, `TOO_MANY_PARTS`, `MEMORY_LIMIT_EXCEEDED`): the same batch is retried with exponential backoff (`batch.retry.backoff.initial.ms` doubling to `batch.retry.backoff.max.ms`, no attempt cap; spec 10.02).
     - `FATAL`: the worker rethrows and its scheduled task ends; the Debezium thread detects the terminated future at the start of its next batch and stops the engine with the cause (spec 03.01).
     - Special Check: If the exception contains `OffsetStorageWriter is already flushing`, the task immediately terminates because Debezium internal offset semaphore has leaked permanently.

#### Step 3.6: Offset Acknowledgement & Commit Serialization
In `DebeziumOffsetManagement.checkIfBatchCanBeCommitted()` and `acknowledgeRecords()`:
1. Looks up the written group's handoff unit by identity and counts the group as written; a unit split across several workers (hash routing) is complete only when every group is written.
2. A complete unit is acknowledged only if its handoff sequence is the head of the outstanding set (the oldest unacknowledged unit); otherwise it is parked in `completedUnits`.
3. Acknowledging a unit: acquires `synchronized (OFFSET_COMMIT_LOCK)` and iterates over the unit's records in binlog order:
   - Invokes `recordCommitter.markProcessed(record.getSourceRecord())`.
   - On the unit's terminal record, invokes `recordCommitter.markBatchFinished()` (once per unit).
4. Drains `completedUnits` in sequence order while the head of the outstanding set is complete. Commit order is therefore handoff (binlog) order, never wall-clock timestamp order.

---

## 4. Method-by-Method Operation Mapping

The following catalog specifies the behavioral contract, synchronization boundaries, and state mutations of the critical operational methods.

### 4.1 `DebeziumChangeEventCapture`

#### `public void setup(Properties props, DebeziumRecordParserService debeziumRecordParserService, boolean forceStart)`
- **Purpose**: Initializes and starts the embedded Debezium replication runtime.
- **Inputs**: `props` (configuration properties), `debeziumRecordParserService` (record parser), `forceStart` (flag).
- **Outputs**: None.
- **Preconditions**: ClickHouse database credentials must be accessible; driver must be loadable.
- **Locks**: Thread-confined during startup.
- **Mutations**: Instantiates `records` queue; creates `ClickHouseBatchExecutor`; registers Debezium callbacks; starts background engine thread.
- **Failure Modes**: Throws `ClassNotFoundException` or `IOException`; retries startup up to `MAX_RETRIES` (10) with 10s backoff.

#### `void handleChangeEventBatch(List[ChangeEvent] list, RecordCommitter committer, Properties props, DebeziumRecordParserService parser, ClickHouseSinkConnectorConfig config)`
- **Purpose**: Central CDC dispatch loop invoked by Debezium `ChangeConsumer`.
- **Inputs**: List of raw change events, batch record committer, configuration.
- **Outputs**: None.
- **Preconditions**: Debezium engine is running and emitting batches.
- **Locks**: Invoked on Debezium event thread. Acquires monitor locks in `nextSequenceNumber` and `drainBeforeDDL`.
- **Mutations**: Flushes pre-DDL rows via `appendToRecords`; triggers `drainBeforeDDL()` on DDL events; tracks `lastControlRecord`; pushes DML batches to `records` queue; invokes `commitControlRecordOffset()`.
- **Failure Modes**: Throws `InterruptedException` if interrupted during queue handoff.

#### `static synchronized long nextSequenceNumber(long recordTs, SourcePosition position)`
- **Purpose**: Generates a strictly monotonic 64-bit version for `ReplacingMergeTree`.
- **Inputs**: `recordTs` (`source.ts_ms`), `position` (`SourcePosition`).
- **Outputs**: `long` version (`effectiveTs * 1_000_000L + sequenceNumber`).
- **Locks**: `synchronized (DebeziumChangeEventCapture.class)`.
- **Mutations**: Updates `sequenceAnchorTs`, `sequenceNumber`, `sequenceHighWaterPosition`, and `sequenceMaxSourceTs`.
- **Failure Modes**: Arithmetic overflow if sequence exceeds 6 digits within 1 second.

#### `private void drainBeforeDDL()`
- **Purpose**: Enforces pipeline quiescence before executing a DDL statement to prevent schema inversion.
- **Inputs**: None.
- **Outputs**: None.
- **Preconditions**: Called on Debezium event thread upon encountering a DDL record.
- **Locks**: Calls `executor.pause()` (acquires `gate`) and `executor.awaitQuiescent()` (acquires `gate`).
- **Mutations**: Transitions executor state to paused; waits for in-flight tasks to terminate.
- **Failure Modes**: Throws `IllegalStateException` if `records` queue does not empty within 60s or executor fails to quiesce.

#### `boolean commitControlRecordOffset(ChangeEvent controlRecord, RecordCommitter committer, boolean handedOffRows)`
- **Purpose**: Commits the offset of non-DML control records (heartbeats) when the pipeline is idle.
- **Inputs**: `controlRecord`, `committer`, `handedOffRows` (flag if current batch produced DML rows).
- **Outputs**: `boolean` (true if committed).
- **Preconditions**: Pipeline must be quiescent (`isPipelineQuiescent() == true`) and `handedOffRows == false`.
- **Locks**: Acquires `OFFSET_COMMIT_LOCK` via `DebeziumOffsetManagement.acknowledgeRecords`.
- **Mutations**: Advances stored offset in `replica_source_info`.
- **Failure Modes**: Returns false without committing if any rows or batches are in flight.

---

### 4.2 `ClickHouseBatchExecutor`

#### `public void pause()`
- **Purpose**: Signals executor threads to park before starting new tasks.
- **Inputs**: None.
- **Outputs**: None.
- **Locks**: `synchronized (gate)`.
- **Mutations**: Sets `isPaused = true`; calls `gate.notifyAll()`.

#### `public void beforeExecute(Thread t, Runnable r)`
- **Purpose**: Hook executed by worker threads before running a task body.
- **Inputs**: Worker thread `t`, task `r`.
- **Outputs**: None.
- **Locks**: `synchronized (gate)`.
- **Mutations**: While `isPaused` is true, calls `gate.wait(100)`. Increments `activeBatches`.

#### `public void afterExecute(Runnable r, Throwable t)`
- **Purpose**: Hook executed by worker threads immediately after task completion.
- **Inputs**: Task `r`, exception `t`.
- **Outputs**: None.
- **Locks**: `synchronized (gate)`.
- **Mutations**: Decrements `activeBatches`; calls `gate.notifyAll()`.

#### `public boolean awaitQuiescent(long timeoutMs)`
- **Purpose**: Waits until all active worker tasks have exited their task bodies.
- **Inputs**: `timeoutMs` (maximum wait time).
- **Outputs**: `boolean` (true if `activeBatches == 0`).
- **Locks**: `synchronized (gate)`.
- **Mutations**: Calls `gate.wait()` until `activeBatches.get() == 0` or timeout elapses.

#### `public void resume()`
- **Purpose**: Unparks worker threads and re-enables task execution.
- **Inputs**: None.
- **Outputs**: None.
- **Locks**: `synchronized (gate)`.
- **Mutations**: Sets `isPaused = false`; calls `gate.notifyAll()`.

---

### 4.3 `GroupInsertQueryWithBatchRecords`

#### `public void groupQueryWithRecords(List[ClickHouseStruct] records, Map[QueryTemplate, List[ClickHouseStruct]] queryToRecordsMap, Map[TopicPartition, Long] partitionToOffsetMap, ClickHouseSinkConnectorConfig config, String tableName, String databaseName, Connection connection, Map[String, String] columnNameToDataTypeMap)`
- **Purpose**: Deconstructs record batches into parameterized SQL query templates and associated record buckets.
- **Inputs**: Input records, target query-to-record map, offset tracker, config, table/db names, JDBC connection, cached column types.
- **Outputs**: None. Every record is grouped or the method throws; there is no per-record skip and no boolean status (Spec 04.01 §3.3).
- **Locks**: None (thread-confined to worker).
- **Mutations**: Updates `queryToRecordsMap` and `partitionToOffsetMap`; may alter table schema or update cache via `refreshIfRecordHasUnknownColumn`.
- **Failure Modes**: Throws `StaleSchemaCacheException` if record and ClickHouse schema conflict irreconcilably; `MissingTargetColumnException` when a source column cannot be stored; `IllegalStateException` when a record carries no image for its operation, no column metadata is available, or no template can be built.

#### `private Map[String, String] refreshIfRecordHasUnknownColumn(ClickHouseStruct record, Map[String, String] cached, String tableName, String databaseName, Connection connection, ClickHouseSinkConnectorConfig config)`
- **Purpose**: Detects when incoming records carry columns missing from the local metadata cache; resolves staleness or enforces column writability.
- **Inputs**: Record, cached column map, table/database names, connection, config.
- **Outputs**: Updated column map, or null if cache is valid.
- **Locks**: Reads/writes `CacheInvalidationManager` concurrent maps.
- **Mutations**: Executes `system.columns` queries; executes `ALTER TABLE` if `MATERIALIZED` column needs conversion to `DEFAULT`; marks proven absent columns.
- **Failure Modes**: Returns null on JDBC query failure, falling back to cached map.

#### `private boolean enforceSourceColumnIsWritable(String columnName, String tableName, String databaseName, String fullyQualifiedTableName, Connection connection, ClickHouseSinkConnectorConfig config)`
- **Purpose**: Enforces source-of-truth authority by converting ClickHouse `MATERIALIZED` columns to writable `DEFAULT` columns when the source database supplies values for them.
- **Inputs**: Column name, table name, database name, fully qualified name, connection, config.
- **Outputs**: `boolean` (true if column was successfully converted and is now writable).
- **Locks**: None.
- **Mutations**: Queries `default_kind` and `default_expression` from `system.columns`. Emits `ALTER TABLE db.table MODIFY COLUMN col type DEFAULT (expr)` to ClickHouse.
- **Failure Modes**: Returns false if column is `ALIAS`, expression cannot be read, or DDL execution fails.

---

### 4.4 `PreparedStatementFieldMapper`

#### `public void insertPreparedStatement(Map[String, Integer] columnNameToIndexMap, PreparedStatement ps, List[Field] fields, ClickHouseStruct record, Struct struct, boolean beforeSection, ClickHouseSinkConnectorConfig config, Map[String, String] columnNameToDataTypeMap, DBMetadata.TABLE_ENGINE engine, String tableName)`
- **Purpose**: Binds values from a CDC struct to the positional parameters of a JDBC `PreparedStatement`.
- **Inputs**: Column index map, statement `ps`, schema fields, CDC record, data struct, `beforeSection` flag, config, column types, engine type, table name.
- **Outputs**: None.
- **Preconditions**: `ps` must be initialized with placeholders matching `columnNameToIndexMap`.
- **Locks**: None (thread-confined).
- **Mutations**: Sets typed values on `ps` via `setObject()`, `setString()`, `setInt()`, `setNull()`.
- **Failure Modes**: Throws `StaleSchemaCacheException` if a column exists in ClickHouse and the record but has no index placeholder; throws `SQLException` on type binding error.

#### `public void insertTombstonePreparedStatement(Map[String, Integer] columnNameToIndexMap, PreparedStatement ps, List[Field] fields, ClickHouseStruct record, Struct struct, ClickHouseSinkConnectorConfig config, Map[String, String] columnNameToDataTypeMap, DBMetadata.TABLE_ENGINE engine, String tableName)`
- **Purpose**: Binds an explicit `ReplacingMergeTree` delete tombstone row for an UPDATE that changes the row sorting key.
- **Inputs**: Column index map, statement `ps`, before-image fields, record, before struct, config, column types, engine, table name.
- **Outputs**: None.
- **Mutations**: Calls `insertPreparedStatement` with `beforeSection = true`. Overrides delete column to `1` (or `-1`) and binds the version column to `record.getVersion()` unchanged.

---

### 4.5 `DebeziumOffsetManagement`

#### `public static synchronized boolean checkIfBatchCanBeCommitted(List[ClickHouseStruct] batch)`
- **Purpose**: Enforces FIFO in-order offset commitment across concurrently executing worker threads.
- **Inputs**: Batch of records that completed JDBC execution.
- **Outputs**: `boolean` (true if the batch's unit was acknowledged during this call; false if it is still incomplete or parked behind an older unit). The caller drops the batch either way -- a written batch is never re-executed.
- **Locks**: `synchronized (DebeziumOffsetManagement.class)`.
- **Mutations**: Removes the group from `groupToUnit`; when the unit is complete, puts it in `completedUnits`, then acknowledges from the head of `outstandingSequences` while the head is complete (`acknowledgeRecords(unit.records)` in binlog order, removing the sequence from both collections). A committer-bearing batch that was never registered throws `IllegalStateException`.

#### `public static void acknowledgeRecord(RecordCommitter committer, SourceRecord record, boolean isLastInBatch)`
- **Purpose**: Thread-safe entry point for committing an individual record offset through Debezium.
- **Inputs**: Debezium `committer`, Kafka `record`, `isLastInBatch` flag.
- **Outputs**: None.
- **Locks**: `synchronized (OFFSET_COMMIT_LOCK)`.
- **Mutations**: Invokes `committer.markProcessed(record)`. If `isLastInBatch` is true, invokes `committer.markBatchFinished()`.
- **Failure Modes**: Throws `RuntimeException` on `InterruptedException`.

---

## 5. Architectural Critique: Incoherencies & Complexity Traps

A rigorous analysis of the 2.11.0 codebase reveals that recurring operational bugs (stuck offsets, silent data divergence, snapshot loops, metadata query storms, and DDL drain deadlocks) are not isolated defects, but direct consequences of fundamental architectural mismatches.

### 5.1 Impedance Mismatch: Relational In-Place Mutation vs. Columnar Append-Only Storage
- **The Core Conflict**: Relational CDC engines assume mutable, in-place storage with transactional read isolation. ClickHouse is an append-only columnar database where row updates and deletions do not exist at the storage layer; instead, mutations are simulated via merge-tree engines (`ReplacingMergeTree`, `CollapsingMergeTree`).
- **The Resulting Complexity**:
  - The connector must synthesize artificial columns (`_version`, `_sign`, `is_deleted`) and insert tombstone rows to represent deletes.
  - When an UPDATE modifies any column belonging to the ClickHouse `ORDER BY` sorting key, ClickHouse `ReplacingMergeTree` cannot replace the old row (since the sorting key differs). The connector is forced to synthesize an artificial tombstone for the old key and an insert for the new key.
  - Queries against the replica observe duplicate and un-deleted rows unless `FINAL` or `argMax` is explicitly queried, shifting the burden of consistency onto the query layer.

### 5.2 The Arithmetic Version Bleed & Restart Inversion Defect
- **The Formula**: `_version = ts_ms * 1_000_000L + sequenceNumber`.
- **The Structural Flaw**:
  - `ts_ms` is multiplied by 10^6, allocating 6 decimal digits for `sequenceNumber`.
  - However, `SEQUENCE_START` is initialized to `1_000_000_000` (10 decimal digits), and on restart `SEQUENCE_START_INITIAL` is set to `500_000_000` (9 decimal digits).
  - The addition of a 9- or 10-digit sequence number **bleeds directly into the timestamp digits**, shifting the effective timestamp by approximately 500 to 1000 seconds.
- **The Restart Inversion Failure Mode**:
  - When the connector restarts, `SEQUENCE_START_INITIAL` (500m) is chosen so that replayed records receive lower versions than their pre-restart writes (seeded at 1000m).
  - However, if a *genuinely newer* transaction commits at T + 1 ms immediately after restart, its version calculation produces:
    (T + 1) * 1e6 + 500,000,000 is strictly less than T * 1e6 + 1,000,000,000
  - The newer event receives a **strictly lower version** than the pre-restart event for the same key. ClickHouse `ReplacingMergeTree` permanently discards the newer write, causing silent data loss.

### 5.3 Global Static State Anti-Pattern & Multi-Tenancy Hazards
- Critical coordination state across the codebase is held in static variables:
  - `DebeziumChangeEventCapture.sequenceNumber`, `sequenceAnchorTs`, `sequenceHighWaterPosition`, `sequenceMaxSourceTs`.
  - `DebeziumOffsetManagement.handoffCounter`, `outstandingSequences`, `groupToUnit`, `completedUnits`, `OFFSET_COMMIT_LOCK`.
  - `CacheInvalidationManager.INSTANCE`.
- **Consequences**:
  - Multiple connectors or tasks running within the same JVM cross-contaminate state.
  - Thread pools share singletons across independent pipelines, leading to lock contention and corrupted high-water marks.

### 5.4 DDL Synchronization Fragility & Thread Pool Deadlocks
- **The Issue**: Synchronizing synchronous DDL events across asynchronous worker threads via `drainBeforeDDL()` is inherently fragile.
- **Historical Deadlock**: Pausing the executor before draining the queue caused worker threads to park in `beforeExecute()`, preventing any dequeuing and guaranteeing a 60s timeout abort that discarded in-flight batches.
- **Current Vulnerability**: The drain loop relies on a fixed 60,000ms deadline. Under heavy ClickHouse write pressure, batch inserts take longer to flush, causing `drainBeforeDDL()` to time out and abort the DDL, triggering continuous restart loops.

### 5.5 Metadata Cache Storms & Tri-Partite Schema Engines
- The repository maintains three separate, disconnected schema evolution engines:
  1. MySQL ANTLR DDL parser (`MySqlDDLParserService`) in lightweight mode.
  2. PostgreSQL schema drift detector (`PostgresSchemaChangeDetector`) in lightweight mode.
  3. Kafka Connect `ClickHouseAlterTable` in Kafka mode.
- In lightweight mode, `refreshIfRecordHasUnknownColumn` was previously unable to distinguish between a stale schema cache and ClickHouse-computed columns (`MATERIALIZED`/`ALIAS`). This drove an unbounded metadata loop (observed at 9.66 million `system.columns` queries/hour) that exhausted ClickHouse connection pools and stalled replication.

### 5.6 Offset Storage Semaphore Leaks & Masked Divergence
- In Debezium `EmbeddedEngine`, `commitOffsets()` leaks the `flushInProgress` semaphore if multiple threads call `markProcessed()` and `beginFlush()` concurrently without global mutual exclusion.
- When this occurred, `ClickHouseErrorClassifier` categorized the resulting `ConnectException: OffsetStorageWriter is already flushing` as an `UNKNOWN` retriable error. With infinite retries, the connector continued inserting data into ClickHouse while offsets remained frozen, allowing replication to drift hours behind without failing loudly.

### 5.7 Code Duplication & Divergence
- `ClickHouseBatchWriter` (single-threaded mode) and `ClickHouseBatchRunnable` (multi-threaded mode) duplicate approximately 1,000 lines of nearly identical pipeline logic (`getDbWriterForTable`, `processRecordsByTopic`, `flushRecordsToClickHouse`).
- Bug fixes applied to one path (such as lock serialization or timezone handling) have repeatedly failed to be mirrored in the other, creating subtle behavioural divergences.
- Hash-based routing (`appendToRecordsWithHashRouting`) remains partially implemented but hardcoded to `useHashRouting = false` in `ClickHouseBatchRunnable`, leaving dead code with busy-spin loops (`Thread.sleep(10)`).

---

## 6. Blueprint for Architectural Simplification

To transition the sink connector from a reactive, bug-patching posture into a robust, high-performance replication engine, the following architectural redesign is recommended at the specification level:

```
+----------------------------------------------------------------------------------------------------+
|                                    PROPOSED TARGET ARCHITECTURE                                    |
+----------------------------------------------------------------------------------------------------+
|                                                                                                    |
|   +--------------------------------------------------------------------------------------------+   |
|   | 1. DETERMINISTIC LOG-BASED VERSIONING                                                      |   |
|   |    - Replace synthetic timestamp arithmetic with 64-bit monotonic log position:            |   |
|   |      UInt64 = bitShiftLeft(BinlogFileSeq, 32) | BinlogOffset  (MySQL)                       |   |
|   |      UInt64 = LSN (PostgreSQL)                                                             |   |
|   |    - Zero arithmetic carry; zero timestamp drift; 100% deterministic across restarts.       |   |
|   +--------------------------------------------------------------------------------------------+   |
|                                                                                                    |
|   +--------------------------------------------------------------------------------------------+   |
|   | 2. BARRIER-BASED DDL SYNCHRONIZATION                                                       |   |
|   |    - Eliminate thread pausing and timeout-based drain loops.                               |   |
|   |    - Insert a special DdlBarrierRecord into the internal worker queue.                     |   |
|   |    - Workers process all preceding batches; the barrier executes DDL exclusively once     |   |
|   |      preceding batches complete, then resumes normal flow. Deadlock-free by construction.  |   |
|   +--------------------------------------------------------------------------------------------+   |
|                                                                                                    |
|   +--------------------------------------------------------------------------------------------+   |
|   | 3. UNIFIED INGESTION ENGINE & REMOVAL OF DUPLICATION                                       |   |
|   |    - Eliminate ClickHouseBatchWriter; converge all execution onto ClickHouseBatchRunnable.  |   |
|   |    - Unify Kafka Connect and Lightweight modes to share an identical batching core.        |   |
|   |    - Remove all static mutable singletons; encapsulate state inside task-scoped contexts.  |   |
|   +--------------------------------------------------------------------------------------------+   |
|                                                                                                    |
|   +--------------------------------------------------------------------------------------------+   |
|   | 4. STRICT SOURCE-OF-TRUTH SCHEMA SYNCHRONIZATION                                           |   |
|   |    - Consolidate MySQL and PostgreSQL schema evolution into a single Catalog Manager.       |   |
|   |    - Maintain an explicit column contract: ordinary columns mirror source; ALIAS is        |   |
|   |      ignored; MATERIALIZED is strictly prohibited or converted to DEFAULT on startup.       |   |
|   |    - Hard-fail startup if a replicated table lacks a primary/unique key (GIPK mandatory).  |   |
|   +--------------------------------------------------------------------------------------------+   |
|                                                                                                    |
+----------------------------------------------------------------------------------------------------+
```

### Conclusion
By mapping the system to the tee, this specification clarifies every critical path in the 2.11.0 release. The systemic bugs observed in production—offset stalls, version inversions, DDL deadlocks, and metadata storms—stem from shared mutable state, arithmetic version encoding, and decoupled asynchronous DDL execution. Addressing these issues via deterministic log-based versioning, barrier-based DDL synchronization, and pipeline unification will provide a stable, coherent foundation for all future development.

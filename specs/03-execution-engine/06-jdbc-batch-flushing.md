# Spec 03.06: PreparedStatement Batch Flushing & Limits

## 1. Executive Summary & Purpose
Specifies the accumulation, chunking, and JDBC `executeBatch()` dispatching of parameterized SQL statements into ClickHouse.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementExecutor.java`
- **Methods**: `public boolean addToPreparedStatementBatch(String topicName, Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> queryToRecordsMap, BlockMetaData bmd, ClickHouseSinkConnectorConfig config, Connection conn, String tableName, Map<String, String> columnToDataTypeMap, DBMetadata.TABLE_ENGINE engine)` and the private `executePreparedStatement(...)` it calls per query template (there is no `insertBatch` method)
- **Scheduling**: `DebeziumChangeEventCapture.setupProcessingThread` schedules each `ClickHouseBatchRunnable` with `scheduleAtFixedRate(..., 0, buffer.flush.time.ms, MILLISECONDS)`
- **Defaults** (`sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/ClickHouseSinkConnectorConfig.java`): `DEFAULT_BUFFER_FLUSH_TIME = 30L` for key `buffer.flush.time.ms` (`BUFFER_FLUSH_TIME`), `DEFAULT_BUFFER_MAX_RECORDS = 100000L` for key `buffer.max.records` (`BUFFER_MAX_RECORDS`)

---

## 3. Operational Specification

### 3.1 Flush cadence and chunk size
1. **Time cadence (`buffer.flush.time.ms`, default 30)**: each `ClickHouseBatchRunnable` runs at that fixed rate and drains whatever batches are queued for it; there is no separate "batch age" timer.
2. **Chunk size (`buffer.max.records`, default 100000)**: inside `executePreparedStatement`, the records sharing one query template are split with `Lists.partition(records, buffer.max.records)`; each partition is bound into one `PreparedStatement` and executed with a single `executeBatch()`.

### 3.2 JDBC Batch Execution
For each partition:
- a `PreparedStatement` is obtained from `DBMetadata.getPreparedStatement(conn, insertQuery)`;
- rows are bound by `PreparedStatementFieldMapper.insertPreparedStatement` (before/after image per operation), a sorting-key relocation additionally binds a tombstone (`insertTombstonePreparedStatement`, spec 05.02), each followed by `ps.addBatch()`;
- a TRUNCATE record flushes the rows staged so far and truncates in place (spec 04.05);
- `int[] batchResult = ps.executeBatch()` sends the partition.
A failure inside the partition is rethrown as `RuntimeException` from `executePreparedStatement`; the caller (`ClickHouseBatchRunnable`) classifies it via `ClickHouseErrorClassifier` (spec 10.01) — the executor itself does not classify or retry.

---

## 4. Invariants Preserved
- **Invariant I3 (Eventual Convergence)**: every partition carries `_version` and the delete flag for every row, so replays and re-orderings converge under `ReplacingMergeTree`.

---

## 5. Verification Criteria
- `PreparedStatementExecutorSortingKeyTombstoneTest` — the per-record tombstone decision inside the batch loop.
- `TruncateTableIT.testRowsInsertedAfterTruncateSurvive()` — in-place TRUNCATE flush ordering within one batch.
- Verification: chunking at `buffer.max.records` (e.g. 50,000 rows split into partitions) and the `buffer.flush.time.ms` cadence are not yet covered by an automated test (gap).

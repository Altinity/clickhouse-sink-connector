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
- a `PreparedStatement` is obtained from `DBMetadata.getPreparedStatement(conn, insertQuery)` (which throws `SQLException` after `MAX_RETRIES` refused attempts rather than returning `null`, spec 04.05 §3 step 2);
- rows are bound by `PreparedStatementFieldMapper.insertPreparedStatement` (before/after image per operation), a sorting-key relocation additionally binds a tombstone (`insertTombstonePreparedStatement`, spec 05.02), each followed by `ps.addBatch()` **and then `ps.clearParameters()`** — the V2 driver's `addBatch()` does not clear its bound values, so without the clear a parameter one row failed to bind silently carried the previous row's value (Spec 07.07 §3.2.2);
- a replicated TRUNCATE never appears inside a template's record list: it is a segment of its own, executed between the segments around it (spec 04.05 §3), so every partition of an earlier segment has been sent before it runs;
- `int[] batchResult = ps.executeBatch()` sends the partition.
A failure inside the partition is rethrown as `RuntimeException` from `executePreparedStatement`; the caller (`ClickHouseBatchRunnable`) classifies it via `ClickHouseErrorClassifier` (spec 10.01) — the executor itself does not classify or retry.

### 3.3 Per-batch progress lines are DEBUG
A successful batch is the steady state of the connector, and the worker pool
flushes one every `buffer.flush.time.ms` per worker. Four lines used to be
written at INFO for every one of them:
1. `****** Thread: <worker> Batch Size: <n> ******` on pick-up
   (`ClickHouseBatchRunnable.processBatch`, and `ClickHouseBatchWriter.persistRecords`
   in single-threaded mode);
2. `*** INSERT QUERY for Database(<db>) ***: <full INSERT template>` per query
   template (`PreparedStatementExecutor.addToPreparedStatementBatch`);
3. `*************** EXECUTED BATCH Successfully Records: <n> ...` per
   partition (`PreparedStatementExecutor.executePreparedStatement`);
4. `***** BATCH marked as processed to debezium **** Binlog file: ...` per
   acknowledged unit (`DebeziumOffsetManagement.acknowledgeRecords`).

On a deployment with 10 workers and a moderately busy source that was
~2,600 lines a minute, 97% of the log: 184,933 of 401,070 lines in one
rotation were lines 1–3 alone, and the INSERT template — every column of
every table, repeated per batch — was most of the bytes. The log rotated
every ~2 h, the messages that matter (an engine restart, a saturated column,
a DDL) scrolled out of the retained history within hours, and the WARN-filtered
error log was the only place a problem could still be found.

Rule: the four lines are logged at **DEBUG**. They keep their text, so an
operator who needs the per-batch trace enables it with the logger level
(`com.altinity.clickhouse` at DEBUG) and gets exactly the lines the connector
always wrote. Nothing about a FAILED batch changes: the retriable WARN, the
FATAL error, the dead-worker engine stop (spec 03.01 §3.3, spec 10.04) stay
at their levels. Progress is observable without the lines: the offset table
(spec 09.03), `/status`, and the metrics endpoint carry the position, the lag
and the counts.

---

## 4. Invariants Preserved
- **Invariant I3 (Eventual Convergence)**: every partition carries `_version` and the delete flag for every row, so replays and re-orderings converge under `ReplacingMergeTree`.

---

## 5. Verification Criteria
- `PreparedStatementExecutorSortingKeyTombstoneTest` — the per-record tombstone decision inside the batch loop.
- `PreparedStatementExecutorClearParametersTest.parametersAreClearedAfterEveryAddBatch()` — through `addToPreparedStatementBatch` with a recording connection: for a two-row batch the statement receives `addBatch` twice and `clearParameters` once after each `addBatch` (pre-fix code never calls `clearParameters`).
- `PreparedStatementExecutorTruncateTest.truncateIsAppliedAtItsBinlogPositionForBothHashOrders()`, `TruncateTableIT.testRowsInsertedAfterTruncateSurvive()` — TRUNCATE ordering within one batch.
- `PreparedStatementExecutorBatchLogLevelTest.successfulBatchLogsItsProgressAtDebugOnly()` — §3.3 lines 2 and 3: a successful two-row batch through `addToPreparedStatementBatch` with a recording connection writes the INSERT-template line and the EXECUTED-BATCH line at DEBUG and nothing at INFO or above (pre-fix: both at INFO).
- `DebeziumOffsetManagementTest.acknowledgementIsLoggedAtDebug()` — §3.3 line 4: acknowledging a unit through `acknowledgeRecords` writes the "BATCH marked as processed" line at DEBUG and nothing at INFO or above (pre-fix: INFO).
- §3.3 line 1 (`ClickHouseBatchRunnable.processBatch`, `ClickHouseBatchWriter.persistRecords`) has no unit harness that reaches the pick-up line without a live ClickHouse (both paths resolve the destination through `DBMetadata` on a real connection); covered by review of the two call sites.
- Verification: chunking at `buffer.max.records` (e.g. 50,000 rows split into partitions) and the `buffer.flush.time.ms` cadence are not yet covered by an automated test (gap).

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

### 3.3 Per-batch progress lines are INFO, by design
A successful batch is the steady state of the connector, and the worker pool
flushes one every `buffer.flush.time.ms` per worker. Four lines are written
at INFO for every one of them:
1. `****** Thread: <worker> Batch Size: <n> ******` on pick-up
   (`ClickHouseBatchRunnable.processBatch`, and `ClickHouseBatchWriter.persistRecords`
   in single-threaded mode);
2. `*** INSERT QUERY for Database(<db>) ***: <full INSERT template>` per query
   template (`PreparedStatementExecutor.addToPreparedStatementBatch`);
3. `*************** EXECUTED BATCH Successfully Records: <n> ... Result: <k> statements acknowledged ...`
   per partition (`PreparedStatementExecutor.executePreparedStatement`) — `k`
   is the length of the `int[]` that `executeBatch()` returned, one entry per
   staged statement (every row, plus one tombstone per sorting-key
   relocation), followed by `, <f> marked EXECUTE_FAILED` only when the
   driver flagged any entry (`PreparedStatementExecutor.describeBatchResult`).
   An earlier revision printed the array itself, which `int[].toString()`
   renders as an identity hash (`[I@6cee4818`): a field that changed on every
   line and carried no information;
4. `***** BATCH marked as processed to debezium **** Binlog file: ...` per
   acknowledged unit (`DebeziumOffsetManagement.acknowledgeRecords`).

Rule: the four lines are logged at **INFO** by design. They are how an
operator sees, from the log alone, that the connector is progressing — which
batch was picked up, which INSERT ran, that it executed, and that its offset
was acknowledged to Debezium — and that is the first thing a person tails
when a connector is suspected of being stuck. A revision of this spec moved
them to DEBUG because on a deployment with 10 workers they were ~2,600 lines
a minute (97% of the log); the operators reversed that: the progress trace
is wanted at the default level, and the log's retention is sized for it
(size- and time-based rotation, spec-external). The steady-state noise that
had to go was elsewhere — the per-row saturation WARN (spec 07.03 §3.3 rule
2, now DEBUG only) and the first-attempt "Retrying" line of the database
probe (spec 08.03 §3.3) — not the progress lines. Nothing about a FAILED
batch changes: the retriable WARN, the FATAL error, the dead-worker engine
stop (spec 03.01 §3.3, spec 10.04) stay at their levels. The offset table
(spec 09.03), `/status` and the metrics endpoint carry the same position, lag
and counts for tooling.

---

## 4. Invariants Preserved
- **Invariant I3 (Eventual Convergence)**: every partition carries `_version` and the delete flag for every row, so replays and re-orderings converge under `ReplacingMergeTree`.

---

## 5. Verification Criteria
- `PreparedStatementExecutorSortingKeyTombstoneTest` — the per-record tombstone decision inside the batch loop.
- `PreparedStatementExecutorClearParametersTest.parametersAreClearedAfterEveryAddBatch()` — through `addToPreparedStatementBatch` with a recording connection: for a two-row batch the statement receives `addBatch` twice and `clearParameters` once after each `addBatch` (pre-fix code never calls `clearParameters`).
- `PreparedStatementExecutorTruncateTest.truncateIsAppliedAtItsBinlogPositionForBothHashOrders()`, `TruncateTableIT.testRowsInsertedAfterTruncateSurvive()` — TRUNCATE ordering within one batch.
- `PreparedStatementExecutorBatchLogLevelTest.successfulBatchLogsItsProgressAtInfo()` — §3.3 lines 2 and 3: a successful two-row batch through `addToPreparedStatementBatch` with a recording connection, logger at the default INFO level, writes the INSERT-template line and the EXECUTED-BATCH line at INFO and nothing at WARN or above (the DEBUG revision writes neither at INFO, so the test fails on it); the EXECUTED-BATCH line carries `Result: 2 statements acknowledged` and no `[I@` identity (the `toString()` revision fails here).
- `PreparedStatementExecutorBatchLogLevelTest.batchResultDescriptionCountsAcknowledgedAndFailedStatements()` — §3.3 line 3 `Result` field: the count of acknowledged statements, the `, <f> marked EXECUTE_FAILED` suffix only when the driver flagged an entry, and `no result` for a null array.
- `DebeziumOffsetManagementTest.acknowledgementIsLoggedAtInfo()` — §3.3 line 4: acknowledging a unit through `acknowledgeRecords`, logger at INFO, writes the "BATCH marked as processed" line at INFO and nothing at WARN or above (fails on the DEBUG revision).
- §3.3 line 1 (`ClickHouseBatchRunnable.processBatch`, `ClickHouseBatchWriter.persistRecords`) has no unit harness that reaches the pick-up line without a live ClickHouse (both paths resolve the destination through `DBMetadata` on a real connection); covered by review of the two call sites.
- Verification: chunking at `buffer.max.records` (e.g. 50,000 rows split into partitions) and the `buffer.flush.time.ms` cadence are not yet covered by an automated test (gap).

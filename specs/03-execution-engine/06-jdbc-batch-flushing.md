# Spec 03.06: PreparedStatement Batch Flushing & Limits

## 1. Executive Summary & Purpose
Specifies the accumulation, chunking, and JDBC `executeBatch()` dispatching of parameterized SQL statements into ClickHouse.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementExecutor.java`
- **Methods**: `public boolean addToPreparedStatementBatch(String topicName, Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> queryToRecordsMap, BlockMetaData bmd, ClickHouseSinkConnectorConfig config, Connection conn, String tableName, Map<String, String> columnToDataTypeMap, DBMetadata.TABLE_ENGINE engine)` and the private `executePreparedStatement(...)` it calls per query template (there is no `insertBatch` method)
- **Scheduling**: `DebeziumChangeEventCapture.setupProcessingThread` schedules each `ClickHouseBatchRunnable` with `scheduleAtFixedRate(..., 0, buffer.flush.time.ms, MILLISECONDS)`
- **Defaults** (`sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/ClickHouseSinkConnectorConfig.java`): `DEFAULT_BUFFER_FLUSH_TIME = 30L` for key `buffer.flush.time.ms` (`BUFFER_FLUSH_TIME`), `DEFAULT_BUFFER_MAX_RECORDS = 100000L` for key `buffer.max.records` (`BUFFER_MAX_RECORDS`), `DEFAULT_BUFFER_MAX_BYTES = 256 MiB` for key `buffer.max.bytes` (`BUFFER_MAX_BYTES`); chunking in `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/BatchChunker.java`

---

## 3. Operational Specification

### 3.1 Flush cadence and chunk size
1. **Time cadence (`buffer.flush.time.ms`, default 30)**: each `ClickHouseBatchRunnable` runs at that fixed rate and drains whatever batches are queued for it; there is no separate "batch age" timer. In routing mode the batches queued behind the one it polled are coalesced into the same write, up to the two limits of item 2 (spec 03.03 §3.1.1), so a worker that is behind issues one INSERT per template per `buffer.max.records` rows rather than one per Debezium poll.
2. **Chunk size, in rows AND in estimated bytes (`buffer.max.records`, default 100000; `buffer.max.bytes`, default 256 MiB)**: inside `executePreparedStatement`, the records sharing one query template are split by `BatchChunker.chunk(records, buffer.max.records, buffer.max.bytes)`; each chunk is bound into one `PreparedStatement` and executed with a single `executeBatch()`. The byte limit exists because the V2 driver renders a whole chunk as SQL text in memory before it is sent — the substituted statement per `addBatch()`, then one concatenated INSERT — once per worker thread; a chunk of ten thousand rows is tens of megabytes of text for a narrow table and tens of gigabytes for a table of megabyte BLOBs, so on a fixed heap the row count alone bounds nothing. Each row carries the estimate stamped at handoff (`ClickHouseStruct.estimatedBytes`, spec 01.05 §3.4 item 7). Rules: a chunk closes when either limit would be exceeded by the next row; order is preserved; a row is never split; a chunk always holds at least one row, so a single row wider than the byte limit is written on its own rather than refused; `0` disables a limit; a row never stamped counts as zero bytes and obeys the row limit.

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
- `BatchChunkerTest` — §3.1 item 2: the row limit alone splits like a partition, order and identity preserved (`rowLimitAlone`); the byte limit closes a chunk before the row limit when the rows are wide (`byteLimitClosesEarly`); a single row wider than the byte limit is its own chunk, never dropped or split (`oversizedRowIsItsOwnChunk`); unstamped rows obey the row limit and both limits disabled yield one chunk (`unstampedRowsAndDisabledLimits`); empty input yields no chunks and a null row counts as zero bytes (`emptyAndNull`).
- Verification: the `buffer.flush.time.ms` cadence is not yet covered by an automated test (gap).

---

## 6. Failure Modes & Recovery

The executor sends and does not retry: every failure of `executeBatch()` is rethrown to the worker, which classifies it (spec 10.01) and either retries the whole batch (spec 03.03 §6) or stops (spec 03.01 §6). Its own recovery risks are a call that never returns, a failure after part of the batch was sent, and a driver result it does not check.

- **FM-03.06-1 An INSERT that never returns (no socket read timeout)**
  - **Trigger**: ClickHouse stops answering without closing the socket — host freeze, network partition without a RST, a stuck query, a firewall that drops a long-idle flow.
  - **Behaviour**: `client-v2` 0.9.8 defaults `socket_timeout` to 0 (no read timeout; `ClientConfigProperties` constant pool) and `BaseDbWriter.createConnection` sets none (only `client_name`, `custom_settings`, `http_connection_provider` and the user's `clickhouse.jdbc.params`). `ps.executeBatch()` in `PreparedStatementExecutor.executePreparedStatement` blocks inside the task body: no exception, no retry, no classification. The worker's queue and every offset behind it freeze; the DDL/TRUNCATE drains wait on a live future; on a busy source the handoff hard cap stops the engine after 600 s, but the engine retry keeps the pool with the hung thread, so the cap is met again — ~10 × 610 s before the terminal exit; on a quiet source, forever. Whether the kernel ends it (TCP keepalive is not enabled by the connector; `tcp_retries2` only bounds unacknowledged sends) is host configuration, not verified here.
  - **Detection**: none from the worker. The last line of that thread is `*** INSERT QUERY for Database(<db>) ***: ...` with no `EXECUTED BATCH Successfully` after it; indirectly WARN `Pipeline drain: ... still pending after <n> ms ...` or `DDL drain: a batch is still inside a worker after <n> ms ...` every 60 s when a DDL/TRUNCATE waits, and `Handoff hard cap: ...` 600 s after the cap is met on a busy source.
  - **Blast radius**: the whole connector's committed offset freezes; tables on other workers keep writing but are not acknowledged; no loss.
  - **Recovery**: restart the connector (kill it if it does not stop: `stop()` waits 60 s for the pool). Permanently: `clickhouse.jdbc.params=socket_timeout=<ms>` (accepted by the V2 driver per `BaseDbWriter.V1_ONLY_PROPERTIES`' note), larger than the longest legitimate INSERT, e.g. 600000.
  - **RTO**: unbounded by default; with `socket_timeout` set, that timeout + one backoff — unmeasured.
  - **Test**: GAP: a TCP endpoint that accepts and never answers, asserting the INSERT fails within the configured timeout and the batch is retried.
  - **DEFECT**: no timeout bounds a single INSERT, so a silent network or server hang stalls replication indefinitely without an error.

- **FM-03.06-2 A later chunk fails after earlier chunks were written**
  - **Trigger**: a template split into several chunks by `BatchChunker.chunk` (`buffer.max.records`, `buffer.max.bytes`), and chunk k fails (252, 241, a network error). Also a single INSERT that the server commits block by block (`max_insert_block_size`) and that fails mid-stream — ClickHouse insert semantics, not verified for the V2 driver.
  - **Behaviour**: each chunk is its own `executeBatch()`; the failure is rethrown as `RuntimeException` after chunks 1..k-1 returned, and nothing rolls them back. The worker retries the whole batch (spec 03.03 §6 FM-03.03-2).
  - **Detection**: ERROR `******* ERROR inserting Batch Database(<db>), Table(<t>) *****************` with the driver exception, then the worker's retry lines.
  - **Blast radius**: the earlier chunks are written again: idempotent under ReplacingMergeTree (same key, same `_version`); additive under CollapsingMergeTree and plain MergeTree (spec 05.04 §6 FM-05.04-1).
  - **Recovery**: none for ReplacingMergeTree; `ch-mysql-resync` (spec 11.04) of an additive-engine table.
  - **RTO**: the retry's (spec 03.03 §6); resync time for additive engines — unmeasured.
  - **Test**: `PreparedStatementExecutorFailureModesTest.aFailedChunkLeavesTheEarlierChunksWrittenAndFailsTheBatch()`.

- **FM-03.06-3 ClickHouse rejects the INSERT with a server error**
  - **Trigger**: 252 TOO_MANY_PARTS (parts above `parts_to_throw_insert`), 241 MEMORY_LIMIT_EXCEEDED, 242 TABLE_IS_READ_ONLY (a Replicated table whose Keeper session is lost), 164 READONLY (a read-only user profile), 53/16/60/81/497/516.
  - **Behaviour**: the executor rethrows; `ClickHouseErrorClassifier` files 252, 241, 242 and 164 as RETRIABLE (not in `FATAL_ERROR_CODES`) — retried forever (spec 03.03 §6 FM-03.03-1) — and the rest as FATAL (spec 03.01 §6 FM-03.01-2). 252 and 242 clear by themselves when merges catch up or Keeper returns; 241 does when concurrent pressure passes; 164, and 241 for one chunk above the query budget, never do.
  - **Detection**: ERROR `******* ERROR inserting Batch Database(<db>), Table(<t>) *****************`, then WARN `Retriable ClickHouse error (Code: <n>, Category: RETRIABLE) ...` or ERROR `FATAL ClickHouse error (Code: <n>) ...`; immediate.
  - **Blast radius**: the worker's tables and the connector's committed offset wait (retriable) or everything stops (FATAL); no loss.
  - **Recovery**: 252: spec 03.07 §7 FM-03.07-1; 242: restore Keeper — self-heals; 241: lower `buffer.max.records` / `buffer.max.bytes` or raise the user's memory limit; 164: fix the user profile; FATAL codes: fix and restart.
  - **RTO**: self-healing codes: cause + ≤ 30 s backoff; the others: operator-bound (spec 03.03 §6) — unmeasured.
  - **Test**: `ClickHouseErrorClassifierTest.testClassifyRetriable()`, `ClickHouseErrorClassifierTest.testClassifyFatal()`, `WorkerFailureModesTest.backpressureCodesAreRetriedWithTheBatchKeptUntilTheyClear()`; GAP: a classifier assertion for 242 and 164.

- **FM-03.06-4 A statement the driver marks `EXECUTE_FAILED` is treated as written**
  - **Trigger**: `executeBatch()` returns an `int[]` containing `Statement.EXECUTE_FAILED` without throwing. Whether `jdbc-v2` 0.9.8 ever does so is not verified; the JDBC contract allows it.
  - **Behaviour**: `executePreparedStatement` only counts the entry into the INFO line (`describeBatchResult`) and sets the result true; the worker reports the batch written and its offset is acknowledged.
  - **Detection**: only the suffix `, <f> marked EXECUTE_FAILED` on the INFO line `*************** EXECUTED BATCH Successfully Records: <n> ...`.
  - **Blast radius**: the flagged rows may be missing in ClickHouse while the offset moves past them — silent loss.
  - **Recovery**: value-level checksum (spec 11.02) to find the tables, `ch-mysql-resync` (spec 11.04).
  - **RTO**: resync time — unmeasured.
  - **Test**: `PreparedStatementExecutorFailureModesTest.anExecuteFailedEntryFailsTheBatch()` (disabled, fails on 2.11.0).
  - **DEFECT**: a driver-reported statement failure must fail the batch, not be logged at INFO and acknowledged.

- **FM-03.06-5 A row or chunk larger than the heap can render**
  - **Trigger**: multi-megabyte BLOB/TEXT rows; `BatchChunker` writes a row wider than `buffer.max.bytes` alone, and the driver renders the whole chunk as SQL text in memory, once per worker thread (§3.1 item 2).
  - **Behaviour**: `OutOfMemoryError` in the worker — spec 03.01 §6 FM-03.01-1 (engine stop, exit code 3). The row is redelivered after the restart, so a row that cannot be rendered in the heap fails every time.
  - **Detection**: `java.lang.OutOfMemoryError` as the cause of `Sink worker <i> of <n> is dead ...`, exit code 3, repeating after each restart.
  - **Blast radius**: all replication stops; no loss.
  - **Recovery**: raise `-Xmx`, lower `thread.pool.size` (fewer concurrent renders) and `buffer.max.bytes`, restart. A row cannot be split.
  - **RTO**: restart after the heap change; unmeasured.
  - **Test**: `BatchChunkerTest.oversizedRowIsItsOwnChunk()`.

Summary: 5 failure modes, 2 DEFECT, 2 GAP.

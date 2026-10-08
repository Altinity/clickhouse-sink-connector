# Spec 03.06: PreparedStatement Batch Flushing & Limits

## 1. Executive Summary & Purpose
Specifies the accumulation, chunking, and JDBC `executeBatch()` dispatching of parameterized SQL statements into ClickHouse.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementExecutor.java`
- **Methods**: `public boolean addToPreparedStatementBatch(String topicName, Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> queryToRecordsMap, BlockMetaData bmd, ClickHouseSinkConnectorConfig config, Connection conn, String tableName, Map<String, String> columnToDataTypeMap, DBMetadata.TABLE_ENGINE engine)` and the private `executePreparedStatement(...)` it calls per query template (there is no `insertBatch` method)
- **Scheduling**: `DebeziumChangeEventCapture.setupProcessingThread` schedules each `ClickHouseBatchRunnable` with `scheduleAtFixedRate(..., 0, buffer.flush.time.ms, MILLISECONDS)`
- **Spill-to-disk INSERT path** (§3.4): `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/SpillingInsertStatement.java` (`maybeWrap`, `renderedSizeBound`, `offerHexText`, `offerUnhex`), chosen per chunk in `PreparedStatementExecutor.executePreparedStatement`; the binary binder in `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/ClickHouseDataTypeMapper.java` offers large values to it; keys `insert.spill.threshold.bytes` (`INSERT_SPILL_THRESHOLD_BYTES`, `DEFAULT_INSERT_SPILL_THRESHOLD_BYTES = 32 MiB`) and `insert.spill.directory` (`INSERT_SPILL_DIRECTORY`)
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

### 3.4 Spill-to-disk INSERT path
The V2 driver renders a chunk as SQL text in memory (§3.1 item 2): every bound value is encoded into a `String`, every row is substituted into a `StringBuilder` (`addBatch`), the chunk is concatenated into one more `StringBuilder` and its `String` (`executeInsertBatch`), and the client turns that into a UTF-8 `byte[]` body (jdbc-v2 / client-v2 0.9.8 bytecode). A value of V bytes therefore costs five to six live copies of its text, and a binary value's text (lower-case hex) is 2V. Measured with JFR on 2.11.0 (one 64 MiB and one 256 MiB LONGBLOB row, each inserted and updated in one transaction): 17.5 of the 22.4 GiB allocated were these copies, and a 4 GiB heap exited on `OutOfMemoryError` on the 256 MiB row and again on every restart (FM-03.06-5 before this section). MySQL's own applier holds one event in memory and the rest on disk (the relay log); the connector does the same from here on.

1. **Decision, per chunk.** `PreparedStatementExecutor.executePreparedStatement` passes each chunk's driver statement through `SpillingInsertStatement.maybeWrap(statement, connection, chunk, insert.spill.threshold.bytes, insert.spill.directory)`. The chunk spills when its rendered size bound reaches the threshold (default 32 MiB; `0` spills every chunk; a negative value never spills). The bound (`renderedSizeBound`) walks the before AND after image of every row once: a string counts its length + 2, a binary value 2 × its length + 9, a nested struct, list or map its elements, anything else 24 — exact for the values that dominate (strings, binaries), with no allocation. A chunk under the threshold uses the driver's statement exactly as before.
2. **The driver stays the encoder.** The spilling statement is a `java.sql.PreparedStatement` proxy over the driver's `PreparedStatementImpl`. Every setter the binder calls is delegated to the driver, which encodes the value into its own `values` slot as before; `clearParameters` is delegated too. What the proxy replaces is the statement BUILD: `addBatch()` writes the row to the spill file as the driver's own row template (`valueListTmpl`) with each parameter's encoded text substituted at the driver's own position (`paramPositionsInDataClause`) — the text `addBatch` would have produced, character for character. Rows are separated by `,`. A parameter that is unbound when its row is written fails the row with `SQLException` (never a stale value, §3.2).
3. **Large values are streamed, never materialised as text.** A value whose text is at least 64 KiB (`LARGE_VALUE_CHARS`) is not handed to the driver: the proxy keeps a reference to the row's own object and writes its text into the file with the driver's encoding — `'` + the text with `\` doubled and `'` escaped as `\'` + `'` for a string bound with `setString` or two-argument `setObject` (`SQLUtils.escapeSingleQuotes`); `unhex('<UPPER-CASE HEX>')` (`''` when empty) for `setBytes` (`JdbcUtils.convertToUnhexExpression`); `'<lower-case hex>'` for a binary value the binder would bind as hex text, which `ClickHouseDataTypeMapper` offers through `SpillingInsertStatement.offerHexText` / `offerUnhex` instead of building the hex `String` (both return false — and the binder binds as before — on a plain driver statement or for a value under the limit). A smaller value bound later into the same slot replaces the large one.
4. **Sending.** `executeBatch()` closes the file and sends it as the body of `INSERT INTO <table>(<columns>) FORMAT Values` through the connection's own `Client` (`ConnectionImpl.getClient()`), with the statement's own settings (`localSettings`, e.g. `async_insert=0`, `wait_end_of_query=0`), `<table>(<columns>)` being the statement's text between `INSERT INTO` and `VALUES`. ClickHouse parses the data part of `INSERT ... VALUES` with the same Values input format, so the rows, the literal text and the parser are those of the in-memory path; it is still one INSERT per chunk, so atomicity, idempotence under ReplacingMergeTree and the retry semantics of §3.2 / FM-03.06-2 are unchanged. The result is one `1` per staged row (the length is what §3.3 line 3 counts). A send failure is rethrown as `SQLException` carrying the server's text (so `ClickHouseErrorClassifier` reads its `Code: <n>`, spec 10.01) and the batch fails as before; the file is removed whether the send succeeded or failed; a second `executeBatch()` with nothing staged sends nothing. `executeUpdate`, `execute`, `executeQuery` and `addBatch(String)` on the proxy are refused (they would bypass the staged rows). The progress line of §3.3 line 3 carries ` (sent from a spill file)` for such a chunk.
5. **Heap and disk.** The heap holds one row's encoded small values and two 64 KiB write buffers instead of copies of the chunk; the row images themselves are still held by the batch until it is acknowledged (spec 01.05 §3.6). The disk holds at most one rendered chunk per worker thread (`thread.pool.size`) at a time.
6. **Spill files.** Under `<insert.spill.directory>/<pid>/` (default `<java.io.tmpdir>/clickhouse-sink-connector-spill/<pid>/`), named `insert-*.values`, removed after the send. On a process's first spill, files named `insert-*.values` in sibling `<pid>` directories of processes that are no longer alive (`ProcessHandle.of(pid)` absent) are removed — leftovers of a crash, whose batches are redelivered from the offset anyway; a live process's directory and any other file are never touched.
7. **Refusal.** The template, positions, encoded values, statement text and settings are private fields of the driver, resolved once by reflection. If any is missing (a driver upgrade changed them), or the statement or connection is not the V2 driver's, the path logs one ERROR `Spill-to-disk INSERT path unavailable: ...` and every chunk keeps the driver's in-memory path. A spill directory that cannot be created logs that ERROR for every affected chunk. `SpillingInsertStatementTest.driverIsSupported()` fails the build on such a driver upgrade.

### 3.5 Retry-safe redelivery: marking what ClickHouse already has

A retry of a failed batch re-groups and resends the SAME retained record list (spec 09.01 §3.2) from the start. Without more, that resends every unit the earlier attempt already got ClickHouse to acknowledge, not only the one that failed (FM-03.06-2 before this section, FM-04.05-4, FM-12.03-1). `ClickHouseStruct` carries a boolean, `appliedToClickHouse` (default `false`), set in place — on the SAME instance, never a copy — the moment one of the following returns without throwing:

1. a chunk's `ps.executeBatch()` (§3.2): every row staged into that chunk since it started, or since its last `flushStagedRows()`, is marked;
2. a `flushStagedRows()` flush issued ahead of an inline replication-history statement (spec 12.03 §3.4): the rows staged so far are marked immediately, before the inline statement runs;
3. the inline replication-history statement itself (an SCD2 UPDATE or DELETE, spec 12.03 §3.4): the record driving it is marked once that statement returns;
4. a replicated TRUNCATE's own execution, or its replication-history bulk-close equivalent (spec 04.05 §3 step 2, spec 12.03 §3.4 Gap G-12.03-6): the TRUNCATE event record is marked.

Records are carried by reference from the retained handoff/retry list through every regrouping attempt, never cloned (spec 01.05 §3.6, spec 09.01 §3.2), so a flag set in place on this pass is still set when the SAME list is regrouped on retry. `GroupInsertQueryWithBatchRecords.groupQueryWithRecords` skips any record already marked applied when it builds segments — it is not placed into any chunk or TRUNCATE segment again — but its offset is still folded into `partitionToOffsetMap` exactly as before, so the watermark used for the offset commit (I8, §4) reflects everything actually durable regardless of which attempt wrote it.

A topic whose entire retained list is already marked applied groups into nothing. `PreparedStatementExecutor.addToPreparedStatementBatch`'s existing guard treats an empty group as the unrelated defect of "grouped into nothing before any grouping was attempted" (spec 04.01 §3.3) and fails loudly; reaching that state because every unit already succeeded is not that defect, so `ClickHouseBatchRunnable.processRecordsByTopic` and `ClickHouseBatchWriter.processRecordsByTopic` short-circuit to a successful result before calling into grouping at all when every record of the topic's list is already marked. The short-circuit in `ClickHouseBatchRunnable` still merges each record's offset into the durable watermark (`durablyInsertedOffsets`, max per TopicPartition), the same fold the grouping path performs for an applied record: the attempt that wrote those rows may have returned `false` before reaching the watermark merge, and skipping the fold would leave durable rows outside the committed offset (redelivered after a restart).

Correctness depends on `BatchChunker.chunk` (§3.1 item 2) being a purely LOCAL, greedy algorithm: a chunk boundary is decided only from the rows at and after it, never from rows already placed into an earlier chunk. Filtering out an applied PREFIX of the list and re-chunking the remainder therefore reproduces exactly the boundaries the first attempt would have produced for those same rows — the retry's first chunk is what would have been the first unapplied chunk had nothing failed, not a different split with different row membership.

Residual, stated precisely: the one unit in flight when the failure happened — the chunk whose `executeBatch()` threw, or the statement whose flush or inline execute threw — is never marked, because its own call never returned. Whether ClickHouse applied none, part, or all of that one unit before the failure surfaced is not observable from the driver or the JDBC contract; the retry resends it, so it may duplicate on an engine that does not deduplicate (FM-03.06-2). This mechanism is never read or written across a process/engine restart: a record redelivered after a restart is a fresh instance and starts unmarked (spec 02.04, spec 09.01 §3.8); cross-process redelivery stays governed by I8 as before.

---

## 4. Invariants Preserved
- **Invariant I3 (Eventual Convergence)**: every partition carries `_version` and the delete flag for every row, so replays and re-orderings converge under `ReplacingMergeTree`.

---

## 5. Verification Criteria
- `PreparedStatementExecutorSortingKeyTombstoneTest` — the per-record tombstone decision inside the batch loop.
- `PreparedStatementExecutorClearParametersTest.parametersAreClearedAfterEveryAddBatch()` — through `addToPreparedStatementBatch` with a recording connection: for a two-row batch the statement receives `addBatch` twice and `clearParameters` once after each `addBatch` (pre-fix code never calls `clearParameters`).
- `PreparedStatementExecutorTruncateTest.truncateIsAppliedAtItsBinlogPositionForBothHashOrders()`, `TruncateTableIT.testRowsInsertedAfterTruncateSurvive()` — TRUNCATE ordering within one batch.
- `PreparedStatementExecutorFailureModesTest.aChunkThatFailsThenSucceedsOnRetrySendsEarlierChunksExactlyOnce()`, `PreparedStatementExecutorTruncateTest.aTruncateSegmentIsNotReRunWhenTheFollowingInsertSegmentFailsThenRetries()` — §3.5: a retry of the SAME retained record list after a partial failure excludes every already-applied unit (an earlier chunk, a TRUNCATE) from the regrouping and sends it exactly once; the TRUNCATE is not re-executed even though the segment after it is the one that failed.
- `AllAppliedTopicWatermarkTest.anAllAppliedTopicAdvancesTheDurableWatermark()`, `AllAppliedTopicWatermarkTest.anAllAppliedTopicNeverMovesTheWatermarkBackwards()` — §3.5: the all-applied short-circuit in `ClickHouseBatchRunnable.processRecordsByTopic` returns success without a connection and advances `durablyInsertedOffsets` to the highest offset per TopicPartition, never lowering it.
- `PreparedStatementExecutorBatchLogLevelTest.successfulBatchLogsItsProgressAtInfo()` — §3.3 lines 2 and 3: a successful two-row batch through `addToPreparedStatementBatch` with a recording connection, logger at the default INFO level, writes the INSERT-template line and the EXECUTED-BATCH line at INFO and nothing at WARN or above (the DEBUG revision writes neither at INFO, so the test fails on it); the EXECUTED-BATCH line carries `Result: 2 statements acknowledged` and no `[I@` identity (the `toString()` revision fails here).
- `PreparedStatementExecutorBatchLogLevelTest.batchResultDescriptionCountsAcknowledgedAndFailedStatements()` — §3.3 line 3 `Result` field: the count of acknowledged statements, the `, <f> marked EXECUTE_FAILED` suffix only when the driver flagged an entry, and `no result` for a null array.
- `DebeziumOffsetManagementTest.acknowledgementIsLoggedAtInfo()` — §3.3 line 4: acknowledging a unit through `acknowledgeRecords`, logger at INFO, writes the "BATCH marked as processed" line at INFO and nothing at WARN or above (fails on the DEBUG revision).
- §3.3 line 1 (`ClickHouseBatchRunnable.processBatch`, `ClickHouseBatchWriter.persistRecords`) has no unit harness that reaches the pick-up line without a live ClickHouse (both paths resolve the destination through `DBMetadata` on a real connection); covered by review of the two call sites.
- `BatchChunkerTest` — §3.1 item 2: the row limit alone splits like a partition, order and identity preserved (`rowLimitAlone`); the byte limit closes a chunk before the row limit when the rows are wide (`byteLimitClosesEarly`); a single row wider than the byte limit is its own chunk, never dropped or split (`oversizedRowIsItsOwnChunk`); unstamped rows obey the row limit and both limits disabled yield one chunk (`unstampedRowsAndDisabledLimits`); empty input yields no chunks and a null row counts as zero bytes (`emptyAndNull`).
- `SpillingInsertStatementTest` — §3.4, with real jdbc-v2 `PreparedStatementImpl`s over a connection to a closed port (binding never touches the network; the sender is replaced): the driver internals exist (`driverIsSupported`, the upgrade pin of item 7); for small values the spilled rows are the driver's own `addBatch` row text byte for byte (`smallRowsMatchTheDriver`); a large string, a large `setBytes` value and a large binary offered as hex text are streamed with exactly the driver's encoding (`largeStringMatchesTheDriver`, `largeBytesMatchTheDriver`, `offeredHexTextMatchesTheDriver`); `offer*` declines small values and plain statements (`offerDeclines`); a later small bind replaces a large one (`rebindReplacesALargeValue`); the send names `<table>(<columns>)` and the statement's settings and leaves no file (`sendsTableColumnsAndSettings`); a failed send carries the server text and leaves no file (`failedSendPropagates`); an unbound parameter fails the row (`unboundParameterFails`); bypassing execute paths are refused (`otherExecutePathsRefused`); the threshold decision (`wrapDecision`), the size bound (`renderedSizeBound`) and the dead-process and previous-incarnation sweep (`deadProcessSweep`). Each was mutation-checked: removing the backslash escape, upper-casing the hex text, dropping the template tail, writing `NULL` for an unbound parameter, keeping the sent file and skipping the dead-process sweep each fail at least one of them.
- `sink-connector-lightweight/tests/e2e/large_row_spill.sh` — §3.4 end to end at a fixed heap: rows of `ROW_MB` MiB inserted and updated in one transaction, a kill -9 during the largest one, one transaction of `TXN_ROWS` incompressible rows, the ` (sent from a spill file)` progress lines and no spill file left behind, value level (MD5/CRC, MySQL vs ClickHouse FINAL).
- Spill-path parity: the whole e2e corpus (the binlog-compression harnesses, the base, PK, history, upgrade/downgrade, DDLxDML, DST and time-zone suites) was run on a build whose `insert.spill.threshold.bytes` default was 0 — every chunk sent from a spill file — with the same results as without the spill path.
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
  - **Behaviour**: each chunk is its own `executeBatch()`; the failure is rethrown as `RuntimeException` after chunks 1..k-1 returned, and nothing rolls them back. The worker retries the whole batch (spec 03.03 §6 FM-03.03-2). Since §3.5, chunks 1..k-1's rows are marked `appliedToClickHouse` the instant their `executeBatch()` returns and are excluded from the retry's regrouping, so they are sent exactly once — never again, however many retries it takes. Chunk k itself is never marked, because its own `executeBatch()` never returned: whether ClickHouse applied none, part, or all of it before the failure surfaced is not observable from the driver, so the retry resends chunk k's rows (regrouped, possibly into a differently-sized chunk of the shortened remainder, but the same rows, §3.5).
  - **Detection**: ERROR `******* ERROR inserting Batch Database(<db>), Table(<t>) *****************` with the driver exception, then the worker's retry lines.
  - **Blast radius**: narrowed to chunk k alone — chunks 1..k-1 are never resent. Chunk k's possible duplication: idempotent under ReplacingMergeTree (same key, same `_version`), including a query that reads it back through `FINAL`; a Replicated*MergeTree table additionally deduplicates the resent insert block by its content hash when the retry reproduces an identical block (`insert_deduplicate`, default on, spec-external); plain MergeTree, CollapsingMergeTree, and an SCD2 replication-history table (spec 12.03) may carry one possible duplicate of at most chunk k's rows (spec 05.04 §6 FM-05.04-1, spec 12.03 §7 FM-12.03-1).
  - **Recovery**: none needed for chunks 1..k-1 (never resent); for chunk k on a non-deduplicating engine, only if the ambiguity resolves to an actual duplicate: `ch-mysql-resync` (spec 11.04).
  - **RTO**: the retry's (spec 03.03 §6); resync time for chunk k on an additive engine — unmeasured.
  - **Test**: `PreparedStatementExecutorFailureModesTest.aFailedChunkLeavesTheEarlierChunksWrittenAndFailsTheBatch()` (chunk ordering within one attempt — chunk 1 runs, chunk 2 fails), `PreparedStatementExecutorFailureModesTest.aChunkThatFailsThenSucceedsOnRetrySendsEarlierChunksExactlyOnce()` (the retry of the SAME list sends only chunk 2; chunk 1 is never resent).

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
  - **Trigger**: multi-megabyte BLOB/TEXT rows, or many rows whose chunk renders to tens of megabytes or more; `BatchChunker` writes a row wider than `buffer.max.bytes` alone (§3.1 item 2).
  - **Behaviour**: since §3.4 such a chunk is never rendered in memory: it is written to a spill file and sent from disk, and a value of 64 KiB or more is streamed from the row's own object. What remains in the heap is what the reader and the batch hold — the row's decoded images (spec 01.08 FM-01.08-5, spec 01.05 §3.6). measured with `sink-connector-lightweight/tests/e2e/large_row_spill.sh` (one row inserted and updated in one transaction, i.e. three images of the value in one payload, 4 GiB heap): with `binary.handling.mode=bytes` rows of 64, 128, 256 and 512 MiB are value-exact with no `OutOfMemoryError` (heap after GC at most 2.6 GiB at 512 MiB), where 2.11.0 before §3.4 exited on the 256 MiB row in 2 of 3 runs and crash-looped on the restart; with `binary.handling.mode=base64` (the Ansible template's setting) 64 and 256 MiB are value-exact at 4 GiB and 512 MiB needs more than 4 GiB (value-exact at 8 GiB, heap after GC 3.1 GiB). A row whose decoded images do not fit the heap still ends in `OutOfMemoryError` — spec 03.01 §6 FM-03.01-1 (engine stop, exit code 3), and the restart re-reads the same row.
  - **Detection**: the ` (sent from a spill file)` progress line for every spilled chunk (§3.3 line 3); for the residual case, `java.lang.OutOfMemoryError` as the cause of `Sink worker <i> of <n> is dead ...`, exit code 3, repeating after each restart.
  - **Blast radius**: residual case only: all replication stops; no loss.
  - **Recovery**: none for rendering; residual case: raise `-Xmx` (a MySQL row event cannot exceed `max_allowed_packet`, at most 1 GiB, so a heap sized for the largest row the source can log always suffices) and restart.
  - **RTO**: 0 for rendering; residual case: restart after the heap change.
  - **Test**: `BatchChunkerTest.oversizedRowIsItsOwnChunk()`, `SpillingInsertStatementTest.largeBytesMatchTheDriver()`, `large_row_spill.sh` cases R64-R512 and K (kill -9 during the 512 MiB row), edge E5 at 4 GiB.

- **FM-03.06-6 The spill directory is full or not writable**
  - **Trigger**: `insert.spill.directory` (default under `java.io.tmpdir`) on a full filesystem, a read-only mount, or a path the service user cannot create.
  - **Behaviour**: a directory that cannot be created: ERROR `Spill-to-disk INSERT path unavailable for this chunk: Cannot create the spill directory ...` for every affected chunk, and the chunk is rendered in memory by the driver as before §3.4. A write that fails half way (disk full): `SQLException` `Cannot write the spilled INSERT row to <file> (disk full or insert.spill.directory not writable?)`, the partial file is removed, the batch fails and the worker retries it (spec 03.03 §6) — nothing is sent, nothing is acknowledged.
  - **Detection**: those ERROR lines; the retried batch's WARN lines; disk-usage alerting on the filesystem.
  - **Blast radius**: the worker's tables wait while the disk is full; in the uncreatable-directory case large chunks fall back to the heap (FM-03.06-5 as before §3.4). No loss.
  - **Recovery**: free space or point `insert.spill.directory` at a filesystem with room for one rendered chunk per worker thread (`thread.pool.size` × the largest chunk), restart if the setting changed; a disk-full stall resumes by itself once space is freed.
  - **RTO**: space freed + one retry backoff (≤ 30 s, spec 03.03 §6); unmeasured.
  - **Test**: GAP: a test with a full or read-only spill directory.

- **FM-03.06-7 A driver upgrade removes the internals the spill path reads**
  - **Trigger**: a jdbc-v2 release that renames or removes `PreparedStatementImpl.values`, `valueListTmpl`, `paramPositionsInDataClause`, `argCount`, `insertStmtWithValues`, `originalSql`, `parsedPreparedStatement` or `StatementImpl.localSettings`.
  - **Behaviour**: the path refuses itself: one ERROR `Spill-to-disk INSERT path unavailable: the JDBC driver does not expose ...` and every chunk is rendered in memory by the driver — correct, with the pre-§3.4 memory cost (FM-03.06-5 as before).
  - **Detection**: that ERROR line at the first large chunk; at build time, `SpillingInsertStatementTest.driverIsSupported()` fails.
  - **Blast radius**: memory headroom only; no loss.
  - **Recovery**: adapt `SpillingInsertStatement.DriverAccess` to the new driver, or pin the previous driver.
  - **RTO**: a release; meanwhile the heap must cover FM-03.06-5 as before §3.4.
  - **Test**: `SpillingInsertStatementTest.driverIsSupported()`.

Summary: 7 failure modes, 2 DEFECT, 3 GAP.

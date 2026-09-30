# Spec 01.03: CDC Event Batch Dispatch & Triage Loop

## 1. Executive Summary & Purpose
Specifies the central event dispatch loop invoked by Debezium's `ChangeConsumer`. The loop assigns a version to every incoming change event, categorizes events into DDL, DML, or control records, enforces the DDL barrier, hands row batches to the writers and commits control-record offsets when the pipeline is quiescent.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
- **Key Methods**:
  - `void handleChangeEventBatch(List<ChangeEvent<SourceRecord, SourceRecord>> list, ...)` (package-private; the per-batch loop)
  - `static synchronized long nextSequenceNumber(long recordTs, SourcePosition position)` (spec 02.01–02.03)
  - `ClickHouseStruct processEveryChangeRecord(Properties props, ChangeEvent record, DebeziumRecordParserService parser, ClickHouseSinkConnectorConfig config, RecordCommitter committer, boolean lastRecordInBatch, long recordSequenceNumber)` — returns the parsed row, or `null` for DDL and control records
  - `boolean isDDLRecord(ChangeEvent record)`
  - `private void drainBeforeDDL()` / `private void performDDLOperation(String DDL, Properties props, SourceRecord sr, ClickHouseSinkConnectorConfig config, RecordCommitter committer, ChangeEvent record, boolean lastRecordInBatch, ClickHouseStruct ddlStruct)`
  - `static void markTerminalRecord(List<ClickHouseStruct> batch)`
  - `void commitControlRecordOffset(ChangeEvent lastControlRecord, RecordCommitter committer, boolean handedOffRows)` (spec 01.06 / 09.04)

---

## 3. Dispatch & Triage Algorithm

For every incoming batch emitted by Debezium:
1. Initialize `List<ClickHouseStruct> batch = new ArrayList<>()`, `ChangeEvent lastControlRecord = null`, `boolean handedOffRows = false`.
2. For each `ChangeEvent record` (index `i`; `lastRecordInBatch = (i == list.size() - 1)`):
   - Extract the source timestamp: `long recordTs = ClickHouseStruct.getSourceTsFromChangeEvent(record)` (`source.ts_ms` for streaming rows; the envelope `ts_ms` for snapshot rows and for records without a source struct).
   - **Compute the version for EVERY record, before triage**: `long recordSequenceNumber = nextSequenceNumber(recordTs, ClickHouseStruct.getSourcePositionFromChangeEvent(record))`. This call is made for DDL records, heartbeats and transaction-boundary events as well as for rows, so every record advances the sequence counter and can raise the timestamp floor (spec 02.02 §3.2).
   - `boolean ddlRecord = isDDLRecord(record)`. **If DDL and `batch` is non-empty**: `markTerminalRecord(batch)`; `appendToRecords(new ArrayList<>(batch), config)`; `batch.clear()`; `handedOffRows = true`. Rows read before the DDL in the same Debezium batch are handed to the writers first so the schema change lands at its true stream position.
   - `ClickHouseStruct chStruct = processEveryChangeRecord(props, record, parser, config, committer, lastRecordInBatch, recordSequenceNumber)`:
     - **DDL path** (inside `processEveryChangeRecord`): in a `try` — `drainBeforeDDL()` (spec 06.01: wait for the handoff queue to empty, pause the executor, await quiescence), build a `ddlStruct` carrying the current `sequenceNumber`, then `performDDLOperation(...)`, which translates and executes the DDL (with the configured retry policy), invalidates schema caches (`CacheInvalidationManager.invalidateTable(tableKey)` for every resolved table, or `invalidateAll()` when the affected table cannot be determined) and **acknowledges the DDL's offset itself** via `DebeziumOffsetManagement.acknowledgeRecords(committer, ddlStruct, lastRecordInBatch)`. Any failure is wrapped in `DDLReplicationException` and re-thrown ahead of the method's catch-all (spec 10.04 §3.3). The executor is resumed in a **`finally`** block (`this.executor.resume()` when the executor is not `null`), so a failed DDL never leaves the pool paused. Returns `null`.
     - **DML path**: parses the record, sets the version from `recordSequenceNumber`, returns the `ClickHouseStruct`. If the parser throws, or returns `null` for a record whose value carries an `op` field, the record is a row that was NOT converted: `RecordReplicationException` is raised and re-thrown ahead of the catch-all (spec 01.06 §3.1, spec 10.04 §3.3). A value that is neither a Struct nor `null` is rejected the same way before the version is computed.
     - **Control / heartbeat path** (a Struct value with no `op`, or a `null` value — a tombstone): returns `null`.
   - If `chStruct != null`: `batch.add(chStruct)`. Else if `!ddlRecord`: the record may become `lastControlRecord` **only if** `isControlRecord(record.value())`; otherwise `RecordReplicationException` is thrown here too (second line of defence). DDL acknowledges itself and is never treated as a control record.
3. Post-loop:
   - If `batch` is non-empty: `markTerminalRecord(batch)` (the last handed-off row carries `isLastRecordInBatch = true` even when the Debezium batch ended with a control record), `appendToRecords(batch, config)`, `handedOffRows = true`.
   - `commitControlRecordOffset(lastControlRecord, committer, handedOffRows)` is always called; it commits the newest control record's offset only when no rows from this batch were handed off (and the pipeline is otherwise quiescent — spec 09.04).

---

## 4. Invariants Preserved
- **Invariant I5 (DDL Barrier Quiescence)**: rows preceding a DDL in the same batch are handed off before the drain; the drain and pause complete before the DDL executes; caches are invalidated before the executor resumes.
- **Invariant I12 (Control-record offset progress)**: every handed-off batch carries a terminal marker, so its offset is flushed once written regardless of where control records fall in the Debezium batch.
- **Loud DDL failure (I9)**: a DDL that cannot be applied stops the engine rather than being skipped.

---

## 5. Verification Criteria
- `DebeziumChangeEventCaptureTest.shouldRecogniseDDLRecord()`, `DebeziumChangeEventCaptureTest.shouldNotTreatRowChangeAsDDL()`, `DebeziumChangeEventCaptureTest.shouldNotTreatEmptyDDLAsDDL()` — the triage predicate.
- `SnapshotOffsetProgressTest` — `markTerminalRecord` marks exactly the last handed-off row.
- `ControlRecordOffsetCommitTest` — control records are retained as `lastControlRecord` and committed only when nothing was handed off.
- `UnparseableRowRecordIsTerminalTest` — a row record that produced no struct is never retained as `lastControlRecord`: `RecordReplicationException` leaves `handleChangeEventBatch` and nothing is acknowledged.
- `DdlFailureLoudTest.ddlFailurePropagatesInsteadOfBeingSwallowed()` — the DDL path escapes the catch-all; `DdlDrainDeadlockTest` — the drain does not deadlock.
- Verification: an end-to-end test of interleaved DML and DDL asserting exact execution ordering is not yet covered by an automated test (gap).

---

## 6. Failure Modes & Recovery
The dispatch loop's posture is "a record that cannot be handled leaves `handleChangeEventBatch` as an exception": the engine stops, nothing past the record is acknowledged, and the restart redelivers it (spec 01.01 FM-01.01-1). The unconvertible row is spec 01.06 FM-01.06-1; the loop's own failure modes are below. Its one remaining hole is the catch-all at the bottom of `processEveryChangeRecord`, which still swallows exceptions thrown on the DDL path before the `DDLReplicationException` guard.

- **FM-01.03-1 A DDL that cannot be applied**
  - **Trigger**: ClickHouse rejects the translated statement (unknown table, type mismatch, access denied, read-only replica), its retries are exhausted, or the pre-DDL drain aborts on a dead worker.
  - **Behaviour**: `processEveryChangeRecord` wraps `drainBeforeDDL()` and `performDDLOperation(...)` and raises `DDLReplicationException`, re-thrown ahead of the catch-all; the executor is resumed in `finally`; the DDL acknowledges its own offset only on success. The engine stops; a ClickHouse code in `FATAL_ERROR_CODES` (e.g. 16, 50, 60, 81, 497) is terminal at once (exit 3), anything else draws on the retry budget.
  - **Detection**: ERROR `DDL replication failed for [<ddl>]; stopping the pipeline rather than advancing offsets past an unapplied schema change and silently diverging from MySQL.`, then `Engine stopped with an error:` — at the DDL.
  - **Blast radius**: all tables stop at the DDL's position; no loss; rows before the DDL were handed off and are written first (§3 step 2).
  - **Recovery**: fix the target (apply the equivalent change by hand, grant the privilege, restore the table) and restart; if the statement must not be replicated, add a matching `ignore.ddl.regex` entry (entries separated by `||`) and restart — the ignored DDL is then committed as a no-row record — and align the ClickHouse schema by hand.
  - **RTO**: after the fix, 30 s (systemd) or ≤ 10 s (retry) + start + redelivery of the batch holding the DDL; unmeasured.
  - **Test**: `DdlFailureLoudTest.ddlFailurePropagatesInsteadOfBeingSwallowed()`, `TerminalFailureExitTest.fatalErrorCodeIsNotRetried()`.

- **FM-01.03-2 A DDL silently dropped when its ignore rules cannot be evaluated**
  - **Trigger**: an exception inside `checkIfDDLNeedsToBeIgnored` — typically an `ignore.ddl.regex` entry that does not compile (`Pattern.compile` throws `PatternSyntaxException` for every DDL), or a failure in table-name extraction / `DdlCaptureFilter.isCaptured`.
  - **Behaviour**: that call sits BEFORE the `try` that raises `DDLReplicationException`, so the exception reaches the catch-all `catch (Exception e) { log.error("Exception processing record", e); }`; the method returns `null`; `handleChangeEventBatch` treats a DDL record as self-acknowledging (`else if (!ddlRecord)` is false) and throws nothing, and the next written rows move the durable offset past a schema change ClickHouse never received.
  - **Detection**: ERROR `Exception processing record` with the stack trace, once per DDL; the pipeline keeps running and reports healthy.
  - **Blast radius**: every DDL of the process (for a bad regex) is lost: silent schema divergence, later rows written against the old ClickHouse schema (NULL or default for new columns, failures on dropped ones).
  - **Recovery**: fix `ignore.ddl.regex` and restart; the dropped DDLs are NOT redelivered (their offsets are behind the durable position), so compare `SHOW CREATE TABLE` on MySQL with ClickHouse for every table altered since the bad setting, apply the missing changes by hand, and `ch-mysql-resync` (spec 11.04) the tables whose rows were written against the wrong schema.
  - **RTO**: unbounded — nothing announces the divergence; repair is per table.
  - **Test**: `DdlIgnoreRuleFailureIsLoudTest.invalidIgnoreRegexDoesNotSilentlySkipTheDdl()` (disabled, fails on 2.11.0: nothing is thrown).
  - **DEFECT**: an exception in the DDL ignore-rule evaluation drops the DDL and lets the offset pass it; an invalid `ignore.ddl.regex` is not validated at startup.

- **FM-01.03-3 The pre-DDL drain holds the reader behind a live but failing writer**
  - **Trigger**: a DDL arrives while a worker retries a transient ClickHouse error (server down, `TOO_MANY_PARTS`, `MEMORY_LIMIT_EXCEEDED`) — retried without limit by `ClickHouseBatchRunnable.run`.
  - **Behaviour**: `drainBeforeDDL` → `awaitPipelineQuiescent` waits while the pipeline is not quiescent, bounded only by worker liveness (`failIfWorkerDiedDuringDrain`), not by time — unlike the 600 s limit at the hard cap (spec 01.05). The Debezium thread is held; the source aborts the undrained dump after `net_write_timeout`; when the drain completes the DDL is applied and the next read fails, which is spec 01.07 FM-01.07-1. Detail in spec 06.01 §6.
  - **Detection**: WARN `Pipeline drain: <backlog> still pending after <ms> ms; the writers are alive, so the backlog is a slow or retrying batch, not a dead one.` every 60 s (`ddlDrainWarnIntervalMs`), plus the worker's WARN `Retriable ClickHouse error (Code: <c>, Category: <cat>) -- the same batch will be retried in <ms> ms`.
  - **Blast radius**: all tables stop until ClickHouse accepts the batch; no loss.
  - **Recovery**: self-heals when ClickHouse recovers; otherwise fix ClickHouse (parts, memory, availability).
  - **RTO**: ClickHouse recovery + worker backoff (≤ `batch.retry.backoff.max.ms`, 30 s) + one engine restart (FM-01.07-1) + redelivery; unmeasured.
  - **Test**: `DdlDrainDeadlockTest.testUndrainableQueueWithLiveWorkersKeepsWaiting()`, `DdlDrainDeadlockTest.testStuckQueueWithDeadWorkerAborts()`.

- **FM-01.03-4 A dead worker is only noticed on the next source batch**
  - **Trigger**: a worker's scheduled task terminates (FATAL ClickHouse code, `OutOfMemoryError`, poisoned offset writer).
  - **Behaviour**: `failIfWorkerDied` runs first in every `handleChangeEventBatch` and between hard-cap slices; on an idle MySQL source the next batch is the Debezium heartbeat that follows the next server heartbeat (requested at 0.8 × `connect.keep.alive.interval.ms` = 48 s by default, inferred from `BinlogStreamingChangeEventSource.handleEvent` dispatching a heartbeat after each event). The engine stops; the completion callback makes it terminal (`hasDeadWorker`, exit 3). If the reader is instead blocked in a queue `put`, nothing notices (spec 01.05 FM-01.05-4).
  - **Detection**: the worker's ERROR (e.g. `FATAL ClickHouse error (Code: 60) -- this batch will never succeed.`) at once; ERROR `Sink worker N of M is dead: its scheduled task has terminated...` within ≤ ~48 s on an idle source, at once on a busy one; exit code 3.
  - **Blast radius**: all tables stop; no loss.
  - **Recovery**: fix the FATAL cause (create the missing table or column, restore the privilege), then let systemd restart the process.
  - **RTO**: 30 s + start after the fix; unmeasured.
  - **Test**: `WorkerDeathIsLoudTest.deadWorkerFailsTheNextBatchLoudly()`, `DeadWorkerRetryIsTerminalTest.deadWorkerIsTerminalAtOnce()`.

Summary: 4 failure modes, 1 DEFECT, 0 GAP.

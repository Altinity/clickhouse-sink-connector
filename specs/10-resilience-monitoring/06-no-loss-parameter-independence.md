# Spec 10.06: No-Row-Loss Is Parameter-Independent

## 1. Executive Summary & Purpose
States, and pins with tests, the guarantee that **no committed configuration
value can turn replication into row loss**. The zero-loss mission
(Constitution Preamble; Invariant I8) is a property of the *code* — the
durable offset is advanced only by the writer path, only after rows are
durably in ClickHouse, and only in binlog (handoff-sequence) order — not of
any tuning parameter. The offset-flush, buffer, thread-pool, retry and
deployment-layer shutdown parameters change only **latency, redelivery volume
and throughput**; they cannot move the durable offset ahead of durably-written
rows, so the worst outcome any value can produce is redelivery (duplicate
rows, idempotent under `_version`) or a loud stop — never a lost row.

This spec adds no runtime behaviour. It exists because the guarantee was being
attributed to a parameter value (raising `offset.flush.timeout.ms` to keep a
restart from "losing" rows). Under I8 an offset can never advance past an
unwritten insert regardless of that value, and this spec makes that a declared,
tested invariant so the parameters are documented and tested as tuning knobs,
never as the guarantee.

---

## 2. Codebase Mapping on 2.11.0
- **System Constitution**: Invariant I8 (Durable Offset Quiescence), Preamble
  (zero-loss mission), Invariant I9 (Loud Failure).
- **Offset acknowledgement engine**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/DebeziumOffsetManagement.java`
  — `registerHandoff`, `checkIfBatchCanBeCommitted`, `acknowledgeRecords`,
  `hasUnwrittenBatches`, `outstandingCount`, `reset` (the in-process restart
  abandon path).
- **Writer that acknowledges only after a durable write**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchRunnable.java`
  — `processBatch` (the `if (result)` branch calls `checkIfBatchCanBeCommitted`
  only after `processRecordsByTopic` returned `true`), and `isOffsetWriterPoisoned`
  (the flush-timeout semaphore-leak path is made a loud FATAL stop, not silent
  divergence).
- **Producer that hands off without acknowledging**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
  — `handleChangeEventBatch` (rows are handed off and the method returns before
  they are acknowledged), `isPipelineQuiescent`, `commitControlRecordOffset`,
  `ensureHeartbeatInterval`.
- **Offset commit policy**: `io.debezium.engine.spi.OffsetCommitPolicy` —
  `OffsetCommitPolicy.always()` in `DebeziumChangeEventCapture` setup; the
  policy only decides *when a flush is attempted*, never *what has been staged*.
- **Parameters governed here (all optional, none required for correctness)**:
  `offset.flush.interval.ms`, `offset.flush.timeout.ms`,
  `buffer.flush.time.ms` (`ClickHouseSinkConnectorConfigVariables.BUFFER_FLUSH_TIME`),
  `buffer.max.records`, `thread.pool.size`, `errors.max.retries`
  (`ClickHouseSinkConnectorConfigVariables.ERRORS_MAX_RETRIES`),
  `heartbeat.interval.ms`. Deployment-layer knobs (`KillSignal`,
  `TimeoutStopSec`, JDBC `socket_timeout` / `connection_timeout`) live outside
  the JVM and are covered by §3.3.
- **Formal model**: `formal_specs/lean/Replication/OffsetFifo.lean` — the FIFO
  model has no flush-timeout, buffer or retry parameter at all, so its safety
  theorems ARE the parameter-free statement of this guarantee.

---

## 3. Operational Specification

### 3.1 The guarantee is a code property, stated once
The durable binlog position (`replica_source_info`) is advanced only by
`DebeziumOffsetManagement.acknowledgeRecords`, which is called only from
`checkIfBatchCanBeCommitted` (after a batch's rows are durably written, spec
09.01 §3.2) and from `commitControlRecordOffset` (only when the pipeline is
quiescent, spec 09.04). No other path stages an offset. Therefore:

> **Durable-offset ⟹ written-rows.** Every source position committed to
> `replica_source_info` is a position at or before which every row has been
> durably written to ClickHouse, in binlog order (Invariant I8;
> `Replication.OffsetFifo.commit_never_passes_outstanding`).

Its contrapositive is the no-loss guarantee: a row not yet in ClickHouse is
below the committed position by nothing, so on any abrupt stop the next start
resumes at or before it and **redelivers** it — at-least-once, never a gap
(`Replication.OffsetFifo.acked_never_rolled_back`,
`Replication.OffsetFifo.restart_quiescent`). Redelivered rows are idempotent
under `_version` (specs 02.02, 02.04).

None of the statements above mention a parameter. That is the point: the
guarantee holds by construction of the acknowledgement path, for every value of
every knob.

### 3.2 Parameter classification (each knob is latency / redelivery / throughput)
For every parameter, the reason it cannot advance the durable offset past an
unwritten row, and what it *does* change.

| Parameter | What it tunes | Why it cannot cause loss |
|---|---|---|
| `offset.flush.interval.ms` | How often the staged offset is flushed to `replica_source_info`. | The offset store flushes only what `acknowledgeRecords` has STAGED, i.e. only written rows. A longer interval flushes less often ⟹ the durable position lags further behind written rows ⟹ **more** redelivery on restart, never less coverage. A shorter interval never stages an unwritten row because nothing unwritten is ever staged. |
| `offset.flush.timeout.ms` | The deadline for one flush of staged offsets. | A timeout means the *already-safe* staged position is **not** persisted this round; the durable position stays behind ⟹ redelivery. It can never persist a position beyond what was staged. The historical hazard — Debezium leaking its `flushInProgress` semaphore on timeout and then freezing all future flushes while inserts kept succeeding — is caught as a loud FATAL stop by `ClickHouseBatchRunnable.isOffsetWriterPoisoned` (spec 10.04), converting even that into stop-and-redeliver, never silent loss. |
| `buffer.flush.time.ms`, `buffer.max.records` | How large / how old a JDBC batch grows before it is executed. | They change batch shape and write latency. A batch is acknowledged only after it is written (spec 09.01 §3.2); its offset is never staged before the write regardless of batch size or age. |
| `thread.pool.size` | Legacy single queue vs. per-table hash routing across N workers. | Acknowledgement is ordered by handoff sequence across all workers, not by which worker finishes first (spec 09.01 §3.3). A batch written out of turn is parked, not acknowledged, until every lower sequence is acknowledged, so more workers change throughput, never the commit frontier. |
| `errors.max.retries`, `exit.on.terminal.failure` | How many times a failed engine/operation is retried before a terminal stop, and whether the process exits on it. | A larger budget retries longer; an exhausted budget stops loudly (spec 10.04 §3.5). Neither commits an offset past a failed write: a terminal failure commits nothing and the next start resumes from the last committed offset. |
| `heartbeat.interval.ms` | How often an idle source emits a control record that can advance the offset. | A control-record offset is committed only when the pipeline is quiescent — nothing handed off is unwritten (spec 09.04). It therefore never leapfrogs an in-flight row whatever the interval; a value of `0` only strands snapshot-completion liveness (issue #1379), never loses a row. |

The single direction every row of the table shares: turning a knob "the wrong
way" costs **latency or redelivery**, and the safe reading of a restart is
"resume at or before the last durable position", which is exactly what
at-least-once redelivery does.

### 3.3 Deployment-layer knobs are the same story, one layer out
`KillSignal` / `TimeoutStopSec` (systemd) and JDBC `socket_timeout` /
`connection_timeout` are not connector parameters; they govern how abruptly the
JVM is stopped and how long an insert may take. A graceful `SIGTERM` with a
generous `TimeoutStopSec` lets the engine drain and acknowledge in-flight work
before exit (spec 01.01 §3.3), so a restart redelivers *less*. A hard `SIGKILL`
at any instant abandons only unacknowledged work; the durable offset is still
`Durable-offset ⟹ written-rows`, so the next start redelivers from it. These
knobs therefore reduce restart churn and avoid the flush-timeout hazard of
§3.2; they do not provide the guarantee and cannot break it. The correctness of
a restart never depends on their values.

### 3.5 Enforcement: the commit decision stays parameter-free
The property above is enforced against the CODE, not only argued: the class that
decides when an offset may be committed (`DebeziumOffsetManagement` — the whole
of §3.1's acknowledgement path) must reach that decision without reading any
tuning parameter. `OffsetCommitDecisionParameterFreeTest` fails the build if that
class ever declares a method, constructor or field carrying a configuration type
(`ClickHouseSinkConnectorConfig` or `java.util.Properties`). Batch SHAPING
(`buffer.flush.time.ms`, `buffer.max.records`) legitimately reads configuration
in `ClickHouseBatchRunnable`, which is a different class on the WRITE path — the
guard is scoped to the frontier decision precisely so shaping stays free to tune
while the guarantee stays parameter-free. Coupling the frontier to a knob is thus
a compile/test failure that forces a revision of this spec (Constitution, Law of
Reviewed Immutability), not a silent regression.

### 3.4 What is NOT claimed
- This spec does not weaken any per-record loud-failure rule (spec 10.04): a
  configuration that guarantees per-record divergence (`binlog_row_image` other
  than `FULL`, `ignore_delete=true`, `schema.history.internal.skip.unparseable.ddl=true`)
  is refused or announced there, not here. Those are *deliberate* divergence
  switches, not the accidental parameter-sensitivity this spec forecloses.
- "No loss" means the committed offset never passes an unwritten row, so every
  source row is eventually written at least once. It is not a no-duplicate
  claim; duplicates from redelivery are collapsed by `_version` under
  `ReplacingMergeTree` `FINAL` (Invariant I3).

---

## 4. Invariants Preserved
- **Invariant I8 (Durable Offset Quiescence)**: restated as parameter-independence
  — the commit frontier is a function of what has been written and acknowledged
  in handoff order, never of any flush/buffer/pool/retry/shutdown value.
- **Invariant I9 (Loud Failure)**: the one parameter interaction that could have
  produced silent divergence (flush-timeout semaphore leak) is a loud stop, not
  a swallowed error.

---

## 5. Verification Criteria
- `OffsetNoLossParameterIndependenceTest.flushCannotStageAnUnwrittenRowAtAnyTimeout()`
  — §3.1/§3.2: a younger written batch is parked, and NOTHING is staged with the
  committer, while an older batch is still unwritten — the state a flush (at any
  `offset.flush.timeout.ms`) would persist never contains an unwritten row.
- `OffsetNoLossParameterIndependenceTest.killAtAnyPointNeverAcknowledgesUnwrittenRows()`
  — §3.1/§3.3: an abrupt stop (`reset()`) with units outstanding abandons only
  what was never acknowledged; the committer staged nothing for them and the FIFO
  is quiescent afterwards (the next start redelivers).
- `OffsetNoLossParameterIndependenceTest.redeliveryAfterAbruptStopIsAtLeastOnceNeverAGap()`
  — §3.1: after an abrupt stop the same units re-handed-off and written ARE
  acknowledged in full — redelivery covers every row, no gap.
- `OffsetNoLossParameterIndependenceTest.commitFrontierIsIndependentOfCompletionOrder()`
  — §3.2 `thread.pool.size` row: out-of-order writes (3,1,2) stage in handoff
  order (1,2,3), one `markBatchFinished` per unit — the frontier does not depend
  on which worker finishes first.
- `OffsetNoLossParameterIndependenceTest.retriedBatchIsNeverAcknowledgedUntilWritten()`
  — §3.2 `errors.max.retries` row: a batch reported not-written is never staged,
  however many times the caller retries; it is staged only once it is written.
- **Formal (parameter-free by construction)**: `Replication.OffsetFifo.commit_never_passes_outstanding`,
  `Replication.OffsetFifo.acked_never_rolled_back`,
  `Replication.OffsetFifo.restart_quiescent`,
  `Replication.OffsetFifo.write_at_most_once`,
  `Replication.OffsetFifo.written_batch_not_reexecuted` — the FIFO model carries
  no flush/buffer/retry parameter, so these safety theorems hold for every
  parameterisation the code can take.
- **Code-level enforcement (§3.5)**: `OffsetCommitDecisionParameterFreeTest.decisionApiExists()`,
  `OffsetCommitDecisionParameterFreeTest.noMethodTouchesConfiguration()`,
  `OffsetCommitDecisionParameterFreeTest.noConstructorOrFieldHoldsConfiguration()`
  — a build-breaking guard that fails if the commit-decision class
  (`DebeziumOffsetManagement`) ever gains a dependency on a configuration type
  (`ClickHouseSinkConnectorConfig` or `java.util.Properties`), i.e. on a tuning
  parameter. It asserts the decision API still exists first, so a rename cannot
  make it pass vacuously — a rename must revisit this spec.

---

## 6. Failure Modes & Recovery
No parameter value moves the durable offset past an unwritten row, so every abrupt stop recovers by redelivery. What parameters do change is the RTO -- how much is redelivered and how soon a failure is detected -- and one value, `errors.max.retries <= 0`, silently disables every catalog statement the connector issues. The guarantee of section 3.1 holds; the recovery-time half of Invariant I15 is parameter-dependent.

- **FM-10.06b-1 The process dies at an arbitrary instant**
  - **Trigger**: `kill -9`, the kernel OOM killer, a host crash, or systemd's `SIGKILL` after `TimeoutStopSec`.
  - **Behaviour**: only offsets staged by `DebeziumOffsetManagement.acknowledgeRecords` (written rows, in handoff order) or quiescent control commits can have reached `replica_source_info`; the next start resumes at or before the first unwritten row and redelivers. Redelivery is bounded by the units handed off and not yet acknowledged -- at most `sink.connector.handoff.max.outstanding.records` (500,000) rows or `sink.connector.handoff.max.outstanding.bytes` (a quarter of the heap) -- plus acknowledged offsets not yet flushed (the engine runs with `OffsetCommitPolicy.always()`; Debezium's flush timing was not re-read in this run).
  - **Detection**: the supervisor sees the process exit (`Restart=always`); on restart INFO `Version floor seeded to {} ms from {}` and the resumed binlog position in the log.
  - **Blast radius**: redelivered rows are rewritten with the same data and collapse under `_version`; no loss.
  - **Recovery**: automatic (systemd restart after `RestartSec=30`).
  - **RTO**: 30 s + engine start + re-apply of at most the handoff cap (500,000 rows; seconds at production write rates) and the in-flight source transaction; unmeasured here (spec 01.08 section 6 owns the chaos harness).
  - **Test**: `OffsetNoLossParameterIndependenceTest.killAtAnyPointNeverAcknowledgesUnwrittenRows()`, `OffsetNoLossParameterIndependenceTest.redeliveryAfterAbruptStopIsAtLeastOnceNeverAGap()`, `OffsetNoLossParameterIndependenceTest.flushCannotStageAnUnwrittenRowAtAnyTimeout()`.

- **FM-10.06b-2 An offset flush times out and poisons the offset writer**
  - **Trigger**: a flush to `replica_source_info` exceeds `offset.flush.timeout.ms` (ClickHouse slow or unreachable); Debezium leaves its `OffsetStorageWriter` in the "already flushing" state.
  - **Behaviour**: the next acknowledgement throws; `ClickHouseBatchRunnable.isOffsetWriterPoisoned` recognises the message before classification and rethrows as a FATAL worker stop; the dead worker makes the engine failure terminal (spec 10.04 FM-10.04-2); the process exits 3 and redelivers from the last durable offset.
  - **Detection**: ERROR `FATAL: the Debezium OffsetStorageWriter is stuck in the 'already flushing' state -- Task({}). ...`, ERROR `Sink worker %d of %d is dead: ...`, FATAL `Replication is STOPPED: ...`, exit code 3; within one Debezium batch.
  - **Blast radius**: all replication stops; rows written after the last durable offset are redelivered; no loss.
  - **Recovery**: automatic via the supervisor once ClickHouse accepts writes to the offset table again.
  - **RTO**: 30 s + engine start + redelivery (FM-10.06b-1); unmeasured.
  - **Test**: `DeadWorkerRetryIsTerminalTest.deadWorkerIsTerminalAtOnce()` (the terminal path the poisoned writer takes), `OffsetNoLossParameterIndependenceTest.flushCannotStageAnUnwrittenRowAtAnyTimeout()`.

- **FM-10.06b-3 Parameters that stretch the RTO without risking loss**
  - **Trigger**: `sink.connector.handoff.max.outstanding.records=0` and `...bytes=0` (caps disabled), very large caps or `buffer.max.records`, `heartbeat.interval.ms=0`, or a large `batch.retry.backoff.max.ms`.
  - **Behaviour**: with the caps disabled the redelivery after FM-10.06b-1 is everything outstanding (bounded only by the heap) and the hard-cap stop of spec 10.04 FM-10.04-4 never happens; with heartbeats off, an idle source commits no control records, a dead worker is detected only at the next source event, and a finished snapshot is not committed (issue #1379); a larger backoff cap delays noticing a fixed cause. None of them can advance the offset past an unwritten row.
  - **Detection**: INFO at start for the heartbeat setting (`Heartbeat interval is set to {}ms by configuration; ... a value of 0 disables heartbeats ...`); none for the caps.
  - **Blast radius**: longer redelivery and slower detection; no loss.
  - **Recovery**: restore the defaults and restart.
  - **RTO**: grows with the configured values; unmeasured.
  - **Test**: `OffsetCommitDecisionParameterFreeTest.noMethodTouchesConfiguration()` (the commit decision reads no parameter), `HandoffHardCapBackpressureTest.zeroDisablesTheCap()`; GAP: a test that relates the configured caps to a stated maximum redelivery and rejects values that exceed the RTO.

- **FM-10.06b-4 `errors.max.retries <= 0` disables every catalog statement**
  - **Trigger**: `errors.max.retries=0` (or negative); `ClickHouseSinkConnectorConfig` declares it with no lower bound, and `DebeziumChangeEventCapture.setup` copies it into `DBMetadata.MAX_RETRIES` and the engine budget.
  - **Behaviour**: every `while (retryCount < MAX_RETRIES)` loop in `DBMetadata` runs zero times: `executeSystemQuery` executes nothing and returns null (a replicated DDL, `CREATE TABLE`, `ADD COLUMN` all "succeed" without running), `getColumnsDataTypesForTable` returns an empty map (every writer is refused its metadata), and `handleEngineCompletion` treats the first engine failure as terminal. A DDL that arrives while nothing is outstanding is acknowledged without having been applied.
  - **Detection**: ERROR `*** TABLE METADATA not retrieved for Database(%s), table(%s), retrying on next attempt` for every table on every attempt; for a DDL only WARN `Timeout ({}ms) waiting for columns to appear in {}.{}: ...` from `DDLSchemaChangeWaiter` when it adds or drops columns, nothing otherwise.
  - **Blast radius**: rows stall loudly; DDL applied at the source during the window is lost from ClickHouse and later rows of those tables diverge (silently for a type change).
  - **Recovery**: set `errors.max.retries` to a positive value (default 10) and restart; apply each DDL acknowledged during the window by hand (`sink-connector-client ddl_translate`) and re-synchronise the affected tables (spec 11.04).
  - **RTO**: restart 30 s + start for the stall; the lost DDL is unbounded (silent); unmeasured.
  - **Test**: `DBMetadataRetryExhaustionTest.zeroRetryBudgetStillExecutesOnce()` (`@Disabled`, confirmed red on 2.11.0).
  - **DEFECT**: a configuration value accepted without validation turns every catalog DDL, including replicated DDL, into a silent no-op.

Summary: 4 failure modes, 1 DEFECT, 1 GAP.

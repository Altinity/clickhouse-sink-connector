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

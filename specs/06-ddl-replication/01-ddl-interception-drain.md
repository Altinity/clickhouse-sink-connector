# Spec 06.01: Pre-DDL Queue Draining & Barrier Synchronization Protocol

## 1. Executive Summary & Purpose
Specifies the execution barrier protocol (`drainBeforeDDL`) that guarantees every
data row read from the source BEFORE a DDL event is durably written to ClickHouse
under the pre-alteration schema before that DDL is executed.

The barrier must cover **every handoff path a row can be on**: the legacy shared
queue, every per-thread hash-routing queue, and the batches a worker has already
dequeued but not yet acknowledged. A row still pending on ANY of those paths when
the ALTER runs is written against the post-DDL schema — the rows insert
successfully, row counts match, and the contents are wrong (e.g. after
`CHANGE COLUMN` the buffered record still carries the old field name, so the
writer binds NULL over the real value). Waiting on only one of the paths is
therefore not a barrier at all; see §3.1 and the formal counterexample in §3.4.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
- **Method**: `private void drainBeforeDDL()`
- **Barrier predicate**: `private boolean isPipelineQuiescent()` — the SAME
  predicate that gates the control-record offset commit (spec 01.06 §3.2), so
  "nothing is pending" has exactly one definition in the codebase.
- **Diagnostic helper**: `private String describePendingHandoff()` — names the
  backlog (legacy batches, routed batches, unacknowledged batches) in the abort
  message.
- **Handoff paths**:
  - `LinkedBlockingQueue<List<ClickHouseStruct>> records` — legacy queue
    (`thread.pool.size == 1`), filled by `appendToRecords()`.
  - `List<LinkedBlockingQueue<RoutedBatch>> routedQueues` — one queue per worker
    (`thread.pool.size > 1`, the default is 10), filled by
    `appendToRecordsWithHashRouting()`.
  - `DebeziumOffsetManagement.hasUnwrittenBatches()` — batches registered by
    `batchHandedOff()` and released only by `acknowledgeRecords()`; covers a batch
    a worker has `poll()`ed but not yet written and acknowledged.
- **Pool primitives**: `ClickHouseBatchExecutor.pause()`, `awaitQuiescent(long)`
  (spec 06.02).
- **Liveness check**: `failIfWorkerDied()` (spec 03.01 §3.3), run on every
  poll of the drain through `failIfWorkerDiedDuringDrain()`.
- **Pre-barrier filter**: `checkIfDDLNeedsToBeIgnored()` (spec 06.08 §3.3),
  evaluated by the DDL branch of `processEveryChangeRecord()` BEFORE
  `drainBeforeDDL()`; an ignored statement never reaches the barrier (§3.5).
- **Progress log interval**: `ddlDrainWarnIntervalMs = 60_000` (60 seconds).
  NOT a timeout — see §3.2.
- **Formal model**: `formal_specs/lean/Replication/DdlBarrier.lean`.

---

## 3. Operational Specification

### 3.1 Handoff paths the barrier must observe

| Path | Populated by | Emptied by | Observed via |
|---|---|---|---|
| legacy `records` | `appendToRecords()` | legacy `ClickHouseBatchRunnable.run()` | `records.isEmpty()` |
| `routedQueues[i]` | `appendToRecordsWithHashRouting()` | worker `i` only | `queue.isEmpty()` for **every** `i` |
| dequeued, not yet acknowledged | worker `poll()` | `DebeziumOffsetManagement.acknowledgeRecords()` | `!DebeziumOffsetManagement.hasUnwrittenBatches()` |

`ClickHouseBatchExecutor.awaitQuiescent()` observes only `activeBatches` — the
batches inside a task body *right now*. Between two scheduled ticks that count
is 0 even while every routed queue holds work, so `pause()` + `awaitQuiescent()`
on their own do **not** establish the barrier. The queue and acknowledgement
checks in step 1 are what make the barrier real; the pause/quiescence pair then
closes the window for a batch that started just before the pause.

### 3.2 Protocol

0. **Single-threaded mode** (`executor == null`): return immediately. Rows are
   persisted inline on the Debezium thread before the DDL record is handled, so
   nothing is pending and there is no pool to pause (spec 06.08 §3.1 point 4).
1. **Drain phase — pool still running.** Loop until `isPipelineQuiescent()`
   holds, i.e. until ALL of:
   - `records` is null or empty,
   - every queue in `routedQueues` is empty, and
   - `!DebeziumOffsetManagement.hasUnwrittenBatches()`.

   Poll every 50 ms. The pool MUST NOT be paused before this phase completes:
   `pause()` parks every worker in `beforeExecute()`, so a paused pool can never
   shrink a queue and the loop would always end in the abort below (the
   self-inflicted deadlock fixed in #1445). The queued set is fixed for the
   duration of the drain because both producers (`appendToRecords()` and
   `appendToRecordsWithHashRouting()`) run on the very Debezium thread that is
   executing the drain.

   The wait is bounded by **liveness, not by time**:
   - On every poll, `failIfWorkerDiedDuringDrain()`: a worker whose scheduled
     task has terminated makes the pending backlog undrainable, so the attempt
     is aborted at once with an `IllegalStateException` that names the backlog
     via `describePendingHandoff()` and carries the worker's cause. Applying the
     DDL over that backlog is the silent-corruption case; aborting is the only
     safe outcome.
   - Every `ddlDrainWarnIntervalMs` (60 s) a WARN names the backlog still
     pending and the elapsed time; the drain keeps waiting. A backlog held by a
     LIVE worker — one retrying a transient ClickHouse error such as
     `TOO_MANY_PARTS`, or reconnecting — is slow, not dead, and will drain. The
     previous fixed 60 s abort turned exactly that into `DDLReplicationException`
     → engine restart → the same drain → after `errors.max.retries` a terminal
     stop (spec 10.04 §3.5), for a condition that would have cleared. There is
     no wall-clock limit because no wall-clock limit is correct: the DDL may
     only ever be applied once every pre-DDL row is in ClickHouse, however long
     that takes.
   - On interruption (the engine being closed): throw `IllegalStateException`
     (never apply the DDL over in-flight writes).
2. **Pause**: `executor.pause()` — no new batch may start.
3. **Await in-flight quiescence**: loop on
   `executor.awaitQuiescent(ddlDrainWarnIntervalMs)`; a batch retrying a
   transient error stays inside its task body for the whole retry sequence, so
   this wait is bounded by liveness too: each round re-checks
   `failIfWorkerDiedDuringDrain()` and the interrupt flag, and logs a WARN.

Any exception from steps 1–3 is wrapped into `DDLReplicationException` by the
DDL branch of `processEveryChangeRecord()` and halts the pipeline (spec 06.08
§3.1). The pool is resumed in a `finally` so a failed drain is a retryable
event, not a stall.

### 3.3 Why the barrier predicate is `isPipelineQuiescent()`
The control-record commit (spec 01.06) already had to answer "is anything the
connector has read still unwritten?" and answers it with the three checks above.
Reusing that predicate for the DDL barrier means the two safety properties
(never commit a heartbeat past unwritten rows; never apply a DDL over unwritten
rows) cannot drift apart when a fourth handoff path is added: whoever extends
`isPipelineQuiescent()` extends both.

### 3.4 Formal model (`Replication.DdlBarrier`)
The barrier is modelled abstractly as a state with a legacy queue, N routed
queues and an `outstanding` counter of dequeued-but-unacknowledged batches:

- `barrierReady s := s.legacy = [] ∧ (∀ q ∈ s.routed, q = []) ∧ s.outstanding = 0`
- `legacyEmpty s := s.legacy = []` — the pre-fix guard.
- `Step` — handoff to the legacy or a routed queue, a worker taking a batch off
  a queue, a write acknowledgement, and `ddl`, which is enabled ONLY by
  `barrierReady`.

Theorems (machine-checked, no `sorry`):
- `ddl_applies_only_when_no_pending_rows` — if a step applies the DDL then
  `pending s = 0`: no pre-DDL batch is on any queue or in flight.
- `ddl_step_barrierReady` — a step that applies the DDL was taken from a state
  satisfying the full predicate.
- `old_predicate_insufficient` — `∃ s, legacyEmpty s ∧ ¬ barrierReady s`
  (concrete witness: legacy empty, one routed queue holding a batch).
- `old_predicate_admits_pending_rows` — the same witness has `0 < pending s`:
  the pre-fix guard would apply the DDL over a pending row.
- `queues_empty_insufficient` — both queue sets empty is still not enough while
  a batch is dequeued and unacknowledged.

### 3.5 An ignored DDL takes no barrier
The ignore rules of spec 06.08 §3.3 (`checkIfDDLNeedsToBeIgnored()`:
`disable.ddl=true`, an `ignore.ddl.regex` or bundled-pattern match, a table
outside the capture lists, snapshot DDL without `enable.snapshot.ddl`) are
evaluated BEFORE `drainBeforeDDL()`. A statement they reject is logged once,
at INFO, by the rule that rejected it — the line names the rule (spec 06.08
§3.3); the DDL branch itself adds only a DEBUG line, never a second INFO copy
of the statement — and recorded in `lastIgnoredDDL` (the two rules that reject
every statement, `disable.ddl=true` and snapshot DDL without
`enable.snapshot.ddl`, stay silent above DEBUG). The DDL branch ends there: no drain, no
`pause()`, no `awaitQuiescent()`, no translation. Invariant I5 is about rows
written under the pre-DDL schema before the schema CHANGES; a statement that is
never applied changes nothing, so there is nothing for the barrier to protect.
The record's offset is committed exactly like that of any other record without
a row — only once the pipeline is quiescent (spec 09.04) — so skipping the
drain moves no durable position past an unwritten batch.

The order matters operationally. The ignore rules cost regex matches and list
lookups; the drain costs the whole queued backlog. Measured: 6 min 28 s for one
`CREATE OR REPLACE ... SQL SECURITY DEFINER VIEW` that matched
`ignore.ddl.regex` while 1,356 batches were queued, during which the blocked
event thread let the binlog client's keepalive declare the source connection
lost and reconnect (a re-delivery). Every one of those seconds was spent
deciding to do nothing; the twenty-two view statements that followed in the
same minute were then cheap only because the queue had already been emptied.

`disable.drop.truncate` (spec 06.08 §3.5) is NOT part of this pre-barrier
decision: its flag is computed by the parser, after the drain, and a suppressed
`DROP TABLE`/`TRUNCATE` is rare.

---

## 4. Invariants Preserved
- **Invariant I5 (DDL Barrier Quiescence)**: every pre-DDL data record — on the
  legacy queue, on any routed queue, or dequeued but unacknowledged — is written
  under the pre-DDL schema before the DDL executes.
- **Invariant I9 (Loud Failure)**: a barrier that can never be reached (a dead
  worker) aborts the DDL attempt loudly instead of applying it over pending
  rows; a barrier that is merely slow is waited for, visibly (WARN per
  interval), never silently abandoned and never turned into a terminal stop.

---

## 5. Verification Criteria
- `Replication.DdlBarrier.ddl_applies_only_when_no_pending_rows`,
  `Replication.DdlBarrier.old_predicate_insufficient`,
  `Replication.DdlBarrier.old_predicate_admits_pending_rows`,
  `Replication.DdlBarrier.queues_empty_insufficient` (Lean, `lake build`).
- `DdlIgnoreRulesTest.ignoredDdlDoesNotDrainThePipeline` — §3.5: with a live
  pool and one batch queued that nobody consumes, a statement matching
  `ignore.ddl.regex` returns at once through `processEveryChangeRecord`, is
  recorded in `lastIgnoredDDL`, the batch is still queued and the pool was never
  paused. Fails on the pre-fix code, which drained first and waited until
  interrupted.
- `DdlIgnoreRulesTest.appliedDdlStillDrainsThePipeline` — §3.5 control: the
  same setup with an applied `ALTER TABLE` is still waiting on the barrier
  after a second, has discarded nothing, and ends only on interrupt with
  `DDLReplicationException`.
- `DdlDrainDeadlockTest.testDrainDoesNotDeadlockOnItsOwnPause` — a non-empty
  legacy queue is drained before the pause (regression for #1445).
- `DdlDrainDeadlockTest.testWriterIsPausedAndQuiescentAfterDrain` — the pool is
  paused and `awaitQuiescent(0)` holds when the drain returns.
- `DdlDrainDeadlockTest.testStuckQueueWithDeadWorkerAborts` — INVERTED from
  `testGenuinelyStuckQueueStillAborts` (a 60 s timeout abort): an undrainable
  legacy queue aborts promptly, with the dead worker's cause, and only because
  a worker is dead.
- `DdlDrainDeadlockTest.testUndrainableQueueWithLiveWorkersKeepsWaiting` — with
  a live worker the drain is still waiting well past several warn intervals,
  has logged a WARN naming the pending backlog, has discarded nothing, and ends
  only on interrupt (with `IllegalStateException`). Fails on the pre-fix code,
  which aborted on the timeout.
- `DdlDrainDeadlockTest.testDrainWaitsForRoutedQueues` — `thread.pool.size > 1`,
  legacy queue empty, one routed queue non-empty and drained by a worker after a
  delay: the drain blocks until that queue is empty and returns with the pool
  paused. Fails on the pre-fix code, which returned immediately.
- `DdlDrainDeadlockTest.testUndrainableRoutedQueueWithDeadWorkerAborts` —
  INVERTED from `testUndrainableRoutedQueueAborts` (timeout abort): the routed
  queue is never drained and its worker is dead: the drain aborts with a
  message naming the routed backlog, and the batch is not discarded. Still
  fails on code that never looks at the routed queues (returned immediately).
- `DdlDrainDeadlockTest.testDrainWaitsForUnacknowledgedBatches` — both queue
  sets empty but one batch registered via `batchHandedOff()` and not yet
  acknowledged: the drain blocks until it is released. Fails on the pre-fix
  code.
- `DdlFailureLoudTest.drainIsNoOpWhenExecutorIsNull` — single-threaded mode.
- Mutation check: reverting step 1 to `while (!records.isEmpty())` turns the
  three new tests red; restoring it turns them green.

---

## 6. Failure Modes & Recovery
The barrier trades availability for correctness on purpose: while it is held, NO table replicates, and it is released only when every pre-DDL row is in ClickHouse or the attempt aborts without applying the DDL. Recovery is therefore always "remove the cause, let the barrier drain or restart and let the DDL be re-delivered"; the DDL's offset is never committed by a failed attempt (spec 06.08 §3.1).

- **FM-06.01-1 Barrier held by a live but failing writer**
  - **Trigger**: a pre-DDL batch keeps failing with a retriable ClickHouse error while the DDL waits: `TOO_MANY_PARTS` (252), `MEMORY_LIMIT_EXCEEDED` (241), ClickHouse restarting, a replica read-only after Keeper loss, a network cut.
  - **Behaviour**: `DebeziumChangeEventCapture.awaitPipelineQuiescent()` (step 1 of `drainBeforeDDL()`) polls `isPipelineQuiescent()` every 50 ms with no time limit; the worker retries the batch forever with the 500 ms to 30 s backoff of spec 10.02. The Debezium thread is blocked in the drain, so no event of any table is read; a long block lets the binlog client's keepalive declare the source connection lost and reconnect (§3.5 measured a 6 min 28 s drain doing exactly this).
  - **Detection**: WARN every `ddlDrainWarnIntervalMs` (60 s) `Pipeline drain: <n> legacy queue batch(es), <n> routed queue batch(es), unacknowledged handed-off batches: yes still pending after <ms> ms; the writers are alive, so the backlog is a slow or retrying batch, not a dead one.` plus the worker's own retry ERRORs. No ERROR from the barrier itself and no metric names the stalled barrier; replication lag in `show_replica_status` grows.
  - **Blast radius**: every table stops; nothing is lost, duplicated or reordered (offsets stay behind the unwritten batch).
  - **Recovery**: remove the ClickHouse-side cause (merges catch up, memory freed, Keeper back, replica writable). Self-heals: the next retry lands within the 30 s backoff cap, the drain completes and the DDL is applied. No operator step on the connector.
  - **RTO**: cause removal + at most 30 s (backoff cap, spec 10.02) + write of the queued backlog; unmeasured end to end.
  - **Test**: `DdlDrainDeadlockTest.testUndrainableQueueWithLiveWorkersKeepsWaiting()` (waits past several warn intervals, logs the WARN, discards nothing).
  - **DEFECT**: the stalled barrier is reported only at WARN, with no ERROR line and no metric, so a stall of every table on a still-failing ClickHouse satisfies neither half of I15 property 1.

- **FM-06.01-2 A worker dies while the barrier waits for its backlog**
  - **Trigger**: a worker's scheduled task terminates (FATAL batch error, `Error`, bug) while batches are queued for it or dequeued and unacknowledged.
  - **Behaviour**: `failIfWorkerDiedDuringDrain()` on the next 50 ms poll turns `failIfWorkerDied()`'s exception into `IllegalStateException`; the DDL branch of `processEveryChangeRecord()` wraps it in `DDLReplicationException`; the pool is resumed in `finally`; `handleEngineCompletion()` sees `hasDeadWorker()` and stops terminally without drawing on the retry budget.
  - **Detection**: ERROR `Sink worker <i> of <n> is dead: its scheduled task has terminated...`, then `DDL drain: a worker died while <backlog>; that backlog can never drain...Aborting this DDL attempt.`, then `Engine stopped while a sink worker is dead; not retrying...` and FATAL `Replication is STOPPED...`; process exit code 3 (`exit.on.terminal.failure=true`, default). Within 50 ms of the worker's death.
  - **Blast radius**: all replication stops; the DDL is not applied; no data lost (offsets behind the backlog).
  - **Recovery**: fix what killed the worker (its cause is in the first ERROR); restart the process (systemd / supervisor, or `sink-connector-client restart`). It resumes from the last committed offset, re-writes the backlog (ReplacingMergeTree collapses the duplicates) and re-delivers the DDL.
  - **RTO**: process restart + replay from the last committed offset to the DDL; unmeasured.
  - **Test**: `DdlDrainDeadlockTest.testStuckQueueWithDeadWorkerAborts()`, `DdlDrainDeadlockTest.testUndrainableRoutedQueueWithDeadWorkerAborts()`.

- **FM-06.01-3 Barrier attempt interrupted or failed (shutdown, stop_replica, engine close)**
  - **Trigger**: the engine is closed or the Debezium thread interrupted while the drain waits.
  - **Behaviour**: the 50 ms sleep throws; `awaitPipelineQuiescent()` raises `IllegalStateException("Pipeline drain interrupted before the writer was quiescent.")`, wrapped in `DDLReplicationException`; `finally` calls `executor.resume()`; the DDL offset is not acknowledged.
  - **Detection**: ERROR `Engine stopped with an error: ...DDL replication failed for [<DDL>]...` (or a clean stop when the operator stopped it); immediate.
  - **Blast radius**: none beyond the stop; the pending batch is kept and written by the resumed pool.
  - **Recovery**: none needed; on the next start Debezium re-delivers the DDL from the last committed offset.
  - **RTO**: engine restart (~15-20 s, spec 10.04 measured 13-19 s between restarts) + re-delivery of the DDL; unmeasured here.
  - **Test**: `DdlFailureModesTest.failedDdlLeavesThePoolResumed()` (pool not left paused, batch not discarded, offset not acknowledged), `DdlFailureLoudTest.ddlFailurePropagatesInsteadOfBeingSwallowed()`.

- **FM-06.01-4 DDL storm (hundreds of ALTERs)**
  - **Trigger**: a migration or ORM issues hundreds of `ALTER TABLE` statements interleaved with DML.
  - **Behaviour**: every applied DDL pays, on the single Debezium thread and with every table stopped: a full drain of the DML queued since the previous DDL, `pause()`/`awaitQuiescent()`, two `system.columns` reads by the translator (`TargetSchemaLookup`), the ALTER round trip (for `Replicated*` tables through Keeper), and `DDLSchemaChangeWaiter.waitForSchemaVisibility()`: a fixed 500 ms sleep (`5 x ddl.schema.change.poll.interval.ms`) for any statement without `ADD`/`DROP COLUMN`, polling up to `ddl.schema.change.timeout.ms` (30 s) for those with. 300 `MODIFY COLUMN` statements therefore cost at least 150 s of pure sleep. Ignored statements take none of this (§3.5).
  - **Detection**: INFO `***** DDL received, Flush all existing records` and `ClickHouse DDL: <stmt>` per statement; lag grows. No metric counts DDLs usefully: `clickhouse.sink.ddl` is incremented by the elapsed time of `updateMetrics()` itself (about 0) under the tag `fail=true` on SUCCESS (`DebeziumChangeEventCapture.updateMetrics`, `Metrics.updateDdlMetrics`).
  - **Blast radius**: throughput collapse and lag for all tables; no loss.
  - **Recovery**: self-heals when the storm ends. Mitigation: `ignore.ddl.regex` for statements that need not be replicated (no drain for them), `ddl.schema.change.poll.interval.ms` lower.
  - **RTO**: proportional to the storm: >= 0.5 s x statements + ALTER latency; unmeasured.
  - **Test**: GAP: a unit test that feeds N applied DDLs through processEveryChangeRecord with a recording connection and asserts the per-DDL fixed cost (drains, sleeps, metadata queries) stays within a stated bound.
  - **DEFECT**: the DDL metric `clickhouse.sink.ddl` is recorded only on success, tagged `fail=true`, and incremented by about 0, so neither DDL volume nor DDL failures are observable from metrics.

Summary: 4 failure modes, 2 DEFECT, 1 GAP.

# Spec 09.04: Non-DML Control Record Commit & Quiescence Gating

## 1. Executive Summary & Purpose
Specifies the quiescence gating rules required before advancing persistent offsets for non-data control records (e.g. heartbeats and snapshot completion tokens).

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
- **Methods**: `commitControlRecordOffset()`, `isPipelineQuiescent()`
- **Quiescence primitives**: `DebeziumOffsetManagement.hasUnwrittenBatches()` and `DebeziumOffsetManagement.hasUnprovenAbandonment()` (spec 09.01 §3.6)

---

## 3. Operational Specification

When a heartbeat event or null-payload record arrives:
1. Check `handedOffRows`: if true (the batch also contained DML records), the control record offset is skipped to allow DML batch flushes to commit the offset naturally.
2. If `handedOffRows == false`, the commit is taken only when BOTH of these
   hold at the GATE (`commitControlRecordOffset` itself):
   - `!DebeziumOffsetManagement.hasUnprovenAbandonment()` — no `reset()`
     abandoned units whose rows are still unaccounted for (spec 09.01 §3.6).
     This is why the gate is not merely "the pipeline is quiescent": an
     abandoned unit leaves the FIFO without ever being acknowledged, so the
     set can be empty while rows it owed are still absent from ClickHouse.
     A heartbeat's offset is the CURRENT position, ahead of them.
   - `isPipelineQuiescent()` — true iff ALL of:
     - the legacy `records` queue is null or empty,
     - every per-thread routed queue is empty, and
     - `!DebeziumOffsetManagement.hasUnwrittenBatches()`, i.e. the set of
       outstanding handoff sequences is empty — no unit handed to the writers
       is still unwritten, in flight, or parked awaiting acknowledgement.
   
   **The abandonment latch is a term of the gate only.** It is deliberately NOT
   a term of `isPipelineQuiescent()`: that predicate is also what the drains
   read (`drainBeforeStop()`, `awaitPipelineQuiescent()` behind the DDL and
   TRUNCATE barriers, and `setup()`'s start-time refusal), and all of those
   must keep treating an abandoned FIFO as EMPTY — a latch inside the predicate
   makes the stop drain burn its whole `stopDrainTimeoutMs`, makes the DDL
   drain wait on liveness until it aborts, and refuses the restart that exists
   to recover from the abandonment. None of those consumers can release the
   latch: only an acknowledged post-reset unit can, and that is a write
   (spec 09.01 §3.6).
   - If both hold: calls `committer.markProcessed(controlRecord)` and `committer.markBatchFinished()` under `OFFSET_COMMIT_LOCK`.
   - Otherwise: discards the heartbeat without committing.

The outstanding set is populated by the PRODUCER at handoff (before the batch
is visible to any consumer) and emptied only by acknowledgement, so the queue
checks and the set together leave no window in which a handed-off batch is in
neither. A worker that died (spec 03.01 §3.3) leaves its batches outstanding
forever, so no control offset can pass them; that stall is surfaced loudly by
`handleChangeEventBatch` rather than left silent.

---

## 4. Invariants Preserved
- **Invariant I8 (Durable Offset Quiescence)**: Guarantees that control records never leapfrog in-flight data rows, whether those rows are still outstanding or were abandoned by a `reset()`.
- **Invariant I12**: safety half of the control-record commit.

---

## 5. Verification Criteria
- `ControlRecordOffsetCommitTest` — commit on a quiescent pipeline; no commit while rows are handed off; no commit while an abandonment is unproven.
- `HandedOffBatchVisibilityTest` — a handed-off unit blocks the commit until every one of its groups is written and the unit is acknowledged.
- `AbandonLatchTest` — spec 09.01 §3.6, in the `sink-connector` module: the latch's own transitions — armed only by a `reset()` that abandoned a unit, armed by neither a handoff nor an acknowledgement, cleared by a post-reset acknowledgement, kept armed by a pre-reset one.
- `AbandonLatchGateTest` — the same rule at the GATE, in the `sink-connector-lightweight` module where `commitControlRecordOffset` is reachable: an armed latch withholds the control-record commit with an empty FIFO, nothing is staged with the engine, and the commit resumes once a post-reset unit is acknowledged.
- `DdlDrainDeadlockTest.testArmedLatchDoesNotBlockTheDdlDrain()`, `DdlDrainDeadlockTest.testArmedLatchDoesNotBlockTheStopDrain()` — §3's negative half: with the latch ARMED and the FIFO empty, the DDL drain returns and the stop drain does not burn its timeout. A latch folded into `isPipelineQuiescent()` fails both (the DDL drain aborts with `DDL drain:` after the 150 ms wait; the stop drain reports the timeout).
- `EngineRestartFifoResetTest.stopThenStartNewInstanceIsNotPoisoned()` — end to end: `stop()` abandons the outstanding unit and arms the latch, the restarted engine's heartbeat is withheld, its first unit is acknowledged, and the next heartbeat commits.
- `Replication.Snapshot.control_commit_safe` / `quiescent_control_commits`.

---

## 6. Failure Modes & Recovery
The gate fails safe: when in doubt it withholds the commit, so its failure modes are a durable offset that lags the source, never one that passes unwritten rows. The cost of that lag is re-reading, at the next start, the binlog written since the last commit — for a connector that replicates a few quiet tables of a busy server, that can be much more than the in-flight transaction. A control-record flush that fails is spec 09.03 §6 FM-09.03-1.

- **FM-09.04-1 Heartbeats withheld behind a stuck unit**
  - **Trigger**: a unit stays outstanding on a source whose replicated tables are idle: a live worker retrying (spec 09.01 FM-09.01-2), an acknowledgement waiting for the next drain (spec 09.01 FM-09.01-4).
  - **Behaviour**: `DebeziumChangeEventCapture.commitControlRecordOffset` returns `false` while `isPipelineQuiescent()` is false (`DebeziumOffsetManagement.hasUnwrittenBatches()`), and logs nothing; the durable position stays at the last acknowledged row while the source position moves on.
  - **Detection**: none for the withheld commit itself (no log line on the skip path); the stuck unit's own WARN/ERROR lines (spec 09.01 §6) are the signal.
  - **Blast radius**: no loss; a restart re-reads the binlog written since the last commit (events of non-replicated databases are read and skipped), proportional to the source's write volume during the stall.
  - **Recovery**: clear the stuck unit (spec 09.01 §6); the first heartbeat after quiescence commits the current position.
  - **RTO**: one `heartbeat.interval.ms` (default 5 000 ms) after the pipeline is quiescent; a restart during the stall costs the re-read (unbounded). Unmeasured.
  - **Test**: `ControlRecordOffsetCommitTest.heartbeatIsNotCommittedAheadOfUnwrittenRows()`, `HandedOffBatchVisibilityTest.parkedBatchStillBlocksTheCommit()`.

- **FM-09.04-2 Heartbeats disabled by configuration**
  - **Trigger**: `heartbeat.interval.ms=0` set explicitly.
  - **Behaviour**: `DebeziumChangeEventCapture.ensureHeartbeatInterval` honours the explicit value; no control records arrive, so on idle replicated tables the offset never moves past non-replicated events, and the completed state of an initial snapshot on an idle source is never committed — the snapshot re-runs at every restart (issue #1379).
  - **Detection**: INFO `Heartbeat interval is set to 0ms by configuration; leaving it unchanged. Note that a value of 0 disables heartbeats, and on a source that goes idle after the initial snapshot the snapshot's completed state will then never be committed (issue #1379).` at start — INFO only.
  - **Blast radius**: every restart re-reads the binlog since the last row of a replicated table, or repeats the whole initial snapshot.
  - **Recovery**: remove `heartbeat.interval.ms` (the default 5 000 ms applies) or set a positive value; restart the process.
  - **RTO**: restart ~20 s after the fix; before it, each restart costs a full re-snapshot (hours at production size). Unmeasured.
  - **Test**: `HeartbeatIntervalDefaultTest.testExplicitZeroIsHonoured()`, `HeartbeatIntervalDefaultTest.testAbsentIntervalGetsDefault()`.
  - **DEFECT**: a configuration that makes every restart repeat the initial snapshot is reported at INFO.

- **FM-09.04-4 A heartbeat commits the current position while an abandoned unit's rows are still missing** — CLOSED by §3
  - **Trigger**: an in-process engine restart abandons outstanding units (`DebeziumOffsetManagement.reset()`, spec 09.01 §3.8), the new engine has not yet written anything, and a heartbeat arrives.
  - **Behaviour**: before §3's latch term at the gate, `reset()` cleared `outstandingSequences`, so `hasUnwrittenBatches()` answered "quiescent" the instant the FIFO was emptied, and the heartbeat's offset — the connector's CURRENT position, past the abandoned rows — was committed. The abandoned units were then re-read only if the process died before the next commit; a restart after that commit resumed AFTER them and the rows were never written. With the gate term the commit is withheld until a unit assigned after the reset is acknowledged, i.e. until the redelivered stream has actually reached the FIFO's head and something durably written proves the position is safe to claim.
  - **Detection**: the WARN `Offset FIFO reset: abandoning N handed-off unit(s) ...` (spec 09.01 §3.8 item 1); with the latch, heartbeats stop committing until the new stream is written, and the INFO `Abandonment latch cleared ...` marks the release.
  - **Blast radius**: with the term, none — the durable offset lags, which on restart re-reads the binlog since the last commit (spec 09.04 §6, FM-09.04-1's cost). Without it, silent loss of every abandoned row.
  - **Recovery**: none needed; the latch releases itself on the first acknowledged post-reset unit. A value-level checksum (spec 11.02) is how a deployment that ran a build predating the term finds what it lost.
  - **RTO**: to the first acknowledged unit after the reset — a retry backoff plus one write (unmeasured); no operator action.
  - **Test**: `AbandonLatchGateTest.aResetThatAbandonsAUnitWithholdsTheControlRecordCommit()` — fails on 2.11.0 (where the gate was `!hasUnwrittenBatches()` alone), passes with the term; `DdlDrainDeadlockTest.testArmedLatchDoesNotBlockTheDdlDrain()` pins the other half — the latch must not reach the drains.
  - **CLOSED**: an abandoned unit keeps the control-record commit withheld until a post-reset unit is acknowledged.

- **FM-09.04-3 Control commit racing a worker's acknowledgement**
  - **Trigger**: a heartbeat-only batch arrives while a worker is draining the last outstanding unit.
  - **Behaviour**: the producer is the only thread that adds units, and it is the thread evaluating the gate, so no unit can appear between the check and the commit; a unit is removed from `outstandingSequences` only after its acknowledgement (`drainCompletedUnits`), so a drain in progress keeps the pipeline non-quiescent; the control-record commit and the drain both run under `OFFSET_COMMIT_LOCK` (spec 09.02), and the heartbeat's offset is newer than every row's, so the staged position stays monotone.
  - **Detection**: not applicable — the race cannot pass unwritten rows.
  - **Blast radius**: none.
  - **Recovery**: none needed.
  - **RTO**: 0 — no failure to recover from.
  - **Test**: `ControlRecordOffsetCommitTest.heartbeatOnlyBatchCommitsItsOffset()`, `ControlRecordOffsetCommitTest.heartbeatIsNotCommittedAheadOfUnwrittenRows()`, `OffsetCommitSerializationTest.testAcknowledgeRecordSerializesAgainstBatchVariant()`.

Summary: 4 failure modes, 1 DEFECT, 0 GAP.

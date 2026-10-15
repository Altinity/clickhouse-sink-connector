# Spec 09.04: Non-DML Control Record Commit & Quiescence Gating

## 1. Executive Summary & Purpose
Specifies the quiescence gating rules required before advancing persistent offsets for non-data control records (e.g. heartbeats and snapshot completion tokens).

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
- **Methods**: `commitControlRecordOffset()`, `isPipelineQuiescent()`
- **Quiescence primitive**: `DebeziumOffsetManagement.hasUnwrittenBatches()` (spec 09.01 §3.6)

---

## 3. Operational Specification

When a heartbeat event or null-payload record arrives:
1. Check `handedOffRows`: if true (the batch also contained DML records), the control record offset is skipped to allow DML batch flushes to commit the offset naturally.
2. If `handedOffRows == false`:
   - Checks `isPipelineQuiescent()`, which is true iff ALL of:
     - the legacy `records` queue is null or empty,
     - every per-thread routed queue is empty, and
     - `!DebeziumOffsetManagement.hasUnwrittenBatches()`, i.e. the set of
       outstanding handoff sequences is empty — no unit handed to the writers
       is still unwritten, in flight, or parked awaiting acknowledgement.
   - If true: calls `committer.markProcessed(controlRecord)` and `committer.markBatchFinished()` under `OFFSET_COMMIT_LOCK`.
   - If false: discards the heartbeat without committing.

The outstanding set is populated by the PRODUCER at handoff (before the batch
is visible to any consumer) and emptied only by acknowledgement, so the queue
checks and the set together leave no window in which a handed-off batch is in
neither. A worker that died (spec 03.01 §3.3) leaves its batches outstanding
forever, so no control offset can pass them; that stall is surfaced loudly by
`handleChangeEventBatch` rather than left silent.

---

## 4. Invariants Preserved
- **Invariant I8 (Durable Offset Quiescence)**: Guarantees that control records never leapfrog in-flight data rows.
- **Invariant I12**: safety half of the control-record commit.

---

## 5. Verification Criteria
- `ControlRecordOffsetCommitTest` — commit on a quiescent pipeline; no commit while rows are handed off.
- `HandedOffBatchVisibilityTest` — a handed-off unit blocks the commit until every one of its groups is written and the unit is acknowledged.
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

- **FM-09.04-3 Control commit racing a worker's acknowledgement**
  - **Trigger**: a heartbeat-only batch arrives while a worker is draining the last outstanding unit.
  - **Behaviour**: the producer is the only thread that adds units, and it is the thread evaluating the gate, so no unit can appear between the check and the commit; a unit is removed from `outstandingSequences` only after its acknowledgement (`drainCompletedUnits`), so a drain in progress keeps the pipeline non-quiescent; the control-record commit and the drain both run under `OFFSET_COMMIT_LOCK` (spec 09.02), and the heartbeat's offset is newer than every row's, so the staged position stays monotone.
  - **Detection**: not applicable — the race cannot pass unwritten rows.
  - **Blast radius**: none.
  - **Recovery**: none needed.
  - **RTO**: 0 — no failure to recover from.
  - **Test**: `ControlRecordOffsetCommitTest.heartbeatOnlyBatchCommitsItsOffset()`, `ControlRecordOffsetCommitTest.heartbeatIsNotCommittedAheadOfUnwrittenRows()`, `OffsetCommitSerializationTest.testAcknowledgeRecordSerializesAgainstBatchVariant()`.

Summary: 3 failure modes, 1 DEFECT, 0 GAP.

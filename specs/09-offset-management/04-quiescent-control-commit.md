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

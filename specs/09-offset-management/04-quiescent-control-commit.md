# Spec 09.04: Non-DML Control Record Commit & Quiescence Gating

## 1. Executive Summary & Purpose
Specifies the quiescence gating rules required before advancing persistent offsets for non-data control records (e.g. heartbeats and snapshot completion tokens).

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
- **Method**: `commitControlRecordOffset()`

---

## 3. Operational Specification

When a heartbeat event or null-payload record arrives:
1. Check `handedOffRows`: if true (the batch also contained DML records), the control record offset is skipped to allow DML batch flushes to commit the offset naturally.
2. If `handedOffRows == false`:
   - Checks `isPipelineQuiescent()`.
   - If true: calls `committer.markProcessed(controlRecord)` and `committer.markBatchFinished()` under `OFFSET_COMMIT_LOCK`.
   - If false: discards the heartbeat without committing.

---

## 4. Invariants Preserved
- **Invariant I8 (Durable Offset Quiescence)**: Guarantees that control records never leapfrog in-flight data rows.

---

## 5. Verification Criteria
- `DebeziumChangeEventCaptureTest.testControlRecordOffsetQuiescence()`

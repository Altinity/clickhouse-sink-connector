# Spec 01.06: Control Records, Heartbeats & Snapshot Quiescence

## 1. Executive Summary & Purpose
Specifies the offset commitment rules for non-data control records, such as Debezium heartbeats and snapshot completion tokens. Committing offsets on control records without verifying pipeline quiescence causes offset progression over unwritten in-flight rows, leading to silent data loss on restart.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
- **Key Methods**:
  - `boolean commitControlRecordOffset(ChangeEvent<String, String> controlRecord, RecordCommitter<ChangeEvent<String, String>> committer, boolean handedOffRows)`
  - `boolean isPipelineQuiescent()`

---

## 3. Operational Specification

### 3.1 The Quiescence Verification Check
Before any control record offset can be committed via `committer.markProcessed(controlRecord)`:
`isPipelineQuiescent()` must return `true`.
Quiescence requires that all three conditions hold simultaneously:
1. `records == null || records.isEmpty()` (the handoff queue is empty).
2. `routedRecords == null || routedRecords.isEmpty()` (the topic routing queue is empty).
3. `!DebeziumOffsetManagement.hasUnwrittenBatches()` (zero batches are currently in-flight across worker threads).

### 3.2 Commit Serialization
If `isPipelineQuiescent() == true` and `handedOffRows == false`:
1. Acquires `synchronized (OFFSET_COMMIT_LOCK)`.
2. Invokes:
   ```java
   committer.markProcessed(controlRecord);
   committer.markBatchFinished();
   ```
3. Logs quiescence confirmation.
4. Returns `true`.
If the pipeline is not quiescent, returns `false` without advancing the offset.

---

## 4. Invariants Preserved
- **Invariant I8 (Durable Offset Quiescence)**: No offset may ever be committed to disk that is ahead of data actually acknowledged by ClickHouse.
- **Snapshot Recovery Safety**: Prevents snapshot restart loops where the snapshot offset is committed before initial snapshot tables finish inserting.

---

## 5. Verification Criteria
- `DebeziumChangeEventCaptureTest.testCommitControlRecordOffsetQuiescent()`
- `DebeziumChangeEventCaptureTest.testCommitControlRecordOffsetNonQuiescent()`

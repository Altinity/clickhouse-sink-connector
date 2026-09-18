# Specification 008: Offset Management, Quiescence & Checkpointing

## 1. Executive Summary & Purpose

The offset management subsystem coordinates the durable acknowledgment and checkpointing of CDC source coordinates. Because ClickHouse batch insertion executes asynchronously across worker threads, offsets must not be committed to storage out of order. The subsystem enforces strictly serialized, FIFO offset advancement, guarantees crash-recovery idempotency, and synchronizes Debezium engine flush semaphores under a unified mutual exclusion lock.

---

## 2. Codebase Mapping on 2.11.0

- **Primary Classes**:
  - `com.altinity.clickhouse.sink.connector.db.operations.DebeziumOffsetManagement` (`sink-connector/...`)
  - `com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture` (`sink-connector-lightweight/...`)
  - `com.altinity.clickhouse.debezium.embedded.storage.JdbcOffsetBackingStore` (`sink-connector-lightweight/...`)
- **Key Methods & Locks**:
  - `DebeziumOffsetManagement.addToBatchTimestamps(batch)`
  - `DebeziumOffsetManagement.checkIfBatchCanBeCommitted(batch)`
  - `DebeziumOffsetManagement.acknowledgeRecords()`
  - `OFFSET_COMMIT_LOCK`: Object monitor serializing all offset commits

---

## 3. FIFO Offset Commitment Protocol

In a multi-threaded batch executor, batches may complete out of arrival order. If Batch 2 completes while Batch 1 is still in-flight, committing Batch 2's offset would create a permanent data loss window if the process crashes before Batch 1 finishes.

```
+-----------------------------------------------------------------------------------+
|                         FIFO OFFSET COMMITMENT ENGINE                             |
+-----------------------------------------------------------------------------------+
|                                                                                   |
|  Worker finishes JDBC executeBatch(Batch B):                                      |
|        |                                                                          |
|        v                                                                          |
|  DebeziumOffsetManagement.checkIfBatchCanBeCommitted(B):                          |
|        |                                                                          |
|        +---> Check inFlightBatches:                                               |
|              Are there older overlapping batches still running?                   |
|                 |                                                                 |
|                 +--- [YES]: Older batches in flight:                              |
|                 |      Add Batch B to `completedBatches` buffer.                  |
|                 |      Do NOT commit offset yet.                                  |
|                 |                                                                 |
|                 +--- [NO]: Batch B is oldest in-flight:                           |
|                        Acquire `synchronized (OFFSET_COMMIT_LOCK)`:               |
|                        1. For each record in Batch B:                             |
|                           recordCommitter.markProcessed(record)                   |
|                        2. recordCommitter.markBatchFinished()                     |
|                        3. Remove Batch B from `inFlightBatches`                   |
|                        4. Check `completedBatches`: cascade commit for any        |
|                           consecutive completed batches waiting on B.             |
|                                                                                   |
+-----------------------------------------------------------------------------------+
```

---

## 4. Debezium Semaphore Leak Prevention

In Debezium's `EmbeddedEngine`, invoking `markBatchFinished()` initiates an internal offset flush guarded by an internal semaphore (`flushInProgress`).
- **Hazard**: If multiple threads invoke `markProcessed()` or `markBatchFinished()` concurrently, Debezium throws `ConnectException: OffsetStorageWriter is already flushing`. Under certain race conditions, the semaphore is never released, permanently wedging the replication stream.
- **Prevention (2.11.0 Rule)**:
  All interactions with Debezium `RecordCommitter` must take place strictly inside:
  ```java
  synchronized (OFFSET_COMMIT_LOCK) {
      // markProcessed and markBatchFinished calls
  }
  ```
  This guarantees mutual exclusion and completely eliminates offset flush semaphore leaks.

---

## 5. Durable Storage in ClickHouse (`replica_source_info`)

When using ClickHouse-backed offset storage (`JdbcOffsetBackingStore`):
- Offsets are durably persisted in `systemDb.replica_source_info`.
- Table definition:
  ```sql
  CREATE TABLE IF NOT EXISTS replica_source_info (
      offset_key String,
      offset_val String,
      record_insert_ts DateTime64(3) DEFAULT now64(3)
  ) ENGINE = ReplacingMergeTree(record_insert_ts)
  ORDER BY offset_key
  ```
- On restart, the connector reads the highest version for `offset_key`, resuming binlog replication exactly where the previous run stopped.

---

## 6. Control Record Offset Quiescence

When a heartbeat or snapshot-complete control record is emitted by Debezium:
- `commitControlRecordOffset()` evaluates `isPipelineQuiescent()`:
  - Hand-off queue `records.isEmpty() == true`.
  - Routed queue `routedRecords.isEmpty() == true`.
  - `DebeziumOffsetManagement.hasUnwrittenBatches() == false`.
- Only when all queues and workers are completely empty does the control record offset commit, preventing snapshot recovery loops on restart.

---

## 7. Invariants Preserved

1. **Durable Offset Quiescence (Invariant I8)**:
   Offsets stored in `replica_source_info` must only advance when the corresponding JDBC batches have been durably acknowledged by ClickHouse.
2. **FIFO Commit Monotonicity**:
   Batch commits are strictly serialized in arrival order, ensuring that an offset commit never bypasses an earlier, in-flight batch.

---

## 8. Verification Criteria

- **Unit Tests**:
  `DebeziumOffsetManagementTest`.
- **Integration Tests**:
  `OffsetRecoveryIT`, verifying restart from checkpoint without duplicate or missing rows.
- **Formal Verification**:
  Corresponds to Invariant I8 (Durable Offset Quiescence) in `specs/CONSTITUTION.md`.

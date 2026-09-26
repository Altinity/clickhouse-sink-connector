# Spec 09.02: Mutual Exclusion & Debezium Flush Semaphore Protection

## 1. Executive Summary & Purpose
Specifies the global mutual exclusion lock (`OFFSET_COMMIT_LOCK`) protecting Debezium engine offset committer calls from race conditions and permanent semaphore leaks.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/DebeziumOffsetManagement.java`
- **Lock**: `private static final Object OFFSET_COMMIT_LOCK = new Object()` (line 82 on 2.11.0)
- **Guarded methods**: both `acknowledgeRecords(...)` overloads (the batch variant and the single-record variant) and the control-record commit path; they are also `static synchronized`, but the class monitor alone does not serialise against callers holding other monitors, so the shared lock object is the one thing that serialises every committer interaction.

---

## 3. Operational Specification

In Debezium's `EmbeddedEngine`:
- Calling `committer.markBatchFinished()` triggers internal asynchronous offset writes guarded by an internal semaphore (`flushInProgress`).
- Concurrent invocations by multiple worker threads leak this semaphore, throwing `ConnectException: OffsetStorageWriter is already flushing` and freezing replication.

### 3.1 Serialization Contract
All invocations of:
```java
committer.markProcessed(record);
committer.markBatchFinished();
```
must be enclosed strictly within:
```java
synchronized (OFFSET_COMMIT_LOCK) {
    // serialized committer operations
}
```
This guarantees that Debezium flushes are fully serialized and never overlap, whichever worker thread or the DDL path drives them.

---

## 4. Invariants Preserved
- **Offset Store Stability**: Completely eliminates Debezium `flushInProgress` semaphore leaks.

---

## 5. Verification Criteria
- `OffsetCommitSerializationTest.testConcurrentAcknowledgeRecordDoesNotOverlap()`, `OffsetCommitSerializationTest.testAcknowledgeRecordSerializesAgainstBatchVariant()`, `OffsetCommitSerializationTest.testAcknowledgeRecordIgnoresNulls()`.
- `DebeziumOffsetManagementTest.testAcknowledgeMarksBatchFinishedOnce()`, `DebeziumOffsetManagementTest.testAcknowledgePropagatesCommitError()`.

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

---

## 6. Failure Modes & Recovery
The lock makes every committer interaction serial, which is what keeps Debezium's `OffsetStorageWriter` usable; the price is that whatever happens inside the committer happens while every other committer caller — and, because the drain also holds the `DebeziumOffsetManagement` class monitor, the producer's `registerHandoff` — waits. A committer that throws is spec 09.01 §6 FM-09.01-4.

- **FM-09.02-1 Flush-in-progress state leaked ("already flushing")**
  - **Trigger**: two committer calls overlap (the race this spec closes), or Debezium itself leaks the state: `AsyncEmbeddedEngine.commitOffsets` returns without `cancelFlush` when `OffsetStorageWriter.doFlush` returns null (Debezium 3.1.3, read from the jar), or a stopped engine's closed store throws inside `doFlush` (spec 09.01 §3.8 item 4).
  - **Behaviour**: with `OFFSET_COMMIT_LOCK` around `markProcessed` + `markBatchFinished` (both `acknowledgeRecords` overloads, `acknowledgeRecord`), the connector's own calls never overlap. If the state leaks anyway, every later `beginFlush` throws `OffsetStorageWriter is already flushing`; `ClickHouseBatchRunnable.isOffsetWriterPoisoned` recognises it and the worker stops FATAL, which stops the engine (spec 09.01 FM-09.01-1).
  - **Detection**: Debezium WARN `Flushing process probably failed, please check previous log for more details.` (the leak), then ERROR `FATAL: the Debezium OffsetStorageWriter is stuck in the 'already flushing' state -- Task(...). ...` at the next acknowledgement, `Sink worker i of n is dead ...`, FATAL `Replication is STOPPED: ...`, exit code 3; within one unit plus one heartbeat interval (5 s).
  - **Blast radius**: replication stops; nothing is acknowledged past the poisoned point; no loss.
  - **Recovery**: restart the process (a fresh `OffsetStorageWriter`); nothing to repair.
  - **RTO**: ≤ 5 s detection + restart ~20 s + replay of the outstanding units (≤ the hard cap, spec 09.01 §3.1); unmeasured.
  - **Test**: `OffsetCommitSerializationTest.testConcurrentAcknowledgeRecordDoesNotOverlap()`, `OffsetCommitSerializationTest.testAcknowledgeRecordSerializesAgainstBatchVariant()`, `DeadWorkerRetryIsTerminalTest.deadWorkerIsTerminalAtOnce()`; GAP: a worker-level test that an `already flushing` cause stops the worker FATAL (`isOffsetWriterPoisoned` is private and untested directly).

- **FM-09.02-2 Slow offset store stalls the whole pipeline under the lock**
  - **Trigger**: the offset table answers slowly or not at all (overloaded server, half-open connection, `TOO_MANY_PARTS` retries on the offset table, Keeper loss on a KeeperMap or replicated offset table).
  - **Behaviour**: the engine commits with `OffsetCommitPolicy.always()`, so every `markBatchFinished` flushes: `AsyncEmbeddedEngine.commitOffsets` waits up to `offset.flush.timeout.ms` (Debezium default 5 000 ms) for `JdbcOffsetBackingStore.save` (itself retried 5 times, 3 000 ms apart, by `RetriableConnection`). That wait runs inside `OFFSET_COMMIT_LOCK` and inside `drainCompletedUnits`, which holds the class monitor; every worker's `checkIfBatchCanBeCommitted` and the Debezium thread's `registerHandoff` (both `static synchronized`) block behind it. Throughput falls to about one unit per flush timeout, and each timed-out flush is cancelled: the durable position does not move.
  - **Detection**: Debezium WARN `Attempt n to call 'Saving offset' failed.` and WARN `Flush of the offsets failed, canceling the flush.` per unit; no connector ERROR, no metric. DEFECT.
  - **Blast radius**: replication crawls on every table; offsets frozen (a restart replays everything since the last successful flush); no loss.
  - **Recovery**: restore the offset table (spec 09.03 §6 FM-09.03-1); the next flush succeeds and the pipeline resumes at full speed without a restart.
  - **RTO**: cause removed → one flush (≤ 5 s); unmeasured.
  - **Test**: GAP: a test with a committer whose `markBatchFinished` blocks for the flush timeout, asserting `registerHandoff` on another thread is not blocked and the stall is reported at ERROR.
  - **DEFECT**: a remote call to the offset store runs under the global commit lock and the FIFO monitor, so a slow offset table throttles every table and the reader, reported only at WARN by Debezium.

Summary: 2 failure modes, 1 DEFECT, 2 GAP.

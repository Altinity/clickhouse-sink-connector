# Spec 06.01: Pre-DDL Queue Draining & Barrier Synchronization Protocol

## 1. Executive Summary & Purpose
Specifies the execution barrier protocol (`drainBeforeDDL`) that guarantees all pre-DDL data rows are durably flushed and committed under the pre-alteration schema before DDL execution begins.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
- **Method**: `private void drainBeforeDDL()`
- **Timeout**: `DDL_DRAIN_TIMEOUT_MS = 60_000L` (60 seconds)

---

## 3. Operational Specification

When a DDL change event is encountered:
1. **Flush Buffered Records**: Any DML records accumulated prior to the DDL in the current batch are pushed to `records` via `appendToRecords()`.
2. **Queue Drain Phase**:
   - CDC capture thread polls `records`:
     ```java
     while (records != null && !records.isEmpty() && waitTime < DDL_DRAIN_TIMEOUT_MS) {
         Thread.sleep(50);
     }
     ```
   - Worker threads continue dequeuing and flushing preceding rows.
   - If the queue fails to empty within 60s, an `IllegalStateException` is thrown, halting replication fail-fast.
3. **Worker Pause**: Calls `executor.pause()`, signaling workers to park before starting new batches.
4. **Await In-Flight Quiescence**: Calls `executor.awaitQuiescent(remainingTimeout)`. The thread waits until `activeBatches.get() == 0`.

---

## 4. Invariants Preserved
- **Invariant I5 (DDL Barrier Quiescence)**: Pre-DDL data records are guaranteed to be evaluated and stored under the pre-DDL schema.

---

## 5. Verification Criteria
- `DebeziumChangeEventCaptureTest.testDrainBeforeDDL()`

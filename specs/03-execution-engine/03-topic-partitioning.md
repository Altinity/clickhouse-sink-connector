# Spec 03.03: Batch Dequeuing & Topic Partitioning

## 1. Executive Summary & Purpose
Specifies how worker threads dequeue heterogeneous batches from the handoff queue and partition them into per-table record lists for statement execution.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchRunnable.java`
- **Routing source**: `sink-connector-lightweight/.../cdc/DebeziumChangeEventCapture.java` (`setupProcessingThread`, `appendToRecordsWithHashRouting`)
- **Method**: `processBatch()`, `runWithHashRouting()`, `runLegacyMode()`

---

## 3. Operational Specification

### 3.0 Per-table routing (MANDATORY for ordering)
With `thread.pool.size > 1` the connector uses hash routing: there is one
routed queue PER worker thread (`routedQueues`, index == thread id). Every batch
for a given table is routed to a single thread's queue by a stable hash
(`RoutedBatch.calculateThreadId(database.table)`), and that thread's runnable
(`runWithHashRouting`) drains ONLY its own queue in FIFO order. This is what
preserves per-table (Invariant I1) ordering across batches.

This MUST be per-thread queues, not one shared queue. Earlier the routed queue
was never assigned, so `run()` always fell back to `runLegacyMode`, every worker
polled one shared `records` queue, and same-table batches ran concurrently and
out of order. A single shared queue that workers filter by thread id (putting
non-matching batches back at the tail) is also wrong: re-enqueuing a sibling's
batch moves it behind later batches for the same table. Per-thread queues remove
the race. Single-thread / legacy mode still uses the shared `records` queue.

### 3.0.1 Thread id computation
`RoutedBatch.calculateThreadId(key, n)` is `Math.floorMod(key.hashCode(), n)`.
`Math.abs(hashCode) % n` is wrong for `hashCode == Integer.MIN_VALUE`
(`Math.abs` returns `Integer.MIN_VALUE`, the modulo is negative, and the queue
lookup throws `IndexOutOfBoundsException` on the Debezium thread).

### 3.1 Batch Dequeue, Write, and Hand-back to the FIFO
1. In routing mode a worker calls `routedRecords.poll()` on its OWN queue; in
   legacy mode it polls the shared `records` queue. There is no registration
   step on pick-up: the batch was registered by the producer at handoff
   (spec 09.01 §3.1) and is already outstanding.
2. The worker writes the batch (§3.2). Then exactly one of:
   - **written** (`result == true`): call
     `DebeziumOffsetManagement.checkIfBatchCanBeCommitted(batch)` ONCE, then
     `currentBatch = null` regardless of the return value, and continue with
     the next queued batch. Whether the offset is acknowledged now or later is
     the FIFO's concern; the worker never runs a written batch again
     (WRITTEN-ONCE, spec 09.01 §3.2).
   - **not written** (`result == false`, or an exception classified retriable):
     keep `currentBatch`, back off (spec 10.02), retry the same batch.
   - **fatal**: rethrow with `currentBatch` retained (spec 03.01 §3.3).

### 3.2 Partitioning by Topic / Table
The raw batch is grouped into a topic map:
```java
Map<String, List<ClickHouseStruct>> topicToRecordsMap = new HashMap<>();
for (ClickHouseStruct record : batch) {
    topicToRecordsMap.computeIfAbsent(record.getTopic(), k -> new ArrayList<>()).add(record);
}
```
Each topic bucket is then processed independently against its corresponding ClickHouse target table.

---

## 4. Invariants Preserved
- **Per-Table Order Preservation (I1)**: all batches for a table are drained by one thread in FIFO order (routing), and within a batch each topic bucket is applied against its target table.
- **WRITTEN-ONCE**: a batch whose rows are durably written is dropped by the worker and never re-executed, even while its offset is parked behind older batches.

---

## 5. Verification Criteria
- `HashRoutingPerTableOrderingTest.sameTableRoutesToOneQueue()` — a table routes to exactly its one owning queue.
- `HashRoutingPerTableOrderingTest.successiveBatchesForSameTableStayOnSameQueue()` — successive batches for a table stay on the same queue (FIFO).
- `HashRoutingPerTableOrderingTest.differentTablesRouteByHash()` — each table's group routes to its hash-assigned queue.
- `RoutedBatchTest.testMinValueHashCodeRoutesInRange()` — `Integer.MIN_VALUE` hash code routes into `[0, n)`.
- `ParkedBatchWrittenOnceTest.parkedBatchIsNotReinserted()` — a written-but-parked batch is executed exactly once.

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

### 3.1 Batch Dequeue & Registration
1. In routing mode a worker calls `routedRecords.poll()` on its OWN queue; in
   legacy mode it polls the shared `records` queue.
2. If `batch != null && !batch.isEmpty()`:
   Registers the batch in `DebeziumOffsetManagement.addToBatchTimestamps(batch)`.

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

---

## 5. Verification Criteria
- `HashRoutingPerTableOrderingTest.sameTableRoutesToOneQueue()` — a table routes to exactly its one owning queue.
- `HashRoutingPerTableOrderingTest.successiveBatchesForSameTableStayOnSameQueue()` — successive batches for a table stay on the same queue (FIFO).
- `HashRoutingPerTableOrderingTest.differentTablesRouteByHash()` — each table's group routes to its hash-assigned queue.

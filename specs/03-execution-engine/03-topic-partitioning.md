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
1. In routing mode a worker calls `routedRecords.poll()` on its OWN queue and
   then coalesces the batches queued behind it into the same write (§3.1.1);
   in legacy mode it polls the shared `records` queue, one batch per write.
   There is no registration step on pick-up: every queued batch was registered
   by the producer at handoff (spec 09.01 §3.1) and is already outstanding.
2. The worker writes the batch (§3.2). Then exactly one of:
   - **written** (`result == true`): call
     `DebeziumOffsetManagement.checkIfBatchCanBeCommitted(group)` ONCE for each
     handed-off group the write contained, in dequeue order (one call when the
     write was a single group), then `currentBatch = null` (and
     `currentGroups = null`) regardless of the return values, and continue with
     the next queued batch. Whether an offset is acknowledged now or later is
     the FIFO's concern; the worker never runs a written batch again
     (WRITTEN-ONCE, spec 09.01 §3.2).
   - **not written** (`result == false`, or an exception classified retriable):
     keep `currentBatch` (and `currentGroups`), back off (spec 10.02), retry the
     same write -- the same groups, none of them reported.
   - **fatal**: rethrow with `currentBatch` retained (spec 03.01 §3.3).

### 3.1.1 Coalesced drain (routing mode)
A worker's queue routinely holds several batches when it is behind -- the
producer hands off one unit per Debezium poll (up to `max.batch.size` rows,
10,000 in the shipped configuration), and each was written as its own INSERT
with its own template grouping, statement preparation, HTTP round trip,
acknowledgement pass and pacing sleep, producing one ClickHouse part of at most
`max.batch.size` rows per write. `coalesceQueuedBatches(first)` instead takes,
after the batch it polled, every batch already queued behind it that still
fits, and writes them as ONE batch:

1. Groups are taken from the head of the worker's own queue in FIFO order
   (`peek` then `poll`; the worker is the queue's only consumer), never split,
   and never taken past `buffer.max.records` rows or `buffer.max.bytes`
   estimated bytes (`ClickHouseStruct.estimatedBytes`, spec 01.05 §3.4 item 7)
   in total -- the same two bounds a single INSERT is chunked to (spec 03.06
   §3.1). A group that exceeds either bound on its own is written alone, as
   before. A queue holding one batch behaves exactly as before
   (`currentGroups == null`, `currentBatch` is that group).
2. `currentBatch` is the concatenation of the groups in dequeue order;
   `currentGroups` keeps the groups themselves. Dequeue order is handoff order,
   which is binlog order, so the concatenation preserves the per-table order
   Invariant I1 requires, and §3.2 still splits it by table with each table's
   rows in that order. Nothing about what is written changes -- only how many
   round trips and parts it costs.
3. Every coalesced group is already outstanding in the offset FIFO from its
   handoff, so the pipeline-quiescence predicate (`isPipelineQuiescent`, spec
   06.01 / 09.01 §3.6) still holds while they are in flight, and the DDL barrier
   still waits for them.
4. After a successful write each group is reported once, in dequeue order
   (step 2 of §3.1); the FIFO acknowledges units in handoff order exactly as
   for single-group writes (spec 09.01 §3.2-3.3). After a failed write the
   whole set is retried as one; no group is reported early.
5. Legacy mode (`thread.pool.size == 1`, the shared `records` queue) is
   unchanged.
6. **Time-bounded coalescing (`coalesce.max.wait.ms`, default 0).** Steps 1-4
   only take what is ALREADY queued, so a worker that keeps up writes every
   poll as it arrives — each write is one ClickHouse part per partition it
   touches, of a few thousand rows, at the source's poll rate; under key-aware
   routing (spec 03.07) ten workers on one hot table multiply that by ten
   (measured on the part-pressure matrix: a 300,000-row stream into a
   month-partitioned table produced 5,210 parts of 58 rows with ten workers
   against 194 parts of 1,546 rows with one). When `coalesce.max.wait.ms > 0`,
   after step 1 leaves the queue empty and the write still under BOTH bounds,
   the worker waits — a timed `poll` on its own queue — up to that long for
   more batches, adding each one that fits (and, after each, whatever arrived
   with it) and stopping early the moment a bound is reached or the deadline
   passes. A batch taken during the wait that does NOT fit is kept as
   `carriedOver` and opens the next write, ahead of anything still queued: it
   is older than all of them, so dequeue (binlog) order is preserved. Nothing
   is split, nothing is reordered, every batch is already outstanding in the
   offset FIFO from its handoff (so the DDL barrier and the quiescence
   predicate see it), and the write that follows reports every group in order
   exactly as step 4. The cost is up to `coalesce.max.wait.ms` of added latency
   on a trickle; the gain is parts bounded to at most one write per wait per
   worker per partition, i.e. parts of `buffer.max.records` rows under load
   and far fewer merges. `0` keeps steps 1-5 as they are.

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
- `CoalescedQueuedBatchesTest.queuedBatchesAreWrittenAsOneAndAcknowledgedInOrder()` — §3.1.1: three queued groups (two tables) become one write with one per-table call each, rows in dequeue order, and every unit is acknowledged in handoff order with one `markBatchFinished` per unit.
- `CoalescedQueuedBatchesTest.coalescingStopsAtBufferMaxRecords()` — §3.1.1 step 1: with `buffer.max.records` below two groups' total, each group is written alone and still acknowledged in order.
- `CoalescedQueuedBatchesTest.waitCoalescesABatchThatArrivesDuringTheWindow()` — §3.1.1 step 6: with `coalesce.max.wait.ms=400` a batch handed off 80 ms after the worker polled the first one is written with it (one write, both units acknowledged in order); with the default `0` the same sequence is two writes.
- `CoalescedQueuedBatchesTest.waitStopsAtTheRowBoundAndCarriesTheRestOver()` — §3.1.1 step 6: with `buffer.max.records=3` and a 4-second wait, two 2-row batches become two writes — the second batch is taken during the wait, does not fit, opens the next write — and the worker does not sit out the wait once the bound is reached (both writes done well under the 4 s).
- `CoalescedQueuedBatchesTest.aFailedCoalescedWriteRetriesTheWholeSetAndReportsNothingEarly()` — §3.1.1 step 4: a retriable failure keeps the whole coalesced set as `currentBatch`, retries it as one, and nothing is acknowledged until the retry succeeds.

---

## 6. Failure Modes & Recovery

A routing-mode worker never acknowledges what it has not written and never drops a batch: a failing write is retried in place, and everything behind it — every table hashed to that worker and, through the handoff-ordered FIFO, every offset of the connector — waits. That makes the retry loop the component's central recovery risk: correct for a condition that clears, unbounded for one that does not.

- **FM-03.03-1 A batch that fails with a RETRIABLE or UNKNOWN classification is retried forever**
  - **Trigger**: 252 TOO_MANY_PARTS or 241 MEMORY_LIMIT_EXCEEDED under merge or memory pressure (transient), the same 241 for one batch larger than the per-query budget (deterministic), 242 TABLE_IS_READ_ONLY while Keeper is lost, 164 READONLY on a read-only user profile, a connection refused, and every exception without a `Code: NNN` — a `NullPointerException`, the connector's own `IllegalStateException` refusals (spec 05.02 §6 FM-05.02-3).
  - **Behaviour**: `ClickHouseBatchRunnable.run` classifies with `ClickHouseErrorClassifier.classify`; anything but FATAL sleeps `RetryBackoff.nextDelayMs` (`batch.retry.backoff.initial.ms` 500 doubling to `batch.retry.backoff.max.ms` 30,000) and the next tick retries the same `currentBatch`. `RetryBackoff` has deliberately no attempt or time cap. On a busy source the reader reaches the handoff hard cap and, after `sink.connector.handoff.wait.timeout.ms` (600 s), the engine stops with `Handoff hard cap: ...`; the engine retry keeps this pool, so the worker is still retrying its old batch and the next engine meets the cap again — `errors.max.retries` (10) stops without an acknowledgement, i.e. ~10 × 610 s ≈ 102 min, before the terminal exit. A DDL or TRUNCATE behind the batch parks the Debezium thread in the drain, which is bounded by liveness only (spec 06.01), so the hard cap is never reached and there is no stop at all. On a quiet source there is no stop either.
  - **Detection**: every attempt logs ERROR `ClickHouseBatchRunnable exception - Task(<id>)` with the stack and WARN `Retriable ClickHouse error (Code: <n>, Category: <c>) -- the same batch will be retried in <d> ms (consecutive failures: <k>). Every table hashed to this worker waits behind it until it succeeds.`; `clickhouse.sink.topics.error.records` counts the failed rows; `clickhouse_sink_db_lag` is set only by a successful write (`Metrics.updateMetrics` after `addToPreparedStatementBatch` returns), so a lag alert sees a frozen value, not a growing one.
  - **Blast radius**: every table on that worker stops; the committed offset of the whole connector freezes (later units park behind the head); the heap is bounded by the handoff cap. No loss and no divergence beyond lag.
  - **Recovery**: a transient cause self-heals within one backoff (≤ 30 s) of clearing. A deterministic one needs the operator: read the code from the WARN — 241: lower `buffer.max.records` / `buffer.max.bytes` or raise the user's `max_memory_usage`; 252: spec 03.07 §7 FM-03.07-1; 164 / a refusal: fix the user profile or the table as the message says — then restart.
  - **RTO**: transient: cause + ≤ 30 s (bounded). Deterministic: unbounded without an operator (≈ 102 min to a terminal exit on a busy source, never on a quiet one or with a DDL queued) — unmeasured.
  - **Test**: `WorkerFailureModesTest.backpressureCodesAreRetriedWithTheBatchKeptUntilTheyClear()`, `RetryBackoffTest.delaySequenceDoublesToCap()`, `ClickHouseErrorClassifierTest.testTooManyPartsIsRetriableBackpressure()`, `ClickHouseErrorClassifierTest.testMemoryLimitExceededIsRetriable()`; `WorkerFailureModesTest.aRetriableFailureThatNeverClearsEventuallyStopsTheWorker()` (disabled, fails on 2.11.0: 968 attempts in 3 s against a proposed 200 ms retry deadline).
  - **DEFECT**: nothing bounds the time one batch may be retried, so a deterministic failure the classifier cannot recognise stalls the whole connector for hours with only WARN lines; a per-batch retry deadline must escalate it to the terminal FATAL path.

- **FM-03.03-2 A write that failed part-way is retried whole (duplicate writes)**
  - **Trigger**: a coalesced or multi-table batch in which one table's INSERT succeeds and a later one fails, or a multi-chunk template whose later chunk fails (spec 03.06 §6 FM-03.06-2).
  - **Behaviour**: `processBatch` writes table by table (a `ConcurrentHashMap`, so the order is unspecified) and aborts on the first failure; the retry writes every table of `currentBatch` again; no group is reported until the whole write succeeds.
  - **Detection**: the FM-03.03-1 retry lines; the duplicate rows themselves are not reported.
  - **Blast radius**: ReplacingMergeTree: the duplicate has the same key and `_version` and collapses (self-heals). CollapsingMergeTree and plain MergeTree: the duplicate persists (spec 05.04 §6 FM-05.04-1).
  - **Recovery**: none for ReplacingMergeTree; `ch-mysql-resync` (spec 11.04) of an additive-engine table.
  - **RTO**: no stall beyond FM-03.03-1; additive engines: resync time, unmeasured.
  - **Test**: `WorkerFailureModesTest.aRetryAfterAPartialWriteRewritesTheTablesAlreadyWritten()`, `CoalescedQueuedBatchesTest.aFailedCoalescedWriteRetriesTheWholeSetAndReportsNothingEarly()`.

- **FM-03.03-3 Acknowledgement throws after a successful coalesced write**
  - **Trigger**: `DebeziumOffsetManagement.checkIfBatchCanBeCommitted` throws for group k of a coalesced write — its own bookkeeping `IllegalStateException`s (`a batch carrying a Debezium committer reached the writer without a handoff sequence ...`, `a completed unit is not outstanding; the handoff FIFO bookkeeping is corrupt`) or a runtime failure of the Debezium committer other than the special-cased `OffsetStorageWriter is already flushing`.
  - **Behaviour**: `processBatch` clears `currentBatch` / `currentGroups` BEFORE reporting, then reports group by group; the exception skips groups k+1..n, whose units therefore stay outstanding forever; `run` classifies it UNKNOWN and moves on to the next batch. The FIFO head is never acknowledged: rows keep flowing, the committed offset stands still until the handoff hard cap stops the engine (600 s after the cap is met) and `retireHandoffsOfStoppedEngine` resets the FIFO.
  - **Detection**: ERROR `ClickHouseBatchRunnable exception - Task(<id>)` with the bookkeeping exception, WARN `Retriable ClickHouse error (Code: -1, Category: UNKNOWN) ...` once, then `Handoff hard cap: ...` ≥ 10 min later on a busy source; nothing more on a quiet one.
  - **Blast radius**: offsets frozen, rows still written; the restart redelivers everything since the last commit (collapses under ReplacingMergeTree). No loss.
  - **Recovery**: restart the process (the FIFO is rebuilt empty).
  - **RTO**: ≥ 10 min + an engine restart on a busy source (automatic); unbounded on a quiet source — unmeasured.
  - **Test**: GAP: a coalesced write whose second group's acknowledgement throws, asserting the later groups are still reported (or the worker stops).
  - **DEFECT**: a failed acknowledgement orphans the remaining groups of the write and is classified as a retriable ClickHouse error; it must either finish reporting every group or stop the worker.

Summary: 3 failure modes, 2 DEFECT, 1 GAP.

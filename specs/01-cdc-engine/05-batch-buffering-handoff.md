# Spec 01.05: Batch Buffering & Worker Handoff Queue

## 1. Executive Summary & Purpose
Specifies the asynchronous decoupling boundary between the single-threaded Debezium capture loop and the concurrent ClickHouse batch executor pool: bounded, thread-safe handoff queues, the handoff-sequence registration that orders offset commits, and backpressure propagation.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
- **Fields**:
  - `LinkedBlockingQueue<List<ClickHouseStruct>> records` — legacy mode only (`thread.pool.size == 1`).
  - `List<LinkedBlockingQueue<RoutedBatch>> routedQueues` — routing mode (`thread.pool.size > 1`), one bounded queue per worker thread, index == thread id.
  - `List<ScheduledFuture<?>> workerFutures` — one per scheduled worker (spec 03.01 §3.3).
  - `sink.connector.max.queue.size` — capacity of EACH queue (default 500000 batches).
- **Key Methods**:
  - `void appendToRecords(List<ClickHouseStruct> batch, ClickHouseSinkConnectorConfig config) throws InterruptedException`
  - `void appendToRecordsWithHashRouting(List<ClickHouseStruct> batch) throws InterruptedException`
- **Registration**: `DebeziumOffsetManagement.registerHandoff(unit, groups)` (spec 09.01 §3.1).
- **Hard cap**: `DebeziumOffsetManagement.awaitHandoffCapacity(maxOutstandingRecords, timeoutMs, livenessCheck)` and `outstandingRecordCount()` (§3.4), called by `appendToRecords` before either enqueue path.
- **Carrier**: `RoutedBatch(batch, assignedThreadId, tableName, handoffSequence)`.

### Configuration defaults (as shipped in `ClickHouseSinkConnectorConfig`)
| Key | Default | Meaning |
|---|---|---|
| `buffer.flush.time.ms` | **30** ms | period of each worker's scheduled tick, and the sleep after every processed batch |
| `buffer.max.records` | **100000** | maximum records per JDBC batch flush |
| `thread.pool.size` | 10 | number of workers; `> 1` selects routing mode |
| `sink.connector.max.queue.size` | 500000 | capacity of each handoff queue, in batches |
| `sink.connector.handoff.max.outstanding.records` | 500000 | hard cap on rows handed off and not yet acknowledged (§3.4); `0` disables it |
| `sink.connector.handoff.wait.timeout.ms` | 600000 | longest one wait at the hard cap may last before the engine stops (§3.4) |

(Earlier revisions of this document stated 10,000 / 1,000 ms; those were never
the shipped defaults.)

---

## 3. Operational Specification

### 3.1 Mode selection (`appendToRecords`)
1. `single.threaded == true`: `singleThreadedWriter.persistRecords(batch)` runs
   inline on the Debezium thread; nothing is queued, nothing is registered, and
   the rows are written (or have thrown) before control returns.
2. `thread.pool.size > 1`: routing mode, §3.3. **The legacy `records` queue is
   not used in this mode** — it stays empty for the life of the process.
3. Otherwise: legacy mode, §3.2.

### 3.2 Legacy enqueue (`thread.pool.size == 1`)
1. `registerHandoff(batch, [batch])` — the batch is one unit with one group; it
   receives the next handoff sequence and is outstanding from this instant.
2. `records.put(batch)` — blocks when the queue is full (backpressure, §3.4).

### 3.3 Routing enqueue (`appendToRecordsWithHashRouting`)
1. Group the batch's records by routing key `database.table`
   (`RoutedBatch.createRoutingKey`), preserving each group's binlog order.
2. `registerHandoff(batch, groups)` — ONE sequence for the whole handed-off
   list (the unit), every group mapped to it. This happens BEFORE any group is
   enqueued so a fast worker cannot finish a group before the unit exists.
3. For each group: `threadId = RoutedBatch.calculateThreadId(routingKey, threadPoolSize)`
   (`Math.floorMod`, so a key whose `hashCode()` is `Integer.MIN_VALUE` still
   maps into `[0, threadPoolSize)`); wrap it as
   `RoutedBatch(group, threadId, tableName, sequence)`; `routedQueues.get(threadId).put(...)`.
4. The terminal marker is on the last row of the UNIT (set by
   `markTerminalRecord` before this method is entered). Groups carry no marker
   of their own; the unit is acknowledged as a whole, in binlog order, once
   every group is written (spec 09.01 §3.3).

### 3.4 Backpressure
`put` on a full bounded queue blocks the Debezium thread, which stops binlog
reading — natural backpressure on the source connection. Capacity warnings are
logged at 90% and at full. Because routing uses per-thread queues, ONE slow or
stalled worker fills ONE queue and then blocks the producer for every table;
see spec 10.02 §3.4 (head-of-line blocking) and spec 03.01 §3.3 (a dead worker
is detected and surfaced rather than allowed to fill its queue silently).

**The queue capacity is not a memory bound, so a hard cap in ROWS applies
first.** The queues are bounded in batches (§3.6), and a Debezium batch holds
one row or ten thousand, so `sink.connector.max.queue.size` bounds nothing in
bytes; every handed-off row stays on the heap until its unit is acknowledged
(spec 09.01 §3.3 — a written-but-parked unit is still held). A reader that
outruns stalled writers therefore used to hand off rows until the heap was
full: on one deployment the writers stopped acknowledging, the reader handed
off 1.4M more rows in the next nineteen minutes, and the JVM spent the rest of
its life in back-to-back full garbage collections — a stall with no error line
— until the source aborted the binlog dump the reader had stopped draining.
The backlog advisory (spec 09.01 §3.1 step 4) named the condition once and
could do nothing about it. Hence, on the Debezium thread, BEFORE
`registerHandoff` of the next unit (both enqueue paths; never in
`single.threaded` mode, where nothing is ever outstanding):

1. If `sink.connector.handoff.max.outstanding.records` is `0`, nothing happens.
2. Otherwise, while `outstandingRecordCount() >= cap` AND at least one unit is
   outstanding, the producer waits. The unit about to be handed off is never
   split, so the count exceeds the cap by at most one unit.
3. The wait ends as soon as an acknowledgement (spec 09.01 §3.3) or a
   `reset()` (spec 09.01 §3.8) brings the count under the cap — both notify
   the waiter; a 50 ms slice bounds the latency otherwise.
4. Between slices the producer runs its dead-worker check (spec 03.01 §3.3):
   a dead worker can never acknowledge, so waiting on it would be exactly the
   silent stall this rule exists to prevent; its throw ends the wait with that
   exception and the engine stops loudly.
5. A wait longer than `sink.connector.handoff.wait.timeout.ms` ends in an
   `IllegalStateException` naming the counts and both knobs: writers that
   have not acknowledged the head of the FIFO in that long are stalled, not
   slow, and the engine stops (spec 10.04) rather than hold the source
   connection open on a reader that will never read again.
6. Logging is edge-triggered: ONE WARN when a wait begins, naming the rows,
   the units and the cap; ONE INFO when it ends, naming how long it lasted;
   nothing per slice; nothing at all when the cap is not met.

While the producer is paused, Debezium's own bounded queue fills and the
binlog client stops reading; a source whose `net_write_timeout` expires in
that window aborts the dump, which is an ordinary engine restart at a
transaction boundary (spec 01.07) with a bounded heap — never a JVM lost to
garbage collection. The cap is in rows so that it scales with the heap
independently of batch size; size it so that many rows of the widest
replicated tables fit the heap with room for the writers.

### 3.5 Handoff failure is loud
`put` can only fail by `InterruptedException`. It is not caught: it propagates
out of `handleChangeEventBatch`, the engine stops, and a restart redelivers
from the last committed offset. The unit's registration is NOT released — the
rows never reached a queue, so an offset must never pass them (spec 09.01 §3.7).

### 3.6 Memory footprint
Each queued batch holds at most one Debezium batch's rows for one table (routing)
or one Debezium batch (legacy). Bounding `sink.connector.max.queue.size` bounds
heap consumption under high source write volume.

---

## 4. Invariants Preserved
- **FIFO Batch Ordering (I1)**: sequences are assigned on the Debezium thread in
  binlog order; every queue is FIFO; every table maps to exactly one queue.
- **Invariant I8**: a batch is outstanding from registration to acknowledgement;
  no control-record offset can pass it (spec 09.04).
- **Invariant I9**: a handoff failure throws.
- **Backpressure Propagation**: a slow ClickHouse slows binlog reading rather
  than growing memory without bound — enforced in rows by the hard cap (§3.4),
  not only in batches by the queue capacity.

---

## 5. Verification Criteria
- `HashRoutingPerTableOrderingTest` — a table always lands on its owning queue;
  successive batches stay on that queue; each group carries the unit's sequence
  and sequences are strictly increasing across handoffs.
- `RoutedBatchTest.testMinValueHashCodeRoutesInRange` — `Integer.MIN_VALUE`
  hash code maps into range.
- `HandedOffBatchVisibilityTest` — a registered unit reads as unwritten before
  any consumer touches it.
- `HandoffHardCapBackpressureTest` — §3.4: the outstanding row count is added
  at handoff and released only at acknowledgement, a parked unit still counted
  (`rowCountFollowsHandoffAndAcknowledgement`); under the cap the producer is
  not delayed and nothing is logged (`underTheCapReturnsAtOnce`); `0` disables
  the cap (`zeroDisablesTheCap`); at the cap the producer is held until the
  head is acknowledged, with one WARN naming the counts and one INFO on
  release (`atTheCapWaitsUntilTheHeadIsAcknowledged`); a `reset()` releases
  the waiter (`resetReleasesTheWaiter`); the dead-worker check's throw ends
  the wait at once with that exception (`livenessCheckThrowEndsTheWait`);
  writers that never acknowledge end the wait in an `IllegalStateException`
  naming the counts and the knob after the limit, nothing abandoned by the
  failure itself (`theWaitIsBoundedAndLoud`).

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
- **Carrier**: `RoutedBatch(batch, assignedThreadId, tableName, handoffSequence)`.

### Configuration defaults (as shipped in `ClickHouseSinkConnectorConfig`)
| Key | Default | Meaning |
|---|---|---|
| `buffer.flush.time.ms` | **30** ms | period of each worker's scheduled tick, and the sleep after every processed batch |
| `buffer.max.records` | **100000** | maximum records per JDBC batch flush |
| `thread.pool.size` | 10 | number of workers; `> 1` selects routing mode |
| `sink.connector.max.queue.size` | 500000 | capacity of each handoff queue, in batches |

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
  than growing memory without bound.

---

## 5. Verification Criteria
- `HashRoutingPerTableOrderingTest` — a table always lands on its owning queue;
  successive batches stay on that queue; each group carries the unit's sequence
  and sequences are strictly increasing across handoffs.
- `RoutedBatchTest.testMinValueHashCodeRoutesInRange` — `Integer.MIN_VALUE`
  hash code maps into range.
- `HandedOffBatchVisibilityTest` — a registered unit reads as unwritten before
  any consumer touches it.

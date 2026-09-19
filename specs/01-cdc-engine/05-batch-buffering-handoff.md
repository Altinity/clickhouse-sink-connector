# Spec 01.05: Batch Buffering & Worker Handoff Queue

## 1. Executive Summary & Purpose
Specifies the asynchronous decoupling boundary between the single-threaded Debezium capture loop and the concurrent ClickHouse batch executor pool using a bounded, thread-safe handoff queue.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
- **Fields**:
  - `LinkedBlockingQueue<List<ClickHouseStruct>> records`
  - `max.queue.size` configuration parameter (default unbounded or integer limit)
- **Key Method**:
  - `void appendToRecords(List<ClickHouseStruct> batch, ClickHouseSinkConnectorConfig config)`

---

## 3. Operational Specification

### 3.1 Queue Enqueue Protocol (`appendToRecords`)
1. Receives an accumulated `List<ClickHouseStruct> batch` of DML records.
2. If `batch.isEmpty()`, returns immediately.
3. Performs deep copy or creates immutable list view of `batch`.
4. Enqueues the batch into `records`:
   - If `records.offer(batch, timeout)` returns false (queue full):
     The CDC capture thread blocks until worker threads drain capacity.
   - This applies natural backpressure to Debezium and the MySQL binlog TCP connection.

### 3.2 Memory Footprint Management
- Each batch in `records` contains up to `buffer.max.records` (default 10,000).
- Capping `max.queue.size` (e.g. to 50 batches) bounds JVM off-heap and heap memory consumption to safe ceilings under high MySQL write volume.

---

## 4. Invariants Preserved
- **FIFO Batch Ordering**: Batches are placed in `records` strictly in the order they were read from the binary log.
- **Backpressure Propagation**: A slow or stalling ClickHouse instance immediately slows down binlog reading, preventing OutOfMemoryErrors (OOM).

---

## 5. Verification Criteria
- `DebeziumChangeEventCaptureTest.testAppendToRecordsBackpressure()`
- Stress tests verifying memory bounds when ClickHouse insertions are artificially delayed.

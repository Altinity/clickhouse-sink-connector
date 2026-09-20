# Spec 03.02: Dual Execution Engines: Multi-Threaded vs. Single-Threaded Modes

## 1. Executive Summary & Purpose
Specifies the architectural contract of the two batch execution modes: multi-threaded (`ClickHouseBatchRunnable`) and single-threaded synchronous (`ClickHouseBatchWriter`), enforcing behavioral parity between them.

---

## 2. Codebase Mapping on 2.11.0
- **Multi-Threaded**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchRunnable.java`
- **Single-Threaded**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchWriter.java`
- **Mode selection**: `DebeziumChangeEventCapture.setupProcessingThread(config)` in `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
- **Configuration**: `single.threaded` (`ClickHouseSinkConnectorConfigVariables.SINGLE_THREADED`), `thread.pool.size` (`THREAD_POOL_SIZE`; hardcoded default `DEFAULT_THREAD_POOL_SIZE = 10` in `ClickHouseSinkConnectorConfig`), `buffer.flush.time.ms` (`BUFFER_FLUSH_TIME`; the fixed-rate period of each `ClickHouseBatchRunnable`, in milliseconds).

---

## 3. Operational Specification

### 3.1 Mode Selection
`setupProcessingThread` selects the mode as follows:
- `single.threaded = true`: initialize `ClickHouseBatchWriter` and process batches
  inline synchronously on the CDC capture thread (no pool).
- otherwise `thread.pool.size > 1`: start `ClickHouseBatchExecutor` with $N$
  threads, each running a `ClickHouseBatchRunnable` bound to its OWN routed queue
  (hash routing — see spec 03.03); same-table batches are drained by one thread
  in FIFO order. Each runnable is scheduled with `scheduleAtFixedRate(..., 0, buffer.flush.time.ms, MILLISECONDS)`.
- otherwise (`thread.pool.size == 1`): legacy single shared `records` queue, one
  `ClickHouseBatchRunnable` on the same fixed-rate schedule.

### 3.2 Behavioral Parity Contract (Unified in PR #1458)
Both engines must share identical logic for:
1. Destination database override resolution and prefix formatting.
2. `CacheInvalidationManager.getVersion()` staleness checking.
3. Unknown column probing and `MATERIALIZED` column conversion.
4. Two-phase primary key relocation tombstoning.
5. `DebeziumOffsetManagement` batch registration and commitment.

---

## 4. Invariants Preserved
- **Deterministic Replication**: Switching between single-threaded and multi-threaded modes produces identical ClickHouse table contents without divergence.

---

## 5. Verification Criteria
- `ClickHouseBatchWriterDatabaseResolutionTest` — destination resolution in the single-threaded writer (item 1).
- `ClickHouseBatchWriterMissingTableTest` — the single-threaded writer fails loudly on a missing target table.
- `ClickHouseBatchRunnableTest`, `HashRoutingPerTableOrderingTest`, `RoutedBatchTest` — the multi-threaded runnable and its routing.
- Verification: a side-by-side equivalence suite asserting identical table contents from both engines is not yet covered by an automated test (gap).

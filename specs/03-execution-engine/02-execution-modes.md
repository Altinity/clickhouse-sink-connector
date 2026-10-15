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

---

## 6. Failure Modes & Recovery

Both engines recover the same way in the end — nothing is acknowledged past an unwritten batch, and a restart redelivers from the committed offset — but they reach that point differently: the pool retries a failing batch in place (spec 03.03 §6), the single-threaded writer fails the engine at once. The failure modes below are the ones the mode selection itself introduces.

- **FM-03.02-1 A transient ClickHouse error stops the engine in single-threaded mode (parity break)**
  - **Trigger**: `single.threaded=true` and any insert failure — connection refused during a ClickHouse restart, 252 TOO_MANY_PARTS, 241 MEMORY_LIMIT_EXCEEDED, a broken keep-alive socket.
  - **Behaviour**: `ClickHouseBatchWriter.persistRecords` wraps every exception except an interrupt in `BatchPersistenceException` and throws it out of the `ChangeConsumer`; `DebeziumChangeEventCapture.handleEngineCompletion` classifies it (not FATAL), sleeps `SLEEP_TIME` (10 s) and recreates the engine, which redelivers from the committed offset; `errors.max.retries` (10) failures without an acknowledged offset in between make it terminal (exit code 3). The multi-threaded pool retries the same condition inside the worker without stopping (FM-03.03-1). The parity list of §3.2 covers what is written, not how a failure is retried.
  - **Detection**: ERROR `Error persisting records to ClickHouse`, ERROR `Engine stopped with an error: ...`, ERROR `Restarting the engine - retry <n> of <max>`; when spent, FATAL `Replication is STOPPED: ...` and exit code 3. Immediate.
  - **Blast radius**: replication stops for the outage; no loss; every engine restart re-reads the schema history and reopens the binlog dump.
  - **Recovery**: self-heals on the first engine retry after ClickHouse answers again; after a terminal exit the supervisor restart (`RestartSec=30`) resumes.
  - **RTO**: outage + ≤ 10 s + engine start, or + 30 s + JVM start after a terminal exit (an outage longer than ~10 × (10 s + engine start) ends in the exit) — unmeasured.
  - **Test**: `ClickHouseBatchWriterMissingTableTest` (the writer fails the batch loudly instead of dropping it); GAP: a single-threaded writer test that fails one insert with a retriable code and asserts the engine is retried, not stopped terminally.

- **FM-03.02-2 Mode, pool size or routing changed across a restart**
  - **Trigger**: an operator flips `single.threaded`, changes `thread.pool.size` or `routing.by.primary.key` and restarts.
  - **Behaviour**: the worker a row maps to is a function of the pool size and routing mode (`RoutedBatch.calculateThreadId`), so rows move to other workers; the new process starts from the committed low-water mark with an empty FIFO, and no unit of the old mapping is still in flight.
  - **Detection**: INFO `********* Running in Single Threaded mode *********`, `********* Using hash-based routing with <n> threads *********` or `********* Using legacy mode with single thread *********` at start.
  - **Blast radius**: redelivery of the units above the committed offset; duplicates collapse under ReplacingMergeTree (additive on CollapsingMergeTree, spec 05.04 §6).
  - **Recovery**: none needed.
  - **RTO**: one restart; measured: none.
  - **Test**: `Replication.KeyRouting.upgrade_downgrade_converges`, `RoutedBatchTest.testRoutingModeDoesNotChangeRowIdentity()`.

- **FM-03.02-3 `thread.pool.size=0` starts a connector with no writer**
  - **Trigger**: configuration mistake (`thread.pool.size` has no range validator in `ClickHouseSinkConnectorConfig`).
  - **Behaviour**: `setupProcessingThread` creates a `ClickHouseBatchExecutor(0, ...)`, takes the legacy branch (`threadPoolSize > 1` is false) and its scheduling loop (`for i < 0`) schedules no runnable; batches are put on the shared `records` queue and nothing drains it; `workerFutures` is empty, so `failIfWorkerDied` and `hasDeadWorker` have nothing to report. (A negative value fails at start: `ScheduledThreadPoolExecutor` rejects a negative core size.)
  - **Detection**: only the misleading INFO `********* Using legacy mode with single thread *********`; on a busy source, after the handoff hard cap fills and `sink.connector.handoff.wait.timeout.ms` (600 s) passes, `Handoff hard cap: ...` and an engine restart, repeated up to `errors.max.retries` times before the terminal exit (~100 min); on a quiet source, nothing ever.
  - **Blast radius**: nothing is replicated; no loss (nothing is acknowledged).
  - **Recovery**: set `thread.pool.size` ≥ 1 and restart.
  - **RTO**: restart once noticed; detection unbounded.
  - **Test**: `ThreadPoolSizeValidationTest.threadPoolSizeBelowOneIsRejected()` (disabled, fails on 2.11.0).
  - **DEFECT**: a value that yields zero writers is accepted and fails silently; it must be refused at configuration load.

Summary: 3 failure modes, 1 DEFECT, 1 GAP.

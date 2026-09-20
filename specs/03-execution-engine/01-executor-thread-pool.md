# Spec 03.01: Scheduled Batch Executor & Worker Thread Pool

## 1. Executive Summary & Purpose
Specifies the thread scheduling, worker concurrency pool, and thread-safe pause/quiescence monitor in `ClickHouseBatchExecutor`.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchExecutor.java`
- **Superclass**: `java.util.concurrent.ScheduledThreadPoolExecutor`
- **Fields**:
  - `private final Object gate = new Object()`
  - `volatile boolean isPaused = false` (package-private and `volatile` — written by the Debezium event thread in `pause()`/`resume()`, read by every pool thread in `beforeExecute`; the `volatile` supplies the happens-before edge)
  - `private final AtomicInteger activeBatches = new AtomicInteger()`
  - `private static final long POLLING_INTERVAL_MS = 100`

---

## 3. Operational Specification

### 3.1 Worker Lifecycle Hooks
- `beforeExecute(Thread t, Runnable r)`:
  1. Acquires `synchronized (gate)`.
  2. While `isPaused`: `gate.wait(POLLING_INTERVAL_MS)` (waiting on the monitor releases the lock while parked, so `pause()`/`resume()`/`awaitQuiescent()` are never blocked by a parked worker; an `InterruptedException` re-interrupts the thread and returns).
  3. Still inside the same critical section, `activeBatches.incrementAndGet()` — so if the increment happens, `isPaused` was false and `awaitQuiescent()` is guaranteed to observe this batch.
- `afterExecute(Runnable r, Throwable t)`:
  1. Under `synchronized (gate)`: `activeBatches.decrementAndGet()` and `gate.notifyAll()`.
  2. `super.afterExecute(r, t)`.

### 3.2 Barrier Control Primitives
- `pause()`: under `gate`, sets `isPaused = true` and calls `gate.notifyAll()`; stops NEW tasks from entering their body. A batch already running keeps going.
- `resume()`: under `gate`, sets `isPaused = false` and calls `gate.notifyAll()` to wake parked workers.
- `awaitQuiescent(long timeoutMs)`: under `gate`, loops while `activeBatches.get() > 0`, waiting on the gate for `min(remaining, POLLING_INTERVAL_MS)`; returns `true` when the count reaches zero, `false` on timeout or interruption (the interrupt flag is restored).

The DDL path (spec 06.01) calls `pause()` then `awaitQuiescent()` so that a record read under the pre-ALTER schema reaches ClickHouse before the ALTER is applied, and `resume()` in a `finally` (spec 01.03).

---

## 4. Invariants Preserved
- **Invariant I5 (DDL Barrier Quiescence)**: the executor can be frozen for new work and drained of running work before DDL execution begins.

---

## 5. Verification Criteria
- `ClickHouseBatchExecutorQuiescentTest.testAwaitQuiescentWaitsForRunningBatch()`, `ClickHouseBatchExecutorQuiescentTest.testAwaitQuiescentReturnsImmediatelyWhenIdle()`, `ClickHouseBatchExecutorQuiescentTest.testAwaitQuiescentAfterFailedBatch()`, `ClickHouseBatchExecutorQuiescentTest.testPauseStillBlocksNewBatches()`.
- `PauseDrainRaceTest`, `PauseDrainAtomicityTest` — the pause/drain window against concurrent task starts.

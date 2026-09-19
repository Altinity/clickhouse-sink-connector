# Spec 03.01: Scheduled Batch Executor & Worker Thread Pool

## 1. Executive Summary & Purpose
Specifies the thread scheduling, worker concurrency pool, and thread-safe pause/quiescence monitor in `ClickHouseBatchExecutor`.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchExecutor.java`
- **Superclass**: `java.util.concurrent.ScheduledThreadPoolExecutor`
- **Fields**:
  - `private final Object gate = new Object()`
  - `private boolean isPaused = false`
  - `private final AtomicInteger activeBatches = new AtomicInteger(0)`

---

## 3. Operational Specification

### 3.1 Worker Lifecycle Hooks
- `beforeExecute(Thread t, Runnable r)`:
  1. Acquires `synchronized (gate)`.
  2. While `isPaused == true`: worker calls `gate.wait(100)` to park before entering its task body.
  3. Increments `activeBatches.incrementAndGet()`.
- `afterExecute(Runnable r, Throwable t)`:
  1. Decrements `activeBatches.decrementAndGet()`.
  2. Acquires `synchronized (gate)` and calls `gate.notifyAll()`.

### 3.2 Barrier Control Primitives
- `pause()`: Sets `isPaused = true` under `gate` lock; halts subsequent task starts.
- `resume()`: Sets `isPaused = false` under `gate` lock; invokes `gate.notifyAll()` to wake parked workers.
- `awaitQuiescent(long timeoutMs)`: Blocks calling thread until `activeBatches.get() == 0` or timeout expires.

---

## 4. Invariants Preserved
- **Invariant I5 (DDL Barrier Quiescence)**: Guarantees that the executor can be completely drained and frozen before DDL execution begins.

---

## 5. Verification Criteria
- `ClickHouseBatchExecutorTest.testPauseResumeAwaitsQuiescent()`

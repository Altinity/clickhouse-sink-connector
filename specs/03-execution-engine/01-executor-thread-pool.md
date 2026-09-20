# Spec 03.01: Scheduled Batch Executor & Worker Thread Pool

## 1. Executive Summary & Purpose
Specifies the thread scheduling, worker concurrency pool, and thread-safe pause/quiescence monitor in `ClickHouseBatchExecutor`.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchExecutor.java`
- **Superclass**: `java.util.concurrent.ScheduledThreadPoolExecutor`
- **Scheduling & liveness**: `sink-connector-lightweight/.../cdc/DebeziumChangeEventCapture.java`
  — `setupProcessingThread` (schedules one `ClickHouseBatchRunnable` per thread
  with `scheduleAtFixedRate(…, 0, buffer.flush.time.ms)` and keeps every
  `ScheduledFuture` in `workerFutures`), `failIfWorkerDied()` (called first
  thing in `handleChangeEventBatch`).
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

### 3.3 Worker liveness: a dead worker is a loud engine stop
`ScheduledThreadPoolExecutor` cancels a periodic task whose run throws: the
task's future completes exceptionally and the task is never scheduled again,
but nothing else happens — no log line from the executor, no callback. A worker
that rethrows a FATAL classification (spec 10.01) therefore died silently: its
batch stayed outstanding (so no control-record offset could ever be committed
again), every later unit stayed parked in the FIFO, its queue filled to
`sink.connector.max.queue.size`, and the Debezium thread finally blocked in
`put` — replication stopped with nothing to say why.

Contract:
1. `setupProcessingThread` retains every `ScheduledFuture` in `workerFutures`.
2. `handleChangeEventBatch` calls `failIfWorkerDied()` BEFORE doing anything
   else with the batch (including the empty-batch early return). For every
   future with `isDone()`: obtain its cause with `get()` (an
   `ExecutionException` wraps the worker's throwable; a cancelled or normally
   completed future has no cause but is equally dead) and throw a
   `RuntimeException` naming the worker and carrying the cause.
3. That exception leaves the Debezium `ChangeConsumer`, so the engine stops
   through its `CompletionCallback` path with the worker's stack trace in the
   log. Offsets are not committed past the dead worker's batch (its unit is
   still outstanding), so a restart redelivers from the last committed offset.
4. The worker does NOT clear `currentBatch` before rethrowing: the batch must
   remain registered so quiescence stays false until the process stops.

---

## 4. Invariants Preserved
- **Invariant I5 (DDL Barrier Quiescence)**: the executor can be frozen for new work and drained of running work before DDL execution begins.
- **Invariant I9 (Loud Failure)**: a worker whose periodic task has terminated stops the engine with its cause on the next Debezium batch; it never degrades into a silent stall.

---

## 5. Verification Criteria
- `ClickHouseBatchExecutorQuiescentTest.testAwaitQuiescentWaitsForRunningBatch()`, `ClickHouseBatchExecutorQuiescentTest.testAwaitQuiescentReturnsImmediatelyWhenIdle()`, `ClickHouseBatchExecutorQuiescentTest.testAwaitQuiescentAfterFailedBatch()`, `ClickHouseBatchExecutorQuiescentTest.testPauseStillBlocksNewBatches()`.
- `PauseDrainRaceTest`, `PauseDrainAtomicityTest` — the pause/drain window against concurrent task starts.
- `WorkerDeathIsLoudTest.deadWorkerFailsTheNextBatchLoudly` — a scheduled task that throws on its first tick makes the next `handleChangeEventBatch` throw with that cause.
- `WorkerDeathIsLoudTest.liveWorkersDoNotInterfere` — a running periodic task does not.

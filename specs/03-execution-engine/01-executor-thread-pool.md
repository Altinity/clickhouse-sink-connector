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
- **Invariant I5 (DDL Barrier Quiescence)**: Guarantees that the executor can be completely drained and frozen before DDL execution begins.
- **Invariant I9 (Loud Failure)**: a worker whose periodic task has terminated stops the engine with its cause on the next Debezium batch; it never degrades into a silent stall.

---

## 5. Verification Criteria
- `ClickHouseBatchExecutorTest.testPauseResumeAwaitsQuiescent()`
- `WorkerDeathIsLoudTest.deadWorkerFailsTheNextBatchLoudly` — a scheduled task that throws on its first tick makes the next `handleChangeEventBatch` throw with that cause.
- `WorkerDeathIsLoudTest.liveWorkersDoNotInterfere` — a running periodic task does not.

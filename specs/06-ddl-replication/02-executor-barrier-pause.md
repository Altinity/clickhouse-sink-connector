# Spec 06.02: Thread Pool Barrier Pause & Quiescence Monitor

## 1. Executive Summary & Purpose
Specifies the synchronization monitor inside `ClickHouseBatchExecutor` that parks worker threads during DDL schema evolution.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchExecutor.java`
- **Methods**:
  - `public void pause()`
  - `public void resume()`
  - `public boolean awaitQuiescent(long timeoutMs)`

---

## 3. Operational Specification

### 3.1 Monitor Gate State Machine
- When `pause()` is invoked:
  ```java
  synchronized (gate) {
      isPaused = true;
      gate.notifyAll();
  }
  ```
- Workers arriving at `beforeExecute()` observe `isPaused == true` and enter:
  ```java
  while (isPaused) {
      gate.wait(100);
  }
  ```
- `awaitQuiescent(timeoutMs)` spins under `gate` until `activeBatches.get() == 0`.
- Once DDL completes, `resume()` sets `isPaused = false` and calls `gate.notifyAll()`, waking parked workers.

---

## 4. Invariants Preserved
- **Zero Race Conditions**: No worker thread can begin a batch insert while DDL DDL operations are modifying table metadata.

---

## 5. Verification Criteria
- `PauseDrainAtomicityTest.testNoTaskStartsWhileGateHeld()` — no worker
  passes `beforeExecute()` while `isPaused` is set.
- `PauseDrainAtomicityTest.testNothingStartsAfterPauseReturns()` — once
  `pause()` has returned, no batch begins until `resume()`.
- `PauseDrainRaceTest.testDrainDoesNotRaceATaskThatIsAboutToStart()` — a task
  already past the queue but not yet counted cannot slip through the drain.
- `PauseDrainRaceTest.testTaskQueuedWhilePausedDoesNotRunBeforeResume()` —
  parked workers wake only on `resume()`.
- `PauseDrainRaceTest.testRepeatedDrainCyclesUnderLoad()` — repeated
  pause / `awaitQuiescent` / resume cycles under concurrent submission.

---

## 6. Failure Modes & Recovery
The monitor has no timeout of its own: it is correct only if every parked worker stays parked until `resume()` and every running batch eventually leaves its task body. Its failure modes are therefore a worker that escapes the gate and a batch that never returns; the caller (spec 06.01) owns the liveness checks and the resume-in-`finally`.

- **FM-06.02-1 Interrupted parked worker escapes the pause**
  - **Trigger**: a pool thread parked in `beforeExecute()` is interrupted (an `executor.shutdownNow()`, a library or container runtime interrupting pool threads) while a DDL holds the barrier.
  - **Behaviour**: `ClickHouseBatchExecutor.beforeExecute()` catches the `InterruptedException` with `t.interrupt(); return;` WITHOUT incrementing `activeBatches`. `ThreadPoolExecutor.runWorker` then runs the task while `isPaused` is still true, i.e. possibly while the ALTER runs, and `afterExecute()` decrements `activeBatches` to -1. From then on `awaitQuiescent()` sees 0 while one batch is running, for the life of the pool.
  - **Detection**: none. No log line, no metric; the effect is pre-DDL rows written against the post-DDL schema (NULL bound over a renamed column's value), count-clean.
  - **Blast radius**: silent value corruption of the rows of that batch, and a barrier that under-counts by one for every later DDL of the process.
  - **Recovery**: restart the process (a fresh pool resets the counter); then find the affected tables by value-level comparison (spec 11.02) and repair them with `ch-mysql-resync` (spec 11.04).
  - **RTO**: restart + table re-synchronisation proportional to table size; unmeasured.
  - **Test**: `ClickHouseBatchExecutorInterruptTest.interruptedParkedWorkerDoesNotRunWhilePaused()` (disabled; fails on 2.11.0: the batch runs while paused).
  - **DEFECT**: an interrupt lets a parked worker run a batch inside the DDL barrier and leaves `activeBatches` negative; `beforeExecute()` must keep waiting (re-asserting the interrupt afterwards) or throw so the task is not run.

- **FM-06.02-2 A batch never leaves its task body (hung JDBC call)**
  - **Trigger**: a half-open TCP connection to ClickHouse, a server that accepted the INSERT and stopped responding, a GC spiral in the worker.
  - **Behaviour**: `awaitQuiescent(ddlDrainWarnIntervalMs)` returns false every 60 s; `drainBeforeDDL()` step 3 loops on it indefinitely, re-checking `failIfWorkerDiedDuringDrain()` (the worker is alive, so no abort). The only bound is the JDBC driver's socket timeout (`clickhouse.jdbc.params` `socket_timeout`; the driver default was not verified here).
  - **Detection**: WARN every 60 s `DDL drain: a batch is still inside a worker after <ms> ms; waiting for it to finish before the DDL is applied.` No ERROR, no metric.
  - **Blast radius**: all tables stop until the call returns; no loss.
  - **Recovery**: restart the process (`systemctl restart` / `sink-connector-client restart`); the batch and the DDL are re-delivered from the last committed offset. Preventive: set `socket_timeout` in `clickhouse.jdbc.params` so a dead connection surfaces as a retriable error.
  - **RTO**: operator reaction + restart; unbounded without a socket timeout; unmeasured.
  - **Test**: GAP: a unit test with a task that blocks forever inside the pool, asserting the drain logs at ERROR and surfaces a named condition after a stated bound.
  - **DEFECT**: a hung in-flight batch stalls every table indefinitely with only a WARN; there is no bound and no ERROR.

- **FM-06.02-3 Pause leaked by a failed DDL**
  - **Trigger**: the drain or the DDL execution throws after `pause()`.
  - **Behaviour**: the DDL branch of `DebeziumChangeEventCapture.processEveryChangeRecord()` calls `executor.resume()` in `finally`, so the pool is never left paused by a failed attempt.
  - **Detection**: the DDL failure itself (spec 06.08 §6); no separate signal is needed.
  - **Blast radius**: none beyond the failed DDL.
  - **Recovery**: none for the monitor; follow the DDL failure's recovery.
  - **RTO**: 0 for the monitor (resume is synchronous).
  - **Test**: `DdlFailureModesTest.failedDdlLeavesThePoolResumed()`.

- **FM-06.02-4 A task starts in the window between the pause check and the in-flight count**
  - **Trigger**: concurrent `pause()` and a worker entering `beforeExecute()`.
  - **Behaviour**: the check of `isPaused` and `activeBatches.incrementAndGet()` share the `gate` monitor with `awaitQuiescent()`, so the batch is either parked or counted and waited out.
  - **Detection**: not applicable (prevented).
  - **Blast radius**: none.
  - **Recovery**: none.
  - **RTO**: 0.
  - **Test**: `PauseDrainAtomicityTest.testNoTaskStartsWhileGateHeld()`, `PauseDrainRaceTest.testDrainDoesNotRaceATaskThatIsAboutToStart()`.

Summary: 4 failure modes, 2 DEFECT, 1 GAP.

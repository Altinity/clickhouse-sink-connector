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
- `ClickHouseBatchExecutorTest.testPauseResume()`

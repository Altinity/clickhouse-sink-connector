# Spec 10.02: Exponential Backoff & Retry Interval Calculation

## 1. Executive Summary & Purpose
Specifies the retry scheduling and exponential backoff calculations applied to retriable database exceptions.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchRunnable.java`
- **Parameters**: `task.retry.backoff.ms` (default 500ms), `task.retry.max.attempts`

---

## 3. Operational Specification

### 3.1 Backoff Algorithm
For retry attempt $n$:
$$\text{delay}(n) = \min(\text{baseDelay} \times 2^n, \text{maxDelay})$$
- Thread sleeps for $\text{delay}(n)$ before re-attempting connection acquisition or query execution.
- If $n \ge \text{maxAttempts}$, the error is escalated from `RETRIABLE` to `FATAL`, halting execution.

---

## 4. Invariants Preserved
- **ClickHouse Cluster Headroom**: Prevents thundering herds from overwhelming a recovering ClickHouse cluster.

---

## 5. Verification Criteria
- `ClickHouseBatchRunnableTest.testExponentialBackoff()`

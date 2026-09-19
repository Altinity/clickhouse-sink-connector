# Spec 03.06: PreparedStatement Batch Flushing & Limits

## 1. Executive Summary & Purpose
Specifies the accumulation, threshold evaluation, and JDBC `executeBatch()` dispatching of parameterized SQL statements into ClickHouse.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/PreparedStatementExecutor.java`
- **Method**: `insertBatch(PreparedStatement ps, ...)`

---

## 3. Operational Specification

### 3.1 Batch Flush Triggers
A batch is dispatched to ClickHouse upon encountering either of two thresholds:
1. **Size Boundary (`buffer.max.records`)**: When accumulated statement rows reach the configured limit (default 10,000 rows).
2. **Time Boundary (`buffer.flush.time`)**: When the scheduled flush interval elapses (default 1,000ms), ensuring low replication latency.

### 3.2 JDBC Batch Execution
```java
ps.addBatch();
// When batch limit reached or end of partition:
int[] results = ps.executeBatch();
```
- If ClickHouse executes the batch successfully, all affected rows are durably written to parts.
- If an exception occurs during `executeBatch()`, the exception is caught, categorized via `ClickHouseErrorClassifier`, and processed per retry policies.

---

## 4. Invariants Preserved
- **Invariant I3 (Eventual Convergence)**: Batches are submitted with valid replacing versions and delete flags, guaranteeing convergence.

---

## 5. Verification Criteria
- `PreparedStatementExecutorTest.testInsertBatchLimits()`
- Integration test with 50,000 rows asserting batch chunking at `buffer.max.records`.

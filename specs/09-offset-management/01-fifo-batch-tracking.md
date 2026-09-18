# Spec 09.01: FIFO Batch Tracking & Out-of-Order Completion Reconciliation

## 1. Executive Summary & Purpose
Specifies the FIFO offset tracking engine (`DebeziumOffsetManagement`) that reconciles out-of-order batch completions across concurrent worker threads to guarantee serialized offset advancement.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/operations/DebeziumOffsetManagement.java`
- **Fields**:
  - `Map<List<ClickHouseStruct>, BatchTimestamps> inFlightBatches`
  - `List<List<ClickHouseStruct>> completedBatches`
- **Key Methods**:
  - `addToBatchTimestamps(List<ClickHouseStruct> batch)`
  - `checkIfBatchCanBeCommitted(List<ClickHouseStruct> batch)`

---

## 3. Operational Specification

### 3.1 Batch Registration & Completion
1. When worker thread polls a batch $B$, calls `addToBatchTimestamps(B)`.
2. When worker completes JDBC flush of $B$:
   Calls `checkIfBatchCanBeCommitted(B)`:
   - Evaluates whether older overlapping batches are still in `inFlightBatches`.
   - If older batches are running $\implies$ Adds $B$ to `completedBatches`; returns `false`.
   - If $B$ is the oldest in-flight batch $\implies$ Commits offsets for $B$, removes $B$ from `inFlightBatches`, and cascades commits for all consecutive completed batches waiting on $B$.

---

## 4. Invariants Preserved
- **Invariant I8 (Durable Offset Quiescence)**: Offsets strictly advance in FIFO order; an offset is never committed if an earlier batch failed or remains unfinished.

---

## 5. Verification Criteria
- `DebeziumOffsetManagementTest.testFifoBatchCommitOrdering()`

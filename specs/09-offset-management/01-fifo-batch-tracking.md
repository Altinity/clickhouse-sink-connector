# Spec 09.01: FIFO Batch Tracking & Out-of-Order Completion Reconciliation

## 1. Executive Summary & Purpose
Specifies the FIFO offset tracking engine (`DebeziumOffsetManagement`) that reconciles out-of-order batch completions across concurrent worker threads to guarantee serialized offset advancement.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/DebeziumOffsetManagement.java`
- **Fields**:
  - `ConcurrentHashMap<BatchKey, List<ClickHouseStruct>> inFlightBatches`
  - `ConcurrentHashMap<BatchKey, List<ClickHouseStruct>> completedBatches`
  - `BatchKey` — an identity wrapper over the batch `List` (`equals`/`hashCode`
    by `System.identityHashCode`), so distinct batches are distinct keys.
- **Key Methods**:
  - `addToBatchTimestamps(List<ClickHouseStruct> batch)`
  - `checkIfThereAreInflightRequests(List<ClickHouseStruct> batch)`
  - `checkIfBatchCanBeCommitted(List<ClickHouseStruct> batch)`
  - `calculateMinMaxTimestampFromBatch(batch)` — derives the `(minTs, maxTs)`
    range used only for the overlap comparison, never as a map key.

---

## 3. Operational Specification

### 3.0 Keying: identity, not timestamp range (MANDATORY)
The maps MUST be keyed by batch **object identity**, never by the batch's
`(minTs, maxTs)` timestamp range. Two distinct batches routinely share a range —
a single multi-row statement split across batches, or two batches whose rows all
fall in the same millisecond. Keying by the range collided them: `put` overwrote
the earlier batch's entry, `remove` deleted the wrong one, and the overlap check's
"skip the same batch" step (also range-based) treated a distinct sibling as
itself. The net effect was that an unwritten older batch stopped blocking the
commit, so the committed binlog position could advance past rows not yet in
ClickHouse and lose them on a crash. The overlap check skips the current batch by
identity (`==`), and compares timestamp ranges only to decide overlap.

### 3.1 Batch Registration & Completion
1. When a worker registers a batch $B$, it calls `addToBatchTimestamps(B)`,
   which puts `(BatchKey(B) -> B)` into `inFlightBatches`.
2. When a worker completes the JDBC flush of $B$ it calls
   `checkIfBatchCanBeCommitted(B)`:
   - `checkIfThereAreInflightRequests(B)` returns true iff some OTHER in-flight
     batch (by identity) overlaps $B$'s range.
   - If an overlapping batch is still running $\implies$ move $B$ to
     `completedBatches` (keyed by identity); return `false`.
   - Otherwise $\implies$ acknowledge $B$, remove it from `inFlightBatches`, and
     cascade-acknowledge every completed batch that no longer overlaps anything
     in flight.

---

## 4. Invariants Preserved
- **Invariant I8 (Durable Offset Quiescence)**: Offsets strictly advance in FIFO order; an offset is never committed if an earlier overlapping batch remains unfinished. Identity keying is what makes this hold when batches share a timestamp range.

---

## 5. Verification Criteria
- `OffsetBatchIdentityTest.equalTimestampBatchesDoNotCollide()` — two distinct batches with the same range are two entries, not one.
- `OffsetBatchIdentityTest.siblingWithSameRangeStillBlocks()` — a distinct same-range sibling still blocks the commit.
- `OffsetBatchIdentityTest.aBatchDoesNotBlockItself()` / `identityRemovalLeavesSibling()`.
- `HandedOffBatchVisibilityTest` (unchanged) — handoff counter quiescence.

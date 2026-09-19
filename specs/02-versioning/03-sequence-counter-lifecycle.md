# Spec 02.03: Intra-Second Sequence Counter Lifecycle & Rollover

## 1. Executive Summary & Purpose
Specifies the lifecycle, rollover boundaries, and base constants of the intra-second sequence counter that provides sub-millisecond event ordering.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
- **Constants & Fields**:
  - `SEQUENCE_START = 1_000_000_000L`
  - `SEQUENCE_START_INITIAL = 500_000_000L`
  - `sequenceNumber`: Current intra-second counter value
  - `sequenceAnchorTs`: Anchor timestamp for the active counter second

---

## 3. Operational Specification

### 3.1 Intra-Second Reset Logic
When processing an event with `effectiveTs`:
1. Calculate delta relative to the anchor timestamp:
   $$\Delta t = \frac{\text{effectiveTs} - \text{sequenceAnchorTs}}{1000}$$
2. If $\Delta t \ge 1$ (time has advanced by at least 1,000 milliseconds):
   - `sequenceAnchorTs = effectiveTs`
   - `sequenceNumber = SEQUENCE_START` (`1_000_000_000L`)
3. If $\Delta t < 1$ (within the same 1-second window):
   - `sequenceNumber++`

### 3.2 Thread Synchronization
`nextSequenceNumber` is guarded by `synchronized (DebeziumChangeEventCapture.class)`, guaranteeing that concurrent access from multiple threads cannot produce duplicate versions or tear state updates.

---

## 4. Invariants Preserved
- **Zero Collision Guarantee**: Two events processed within the same millisecond receive distinct, sequentially ordered versions.

---

## 5. Verification Criteria
- `DebeziumChangeEventCaptureTest.testSequenceRolloverAcrossSeconds()`
- Multi-threaded concurrency test asserting zero duplicate sequence numbers generated.

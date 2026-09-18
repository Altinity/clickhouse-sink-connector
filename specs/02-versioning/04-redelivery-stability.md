# Spec 02.04: Redelivery Stability & Offset Rewind Versioning

## 1. Executive Summary & Purpose
Specifies the versioning behavior when the connector restarts or rewinds to an earlier binary log offset, ensuring that redelivered events do not corrupt the version monotonicity of future events.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
- **Fields**:
  - `sequenceHighWaterPosition`
  - `SEQUENCE_START_INITIAL = 500_000_000L`

---

## 3. Operational Specification

### 3.1 Initial Sequence Seeding on Cold Start
On process startup, `sequenceNumber` is initialized to `SEQUENCE_START_INITIAL` (`500_000_000L`), which is strictly less than `SEQUENCE_START` (`1_000_000_000L`).
- If the connector crashes and restarts at the last committed offset, events replayed from that offset will receive sequence numbers starting at $500\text{M}$.
- Because previous pre-crash runs used $1000\text{M}$ for the same timestamp, replayed rows receive equal or lower versions, preventing them from overwriting newer rows if clocks or positions coincide.

### 3.2 High-Water Gating on Replay
When `position.compareTo(sequenceHighWaterPosition) <= 0`:
- The event is classified as an offset rewind or duplicate delivery.
- The high-water coordinate `sequenceHighWaterPosition` is **not** modified.
- The floor `sequenceMaxSourceTs` is **not** updated.
- This ensures that transient replays do not artificially inflate the high-water floor for future, genuinely new events.

---

## 4. Invariants Preserved
- **Invariant I8 (Durable Offset Quiescence)**: Redelivery preserves existing ClickHouse `ReplacingMergeTree` row states without creating spurious overwrites.

---

## 5. Verification Criteria
- `DebeziumChangeEventCaptureTest.testReplayPreservesHighWaterMark()`
- Crash-restart integration test asserting no data degradation on redelivery.

# Spec 02.01: 64-Bit Monotonic Version Formula & Bit Allocation

## 1. Executive Summary & Purpose
Specifies the mathematical calculation converting MySQL commit timestamps and sequence numbers into a 64-bit integer (`UInt64` in ClickHouse) used as the version column (`_version`) for `ReplacingMergeTree` deduplication.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
- **Method**: `static synchronized long nextSequenceNumber(long recordTs, SourcePosition position)`

---

## 3. Mathematical Formula & Digit Budget

The 64-bit version integer is computed as:
$$V = (\text{effectiveTs} \times 1{,}000{,}000) + \text{sequenceNumber}$$

### 3.1 Digit Budget Breakdown
- **Timestamp Component (`effectiveTs * 1,000,000`)**:
  - `effectiveTs` represents milliseconds since UNIX epoch ($13$ decimal digits, e.g. $1{,}789{,}739{,}438{,}000$).
  - Multiplying by $10^6$ shifts the timestamp by 6 decimal positions, reserving 6 lower digits for intra-millisecond ordering.
- **Sequence Component (`sequenceNumber`)**:
  - A monotonically increasing counter incremented for every event processed within the current second.

### 3.2 Value Range & Overflow Boundaries
- Standard signed 64-bit `long` maximum value: $9{,}223{,}372{,}036{,}854{,}775{,}807$ ($\approx 9.22 \times 10^{18}$).
- At year 2026, $\text{effectiveTs} \approx 1.79 \times 10^{12}$ ms.
- $\text{effectiveTs} \times 10^6 \approx 1.79 \times 10^{18}$, which comfortably fits within signed 64-bit integer limits without overflow until year 2262.

---

## 4. Invariants Preserved
- **Invariant I2 (Deterministic Version Monotonicity)**: For any single thread or coordinated executor, consecutive events receive strictly ascending versions.

---

## 5. Verification Criteria
- `DebeziumChangeEventCaptureTest.testVersionCalculation()`
- Mathematical formalization in `formal_specs/lean/Replication/Engine.lean` (`encodeVersion`).

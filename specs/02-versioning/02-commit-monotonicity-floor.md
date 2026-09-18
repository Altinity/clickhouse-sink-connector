# Spec 02.02: Commit Monotonicity Floor & Late-Commit Inversion Prevention

## 1. Executive Summary & Purpose
Specifies the high-water floor algorithm (`sequenceMaxSourceTs`) that ensures events committing later in the MySQL binary log always receive a higher or equal timestamp component than earlier commits, even when MySQL statement timestamps are non-monotonic.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
- **Fields**:
  - `private static long sequenceMaxSourceTs = 0L`
  - `private static SourcePosition sequenceHighWaterPosition = null`

---

## 3. The Commit Monotonicity Algorithm

When an incoming change event arrives at `nextSequenceNumber(recordTs, position)`:

```
Receive event with (recordTs, position)
       |
       v
Is position a new high-water mark?
(sequenceHighWaterPosition == null || position.compareTo(sequenceHighWaterPosition) > 0)
       |
       +--- [YES: First Delivery of New Commit]:
       |      1. sequenceHighWaterPosition = position
       |      2. effectiveTs = recordTs
       |      3. If effectiveTs < sequenceMaxSourceTs:
       |            effectiveTs = sequenceMaxSourceTs   <-- Clamping Floor!
       |      4. sequenceMaxSourceTs = effectiveTs
       |
       +--- [NO: Replay / Redelivery]:
              1. effectiveTs = recordTs
              (Do not advance high-water mark or floor)
```

### 3.1 Mathematical Proof of Non-Inversion
Let $E_1$ and $E_2$ be two events in the binlog where $E_1$ precedes $E_2$ in commit order ($P(E_1) < P(E_2)$).
- When $E_1$ is processed, it establishes a floor $T_1 = \text{sequenceMaxSourceTs} \ge \text{ts}(E_1)$.
- When $E_2$ is processed, its effective timestamp $T_2 = \max(\text{ts}(E_2), T_1) \ge T_1$.
- Because $T_2 \ge T_1$ and the intra-second counter `sequenceNumber` strictly advances, the resulting version $V(E_2) > V(E_1)$.
- Therefore, no commit inversion can occur.

---

## 4. Invariants Preserved
- **Invariant I2 (Deterministic Version Monotonicity)**: Guarantees that binlog commit sequence strictly dominates statement execution timestamps.

---

## 5. Verification Criteria
- `DebeziumChangeEventCaptureTest.testNonMonotonicSourceTimestampClamping()`
- Lean theorem `version_strictly_monotonic` in `formal_specs/lean/Replication/Proofs.lean`.

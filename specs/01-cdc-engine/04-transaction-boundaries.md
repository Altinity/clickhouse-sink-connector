# Spec 01.04: Transaction Boundaries & Timestamp Semantics

## 1. Executive Summary & Purpose
Specifies how transaction boundaries (`BEGIN`, `COMMIT`, `XID`) in the MySQL binary log relate to CDC events, and formalizes the discrepancy between MySQL statement start time and transaction commit time.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
- **Model**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/model/ClickHouseStruct.java`

---

## 3. Statement vs. Commit Timestamp Discrepancy

In MySQL row-based replication:
- `source.ts_ms` populated by Debezium represents the **statement execution start time**, *not* the commit timestamp.
- **The Late-Commit Inversion Hazard**:
  1. Transaction $T_1$ begins at $t = 100$ and executes statements modifying row $K$.
  2. Transaction $T_2$ begins at $t = 110$, executes statements modifying row $K$, and immediately commits at $t = 111$.
  3. Transaction $T_1$ finally commits at $t = 120$.
  4. In the binlog, $T_2$'s events appear *before* $T_1$'s events.
  5. However, $T_1$'s raw timestamp ($100$) is *older* than $T_2$'s timestamp ($110$).
  6. If raw timestamps are used for `_version`, $T_1$ receives version $100{,}000{,}000$, and $T_2$ receives $110{,}000{,}000$.
  7. ClickHouse `ReplacingMergeTree` would retain $T_2$'s write, silently discarding $T_1$'s later commit!

### 3.1 The High-Water Floor Invariant
To eliminate this hazard without requiring external state stores:
- The connector maintains `sequenceMaxSourceTs`: the highest timestamp assigned to any previously committed transaction.
- When an event arrives with position $P > \text{sequenceHighWaterPosition}$:
  $$\text{effectiveTs} = \max(\text{recordTs}, \text{sequenceMaxSourceTs})$$
  $$\text{sequenceMaxSourceTs} = \text{effectiveTs}$$
- This guarantees that $T_1$, which committed later in the log, receives an `effectiveTs` $\ge 110$, preserving transaction commit monotonicity.

---

## 4. Invariants Preserved
- **Invariant I2 (Deterministic Version Monotonicity)**: Commits appearing later in the binary log are guaranteed to receive greater or equal timestamps, completely preventing version inversion on long transactions.

---

## 5. Verification Criteria
- `LateCommitTransactionIT`: Simulates interleaved long-running and short transactions in MySQL and asserts that the later commit wins in ClickHouse.
- Lean theorem `version_strictly_monotonic` in `formal_specs/lean/Replication/Proofs.lean`.

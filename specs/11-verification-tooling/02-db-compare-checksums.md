# Spec 11.02: Value-Level Checksum Verification (`db_compare`)

## 1. Executive Summary & Purpose
Specifies the ground-truth value-level verification protocol using the `sink-connector/python/db_compare/` toolset to prove data equivalence between MySQL and ClickHouse.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Tool**: `sink-connector/python/db_compare/`
- **Modules**: `db_compare.py`, `table_checksum.py`

---

## 3. Operational Specification

### 3.1 The Row Count Fallacy
- **Principle**: Row count parity between MySQL and ClickHouse is **not** proof of replication correctness.
- In `ReplacingMergeTree`, un-merged duplicate rows, un-purged deletes (`is_deleted = 1`), and column-level default substitutions (e.g. `0` for `NULL`) can all report matching row counts while table contents fundamentally differ.

### 3.2 Value-Level Checksumming Protocol
1. Lock source MySQL table coordinate or take consistent snapshot.
2. Query ClickHouse table with `FINAL` semantics:
   ```sql
   SELECT MD5(groupArray(cityHash64(*))) FROM (
       SELECT * FROM table FINAL ORDER BY id
   )
   ```
3. Compute equivalent hash on MySQL side.
4. Compare hashes:
   - If hashes match $\implies$ Proves 100% bitwise data equivalence across all rows and columns.
   - If hashes differ $\implies$ Positive evidence of data divergence; triggers binary search chunking to pinpoint exact divergent primary keys and column values.

---

## 4. Invariants Preserved
- **Invariant I3 (Eventual Convergence)**: Confirms empirical convergence beyond superficial row counts.

---

## 5. Verification Criteria
- `db_compare` integration test asserting that intentional column divergence trips checksum alarms.

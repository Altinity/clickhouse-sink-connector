# Spec 05.02: Old Key Tombstone Synthesis & Versioning

## 1. Executive Summary & Purpose
Specifies Phase 1 of the sorting key relocation protocol: generating and inserting an explicit delete tombstone row for the old primary/sorting key.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/PreparedStatementFieldMapper.java`
- **Method**: `insertTombstonePreparedStatement()`

---

## 3. Operational Specification

### 3.1 Tombstone Row Construction
When `updateRelocatesSortingKey()` is true:
1. Extract `beforeStruct` (capturing the old sorting key values).
2. Bind positional parameters for all columns using `beforeStruct`.
3. Override engine columns:
   - `is_deleted = 1` (or `_sign = -1` for `CollapsingMergeTree`).
   - `_version = record.getVersion() - 1`.
4. Call `ps.addBatch()`.

### 3.2 Version Decrement ($V - 1$) Rationale
- The old row had version $V_{\text{orig}} < V - 1$.
- Assigning $V - 1$ to the tombstone guarantees that:
  $$V_{\text{orig}} < V_{\text{tombstone}} < V_{\text{new}}$$
- This ensures that the tombstone dominates the old row during part merges, collapsing it to `is_deleted = 1` in `FINAL` queries.

---

## 4. Invariants Preserved
- **Invariant I4 (Sorting Key Mutation Integrity)**: The old sorting key is erased from ClickHouse `FINAL` queries without residual ghost rows.

---

## 5. Verification Criteria
- `PreparedStatementFieldMapperTest.testInsertTombstonePreparedStatement()`
- Lean formal proof `update_pk_relocation_soundness` in `formal_specs/lean/Replication/Proofs.lean`.

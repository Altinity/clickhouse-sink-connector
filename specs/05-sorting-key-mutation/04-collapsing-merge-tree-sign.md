# Spec 05.04: Sign Column Handling for CollapsingMergeTree

## 1. Executive Summary & Purpose
Specifies the binding of the sign column (`_sign`) when replicating to ClickHouse tables using `CollapsingMergeTree` or `VersionedCollapsingMergeTree` engines.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/PreparedStatementFieldMapper.java`

---

## 3. Operational Specification

When the target ClickHouse table engine is detected as `COLLAPSING_MERGE_TREE` or `VERSIONED_COLLAPSING_MERGE_TREE`:
- **For INSERT operations**: `_sign = 1`
- **For DELETE operations**: `_sign = -1`
- **For UPDATE operations**:
  - Phase 1 (Old state cancellation): `_sign = -1`
  - Phase 2 (New state insertion): `_sign = 1`

ClickHouse merges cancel out the $+1$ and $-1$ rows during background compaction.

---

## 4. Invariants Preserved
- **Collapsing Semantics**: Row cancellations strictly mirror source relational deletions and updates.

---

## 5. Verification Criteria
- `CollapsingMergeTreeIT`: Asserts that `_sign = -1` rows collapse properly during merges.

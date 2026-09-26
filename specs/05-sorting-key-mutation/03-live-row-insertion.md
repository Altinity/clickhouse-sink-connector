# Spec 05.03: New Key Live Row Insertion

## 1. Executive Summary & Purpose
Specifies Phase 2 of the sorting key relocation protocol: inserting the live updated row under the new primary/sorting key coordinate.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementFieldMapper.java`
- **Method**: `public void insertPreparedStatement(Map<String, Integer> columnNameToIndexMap, PreparedStatement ps, ...)`
- **Call site**: the UPDATE branch of `PreparedStatementExecutor.executePreparedStatement`, immediately after `insertTombstonePreparedStatement` (spec 05.02) when `updateRelocatesSortingKey(record)` is true

---

## 3. Operational Specification

### 3.1 Live Row Construction
Immediately following the tombstone's `ps.addBatch()`:
1. Bind positional parameters for all columns from the `after` struct (the new sorting key and updated values), using `record.getAfterModifiedFields()`.
2. Set engine columns:
   - `is_deleted = 0` (live row).
   - `_sign = 1` (CollapsingMergeTree only, spec 05.04).
   - `_version = record.getVersion()` (the tombstone carries `_version - 1`).
3. `ps.addBatch()`.
4. Both statements are sent to ClickHouse by the same `executeBatch()` call at the end of the partition (spec 03.06).

---

## 4. Invariants Preserved
- **Invariant I3 (ReplacingMergeTree Convergence)**: the updated data is queryable under the new sorting key with `FINAL`, and the old key resolves to its tombstone.

---

## 5. Verification Criteria
- `PreparedStatementExecutorSortingKeyTombstoneTest.testSortingKeyColumnChangeRequiresTombstone()` — the tombstone-then-live-row pair is emitted for a relocating UPDATE.
- `PreparedStatementFieldMapperRecordCarriesTest`, `PreparedStatementFieldMapperUnboundColumnTest` — binding of the after image.
- Lean: `Replication.Proofs.update_pk_relocation_soundness`.
- Verification: an end-to-end integration test across multiple primary-key updates is not yet covered by an automated test (gap).

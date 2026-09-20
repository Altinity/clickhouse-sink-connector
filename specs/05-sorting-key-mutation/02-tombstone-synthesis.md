# Spec 05.02: Old Key Tombstone Synthesis & Versioning

## 1. Executive Summary & Purpose
Specifies Phase 1 of the sorting key relocation protocol: generating and inserting an explicit delete tombstone row for the old primary/sorting key.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementFieldMapper.java`
- **Method**: `insertTombstonePreparedStatement()`
- **Version source**: `ClickHouseStruct.calculateVersion()` — under GTID
  versioning (`snowflake.id=true`, the default) every row event of one MySQL
  transaction carries the same `_version` (`SnowFlakeId(ts_ms, gtid)`), and
  with `snowflake.id=false` the raw GTID transaction number.

---

## 3. Operational Specification

### 3.1 Tombstone Row Construction
When `updateRelocatesSortingKey()` is true:
1. Extract `beforeStruct` (capturing the old sorting key values).
2. Bind positional parameters for all columns using `beforeStruct`.
3. Override engine columns:
   - `is_deleted = 1` (or `_sign = -1` for `CollapsingMergeTree`).
   - `_version = record.getVersion()` — the record's own version $V$, **not** $V - 1$.
4. Call `ps.addBatch()`.
5. The after-image (Spec 05.03) is added to the same batch **after** the
   tombstone, with `_version = V` and the new sorting key.

### 3.2 Why the tombstone carries $V$ and not $V - 1$
The tombstone (old key) and the after-image (new key) never share a sorting key,
so they never compete under `ReplacingMergeTree`; decrementing the tombstone
bought nothing there. What the tombstone must beat is the **live row already
stored at the old key**, whose version is $V_{\text{orig}}$. Two cases:

- Different transaction: $V_{\text{orig}} < V$, so the tombstone at $V$ dominates.
- Same transaction (`INSERT k='a'` then `UPDATE ... SET k='b'` before COMMIT):
  under GTID versioning $V_{\text{orig}} = V$. A tombstone at $V - 1$ is
  **older** than the live row and loses the merge, leaving a ghost row at
  `k='a'` next to the new row at `k='b'` — MySQL has one row, ClickHouse two.

The rule is therefore
$$V_{\text{orig}} \le V_{\text{tombstone}} = V_{\text{new}} = V$$
and the equal case is resolved by ClickHouse's tie rule: among rows of one
sorting key with equal version, `ReplacingMergeTree` keeps the **last inserted**
row. The tombstone is written after the live row it retires (in stream order,
and inside the same batch after any same-batch insert of the old key), so it
wins the tie. The formal model states the same tie rule in `maxStep` (`>=`,
later record wins) and proves it in `tombstone_wins_version_tie`.

The earlier statement "$V_{\text{orig}} < V - 1$" was false under GTID
versioning and is withdrawn.

---

## 4. Invariants Preserved
- **Invariant I4 (Sorting Key Mutation Integrity)**: The old sorting key is erased from ClickHouse `FINAL` queries without residual ghost rows, including when the relocating UPDATE shares a transaction (and therefore a version) with the row it relocates.

---

## 5. Verification Criteria
- `PreparedStatementFieldMapperTombstoneVersionTest.testTombstoneCarriesRecordVersionUnchanged()` — the value bound to `_version` for the tombstone equals `record.getVersion()` (pre-fix code binds `V - 1`).
- `PreparedStatementFieldMapperTombstoneVersionTest.testTombstoneStillSetsDeleteMarker()`.
- Lean: `update_pk_relocation_soundness` re-proved with `tombstoneVersion i = liveVersion i`, and `tombstone_wins_version_tie` (equal-version tombstone written after the live row yields `none`) in `formal_specs/lean/Replication/Proofs.lean`.

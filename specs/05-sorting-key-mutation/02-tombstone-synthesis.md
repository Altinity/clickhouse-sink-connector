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

A tombstone is a delete marker. For a ReplacingMergeTree /
ReplicatedReplacingMergeTree target whose delete column is not a column of
the table (and `ignore_delete=false`, outside replication-history mode) step 3
has nothing to set, and the row would reach ClickHouse as a LIVE row at the
old key — the ghost row the tombstone exists to prevent.
`insertTombstonePreparedStatement` therefore refuses it with
`IllegalStateException` through `PreparedStatementFieldMapper.requireDeleteColumn`
(spec 08.01 §3.2) instead of writing it; the batch fails with the table, the
missing column and the remediation.

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
- `PreparedStatementFieldMapperEngineColumnTest.testRelocationTombstoneForTableWithoutDeleteColumnIsRefused()` — a target with no delete column refuses the tombstone instead of writing a live row at the old key.
- Lean: `update_pk_relocation_soundness` re-proved with `tombstoneVersion i = liveVersion i`, and `tombstone_wins_version_tie` (equal-version tombstone written after the live row yields `none`) in `formal_specs/lean/Replication/Proofs.lean`.

---

## 6. Failure Modes & Recovery

The tombstone and the after-image of one relocating UPDATE are staged on the same `PreparedStatement` from the same record, so they are always in the same chunk and the same `executeBatch()`: a crash, a failed insert or a redelivery applies both or neither, and a redelivered pair carries the same `_version` and collapses. The failure modes are the configurations in which the tombstone is not a delete marker, and the refusals that are retried instead of stopping.

- **FM-05.02-1 Crash or failed insert between the tombstone and the live row**
  - **Trigger**: kill -9, OOM, network cut or a ClickHouse error during the INSERT carrying a relocation.
  - **Behaviour**: `BatchChunker` splits records, never the rows staged for one record, so the pair travels in one `executeBatch()` (one INSERT); the failure fails the batch before its offset is acknowledged, and the retry or the redelivery stages the pair again with the record's same version (`ClickHouseStruct.calculateVersion` is a function of the GTID / position). A server-side split of one INSERT across `max_insert_block_size` blocks could separate them only at a block boundary (ClickHouse behaviour, not verified).
  - **Detection**: the worker's retry lines (spec 03.03 §6) or the resume summary after a restart; nothing specific to the relocation.
  - **Blast radius**: none after convergence: both rows are rewritten with the same version and collapse.
  - **Recovery**: none needed.
  - **RTO**: that of the retry or restart (spec 03.03 §6, spec 03.01 §6).
  - **Test**: `PreparedStatementExecutorSortingKeyTombstoneTest.testSortingKeyColumnChangeRequiresTombstone()`, `PreparedStatementFieldMapperTombstoneVersionTest.testTombstoneCarriesRecordVersionUnchanged()`, `Replication.Proofs.tombstone_wins_version_tie`.

- **FM-05.02-2 `ignore_delete=true` turns the tombstone into a live ghost row**
  - **Trigger**: a ReplacingMergeTree target with a delete column, `ignore_delete=true`, and an UPDATE that moves a row to another sorting key.
  - **Behaviour**: `PreparedStatementFieldMapper.insertTombstonePreparedStatement` forces the delete marker only when `ignore_delete` is false; otherwise the delete column keeps the `setNull(index, Types.OTHER)` bound for connector-managed columns by `insertPreparedStatement` (observed in the test: parameter = 1111). Stored as the column default 0 — which is how every row written under `ignore_delete=true` is stored — the tombstone is a LIVE row at the old key. `ignore_delete` promises that source removals are not replicated; a relocation is not a source removal.
  - **Detection**: only the startup WARN `ignore_delete=true: source row removals are NOT replicated. ...`, which does not mention relocations.
  - **Blast radius**: one extra live row per relocating UPDATE — MySQL one row, ClickHouse two — permanent.
  - **Recovery**: `ch-mysql-resync` (spec 11.04) of the affected tables; do not combine `ignore_delete=true` with tables whose sorting key can change.
  - **RTO**: resync time — unmeasured.
  - **Test**: `PreparedStatementFieldMapperTombstoneIgnoreDeleteTest.relocationTombstoneUnderIgnoreDeleteStillSetsTheDeleteMarker()` (disabled, fails on 2.11.0).
  - **DEFECT**: the relocation tombstone must set the delete marker whatever `ignore_delete` says.

- **FM-05.02-3 A refused tombstone (or any connector refusal) is retried forever**
  - **Trigger**: a ReplacingMergeTree target without a delete column (`requireDeleteColumn`), an engine column without a placeholder (`requireEngineColumnPlaceholder`), an underivable version (`rejectUnderivableVersion`), a batch grouped into nothing (`addToPreparedStatementBatch`).
  - **Behaviour**: each throws `IllegalStateException` with the table and the remediation — deterministic on every attempt — but without a `Code: NNN`, so `ClickHouseErrorClassifier.classify` answers UNKNOWN and `ClickHouseBatchRunnable.run` retries the batch without bound (spec 03.03 §6 FM-03.03-1), although the messages say the row "cannot be replicated" or is failed "instead of retrying it forever".
  - **Detection**: every attempt: ERROR `ClickHouseBatchRunnable exception - Task(<id>)` with e.g. `The tombstone of an UPDATE that moves the row to another sorting key for ReplacingMergeTree table <db>.<t> cannot be replicated: the table has no delete column ...`, and WARN `Retriable ClickHouse error (Code: -1, Category: UNKNOWN) ...`.
  - **Blast radius**: the worker's tables and the connector's committed offset stall; no loss.
  - **Recovery**: do what the message says (declare `ReplacingMergeTree(<version>, <delete column>)`, point `replacingmergetree.delete.column` at an existing column, or accept `ignore_delete=true`), then restart; a table re-declared with a new engine is reloaded with `ch-mysql-resync` (spec 11.04).
  - **RTO**: operator-bound; unbounded automatically (≈ 102 min to a terminal exit on a busy source, spec 03.03 §6).
  - **Test**: `PreparedStatementFieldMapperEngineColumnTest.testRelocationTombstoneForTableWithoutDeleteColumnIsRefused()` pins the refusal; `ConnectorRefusalClassificationTest.aRefusedTombstoneIsFatal()` and `ConnectorRefusalClassificationTest.aBatchGroupedIntoNothingIsFatal()` (disabled, fail on 2.11.0).
  - **DEFECT**: the connector's deterministic refusals must be FATAL (a terminal exception type, spec 10.01), not UNKNOWN.

Summary: 3 failure modes, 2 DEFECT, 0 GAP.

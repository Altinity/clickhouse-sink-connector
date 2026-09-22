# Spec 05.04: Sign Column Handling for CollapsingMergeTree

## 1. Executive Summary & Purpose
Specifies the binding of the sign column when replicating to ClickHouse tables using the `CollapsingMergeTree` engine.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementFieldMapper.java`
- **Method**: `private void handleSignColumn(Map<String, Integer> columnNameToIndexMap, PreparedStatement ps, ClickHouseStruct record, ClickHouseSinkConnectorConfig config, Map<String, String> columnNameToDataTypeMap, DBMetadata.TABLE_ENGINE engine, boolean beforeSection)`
- **Sign column name**: the `signColumn` constructor argument — the name resolved from the table's engine clause (`CollapsingMergeTree(sgn)` → `sgn`, `DbWriter.getSignColumn()`); `null` disables the binding

---

## 3. Operational Specification

The sign is bound only when `engine == DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE` (compared as the enum constant, never by engine string), `signColumn != null`, and the column exists both in the ClickHouse column map and in the statement's parameter map. A sign column that exists in the table but has no placeholder in the INSERT is refused with `IllegalStateException` (`requireEngineColumnPlaceholder`, spec 04.02 §3.1) rather than skipped: skipping it stored `sgn = 0` for every row and nothing ever collapsed. The resolved name is a connector-managed column of the INSERT whatever it is called (spec 04.02 §3.1). Then:
- **DELETE**: `sign = -1`
- **UPDATE**: `sign = -1` when binding the `before` image (`beforeSection == true`), `sign = 1` when binding the `after` image
- **any other operation (INSERT, snapshot read)**: `sign = 1`

`VersionedCollapsingMergeTree` is not a recognised engine on this path; only `COLLAPSING_MERGE_TREE` triggers the binding. ClickHouse collapses matching `+1`/`-1` pairs during background merges. Because sign rows are additive, a replayed `+1` does not cancel against a single `-1`; the connector therefore never auto-creates this engine (spec 02.04 §3.3, `ReplaySafetyTest`).

### 3.1 An UPDATE stages the cancel row, then the live row
In `PreparedStatementExecutor.executePreparedStatement`, the UPDATE branch on a
`CollapsingMergeTree` target performs, in this order:
1. `insertPreparedStatement(before image, beforeSection = true)` → `sign = -1`;
2. `ps.addBatch()` — the cancel row is staged;
3. `insertPreparedStatement(after image, beforeSection = false)` → `sign = +1`;
4. `ps.addBatch()` — the live row is staged.

The cancel row is what retires the pre-update row under collapsing merges.
Step 2 was missing: the before image was bound and then overwritten in place
by the after image before the only `addBatch()`, so no `-1` row ever reached
ClickHouse, and because the grouping stage appended every UPDATE twice (spec
04.04 §3.1) each UPDATE produced two `+1` rows — the table grew by two live
rows per update and nothing ever collapsed.

---

## 4. Invariants Preserved
- **Collapsing Semantics**: row cancellations mirror source deletions and the before-image half of updates.

---

## 5. Verification Criteria
- `ReplaySafetyTest.testEngineIdentityDoesNotDependOnStringInterning()` — the engine test that gates the sign binding compares the enum constant.
- `ReplaySafetyTest.testAutoCreatedEnginesAreReplaceNotAdditive()` — auto-create never emits `CollapsingMergeTree`.
- `PreparedStatementExecutorCollapsingSignTest.testUpdateStagesCancelRowThenLiveRow()` — §3.1: a recording `PreparedStatement` observes the sign bound at each `addBatch()` for one UPDATE as `[-1, +1]`, the first row carrying the before-image values and the second the after-image values.
- `PreparedStatementExecutorCollapsingSignTest.testInsertStagesOneLiveRow()`, `PreparedStatementExecutorCollapsingSignTest.testDeleteStagesOneCancelRow()` — an INSERT stages exactly `[+1]`, a DELETE exactly `[-1]`.
- `PreparedStatementFieldMapperEngineColumnTest.testNonStandardSignColumnIsBoundOrRefused()` — `CollapsingMergeTree(sgn)`: the sign is bound at `sgn`'s placeholder; a table sign column with no placeholder is refused.
- Verification: an integration test asserting that `-1` rows collapse against their `+1` counterparts on a `CollapsingMergeTree` target is not yet covered by an automated test (gap).

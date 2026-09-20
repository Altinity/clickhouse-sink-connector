# Spec 05.04: Sign Column Handling for CollapsingMergeTree

## 1. Executive Summary & Purpose
Specifies the binding of the sign column when replicating to ClickHouse tables using the `CollapsingMergeTree` engine.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/batch/PreparedStatementFieldMapper.java`
- **Method**: `private void handleSignColumn(Map<String, Integer> columnNameToIndexMap, PreparedStatement ps, ClickHouseStruct record, ClickHouseSinkConnectorConfig config, Map<String, String> columnNameToDataTypeMap, DBMetadata.TABLE_ENGINE engine, boolean beforeSection)`
- **Sign column name**: the `signColumn` constructor argument (configured sign column; `null` disables the binding)

---

## 3. Operational Specification

The sign is bound only when `engine == DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE` (compared as the enum constant, never by engine string), `signColumn != null`, and the column exists both in the ClickHouse column map and in the statement's parameter map. Then:
- **DELETE**: `sign = -1`
- **UPDATE**: `sign = -1` when binding the `before` image (`beforeSection == true`), `sign = 1` when binding the `after` image
- **any other operation (INSERT, snapshot read)**: `sign = 1`

`VersionedCollapsingMergeTree` is not a recognised engine on this path; only `COLLAPSING_MERGE_TREE` triggers the binding. ClickHouse collapses matching `+1`/`-1` pairs during background merges. Because sign rows are additive, a replayed `+1` does not cancel against a single `-1`; the connector therefore never auto-creates this engine (spec 02.04 §3.3, `ReplaySafetyTest`).

---

## 4. Invariants Preserved
- **Collapsing Semantics**: row cancellations mirror source deletions and the before-image half of updates.

---

## 5. Verification Criteria
- `ReplaySafetyTest.testEngineIdentityDoesNotDependOnStringInterning()` — the engine test that gates the sign binding compares the enum constant.
- `ReplaySafetyTest.testAutoCreatedEnginesAreReplaceNotAdditive()` — auto-create never emits `CollapsingMergeTree`.
- Verification: an integration test asserting that `-1` rows collapse against their `+1` counterparts on a `CollapsingMergeTree` target is not yet covered by an automated test (gap).

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
2a. `ps.clearParameters()` — the cancel row's bind state is cleared;
3. `insertPreparedStatement(after image, beforeSection = false)` → `sign = +1`;
4. `ps.addBatch()` — the live row is staged.

The cancel row is what retires the pre-update row under collapsing merges.
Step 2 was missing: the before image was bound and then overwritten in place
by the after image before the only `addBatch()`, so no `-1` row ever reached
ClickHouse, and because the grouping stage appended every UPDATE twice (spec
04.04 §3.1) each UPDATE produced two `+1` rows — the table grew by two live
rows per update and nothing ever collapsed.

Step 2a is required for the same reason the ReplacingMergeTree relocation
tombstone clears its bind state (spec 05.02, spec 07.07 §3.2.2): the V2
ClickHouse JDBC driver retains the parameters bound for a row across
`addBatch()`. The after image at step 3 binds `sign = +1` and its own columns,
but any parameter it does not rebind — a column absent from the after image —
would otherwise silently inherit the cancel row's value, including `sign = -1`,
turning the live row into a second cancel row so the UPDATE never lands.
Clearing the bind state between the two rows makes the after image depend only
on what it explicitly binds, exactly as the general per-row path and the
ReplacingMergeTree tombstone path already do.

---

## 4. Invariants Preserved
- **Collapsing Semantics**: row cancellations mirror source deletions and the before-image half of updates.

---

## 5. Verification Criteria
- `ReplaySafetyTest.testEngineIdentityDoesNotDependOnStringInterning()` — the engine test that gates the sign binding compares the enum constant.
- `ReplaySafetyTest.testAutoCreatedEnginesAreReplaceNotAdditive()` — auto-create never emits `CollapsingMergeTree`.
- `PreparedStatementExecutorCollapsingSignTest.testUpdateStagesCancelRowThenLiveRow()` — §3.1: a recording `PreparedStatement` observes the sign bound at each `addBatch()` for one UPDATE as `[-1, +1]`, the first row carrying the before-image values and the second the after-image values; the recording statement also clears its parameter map on `addBatch()`, so the after image's `+1` is bound independently of the cancel row (step 2a).
- `PreparedStatementExecutorCollapsingSignTest.testInsertStagesOneLiveRow()`, `PreparedStatementExecutorCollapsingSignTest.testDeleteStagesOneCancelRow()` — an INSERT stages exactly `[+1]`, a DELETE exactly `[-1]`.
- `PreparedStatementFieldMapperEngineColumnTest.testNonStandardSignColumnIsBoundOrRefused()` — `CollapsingMergeTree(sgn)`: the sign is bound at `sgn`'s placeholder; a table sign column with no placeholder is refused.
- Verification: an integration test asserting that `-1` rows collapse against their `+1` counterparts on a `CollapsingMergeTree` target is not yet covered by an automated test (gap).

---

## 6. Failure Modes & Recovery

A CollapsingMergeTree target has no recovery by idempotence: sign rows are additive, so every write the connector repeats — a whole-batch retry after a partial write, a redelivery after a restart — is a permanent divergence, and every error in reading the sign column from the engine clause silently disables collapsing. The connector never auto-creates this engine (spec 02.04 §3.3); these failure modes apply to tables an operator created.

- **FM-05.04-1 A repeated write duplicates sign rows**
  - **Trigger**: a batch retried after one of its tables or chunks was already written (spec 03.03 §6 FM-03.03-2, spec 03.06 §6 FM-03.06-2), or the units above the committed offset redelivered after any restart (spec 03.07 §7 FM-03.07-3).
  - **Behaviour**: the `-1`/`+1` rows are written again; nothing deduplicates them. ClickHouse's block-level insert deduplication (Replicated* engines, `insert_deduplicate`) could absorb a byte-identical retried block — that the V2 driver renders a retried INSERT identically is not verified — and never absorbs a redelivery, which forms different batches.
  - **Detection**: none: `sum(sign)` over the key drifts from MySQL; found by the value-level checksum (spec 11.02).
  - **Blast radius**: every repeated row of that table, permanently (an unmatched extra `+1` or `-1` never collapses).
  - **Recovery**: `ch-mysql-resync` (spec 11.04) of the table; move the table to ReplacingMergeTree.
  - **RTO**: resync time, proportional to the table — unmeasured.
  - **Test**: `ReplaySafetyTest.testAutoCreatedEnginesAreReplaceNotAdditive()`, `WorkerFailureModesTest.aRetryAfterAPartialWriteRewritesTheTablesAlreadyWritten()`.
  - **DEFECT**: at-least-once delivery is not replay-safe on this engine and nothing detects the resulting divergence.

- **FM-05.04-2 A sign column without a placeholder is refused and retried forever**
  - **Trigger**: the table has its sign column but the generated INSERT has no placeholder for it (a column-name case mismatch, a stale template).
  - **Behaviour**: `requireEngineColumnPlaceholder` throws `IllegalStateException` rather than storing `sign = 0` — then the refusal is classified UNKNOWN and retried without bound (spec 05.02 §6 FM-05.02-3).
  - **Detection**: every attempt: ERROR `ClickHouseBatchRunnable exception - Task(<id>)` with the refusal message, WARN `Retriable ClickHouse error (Code: -1, Category: UNKNOWN) ...`.
  - **Blast radius**: the worker's tables and the connector's committed offset stall; no loss.
  - **Recovery**: fix the table or the writer's schema cache as the message says, restart.
  - **RTO**: operator-bound (spec 03.03 §6).
  - **Test**: `PreparedStatementFieldMapperEngineColumnTest.testNonStandardSignColumnIsBoundOrRefused()`.
  - **DEFECT**: shared with FM-05.02-3 — a deterministic refusal is retried instead of stopping.

- **FM-05.04-3 The sign column is misread from a Replicated or Versioned engine clause**
  - **Trigger**: a `ReplicatedCollapsingMergeTree('<zk path>', '<replica>', sign)` or a `VersionedCollapsingMergeTree(sign, ver)` target.
  - **Behaviour**: `DBMetadata.getEngineFromResponse` matches `engine_full.contains("CollapsingMergeTree")`, so both are COLLAPSING_MERGE_TREE (contrary to §3's statement that the Versioned engine is not recognised), and `getSignColumnForCollapsingMergeTree` takes the text between `CollapsingMergeTree(` and the first `)`: `'<zk path>', '<replica>', sign` and `sign, ver`. No column has that name, so `handleSignColumn` binds nothing to the real sign column; what the bind path then writes for it (spec 04.03) was not traced here.
  - **Detection**: none specific; the effect is a table that never collapses, or a batch that fails in the bind path.
  - **Blast radius**: every row of such a table is written without the connector's sign — collapsing semantics lost.
  - **Recovery**: `ch-mysql-resync` (spec 11.04) into a ReplacingMergeTree table; until fixed, do not replicate into these engines.
  - **RTO**: resync time — unmeasured.
  - **Test**: `CollapsingEngineParsingTest.plainCollapsingMergeTreeResolvesItsSignColumn()`; `CollapsingEngineParsingTest.replicatedCollapsingMergeTreeResolvesItsSignColumn()` and `CollapsingEngineParsingTest.versionedCollapsingMergeTreeIsNotTreatedAsCollapsing()` (disabled, fail on 2.11.0).
  - **DEFECT**: the sign column must be parsed as the engine's last (Replicated) or first (Versioned, if supported) argument, and an unsupported engine refused, instead of a substring match.

Summary: 3 failure modes, 3 DEFECT, 0 GAP.

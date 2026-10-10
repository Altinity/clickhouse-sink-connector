# Spec 06.07: Primary Key Alteration Rules

## 1. Executive Summary & Purpose
Specifies how `ALTER TABLE ... ADD PRIMARY KEY` and `ALTER TABLE ... DROP PRIMARY KEY` are handled on existing ClickHouse tables, where the sorting key is immutable (`Code: 524`): a restatement of the identity the replica already has is skipped; a change of the source's row identity is followed by rebuilding the replica under the new key at the DDL barrier (Spec 06.09), or — when that rebuild is disabled — stops the pipeline loudly and names the manual rebuild.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java` (`enterAlterTable`, `enforcePrimaryKeyPolicy`, `isNoOpSpecification`, `primaryKeyRebuildPlan`)
- **Target key**: `com.altinity.clickhouse.debezium.embedded.ddl.parser.TargetSchemaLookup` (`sortingKeyTypes`)
- **Rebuild plan**: `com.altinity.clickhouse.debezium.embedded.ddl.parser.PrimaryKeyRebuildPlan` (Spec 06.09)
- **Configuration**: `ddl.primary.key.rebuild` (`SinkConnectorLightWeightConfig.DDL_PRIMARY_KEY_REBUILD`, default `true`)
- **Formal model**: `formal_specs/lean/Replication/DdlTranslation.lean`

---

## 3. Operational Specification

In ClickHouse `ReplacingMergeTree` the primary key and `ORDER BY` sorting key are established at `CREATE TABLE` and are immutable. Both `ALTER TABLE t ADD PRIMARY KEY (...)` and any statement that would change the key fail with
`Code: 524. DB::Exception: Modifying primary key is not supported.`

The sorting key is the replica's **row identity**: `ReplacingMergeTree` collapses rows that agree on it. When the source changes its identity and the replica keeps the old one, rows that the source keeps distinct collapse in ClickHouse (or a relocated row is not retired) — count-clean, permanent divergence. Skipping such a change silently, as this spec once prescribed, was a loss in disguise.

### 3.1 The net identity of a statement
`enterAlterTable` records, over the whole statement, whether a `DROP PRIMARY KEY` clause occurred and the column list of the **last** primary-key declaration — an `ADD [CONSTRAINT] PRIMARY KEY (cols)` clause or a column-level `PRIMARY KEY` inside an `ADD`/`MODIFY`/`CHANGE COLUMN` definition (which also makes that column `NOT NULL` on `ADD COLUMN`, MySQL's rule). The clauses themselves emit nothing (there is no ClickHouse equivalent) and leave no dangling comma; the neighbouring column clauses are translated as usual. Once the whole clause list has been walked, `enforcePrimaryKeyPolicy` compares the net identity with the target table's sorting key (`TargetSchemaLookup.sortingKeyTypes`, clean identifiers, the connector's own columns `_version`, `_sign`, `is_deleted`/`_is_deleted`, `_valid_from`, `_valid_to`, `_operation` removed):

1. **Sorting key unknown** (no writer/lookup, table not found, or the table has `ORDER BY tuple()`, which the lookup cannot tell apart from unknown): nothing can be checked; the key clauses are skipped at INFO exactly as before and the rest of the statement is emitted. Pinned by `testAlterAddPrimaryKeyOnlyIsSkipped`, `testAlterAddPrimaryKeyFirstNoLeadingComma`, `testAlterAddColumnThenAddPrimaryKey`, `testAlterDropPrimaryKeyOnlyIsSkipped` and the unknown-schema half of `testAlterDropPrimaryKeyModifyKeyAddColumnAddPrimaryKey`.
2. **Restatement** — a primary key is declared and its column set equals the sorting-key column set (case-insensitively, backticks stripped, order ignored): the identity is unchanged (typical when the replica's key was adopted from a `UNIQUE` key the source now promotes to `PRIMARY KEY`, or when `DROP PRIMARY KEY, ADD PRIMARY KEY (same)` re-declares it). Skipped at INFO; the statement's other clauses are emitted.
3. **Identity change** — a primary key is declared whose column set differs from the sorting key, or `DROP PRIMARY KEY` occurs without a new key being declared (the source no longer identifies rows by those columns at all). ClickHouse cannot re-key a table in place, so the replica is **rebuilt** under the new identity at the DDL barrier — the same clustered-index rebuild MySQL performs for the statement — per Spec 06.09: `enforcePrimaryKeyPolicy` records a `PrimaryKeyRebuildPlan` (old key, new key, per-column provenance) and the statement's representable clauses are emitted as usual. With `ddl.primary.key.rebuild=false` the former behaviour applies: `DDLReplicationException` naming the table, the existing sorting key, the requested key and the manual remedy — re-create the ClickHouse table with the new `ORDER BY` and re-snapshot it — is raised and **nothing is emitted for the statement** (Invariant I9). Both cover the production shape `DROP PRIMARY KEY, MODIFY COLUMN old_id ..., ADD COLUMN new_id ... FIRST, ADD PRIMARY KEY (new_id)`, which once translated to just the `ADD COLUMN` and left the replica keyed by `old_id`, and the fix a keyless table eventually receives (`ADD COLUMN my_row_id ... INVISIBLE PRIMARY KEY`, Spec 06.05 §3.6).

Pinned by `testAddPrimaryKeyThatChangesIdentityPlansRebuild`, `testDropPrimaryKeyPlansRebuild` and `testPrimaryKeyChangeIsLoudWhenRebuildDisabled`; formalised as `primary_key_change_rebuilds` (`Clause.primaryKeyChange` translates to `Emission.rebuild` in `DdlTranslation.lean`; a restatement or an unknown key is a `noOp`).

### 3.2 Why the change is followed by a rebuild, not an ALTER
ClickHouse cannot alter a sorting key in place, and a new identity cannot be applied to the rows already stored under the old one without re-keying them — and, when the new key column was generated by the source (`AUTO_INCREMENT`), without reading its values from the source. MySQL itself copies the table into a new clustered index for this statement (reference manual §17.12.1, *Online DDL Operations*), so following the source means doing the same on the replica; Spec 06.09 specifies that rebuild, its preconditions and what stays loud. Reporting the change loudly instead (the former rule 3, still available with `ddl.primary.key.rebuild=false`) is strictly better than the behaviour before it, where the divergence surfaced only in `db_compare`, long after the rows had collapsed.

### 3.3 MODIFY / CHANGE / RENAME of a sorting-key column
Handled by Spec 06.05 §3.4: a same-or-narrower type re-declaration is suppressed as loss-free; a widening, non-comparable or renaming change is deferred to a rebuild of the table under the same identity (Spec 06.09 §3.1.1 — MySQL rebuilds its clustered index for such a change too), or, with `ddl.primary.key.rebuild=false`, raises `DDLReplicationException` naming the manual rebuild. Formalised as `wider_key_change_rebuilds`.

### 3.4 No bare statement
A statement consisting only of skipped key/index clauses never reaches ClickHouse as `ALTER TABLE t` (`Code: 62`). Formalised as `no_bare_alter`.

---

## 4. Invariants Preserved
- **Continuous Stream Health (I5)**: no ALTER is sent for an operation ClickHouse physically cannot execute; an identity change is applied inside the DDL barrier as a rebuild.
- **Eventual Convergence (I3)**: the replica is never left identifying rows by a key the source no longer uses — it is re-keyed (Spec 06.09) or the operator is told.
- **Loud Failure (I9)**: a key change that cannot be rebuilt, or whose rebuild is disabled, is raised, not retried and not skipped.

---

## 5. Verification Criteria
- `AlterTableModifyColumnIT.testAlterAddPrimaryKeyAndModifyNotNull()`
- `MySqlDDLParserListenerImplTest.testAlterAddPrimaryKeyOnlyIsSkipped()`, `testAlterAddPrimaryKeyFirstNoLeadingComma()`, `testAlterAddColumnThenAddPrimaryKey()`, `testAlterDropPrimaryKeyOnlyIsSkipped()` (unknown key: skipped), `testAlterDropPrimaryKeyModifyKeyAddColumnAddPrimaryKey()` (known key `id`, new key `ref_id`: rebuild planned, `ADD COLUMN` emitted; unknown key: unchanged), `testAddPrimaryKeyThatChangesIdentityPlansRebuild()` (restatement skipped; different set / superset / column-level PRIMARY KEY plan a rebuild and emit their other clauses), `testDropPrimaryKeyPlansRebuild()` (lone DROP plans the all-columns key; `DROP ..., ADD PRIMARY KEY (same)` skipped), `testPrimaryKeyChangeIsLoudWhenRebuildDisabled()` (`ddl.primary.key.rebuild=false`: loud, nothing emitted).
- Formal: `no_bare_alter`, `wider_key_change_rebuilds`, `primary_key_change_rebuilds`, `rebuild_never_bare`, `rebuild_only_when_not_loud` in `formal_specs/lean/Replication/DdlTranslation.lean`.

---

## 6. Failure Modes & Recovery
The policy is only as good as its knowledge of the replica's current sorting key: with the key known, an identity change is rebuilt (spec 06.09) or refused; with the key "unknown" it is skipped, and a skipped identity change is a silent, count-clean collapse of rows. The failure modes are the paths that end in "unknown" when the key is in fact knowable, and the loud refusal.

- **FM-06.07-1 Identity change refused (rebuild disabled or not possible)**
  - **Trigger**: `ADD PRIMARY KEY` of another column set, or `DROP PRIMARY KEY` without replacement, with `ddl.primary.key.rebuild=false`, or with a rebuild precondition unmet (spec 06.09 §3.2).
  - **Behaviour**: `MySqlDDLParserListenerImpl.enforcePrimaryKeyPolicy()` raises `DDLReplicationException` (or `PrimaryKeyRebuild.refuse()` does, spec 06.09); nothing of the statement is emitted; engine restarts `errors.max.retries` times, then terminal stop.
  - **Detection**: ERROR naming the table, the existing sorting key, the requested key and `Manual rebuild required: re-create ... with ORDER BY matching the new key and re-snapshot the table`; exit code 3 after the restarts.
  - **Blast radius**: all replication stops at the statement; nothing lost.
  - **Recovery**: when disabled by configuration, set `ddl.primary.key.rebuild=true` and restart (the re-delivered statement is rebuilt online). Otherwise rebuild by hand: create `<t>_new` with the new `ORDER BY`, load it from MySQL (`ch-mysql-resync`, spec 11.04), count-reconcile, `EXCHANGE TABLES` (ON CLUSTER in replicated mode), apply the statement's other clauses by hand, add `ignore.ddl.regex` for the statement, restart, remove the entry.
  - **RTO**: restart + online rebuild when enabled (spec 06.09 §6); otherwise proportional to the table size; unmeasured.
  - **Test**: `MySqlDDLParserListenerImplTest.testPrimaryKeyChangeIsLoudWhenRebuildDisabled()`.
  - **DEFECT**: when the automatic rebuild cannot run, recovery is a manual reload of the table, beyond the I15 RTO.

- **FM-06.07-2 Identity change on a table whose key reads as empty (`ORDER BY tuple()`)**
  - **Trigger**: a replica table created with `ORDER BY tuple()` (by a version before spec 06.05 §3.6, or by hand), then a source `ADD PRIMARY KEY`.
  - **Behaviour**: `TargetSchemaLookup.sortingKeyTypes` returns empty for `ORDER BY tuple()`; §3.1 rule 1 treats it as unknown: the key clauses are skipped at INFO and the rest of the statement is emitted. The replica keeps `ORDER BY tuple()`, under which `ReplacingMergeTree` keeps one row for the whole table.
  - **Detection**: INFO only (the skipped clause is logged); no ERROR.
  - **Blast radius**: that table holds one row per merge, permanently; row counts diverge.
  - **Recovery**: rebuild the table by hand with the source key (as in FM-06.07-1) and re-synchronise it (`ch-mysql-resync`, spec 11.04).
  - **RTO**: table rebuild, proportional to its size; unmeasured.
  - **Test**: `MySqlDDLParserListenerImplTest.testAlterAddPrimaryKeyOnlyIsSkipped()` (unknown key: skipped).
  - **DEFECT**: an `ORDER BY tuple()` replica is indistinguishable from "unknown" and the identity change that would repair it is skipped silently; `system.tables.sorting_key = ''` should be read explicitly and refused or rebuilt.

- **FM-06.07-3 Sorting-key lookup fails and the identity change is skipped**
  - **Trigger**: ClickHouse briefly unreachable, a `system.columns` timeout or a missing privilege while `ALTER TABLE ... DROP PRIMARY KEY, ADD PRIMARY KEY (...)` is translated.
  - **Behaviour**: `DBMetadata.getSortingKeyColumns()` catches the error and returns an empty list; `MetadataTargetSchemaLookup.sortingKeyTypes()` answers empty; `enforcePrimaryKeyPolicy()` applies rule 1 (unknown): no rebuild plan, key clauses skipped, statement acknowledged. The same fallback lets a key-column `MODIFY` through as an ordinary clause, which ClickHouse refuses with Code 524 and which is then swallowed (spec 06.08 §6 FM-06.08-2).
  - **Detection**: ERROR `Error retrieving sorting key columns for <db>.<t>`, then INFO for the skipped clauses; nothing afterwards.
  - **Blast radius**: the replica keeps the old identity; rows the source now keeps distinct collapse (or a relocated row is not retired), count-clean and permanently.
  - **Recovery**: rebuild the table under the new key (FM-06.07-1 manual procedure, or re-deliver the statement: rewind to it with `sink-connector-client change_replication_source` to the binlog position/GTID before the DDL, which the rebuild then applies, followed by the spec 11.04 reconciliation of the table as I15 requires for any offset move).
  - **RTO**: rebuild + reconciliation of the table; unmeasured.
  - **Test**: `DdlTranslationFailureModesTest.failedSortingKeyLookupIsLoudNotUnknown()` (disabled; fails on 2.11.0), `DdlTranslationFailureModesTest.failedSortingKeyLookupSkipsPrimaryKeyChangeToday()`.
  - **DEFECT**: a transient failure of the key lookup turns an identity change into a silent no-op; the lookup must distinguish "query failed" (retry/halt) from "no key".

- **FM-06.07-4 Identity change re-delivered after it was applied**
  - **Trigger**: crash after the rebuild's swap and before the DDL offset was committed; or an operator rewind over the statement.
  - **Behaviour**: the replica's sorting key now equals the declared key, so rule 2 (restatement) applies: no second rebuild; the statement's other clauses are guarded and idempotent (spec 06.04 §6 FM-06.04-2).
  - **Detection**: INFO for the skipped key clauses; none needed.
  - **Blast radius**: none.
  - **Recovery**: self-heals; a pending backfill is resumed from the retired table (spec 06.09 §6 FM-06.09-3).
  - **RTO**: restart + re-delivery; unmeasured.
  - **Test**: `MySqlDDLParserListenerImplTest.testRedeliveredPrimaryKeyChangeIsRestatement()`.

Summary: 4 failure modes, 3 DEFECT, 0 GAP.

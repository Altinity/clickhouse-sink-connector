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
Handled by Spec 06.05 §3.4: a same-or-narrower type re-declaration is suppressed as loss-free; a widening, non-comparable or renaming change raises `DDLReplicationException` naming the manual rebuild. Formalised as `wider_key_change_is_loud`.

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
- Formal: `no_bare_alter`, `wider_key_change_is_loud`, `primary_key_change_rebuilds`, `rebuild_never_bare` in `formal_specs/lean/Replication/DdlTranslation.lean`.

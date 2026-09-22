# Spec 06.07: Primary Key Alteration Rules

## 1. Executive Summary & Purpose
Specifies how `ALTER TABLE ... ADD PRIMARY KEY` and `ALTER TABLE ... DROP PRIMARY KEY` are handled on existing ClickHouse tables, where the sorting key is immutable (`Code: 524`): a change of the source's row identity that the replica cannot follow stops the pipeline loudly and names the rebuild; a restatement of the identity the replica already has is skipped.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java` (`enterAlterTable`, `enforcePrimaryKeyPolicy`, `isNoOpSpecification`)
- **Target key**: `com.altinity.clickhouse.debezium.embedded.ddl.parser.TargetSchemaLookup` (`sortingKeyTypes`)
- **Formal model**: `formal_specs/lean/Replication/DdlTranslation.lean`

---

## 3. Operational Specification

In ClickHouse `ReplacingMergeTree` the primary key and `ORDER BY` sorting key are established at `CREATE TABLE` and are immutable. Both `ALTER TABLE t ADD PRIMARY KEY (...)` and any statement that would change the key fail with
`Code: 524. DB::Exception: Modifying primary key is not supported.`

The sorting key is the replica's **row identity**: `ReplacingMergeTree` collapses rows that agree on it. When the source changes its identity and the replica keeps the old one, rows that the source keeps distinct collapse in ClickHouse (or a relocated row is not retired) — count-clean, permanent divergence. Skipping such a change silently, as this spec previously prescribed, was a loss in disguise.

### 3.1 The net identity of a statement
`enterAlterTable` records, over the whole statement, whether a `DROP PRIMARY KEY` clause occurred and the column list of the **last** primary-key declaration — an `ADD [CONSTRAINT] PRIMARY KEY (cols)` clause or a column-level `PRIMARY KEY` inside an `ADD`/`MODIFY`/`CHANGE COLUMN` definition (which also makes that column `NOT NULL` on `ADD COLUMN`, MySQL's rule). The clauses themselves emit nothing (there is no ClickHouse equivalent) and leave no dangling comma; the neighbouring column clauses are translated as usual. Once the whole clause list has been walked, `enforcePrimaryKeyPolicy` compares the net identity with the target table's sorting key (`TargetSchemaLookup.sortingKeyTypes`, clean identifiers, the connector's own columns `_version`, `_sign`, `is_deleted`/`_is_deleted`, `_valid_from`, `_valid_to`, `_operation` removed):

1. **Sorting key unknown** (no writer/lookup, table not found, or the table has `ORDER BY tuple()`, which the lookup cannot tell apart from unknown): nothing can be checked; the key clauses are skipped at INFO exactly as before and the rest of the statement is emitted. Pinned by `testAlterAddPrimaryKeyOnlyIsSkipped`, `testAlterAddPrimaryKeyFirstNoLeadingComma`, `testAlterAddColumnThenAddPrimaryKey`, `testAlterDropPrimaryKeyOnlyIsSkipped` and the unknown-schema half of `testAlterDropPrimaryKeyModifyKeyAddColumnAddPrimaryKey`.
2. **Restatement** — a primary key is declared and its column set equals the sorting-key column set (case-insensitively, backticks stripped, order ignored): the identity is unchanged (typical when the replica's key was adopted from a `UNIQUE` key the source now promotes to `PRIMARY KEY`, or when `DROP PRIMARY KEY, ADD PRIMARY KEY (same)` re-declares it). Skipped at INFO; the statement's other clauses are emitted.
3. **Identity change** — a primary key is declared whose column set differs from the sorting key, or `DROP PRIMARY KEY` occurs without a new key being declared (the source no longer identifies rows by those columns at all): `DDLReplicationException` naming the table, the existing sorting key, the requested key and the remedy — re-create the ClickHouse table with the new `ORDER BY` and re-snapshot it — is raised and **nothing is emitted for the statement** (Invariant I9). This includes the production shape `DROP PRIMARY KEY, MODIFY COLUMN old_id ..., ADD COLUMN new_id ... FIRST, ADD PRIMARY KEY (new_id)`, which previously translated to just the `ADD COLUMN` and left the replica keyed by `old_id`, and the fix a keyless table eventually receives (`ADD COLUMN my_row_id ... INVISIBLE PRIMARY KEY`, Spec 06.05 §3.6), after which the table is rebuilt with its real identity.

Pinned by `testAddPrimaryKeyThatChangesIdentityIsLoud` and `testDropPrimaryKeyIsLoud`; formalised as `primary_key_change_is_loud` (`Clause.primaryKeyChange` is in the loud set of `DdlTranslation.lean`; a restatement or an unknown key is a `noOp`).

### 3.2 Why not follow the change
ClickHouse cannot alter a sorting key in place, and a new identity cannot be applied to the rows already stored under the old one without re-reading them from the source. The rebuild is therefore the only correct remedy, and it is an operator action on the replica (Spec 08.05 §3.2.2 lists the same limit for the value-derived fallback key). Reporting it loudly at the DDL is strictly better than the previous behaviour, where the divergence surfaced only in `db_compare`, long after the rows had collapsed.

### 3.3 MODIFY / CHANGE / RENAME of a sorting-key column
Handled by Spec 06.05 §3.4: a same-or-narrower type re-declaration is suppressed as loss-free; a widening, non-comparable or renaming change raises `DDLReplicationException` naming the manual rebuild. Formalised as `wider_key_change_is_loud`.

### 3.4 No bare statement
A statement consisting only of skipped key/index clauses never reaches ClickHouse as `ALTER TABLE t` (`Code: 62`). Formalised as `no_bare_alter`.

---

## 4. Invariants Preserved
- **Continuous Stream Health (I5)**: no ALTER is sent for an operation ClickHouse physically cannot execute.
- **Eventual Convergence (I3)**: the replica is never left identifying rows by a key the source no longer uses without the operator being told.
- **Loud Failure (I9)**: a key change that would silently change row identity or lose values is raised, not retried and not skipped.

---

## 5. Verification Criteria
- `AlterTableModifyColumnIT.testAlterAddPrimaryKeyAndModifyNotNull()`
- `MySqlDDLParserListenerImplTest.testAlterAddPrimaryKeyOnlyIsSkipped()`, `testAlterAddPrimaryKeyFirstNoLeadingComma()`, `testAlterAddColumnThenAddPrimaryKey()`, `testAlterDropPrimaryKeyOnlyIsSkipped()` (unknown key: skipped), `testAlterDropPrimaryKeyModifyKeyAddColumnAddPrimaryKey()` (known key `id`, new key `ref_id`: loud — flipped from "emits the ADD COLUMN"; unknown key: unchanged), `testAddPrimaryKeyThatChangesIdentityIsLoud()` (restatement skipped, different set / superset / column-level PRIMARY KEY loud, nothing emitted), `testDropPrimaryKeyIsLoud()` (lone DROP loud; `DROP ..., ADD PRIMARY KEY (same)` skipped).
- Formal: `no_bare_alter`, `wider_key_change_is_loud`, `primary_key_change_is_loud` in `formal_specs/lean/Replication/DdlTranslation.lean`.

# Spec 06.07: Primary Key Alteration Suppression Rules

## 1. Executive Summary & Purpose
Specifies how `ALTER TABLE ... ADD PRIMARY KEY` and `ALTER TABLE ... DROP PRIMARY KEY` are handled on existing ClickHouse tables, where the sorting key is immutable (`Code: 524`), and what a consumer can expect when the source changes the uniqueness of those columns.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java` (`enterAlterTable`, `isNoOpSpecification`)
- **Formal model**: `formal_specs/lean/Replication/DdlTranslation.lean`

---

## 3. Operational Specification

In ClickHouse `ReplacingMergeTree` the primary key and `ORDER BY` sorting key are established at `CREATE TABLE` and are immutable. Both `ALTER TABLE t ADD PRIMARY KEY (...)` and any statement that would change the key fail with
`Code: 524. DB::Exception: Modifying primary key is not supported.`

### 3.1 ADD PRIMARY KEY — skipped
- When `enterAlterTable` encounters an `ADD PRIMARY KEY` clause on an existing table:
  - In a multi-clause statement (e.g. `ALTER TABLE t ADD PRIMARY KEY (id), ADD COLUMN c INT`) the clause is stripped and no leading, trailing or doubled comma is left behind.
  - When it is the only clause, the translator logs at INFO and emits the empty string; the event is acknowledged without failing the stream.
- The id COLUMN a surrogate key introduces is still added by the accompanying `ADD COLUMN` clause, so no source value is lost; only the un-representable constraint is dropped.

### 3.2 DROP PRIMARY KEY — skipped, with a documented consequence
- `ALTER TABLE t DROP PRIMARY KEY` is in the skip class (Spec 06.03 §3.2): it emits nothing, logs at INFO, and a lone `DROP PRIMARY KEY` translates to `""`. The existing ClickHouse sorting key stays as it was.
- **Consequence.** The ClickHouse sorting key continues to identify rows by the *old* key columns. If the source, having dropped uniqueness on those columns, later holds two rows that agree on them, `ReplacingMergeTree` collapses them to one under `FINAL`. That divergence is not representable by any ALTER; it is reported the same way every key divergence is: by the value-level checksum (`db_compare`) and, when the source afterwards tries to change the type of one of those columns, by the loud path in §3.3. The remediation is a manual rebuild of the ClickHouse table with the new identity (a new `ORDER BY`) followed by a re-snapshot of that table.
- The usual production shape — `DROP PRIMARY KEY, MODIFY COLUMN old_id ..., ADD COLUMN new_id ... FIRST, ADD PRIMARY KEY (new_id)` — therefore translates to just the `ADD COLUMN` (Spec 06.03 §3.2). The old key column remains the ClickHouse sorting key and remains `NOT NULL` on the source until the source drops the column, at which point `DROP COLUMN` of a key column is itself rejected by ClickHouse (`Code: 47`) and surfaces the need for the rebuild.

### 3.3 MODIFY / CHANGE / RENAME of a sorting-key column
Handled by Spec 06.05 §3.4: a same-or-narrower type re-declaration is suppressed as loss-free; a widening, non-comparable or renaming change raises `DDLReplicationException` naming the manual rebuild. Formalised as `wider_key_change_is_loud`.

### 3.4 No bare statement
A statement consisting only of skipped key/index clauses never reaches ClickHouse as `ALTER TABLE t` (`Code: 62`). Formalised as `no_bare_alter`.

---

## 4. Invariants Preserved
- **Continuous Stream Health (I5)**: no ALTER is sent for an operation ClickHouse physically cannot execute.
- **Loud Failure (I9)**: a key change that would silently change row identity or lose values is raised, not retried.

---

## 5. Verification Criteria
- `AlterTableModifyColumnIT.testAlterAddPrimaryKeyAndModifyNotNull()`
- `MySqlDDLParserListenerImplTest.testAlterAddPrimaryKeyOnlyIsSkipped()`, `testAlterAddPrimaryKeyFirstNoLeadingComma()`, `testAlterAddColumnThenAddPrimaryKey()`, `testAlterDropPrimaryKeyOnlyIsSkipped()`, `testAlterDropPrimaryKeyModifyKeyAddColumnAddPrimaryKey()`
- Formal: `no_bare_alter`, `wider_key_change_is_loud` in `formal_specs/lean/Replication/DdlTranslation.lean`.

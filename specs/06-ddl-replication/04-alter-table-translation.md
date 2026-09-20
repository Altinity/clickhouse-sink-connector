# Spec 06.04: ALTER TABLE Clause Translation Rules

## 1. Executive Summary & Purpose
Specifies the translation mapping from MySQL `ALTER TABLE` clauses (and the table-level statements `RENAME TABLE`, `DROP TABLE`, `CREATE TABLE ... LIKE`) to native ClickHouse DDL, including the replay-safety guards each emitted statement must carry and the text-normalisation rules for identifiers, defaults and key lists.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java` (`enterAlterTable`, `parseAlterTable`, `translateColumnClause`, `parseRenameColumn`, `enterCopyCreateTable`, `enterDropTable`, `enterRenameTable`)
- **Templates**: `com.altinity.clickhouse.debezium.embedded.ddl.parser.Constants`
- **Type mapping**: `com.altinity.clickhouse.debezium.embedded.parser.DataTypeConverter`
- **Formal model**: `formal_specs/lean/Replication/DdlTranslation.lean`

---

## 3. Translation Mapping Specification

### 3.1 Column clauses

| MySQL Syntax | ClickHouse Translated Syntax | Notes |
|---|---|---|
| `ALTER TABLE t ADD COLUMN c INT` | `ALTER TABLE \`db\`.t ADD COLUMN IF NOT EXISTS c Nullable(Int32)` | Type mapped via `DataTypeConverter`; nullability per Spec 06.05 |
| `ALTER TABLE t ADD COLUMN c INT AFTER a` / `FIRST` | `... ADD COLUMN IF NOT EXISTS c Nullable(Int32) AFTER a` / `FIRST` | Position preserved |
| `ALTER TABLE t ADD COLUMN (a INT, b INT)` | `ALTER TABLE \`db\`.t ADD COLUMN IF NOT EXISTS a Nullable(Int32), ADD COLUMN IF NOT EXISTS b Nullable(Int32)` | `alterByAddColumns` / `alterByAddDefinitions`: each `(uid, columnDefinition)` pair goes through the single ADD COLUMN logic; index/constraint declarations inside the list are skipped |
| `ALTER TABLE t DROP COLUMN c` | `ALTER TABLE \`db\`.t DROP COLUMN IF EXISTS c` | Idempotent drop; `c` is case-resolved against the target table |
| `ALTER TABLE t MODIFY COLUMN c VARCHAR(200)` | `ALTER TABLE \`db\`.t MODIFY COLUMN c Nullable(String)` | Deliberately **unguarded** (a replayed MODIFY is a no-op; `IF EXISTS` would hide a missing column). Suppressed or loud when `c` is a sorting-key column (Spec 06.05 §3.4) |
| `ALTER TABLE t CHANGE COLUMN old new INT` | `ALTER TABLE \`db\`.t MODIFY COLUMN IF EXISTS old Nullable(Int32)` **newline** `ALTER TABLE \`db\`.t RENAME COLUMN IF EXISTS old to new` | Two statements. The MODIFY half carries `IF EXISTS` because once the rename has been applied `old` no longer exists and a replay would fail with `Code: 10`. The RENAME half is emitted **only when the backtick-stripped names differ**: `CHANGE COLUMN c c BIGINT` emits just `MODIFY COLUMN c Nullable(Int64)` (a self-rename is rejected with `Code: 15`). Both statements target the destination database. |
| `ALTER TABLE t RENAME COLUMN a TO b` | `ALTER TABLE \`db\`.t RENAME COLUMN IF EXISTS a to b` | Guarded; `a` is case-resolved |
| `ALTER TABLE t ADD CONSTRAINT k CHECK (expr)` | `ALTER TABLE \`db\`.t ADD CONSTRAINT k CHECK (expr)` | Appended by `enterAlterByAddCheckTableConstraint` |
| `ALTER TABLE t DROP CONSTRAINT k` | `ALTER TABLE \`db\`.t DROP CONSTRAINT IF EXISTS k` | |

Position tokens (`FIRST` / `AFTER x`) are collected from the clause's own children and appended after the column definition and any `DEFAULT`.

### 3.2 DEFAULT clauses on ADD / MODIFY / CHANGE
A source `DEFAULT` is copied to ClickHouse **only when it is a literal**: `NULL`, or `unaryOperator? constant` (string, integer, decimal, hexadecimal, bit-string, boolean literal). Everything else — `CURRENT_TIMESTAMP`, `CURRENT_TIMESTAMP(n)`, `NOW()`, `LOCALTIME*`, `... ON UPDATE CURRENT_TIMESTAMP`, `CAST(...)`, parenthesised expressions, sequence/vendor forms — is **dropped** and logged at INFO. Rationale: row images carry the source value for every replicated row, so a ClickHouse `DEFAULT` only ever affects pre-existing rows, which MySQL back-filled one-shot on the source; a verbatim MySQL function reference is invalid ClickHouse (`Code: 47`/`62`), non-retryable, and would leave the column never added. Pinned by `MySqlDDLParserListenerImplTest.testAddColumnDefaultCurrentTimestampIsDropped` and `testAddColumnLiteralDefaultIsKept`. The `TINYINT(1) NOT NULL DEFAULT 0` → `coins Int8 DEFAULT 0` mapping is unchanged.

### 3.3 Skipped clauses and the no-bare-ALTER rule
Clauses in the skip class of Spec 06.03 §3.2 (indexes, keys, foreign keys, `DROP PRIMARY KEY`, `ALTER COLUMN SET/DROP DEFAULT`, charset/collation, table options, `ALGORITHM`/`LOCK`, partition operations) emit nothing and drop the separator that preceded them. A statement whose clauses are all skipped translates to `""`. Pinned by `testAlterAddIndexOnlyIsSkipped`, `testAlterDropPrimaryKeyOnlyIsSkipped`, `testAddConstraints`, `testAlterDropPrimaryKeyModifyKeyAddColumnAddPrimaryKey`. Formal: `no_bare_alter`, `add_columns_preserved`.

### 3.4 Table-level statements

| MySQL Syntax | ClickHouse Translated Syntax | Notes |
|---|---|---|
| `ALTER TABLE t ADD COLUMN c INT, RENAME TO t2` | `ALTER TABLE \`db\`.t ADD COLUMN IF NOT EXISTS c Nullable(Int32)` **newline** `RENAME TABLE IF EXISTS \`db\`.t TO \`db\`.t2` | The rename is a separate statement emitted after the body; it never discards the other clauses. A lone `RENAME TO` emits only the `RENAME TABLE`. A qualified target (`RENAME TO db2.t2`) is split on the dot and re-qualified with the destination database (`\`db\`.t2`, never `\`db\`.db2.t2`). |
| `RENAME TABLE a TO b [, c TO d]` | `RENAME TABLE IF EXISTS db.a to db.b[,db.c to db.d]` | `IF EXISTS` (accepted by ClickHouse 24.8, applies to every clause) makes a replay after restart a no-op: once applied, `a` is gone. |
| `DROP TABLE [IF EXISTS] t` | `DROP TABLE IF EXISTS db.t` | **Always** `IF EXISTS`. MySQL binlogs `DROP TABLE db.t /* generated by server */` without it, so a replay of the source form would fail. |
| `CREATE TABLE n LIKE o` / `CREATE TABLE n (LIKE db2.o)` | `CREATE TABLE IF NOT EXISTS \`db\`.n AS \`db\`.o` | `IF NOT EXISTS` for replay; **both** operands are split on the dot and re-qualified with the destination database. |
| `TRUNCATE TABLE t` | `TRUNCATE TABLE \`db\`.t` | |

### 3.5 Text normalisation rules
- **Index column lists** (`PRIMARY KEY (id ASC, name(10) DESC)`) are read from the parse tree, not from `getText()`: the emitted `ORDER BY (id,name)` carries bare column names, never `idASC` (`Code: 47`) or prefix lengths.
- **DECIMAL/DEC/NUMERIC/FIXED with a precision but no scale** (`DECIMAL(20)`, `DECIMAL(18) UNSIGNED`) maps to `Decimal(20, 0)` / `Decimal(18, 0)`. A bare `Decimal` is `Decimal(10, 0)` in ClickHouse and rejects values with more than ten digits (`Code: 69`). Types without any dimension are unchanged.
- **Identifiers** used in `system.columns` lookups are cleaned as in Spec 06.03 §3.3.

---

## 4. Invariants Preserved
- **Idempotency (I5/I9)**: every emitted statement whose replay could fail carries a guard (`IF NOT EXISTS` on ADD/CREATE, `IF EXISTS` on DROP/RENAME/the MODIFY half of CHANGE); a plain user `MODIFY` stays unguarded on purpose.
- **Column authority (I6)**: an ADD COLUMN is never lost because a neighbouring clause was unrepresentable.
- **Loud failure (I9)**: unrepresentable *changes* (as opposed to unrepresentable *constraints*) raise `DDLReplicationException` (Spec 06.05 §3.4).

---

## 5. Verification Criteria
- `MySqlDDLParserListenerImplTest`: `testAlterDatabaseAddColumn`, `testDropColumn`, `testAlterAddColumnsParenthesisedList`, `testAlterAddDefinitionsSkipsEmbeddedIndex`, `testChangeColumn`, `testChangeColumnSameNameEmitsNoRename`, `testAlterAddColumnThenRenameToKeepsBothStatements`, `testAlterRenameToQualifiedTarget`, `testCreateTableLike`, `testCreateTableLikeQualifiedSource`, `dropTable`, `renameTable`, `testCreateTablePrimaryKeyWithSortOrder`, `testDecimalPrecisionWithoutScale`, `testAddColumnDefaultCurrentTimestampIsDropped`, `testAddColumnLiteralDefaultIsKept`.
- `DdlReplayIdempotencyTest`: `testChangeColumnModifyHalfIsGuarded`, `testCreateTableLikeIsGuarded`, `testRenameTableIsGuarded`, `testDropTableIsAlwaysGuarded`, `testModifyColumnIsDeliberatelyNotGuarded`.
- Formal: `no_bare_alter`, `add_columns_preserved` in `DdlTranslation.lean`.

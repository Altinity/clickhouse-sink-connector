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
| `ALTER TABLE t ADD [CONSTRAINT k] CHECK (expr) [[NOT] ENFORCED]` | *(nothing — skip class)* | A CHECK constraint holds no data and MySQL has already validated every replicated row against it. Echoed verbatim it is rejected by ClickHouse when unnamed (`ADD CHECK (a > 0)`), when `NOT ENFORCED` is present, or when the expression uses a MySQL function ClickHouse lacks; and when ClickHouse does accept it, it re-enforces the constraint on INSERT, so a source row admitted under a later-relaxed or `NOT ENFORCED` constraint fails the batch. `CREATE TABLE` already dropped CHECKs; ALTER now agrees. |
| `ALTER TABLE t DROP CONSTRAINT k` / `DROP CHECK k` | *(nothing — skip class)* | No CHECK constraint is ever created on the replica, so there is nothing to drop; `DROP CONSTRAINT IF EXISTS k` was emitted before and was a no-op at best (and, for a source `DROP CONSTRAINT` naming a foreign key or unique constraint, an unrelated statement). |

Position tokens (`FIRST` / `AFTER x`) are collected from the clause's own children and appended after the column definition and any `DEFAULT`.

### 3.2 DEFAULT clauses on ADD / MODIFY / CHANGE
A ClickHouse `DEFAULT` never changes a replicated value: every row image
carries the source value and the writer binds it explicitly (Spec 04.03). It
matters for exactly one thing — the **back-fill of the rows that pre-date an
`ADD COLUMN`**. MySQL fills those rows one-shot with the column's default (or
its implicit default when none is declared) at the moment of the ALTER;
ClickHouse fills them with the column's `DEFAULT` expression, or the type's
zero value when there is none. If the two differ, every pre-existing row of
that column diverges, count-clean and permanently, which is the divergence
the rules below prevent. A `MODIFY`/`CHANGE` back-fills nothing, so its
`DEFAULT` is carried only when it is a literal and dropped (INFO) otherwise.

#### 3.2.1 Literal defaults — carried, normalised to ClickHouse syntax
`NULL` and `unaryOperator? constant` are carried on ADD, MODIFY and CHANGE,
rewritten from MySQL to ClickHouse literal syntax where the two differ
(copied verbatim, each of these is rejected by ClickHouse and, being retried,
stalls the stream):
- **Double-quoted strings** (`DEFAULT "dq"`, a string in MySQL, an identifier
  in ClickHouse) → single-quoted (`'dq'`), with an embedded `'` escaped as
  `\'` and the MySQL `""`/`\"` escapes unescaped. A national prefix (`N'x'`)
  and a charset introducer (`_utf8mb4'x'`) are stripped.
- **Bit-string literals** `b'0101'`: for a numeric column type (`Int*`,
  `UInt*`, `Float*`, `Decimal`, `Bool`) the integer value (`b'101'` → `5`);
  for every other type the bytes MySQL would store (bits packed big-endian
  into whole bytes), in the representation the writer uses for a BYTES value
  in a `String` column (Spec 07.05): lowercase hex text by default
  (`b'1000001'` → `'41'`, `b''` → `''`), or the raw bytes as `unhex('41')`
  when `persist.raw.bytes=true`. A back-fill that used the other
  representation would make every pre-existing row differ from the rows the
  writer inserts.
- **Hexadecimal literals** `X'0A0B'` / `0x0A0B`: for a numeric column type the
  integer value (`0x0A` → `10`); otherwise the same BYTES representation
  (`'0a0b'` by default, `unhex('0A0B')` under `persist.raw.bytes=true`) — the
  exact source bytes for a `String` column holding `BINARY`/`VARBINARY`/`BLOB`
  or `BIT(n>1)` data.
- Integer, decimal, real and boolean literals are copied as written; a
  leading unary `-`/`+` is kept.
Pinned by `testAddColumnLiteralDefaultIsKept` and
`testBitStringAndHexDefaultsAreTranslated`. The `TINYINT(1) NOT NULL DEFAULT
0` → `coins Int8 DEFAULT 0` mapping is unchanged.

#### 3.2.2 `ADD COLUMN` back-fill of a non-literal default
1. **`DEFAULT CURRENT_TIMESTAMP[(n)]` / `NOW()` / `LOCALTIME[STAMP]`** (with or
   without `ON UPDATE CURRENT_TIMESTAMP`): MySQL back-filled every existing row
   with the statement's own timestamp. The translator emits that instant as a
   literal derived from the DDL event's `source.ts_ms` (the statement time
   Debezium records for the schema-change event, handed to the parser by
   `performDDLOperation` through `DDLParserService.setDdlEventTimestampMs`):
   `DEFAULT toDateTime64('<UTC wall clock>', p, 'UTC')` for a `DateTime64(p…)`
   column (`p` = 3 when the type carries no precision, ClickHouse's default
   scale), `DEFAULT toDateTime('<UTC wall clock>', 'UTC')` for a `DateTime`
   column. The literal names its zone, so the stored instant does not depend
   on the ClickHouse server zone; `source.ts_ms` is millisecond-precise, so
   digits beyond the millisecond are zero. The `ON UPDATE` half is not
   representable and is dropped (later rows carry their values). When the
   event timestamp is unknown (no `source.ts_ms` was supplied) the
   translator cannot compute the back-fill and fails loudly (rule 3) rather
   than guess.
2. **`ENUM(...) NOT NULL` with no `DEFAULT`**: MySQL's implicit default is the
   first enumeration member, so the translator emits `DEFAULT '<first
   member>'` (the ClickHouse column is `String`, whose zero value `''` is not a
   member). A nullable `ENUM` without a default stays without one (NULL on both
   sides). MySQL's other implicit defaults coincide with ClickHouse's zero
   values (`0`, `''`); a `DATE`/`DATETIME NOT NULL` column without a default
   cannot be added to a non-empty table under MySQL's default `sql_mode`
   (`NO_ZERO_DATE`), and under a permissive mode its `'0000-00-00'` back-fill is
   a documented gap.
3. **Any other non-literal default** — `CAST(...)`, a parenthesised
   expression (`DEFAULT (UUID())`, `DEFAULT (CURRENT_DATE)`, `DEFAULT (1 + 2)`),
   sequence and vendor forms — is a value MySQL computed per row (or once) on
   the source that the replica cannot reproduce for the rows that already
   exist. Dropping it silently (the previous behaviour) left those rows
   holding the type's zero value. The translator now raises
   `DDLReplicationException` naming the table, the column, the default and
   the remedy: apply the column on ClickHouse by hand with the values the
   source holds (or re-snapshot the table), then let the connector past the
   statement with `ignore.ddl.regex`. Nothing is emitted for the statement.

Pinned by `testAddColumnCurrentTimestampBackfillsLiteral`,
`testAddEnumNotNullDefaultsToFirstMember`, `testAddColumnExpressionDefaultIsLoud`
and, for the MODIFY/CHANGE drop, `testModifyColumnDefaultCurrentTimestampIsDropped`.

### 3.3 Skipped clauses and the no-bare-ALTER rule
Clauses in the skip class of Spec 06.03 §3.2 (indexes, keys, foreign keys, `DROP PRIMARY KEY`, `ALTER COLUMN SET/DROP DEFAULT`, CHECK constraints — `ADD [CONSTRAINT] CHECK` and `DROP CONSTRAINT|CHECK` —, charset/collation, table options, `ALGORITHM`/`LOCK`, partition operations) emit nothing and drop the separator that preceded them. A statement whose clauses are all skipped translates to `""`. Pinned by `testAlterAddIndexOnlyIsSkipped`, `testAlterDropPrimaryKeyOnlyIsSkipped`, `testAddConstraints`, `testAddCheckConstraintIsSkipped`, `testAlterDropPrimaryKeyModifyKeyAddColumnAddPrimaryKey`. Formal: `no_bare_alter`, `add_columns_preserved`.

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
- `MySqlDDLParserListenerImplTest`: `testAlterDatabaseAddColumn`, `testDropColumn`, `testAlterAddColumnsParenthesisedList`, `testAlterAddDefinitionsSkipsEmbeddedIndex`, `testChangeColumn`, `testChangeColumnSameNameEmitsNoRename`, `testAlterAddColumnThenRenameToKeepsBothStatements`, `testAlterRenameToQualifiedTarget`, `testCreateTableLike`, `testCreateTableLikeQualifiedSource`, `dropTable`, `renameTable`, `testCreateTablePrimaryKeyWithSortOrder`, `testDecimalPrecisionWithoutScale`, `testAddColumnLiteralDefaultIsKept`, `testBitStringAndHexDefaultsAreTranslated`, `testAddColumnCurrentTimestampBackfillsLiteral`, `testAddEnumNotNullDefaultsToFirstMember`, `testAlterDatabaseAddColumnEnum` (flipped: `ENUM NOT NULL` now gets `DEFAULT '<first member>'`), `testAddColumnExpressionDefaultIsLoud`, `testModifyColumnDefaultCurrentTimestampIsDropped` (the former `testAddColumnDefaultCurrentTimestampIsDropped`, which pinned the dropped back-fill, is split into these), `testAddCheckConstraintIsSkipped` (`ADD CHECK`, `ADD CONSTRAINT ... CHECK ... NOT ENFORCED`, `DROP CONSTRAINT`, `DROP CHECK` emit nothing alone and never drop a neighbouring `ADD COLUMN`; pre-fix code echoed them), `testDropContraints`, `testAddConstraintsWithAnd`, `testAlterTableAddConstraint` (flipped from echo to skip).
- `DdlReplayIdempotencyTest`: `testChangeColumnModifyHalfIsGuarded`, `testCreateTableLikeIsGuarded`, `testRenameTableIsGuarded`, `testDropTableIsAlwaysGuarded`, `testModifyColumnIsDeliberatelyNotGuarded`.
- Formal: `no_bare_alter`, `add_columns_preserved` in `DdlTranslation.lean`.

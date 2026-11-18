# Spec 06.04: ALTER TABLE Clause Translation Rules

## 1. Executive Summary & Purpose
Specifies the translation mapping from MySQL `ALTER TABLE` clauses (and the table-level statements `RENAME TABLE`, `DROP TABLE`, `CREATE TABLE ... LIKE`) to native ClickHouse DDL, including the replay-safety guards each emitted statement must carry and the text-normalisation rules for identifiers, defaults and key lists.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java` (`enterAlterTable`, `parseAlterTable`, `translateColumnClause`, `parseRenameColumn`, `enterCopyCreateTable`, `enterDropTable`, `enterRenameTable`, `resolveDefault`)
- **DEFAULT-literal translation**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/DefaultLiteralTranslator.java` (`literalDefault`, `isCurrentTimestampDefault`, `firstEnumMember`; §3.2.1 and the literal parts of §3.2.2 rules 1-2). `resolveDefault` calls into it for the literal rewrite and the two literal implicit-default rules, and keeps the non-literal back-fill decision (§3.2.2 rule 3) and the DDL-event-timestamp-dependent `eventInstantLiteral`/`backfillNotReproducible` helpers, which need the engine's own state.
- **Templates**: `com.altinity.clickhouse.debezium.embedded.ddl.parser.Constants`
- **Type mapping**: `com.altinity.clickhouse.debezium.embedded.parser.DataTypeConverter`
- **Column lists**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/DdlColumnFilter.java` (`from`, `keeps`, `NONE`; the list property names are its `COLUMN_INCLUDE_LIST` / `COLUMN_EXCLUDE_LIST` constants, read from Debezium's `RelationalDatabaseConnectorConfig`). Supplied per statement through `DDLParserService.setColumnFilter` and applied by `MySqlDDLParserListenerImpl.keepsCreateColumn` / `keepsAlterColumn` / `keepsColumn` (§3.6)
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
`NULL` and `unaryOperator? constant` are carried on ADD, MODIFY and CHANGE
(with the single exception of §3.2.3: a `NULL` literal on a nullable column
is implied by the type and is not emitted),
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
- **An enclosing parenthesis around a single literal is transparent**
  (`DEFAULT (0)`, `DEFAULT ((0))`, `DEFAULT (-1)`, `DEFAULT ('x')`,
  `DEFAULT (NULL)`): the default is the literal it wraps and is translated
  exactly as the unparenthesised form would be. MySQL 8.0 writes a default
  supplied in parentheses as the `'(' expression ')'` alternative of its
  grammar, so this shape is common even for a plain constant — a
  `TINYINT(1) NOT NULL DEFAULT (0)` column back-filled every pre-existing
  row with `0`, and `DEFAULT 0` reproduces it exactly. It must be carried:
  the ClickHouse `DEFAULT` is re-evaluated at read time for every part that
  lacks the column, so a value that does not vary with time is the only
  faithful back-fill, and a literal qualifies by construction. A
  parenthesised *expression* (`(1 + 2)`, `(UUID())`, `(CURRENT_DATE)`) is
  not a single literal and stays in rule 3 of §3.2.2.
Pinned by `testAddColumnLiteralDefaultIsKept`,
`testBitStringAndHexDefaultsAreTranslated` and
`testParenthesizedLiteralDefaultIsTranslated`. The `TINYINT(1) NOT NULL DEFAULT
0` → `coins Int8 DEFAULT 0` mapping is unchanged, with or without the
parentheses.

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
   expression that is not a single literal (`DEFAULT (UUID())`,
   `DEFAULT (CURRENT_DATE)`, `DEFAULT (1 + 2)`), sequence and vendor forms —
   is a value MySQL computed per row (or once) on
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

#### 3.2.3 `DEFAULT NULL` on a nullable column is implied and is NOT emitted
A `DEFAULT NULL` on a column the translator emits as `Nullable(T)` adds
nothing: an insert that omits the column stores NULL with or without it, so
the value of every replicated row and every back-filled row is identical
either way (measured: Spec 06.04 §5 `testExplicitNullDefaultOnANullableColumnIsNotEmitted`).
It is not inert, though — ClickHouse validates a column's `DEFAULT`
expression on **every later `ALTER`**, and the expression `NULL` is cast to
the new type as part of that validation. When the statement has no `DEFAULT`
of its own, the carried one is what breaks it:

```
ALTER TABLE `db`.`t` MODIFY COLUMN `label` String
  Code: 70. DB::Exception: Cannot convert NULL to a non-nullable type:
  While processing _CAST(NULL AS label_tmp_alter<N>, 'String') AS label:
  default expression and column type are incompatible.
```

with the bare `Nullable(String)` (no `DEFAULT`) the same statement succeeds.
MySQL renders a nullable column that was declared **without** any default as
`DEFAULT NULL` in `SHOW CREATE TABLE` — the form the connector parses — so
this shape arrives for essentially every nullable source column and turns an
ordinary `MODIFY` into a permanently failing DDL. The translator therefore
drops the `NULL` literal in every case, on a nullable column and on a
`NOT NULL` column alike:

- a **`NOT NULL`** column cannot hold NULL at all, so the clause describes no
  value it could store, and ClickHouse rejects it outright: `Int32 NOT NULL
  DEFAULT NULL` is `Code: 70 CANNOT_CONVERT_TYPE` ("Cannot convert NULL to a
  non-nullable type"), and so is `ALTER TABLE ... ADD COLUMN c Int32 DEFAULT
  NULL` — measured on 24.8.8.17 for both statement forms. Emitting it produced
  a statement that failed on every retry and stalled the stream;
- on a **nullable** column it is implied by the type, as above;
- every **non-NULL** literal default is unchanged (§3.2.1).

Pinned by `CreateTableDefaultEmissionTest.explicitNullDefaultOnANullableColumnIsNotEmitted`,
`CreateTableDefaultEmissionTest.nullDefaultOnANotNullColumnIsDropped`,
`DefaultLiteralTranslatorTest.nullLiteralIsNotEmitted`,
`MySqlDDLParserListenerImplTest.testAddColumnLiteralDefaultIsKept` and
`AlterTableDdlVariationMatrixTest.parenthesisedLiteralDefaultsSurvive`.

### 3.3 Skipped clauses and the no-bare-ALTER rule
Clauses in the skip class of Spec 06.03 §3.2 (indexes, keys, foreign keys, `ADD`/`DROP PRIMARY KEY` when the target key is unknown or restated — loud otherwise, Spec 06.07 §3.1 —, `ALTER COLUMN SET/DROP DEFAULT`, CHECK constraints — `ADD [CONSTRAINT] CHECK` and `DROP CONSTRAINT|CHECK` —, `DEFAULT CHARACTER SET` and table options, `ALGORITHM`/`LOCK`, and the partition operations that move no rows: `ADD`, `DROP`, `TRUNCATE`, `COALESCE`, `REORGANIZE`, `ANALYZE`, `CHECK`, `OPTIMIZE`, `REBUILD`, `REPAIR` PARTITION) emit nothing and drop the separator that preceded them. A statement whose clauses are all skipped translates to `""`. Pinned by `testAlterAddIndexOnlyIsSkipped`, `testAlterDropPrimaryKeyOnlyIsSkipped`, `testAddConstraints`, `testAddCheckConstraintIsSkipped`, `testAlterDropPrimaryKeyModifyKeyAddColumnAddPrimaryKey`, `testAlterDefaultCharsetStaysANoOp`. Formal: `no_bare_alter`, `add_columns_preserved`.

**Loud refusals.** A clause that rewrites the stored values of rows MySQL already holds is never skipped: `CONVERT TO CHARACTER SET` (MySQL re-encodes every string column in place with no row event), `DISCARD TABLESPACE`, `IMPORT TABLESPACE`, and `EXCHANGE PARTITION`, `DISCARD PARTITION`, `IMPORT PARTITION`. Translation throws `DDLReplicationException` naming the clause; the connector halts (Spec 06.08 §3.1) instead of acknowledging a count-clean, permanently divergent replica. `DROP PARTITION` and `TRUNCATE PARTITION` are not in this class: they stay ignored (Spec 06.08 §3.3), with a WARN. Pinned by `testAlterConvertCharsetIsRefusedNotSkipped`, `DdlTranslationFailureModesTest.dataChangingPartitionOperationIsLoud`, and on the grammar matrices by `AlterTableDdlVariationMatrixTest.dataChangingClausesAreRefusedLoudly` and `AlterTableGrammarDerivedCoverageTest.everyAlterFormParses` (both name the six clauses and require the `DDLReplicationException`; every other clause in those matrices must still translate without throwing).

### 3.4 Table-level statements

| MySQL Syntax | ClickHouse Translated Syntax | Notes |
|---|---|---|
| `ALTER TABLE t ADD COLUMN c INT, RENAME TO t2` | `ALTER TABLE \`db\`.t ADD COLUMN IF NOT EXISTS c Nullable(Int32)` **newline** `RENAME TABLE IF EXISTS \`db\`.t TO \`db\`.t2` | The rename is a separate statement emitted after the body; it never discards the other clauses. A lone `RENAME TO` emits only the `RENAME TABLE`. A qualified target (`RENAME TO db2.t2`) is split on the dot and re-qualified with the destination database (`\`db\`.t2`, never `\`db\`.db2.t2`). Open: a cross-database `ALTER ... RENAME TO db2.t2` moves the table to `db2` in MySQL but is still emitted into the destination database of the source table; it has the same defect class as the cross-database RENAME TABLE row below and is not changed by this revision. |
| `RENAME TABLE a TO b [, c TO d]` | `RENAME TABLE IF EXISTS db.a to db.b[,db.c to db.d]` | `IF EXISTS` (accepted by ClickHouse 24.8, applies to every clause) makes a replay after restart a no-op: once applied, `a` is gone. |
| `RENAME TABLE src.t TO dst.t` (cross-database) | `RENAME TABLE IF EXISTS src.t to dst.t` | Each side keeps its own database: a qualified side is mapped through the database override map as the DDL's database is, an unqualified side is in the DDL's database. Rewriting both sides to the listener's database renamed a table that does not exist, and `IF EXISTS` made that a silent no-op. Pinned by `renameTableAcrossDatabasesKeepsEachSideDatabase` and `renameTableWithinOneDatabaseIsUnchanged`. The same-database form is unchanged. |
| `DROP TABLE [IF EXISTS] t` | `DROP TABLE IF EXISTS db.t` | **Always** `IF EXISTS`. MySQL binlogs `DROP TABLE db.t /* generated by server */` without it, so a replay of the source form would fail. |
| `CREATE TABLE n LIKE o` / `CREATE TABLE n (LIKE db2.o)` | `CREATE TABLE IF NOT EXISTS \`db\`.n AS \`db\`.o` | `IF NOT EXISTS` for replay; **both** operands are split on the dot and re-qualified with the destination database. |
| `TRUNCATE TABLE t` | `TRUNCATE TABLE \`db\`.t` | |

### 3.5 Text normalisation rules
- **Index column lists** (`PRIMARY KEY (id ASC, name(10) DESC)`) are read from the parse tree, not from `getText()`: the emitted `ORDER BY (id,name)` carries bare column names, never `idASC` (`Code: 47`) or prefix lengths.
- **DECIMAL/DEC/NUMERIC/FIXED with a precision but no scale** (`DECIMAL(20)`, `DECIMAL(18) UNSIGNED`) maps to `Decimal(20, 0)` / `Decimal(18, 0)`. A bare `Decimal` is `Decimal(10, 0)` in ClickHouse and rejects values with more than ten digits (`Code: 69`). Types without any dimension are unchanged.
- **Identifiers** used in `system.columns` lookups are cleaned as in Spec 06.03 §3.3.

### 3.6 `column.include.list` / `column.exclude.list` decide which columns are CREATED
Debezium already applies the two column lists to the **record** schema: an
excluded column's value is never sent. The translator used to ignore them and
translate every column of the source `CREATE TABLE` / `ALTER TABLE`, so the
excluded column was still CREATED on ClickHouse — empty, but present. The lists
exist to keep a column (a password, a token, a large blob) out of the
analytics database, and an empty column defeats that; worse, the two creation
paths disagreed about the same table, because the record-schema path (spec
08.05) creates only the replicated columns.

The DDL path therefore applies the **same** filter, through Debezium's own
matcher, so both paths and the record path cannot disagree:

1. **One filter, Debezium's semantics.** `DdlColumnFilter` reads
   `column.include.list` / `column.exclude.list` through
   `RelationalDatabaseConnectorConfig`'s own field names (never restated
   literals), splits on commas and matches each entry as a **full,
   case-insensitive regular expression** against `catalog.table.column`.
   An **include** list, when set, is the whole rule and the exclude list is
   ignored; both blank means no filtering. MySQL matches in `CATALOG` mode,
   where the database is the catalog.
2. **The match uses the SOURCE database.** The lists are written against
   source names, so the filter is given the statement's source database
   (`sourceDatabaseName`, spec 06.08 §3.3) — never the destination name,
   which may be prefixed, suffixed or overridden. An **unknown database or
   column is never filtered out**: Debezium's matcher answers "no" for a null
   catalog, and treating that as a rejection would drop an entire table.
3. **CREATE TABLE** emits only the kept columns (`keepsCreateColumn`); an
   `ALTER TABLE ... ADD/ADD (…)` emits a clause only for a kept column
   (`keepsAlterColumn`). A dropped, modified or renamed excluded column emits
   nothing: the ClickHouse table never had it, so there is nothing to drop or
   modify and no rebuild-plan entry to record.
4. **A key column is never filtered out.** The columns collected for
   `ORDER BY` — the table-level `PRIMARY KEY`, the first table-level
   `UNIQUE KEY`, and a **column-level** `c INT PRIMARY KEY` / `UNIQUE` /
   `SERIAL` — are always created, with a WARN naming the column and the list.
   Filtering one out would leave the CREATE referring to a column it never
   declares (`Code: 47`), or let the keyless fallback silently re-key the
   table and change which source rows ClickHouse collapses into one. The
   lists are a projection, never a re-keying.
5. **An ALTER that targets the sorting key is left to the key policy**
   (spec 06.05/06.09), which owns those clauses.
6. **A CREATE whose every column the list excludes is created unfiltered**,
   with a WARN. An empty column set cannot be translated at all — the
   sorting-key policy refuses it and a DDL failure is terminal for every
   table on the host (spec 06.08 §3.1) — and the same include list governs
   capture, so Debezium replicates no row of such a table either. Keeping the
   source schema there cannot hide a table that is replicating.
7. **The record path is unchanged.** The filter only stops a column being
   CREATED; the values that arrive are the ones Debezium already sends.

Pinned by `DdlColumnFilterTest` (the matcher: include/exclude, include wins,
full case-insensitive match, unknown identifiers kept, property names from
Debezium, blank list) and `DdlColumnFilterEmissionTest` (the translator: the
reported list `app.users.(id|name),app.orders.(id|user_id)`, the CREATE and
every ALTER clause form, the key column, the no-match CREATE, and the source
vs destination database).

---

## 4. Invariants Preserved
- **Idempotency (I5/I9)**: every emitted statement whose replay could fail carries a guard (`IF NOT EXISTS` on ADD/CREATE, `IF EXISTS` on DROP/RENAME/the MODIFY half of CHANGE); a plain user `MODIFY` stays unguarded on purpose.
- **Column authority (I6)**: an ADD COLUMN is never lost because a neighbouring clause was unrepresentable. The column lists decide which columns exist (a projection of the source), but never which column the table's identity is built from: a key column is always created (§3.6 rule 4).
- **Creation-path parity (Prime Directive)**: the schema-DDL path creates exactly the columns the record path replicates, so a column the lists exclude is absent from ClickHouse whichever path created the table (§3.6).
- **Loud failure (I9)**: unrepresentable *changes* (as opposed to unrepresentable *constraints*) raise `DDLReplicationException` (Spec 06.05 §3.4). A column the list filters is not a failure: it is a documented skip, logged at INFO.

---

## 5. Verification Criteria
- `CreateTableDefaultEmissionTest`: `everyLiteralDefaultIsCarried`, `explicitNullDefaultOnANullableColumnIsNotEmitted` (the `DEFAULT NULL` of a nullable column is implied and dropped, §3.2.3), `nullDefaultOnANotNullColumnIsDropped` (the `DEFAULT NULL` of a `NOT NULL` column is unrepresentable in ClickHouse and is dropped too, §3.2.3), `notNullColumnKeepsItsDefault`, `parenthesizedLiteralIsUnwrapped`, `currentTimestampDefaultIsCarried`, `currentTimestampDefaultFollowsTheColumnScale`, `enumNotNullWithoutDefaultGetsTheFirstMember`, `nullableEnumGetsNoImplicitDefault`, `expressionDefaultsAreDroppedNotGuessed`, `unparenthesizedExpressionDefaultsStayRejected`, `generatedColumnIsUnaffected`, `plainColumnGainsNothing`, `defaultIsInsideTheColumnDeclaration`.
- `MySqlDDLParserListenerImplTest`: `testAlterDatabaseAddColumn`, `testDropColumn`, `testAlterAddColumnsParenthesisedList`, `testAlterAddDefinitionsSkipsEmbeddedIndex`, `testChangeColumn`, `testChangeColumnSameNameEmitsNoRename`, `testAlterAddColumnThenRenameToKeepsBothStatements`, `testAlterRenameToQualifiedTarget`, `testCreateTableLike`, `testCreateTableLikeQualifiedSource`, `dropTable`, `renameTable`, `testCreateTablePrimaryKeyWithSortOrder`, `testDecimalPrecisionWithoutScale`, `testAddColumnLiteralDefaultIsKept`, `testBitStringAndHexDefaultsAreTranslated`, `testAddColumnCurrentTimestampBackfillsLiteral`, `testAddEnumNotNullDefaultsToFirstMember`, `testAlterDatabaseAddColumnEnum` (flipped: `ENUM NOT NULL` now gets `DEFAULT '<first member>'`), `testAddColumnExpressionDefaultIsLoud`, `testModifyColumnDefaultCurrentTimestampIsDropped` (the former `testAddColumnDefaultCurrentTimestampIsDropped`, which pinned the dropped back-fill, is split into these), `testAddCheckConstraintIsSkipped` (`ADD CHECK`, `ADD CONSTRAINT ... CHECK ... NOT ENFORCED`, `DROP CONSTRAINT`, `DROP CHECK` emit nothing alone and never drop a neighbouring `ADD COLUMN`; pre-fix code echoed them), `testDropContraints`, `testAddConstraintsWithAnd`, `testAlterTableAddConstraint` (flipped from echo to skip).
- `DdlReplayIdempotencyTest`: `testChangeColumnModifyHalfIsGuarded`, `testCreateTableLikeIsGuarded`, `testRenameTableIsGuarded`, `testDropTableIsAlwaysGuarded`, `testModifyColumnIsDeliberatelyNotGuarded`.
- `DdlColumnFilterTest` — §3.6 rule 1-2 (the matcher): `noListKeepsEverything`, `includeListKeepsOnlyListedColumns`, `excludeListDropsOnlyListedColumns`, `includeWinsOverExclude`, `fullMatchCaseInsensitive`, `unknownIdentifiersAreKept`, `propertyNamesComeFromDebezium`, `blankListKeepsEverything`.
- `DdlColumnFilterEmissionTest` — §3.6 rule 3-6 (the translator, with the reported list `app.users.(id|name),app.orders.(id|user_id)`): `noListCreatesEveryColumn`, `includeListKeepsTheExcludedColumnOutOfTheCreate`, `includeListFiltersPerTable`, `keyColumnIsAlwaysCreated` (a column-level `PRIMARY KEY` the list excludes is still created and keeps `ORDER BY id`), `noMatchingColumnCreatesEveryColumn`, `excludeListKeepsTheNamedColumnOutOfTheCreate`, `destinationDatabaseDoesNotMatch` (the lists name the source database), `alterAddOfAnExcludedColumnEmitsNothing`, `alterAddOfAKeptColumnIsEmitted`, `alterAddListSkipsOnlyTheExcludedColumn`, `alterDropOfAnExcludedColumnEmitsNothing`, `alterDropOfAKeptColumnIsEmitted`, `alterRenameOfAnExcludedColumnEmitsNothing`, `alterRenameOfAKeptColumnIsUnaffected`, `alterModifyOfAnExcludedColumnEmitsNothing`, `alterModifyOfAKeptColumnIsUnaffected`, `skippedClauseLeavesItsNeighbourIntact`.
- `AlterTableDdlVariationMatrixTest`: `everyGrammarAlternativeHasAMatrixRow` (every `alterSpecification` alternative the Debezium grammar defines is classified; a grammar bump that adds one fails until it is classified), `unreachableAlternativeStaysUnreachable` (`AlterByDefaultCharset` is declared unreachable because `tableOption`, alternative 1, matches `DEFAULT? charSet '='? (charsetName | DEFAULT)` first and every charset form lands on `AlterByTableOption`; a reorder that makes it reachable fails here), `eachClauseEntersItsDeclaredAlternative` (the matrix is keyed on the alternative the parser actually enters, not on how the clause reads: `ALTER COLUMN ... SET VISIBLE` reaches `AlterByAlterColumnDefault`, and every `ALTER COLUMN ... SET DEFAULT` / `DROP DEFAULT` reaches `AlterByChangeDefault`), `noOtherClauseThrowsDuringTranslation` (every clause outside the refused class translates without throwing), `dataChangingClausesAreRefusedLoudly` (the six data-changing clauses each throw `DDLReplicationException`, and never emit or skip), `translatedClausesEmitAndSkippedClausesDoNot` (a column clause that silently joins the skip list fails here), `parenthesisedLiteralDefaultsSurvive` (literal forms across `INT`, `TINYINT(1)`, `BIGINT`, `DECIMAL`, `DOUBLE`, `VARCHAR`, `CHAR`, `DATE`, each with and without parentheses, plus the `NULL` default that must be dropped), `modifyDropsNonConstantDefaultButKeepsType`, `alterOptionsCombineWithRealClause`.
- End-to-end: `tests/alter_ddl_matrix.py` applies the same clause set to a live MySQL and requires a row written after each DDL to reach ClickHouse, so a clause that stalls the stream or emits a statement ClickHouse rejects forever fails the run. Registered in `regression_auto.py` and `regression_auto_replicated.py`.
- Formal: `no_bare_alter`, `add_columns_preserved` in `DdlTranslation.lean`.

---

## 6. Failure Modes & Recovery
The translation is designed so that the ONE DDL that can be in flight at a crash (the DDL thread applies statements one at a time and acknowledges each before reading the next) can be re-applied safely: every emitted statement whose replay could fail carries a guard. The failure modes are the statements for which a guard is not enough (renames that form a cycle, replicated targets), the statements the translator refuses, and schema drift that a guard hides.

- **FM-06.04-1 Translator refuses a statement it cannot represent loss-free**
  - **Trigger**: `ADD COLUMN ... DEFAULT (expr)` / `CAST(...)` / other non-literal default (§3.2.2 rule 3); `DEFAULT CURRENT_TIMESTAMP` with no event timestamp; key-column changes with `ddl.primary.key.rebuild=false` (spec 06.05 §3.4, 06.07 §3.1).
  - **Behaviour**: `MySqlDDLParserListenerImpl` raises `DDLReplicationException` during the walk; nothing is emitted, the offset is not acknowledged; the exception carries no ClickHouse FATAL code, so the engine is restarted `errors.max.retries` (10) times on the same statement before the terminal stop (exit code 3).
  - **Detection**: ERROR naming table, column, default and remedy (e.g. the §3.2.2 message), then `Restarting the engine - retry n of 10`, then FATAL `Replication is STOPPED...`. Immediate; terminal after about 10 x 15-20 s.
  - **Blast radius**: all replication stops at the statement; nothing lost.
  - **Recovery**: apply the column on ClickHouse by hand with the values the source holds (or re-synchronise the table with `ch-mysql-resync`, spec 11.04), add `ignore.ddl.regex` matching exactly that statement, restart, and remove the entry once `show_replica_status` shows the position past it.
  - **RTO**: operator time + one restart (+ the table re-synchronisation when chosen); unmeasured.
  - **Test**: `MySqlDDLParserListenerImplTest.testAddColumnExpressionDefaultIsLoud()`.

- **FM-06.04-2 Crash between execution and acknowledgement (replay of the in-flight DDL)**
  - **Trigger**: kill -9, OOM kill or host loss after `executeDDL` returned and before `DebeziumOffsetManagement.acknowledgeRecords()` (a window that includes the 500 ms schema-visibility sleep, spec 06.01 §6 FM-06.01-4).
  - **Behaviour**: the restart re-delivers the DDL. `ADD COLUMN IF NOT EXISTS`, `DROP COLUMN IF EXISTS`, the `MODIFY COLUMN IF EXISTS` half of `CHANGE`, `RENAME COLUMN IF EXISTS`, `RENAME TABLE IF EXISTS`, `DROP TABLE IF EXISTS` and `CREATE TABLE IF NOT EXISTS ... AS` are no-ops the second time; an unguarded `MODIFY` re-states the same type.
  - **Detection**: none needed; the replay logs the usual `ClickHouse DDL: <stmt>` INFO.
  - **Blast radius**: none.
  - **Recovery**: self-heals on restart.
  - **RTO**: process restart + re-delivery from the last committed offset; unmeasured.
  - **Test**: `DdlReplayIdempotencyTest.testChangeColumnModifyHalfIsGuarded()`, `DdlReplayIdempotencyTest.testRenameTableIsGuarded()`, `DdlReplayIdempotencyTest.testDropTableIsAlwaysGuarded()`, `DdlReplayIdempotencyTest.testCreateTableLikeIsGuarded()`, `DdlReplayIdempotencyTest.testModifyColumnIsDeliberatelyNotGuarded()`.

- **FM-06.04-3 Replay of a RENAME that swaps or cycles tables**
  - **Trigger**: FM-06.04-2's crash window on `RENAME TABLE a TO tmp, b TO a, tmp TO b` (a swap), or on a shadow-table cut-over `RENAME TABLE t TO _t_old, _t_new TO t` (gh-ost, pt-online-schema-change).
  - **Behaviour**: `enterRenameTable()` emits one `RENAME TABLE IF EXISTS ...` statement. `IF EXISTS` makes a replay safe only when the source names are gone; after a swap or cut-over they exist again under the new meaning: the swap is executed a second time (tables swapped back), and the cut-over replay renames the NEW `t` to `_t_old`, which already exists (ClickHouse `TABLE_ALREADY_EXISTS`, Code 57, non-retryable), so every re-delivery fails the same way (ClickHouse behaviour inferred, not measured).
  - **Detection**: swap replay: none (tables silently hold each other's rows). Cut-over replay: ERROR `DDL failed and ddl.retry is not enabled...` with the Code 57 cause, engine restarts, exit code 3.
  - **Blast radius**: swap: two tables' contents exchanged under their names, count-clean to a per-table count check that expects the swapped counts. Cut-over: all replication stops.
  - **Recovery**: compare `SHOW CREATE TABLE`/row counts of the involved tables with MySQL, rename them back by hand on ClickHouse to match the source, add `ignore.ddl.regex` for the statement, restart, remove the entry.
  - **RTO**: operator time + restart; unmeasured.
  - **Test**: GAP: an integration test that kills the connector between execution and acknowledgement of a swap RENAME and asserts the replica's table names match MySQL after restart.
  - **DEFECT**: a replayed swap RENAME is silently applied twice; replay safety of RENAME needs a target-state check (skip when the post-rename state already holds), not only `IF EXISTS`.

- **FM-06.04-4 Replicated target: table-level statements run on one replica only**
  - **Trigger**: `auto.create.tables.replicated=true` (tables created `ON CLUSTER` as `ReplicatedReplacingMergeTree`), and a source `RENAME TABLE`, `DROP TABLE`, `CREATE TABLE ... LIKE` or `DROP DATABASE`.
  - **Behaviour**: only `enterCreateDatabase()` and `enterColumnCreateTable()` append `ON CLUSTER {cluster}`; `enterRenameTable()`, `enterDropTable()`, `enterCopyCreateTable()`, `enterDropDatabase()` and `enterTruncateTable()` do not. On an Atomic database a RENAME/DROP without `ON CLUSTER` runs on the connector's server only (ClickHouse distributed-DDL semantics, not measured here); the other replicas keep the old name or the dropped table, and a later `CREATE TABLE IF NOT EXISTS ... ON CLUSTER` is a no-op on them. `CREATE TABLE ... AS` of a table whose engine path uses `{uuid}` is refused outside `ON CLUSTER` (the Code 36 measured in spec 06.09 §3.3.1, and measured for `AS` end to end on ClickHouse 23.8: `Macro 'uuid' and empty arguments of ReplicatedMergeTree are supported only for ON CLUSTER queries with Atomic database engine`), which is retried and then swallowed (spec 06.08 §6 FM-06.08-2). `ALTER TABLE` column changes need no `ON CLUSTER` on a `Replicated*` table (replicated through Keeper within the shard); `TRUNCATE` of a `Replicated*` table is replicated too.
  - **Detection**: none from the connector (it writes to one server only, which is consistent). Readers of the other replicas see stale or orphaned tables.
  - **Blast radius**: replicas of the cluster diverge in table names and contents; replication through the connector's server continues.
  - **Recovery**: on every other replica apply the same RENAME/DROP by hand (or re-issue it `ON CLUSTER`), compare `system.tables` across the cluster, restart nothing.
  - **RTO**: operator time; unmeasured.
  - **Test**: `DdlTranslationFailureModesTest.replicatedModeTableStatementsRunOnCluster()` (disabled; fails on 2.11.0), `DdlTranslationFailureModesTest.replicatedModeOnlyCreateCarriesOnClusterToday()`.
  - **DEFECT**: in replicated mode RENAME TABLE, DROP TABLE, CREATE TABLE ... LIKE and DROP DATABASE are not issued `ON CLUSTER`, so the cluster's replicas silently diverge.

- **FM-06.04-5 Out-of-band schema drift hidden by a guard**
  - **Trigger**: someone altered the ClickHouse table by hand (column added with another type, column dropped or renamed), then the source issues a DDL on the same column.
  - **Behaviour**: `ADD COLUMN IF NOT EXISTS c <type>` on an existing `c` of a different type is a silent no-op (the replica keeps the hand-made type); `RENAME COLUMN IF EXISTS` on a missing column is a no-op; an unguarded `MODIFY` of a missing column is refused with Code 10, which is retryable and therefore swallowed (spec 06.08 §6 FM-06.08-2), defeating the §3.1 rationale for leaving `MODIFY` unguarded.
  - **Detection**: none for the ADD/RENAME no-ops; a later row carrying a column absent from ClickHouse fails loudly at write time (`MissingTargetColumnException`, spec 08.04), a type mismatch may not.
  - **Blast radius**: that table's column diverges in type or value; others unaffected.
  - **Recovery**: make the ClickHouse column match `SHOW CREATE TABLE` on MySQL by hand, then re-synchronise the table if values were affected (`ch-mysql-resync`, spec 11.04).
  - **RTO**: operator time + table re-synchronisation; unmeasured.
  - **Test**: GAP: a unit test with a target-schema lookup reporting an existing column of another type, asserting that `ADD COLUMN` of it is refused loudly rather than emitted with `IF NOT EXISTS`.
  - **DEFECT**: `IF NOT EXISTS`/`IF EXISTS` turn a type or name conflict with the existing replica column into a silent no-op.

- **FM-06.04-6 A column the lists exclude is created anyway, empty**
  - **Trigger**: any `column.include.list` / `column.exclude.list` together with a translated `CREATE TABLE` or `ALTER TABLE` (before this revision the translator never consulted the lists).
  - **Behaviour**: the excluded column's value is never replicated (Debezium drops it from the record schema) but the ClickHouse table still carried it, because the DDL was translated from the raw source text. The column stayed empty for the life of the table, so a column that exists to be kept out of the analytics database was present in it, and a user could not tell an intentionally-excluded column from one that had simply lost its values. With `enable.snapshot.ddl=true` — the only path that creates a source table holding no rows — a filtered empty table arrived with every excluded column. After this revision that column is not created at all (§3.6); a key column the list names stays created, with a WARN.
  - **Detection**: none from the connector (the replica is internally consistent); `SHOW CREATE TABLE` on ClickHouse and on MySQL side by side, or `DESCRIBE TABLE` versus the configured list. The connector's INFO line `Not replicating column <db>.<t>.<c>: excluded by column.include.list` names each skipped column as its DDL is translated.
  - **Blast radius**: that table's column set, on ClickHouse only; the record path and other tables are unaffected. Values are never wrong, only absent.
  - **Recovery**: `ALTER TABLE <db>.<t> DROP COLUMN <c>` by hand on ClickHouse, or re-synchronise the table with `ch-mysql-resync` (spec 11.04) so the schema and the rows both come from the source; restart nothing.
  - **RTO**: operator time + one manual ALTER; unmeasured.
  - **Test**: `DdlColumnFilterEmissionTest.includeListKeepsTheExcludedColumnOutOfTheCreate()`, `DdlColumnFilterEmissionTest.keyColumnIsAlwaysCreated()`, `DdlColumnFilterTest.includeListKeepsOnlyListedColumns()`.
  - **FIXED** in this revision (§3.6).

Summary: 6 failure modes, 3 DEFECT, 2 GAP.

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
Clauses in the skip class of Spec 06.03 §3.2 (indexes, keys, foreign keys, `ADD`/`DROP PRIMARY KEY` when the target key is unknown or restated — loud otherwise, Spec 06.07 §3.1 —, `ALTER COLUMN SET/DROP DEFAULT`, CHECK constraints — `ADD [CONSTRAINT] CHECK` and `DROP CONSTRAINT|CHECK` —, charset/collation, table options, `ALGORITHM`/`LOCK`, partition operations) emit nothing and drop the separator that preceded them. A statement whose clauses are all skipped translates to `""`. Pinned by `testAlterAddIndexOnlyIsSkipped`, `testAlterDropPrimaryKeyOnlyIsSkipped`, `testAddConstraints`, `testAddCheckConstraintIsSkipped`, `testAlterDropPrimaryKeyModifyKeyAddColumnAddPrimaryKey`. Formal: `no_bare_alter`, `add_columns_preserved`.

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
  - **Behaviour**: only `enterCreateDatabase()` and `enterColumnCreateTable()` append `ON CLUSTER {cluster}`; `enterRenameTable()`, `enterDropTable()`, `enterCopyCreateTable()`, `enterDropDatabase()` and `enterTruncateTable()` do not. On an Atomic database a RENAME/DROP without `ON CLUSTER` runs on the connector's server only (ClickHouse distributed-DDL semantics, not measured here); the other replicas keep the old name or the dropped table, and a later `CREATE TABLE IF NOT EXISTS ... ON CLUSTER` is a no-op on them. `CREATE TABLE ... AS` of a table whose engine path uses `{uuid}` is refused outside `ON CLUSTER` (the Code 36 measured in spec 06.09 §3.3.1, inferred for `AS`), which is retried and then swallowed (spec 06.08 §6 FM-06.08-2). `ALTER TABLE` column changes need no `ON CLUSTER` on a `Replicated*` table (replicated through Keeper within the shard); `TRUNCATE` of a `Replicated*` table is replicated too.
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

Summary: 5 failure modes, 3 DEFECT, 2 GAP.

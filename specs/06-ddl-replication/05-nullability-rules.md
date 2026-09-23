# Spec 06.05: Nullability Translation & NOT NULL Modification Rules

## 1. Executive Summary & Purpose
Specifies how column nullability is translated on `CREATE TABLE` and `ALTER TABLE`, how `MODIFY COLUMN ... NOT NULL` avoids ClickHouse `Code: 36` (Cannot convert column from Nullable to non-Nullable), how a `MODIFY`/`CHANGE` of a **sorting-key** column is handled (ClickHouse `Code: 524`), and which source `DEFAULT` clauses are carried over.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java` (`parseColumnDefinitions`, `translateColumnClause`, `keyColumnChangeVerdict`)
- **Target schema**: `com.altinity.clickhouse.debezium.embedded.ddl.parser.TargetSchemaLookup` (nullability + sorting key of the existing ClickHouse table)
- **Related PRs**: #1455, #1457, #1459

---

## 3. Operational Specification

### 3.1 The ClickHouse Code 36 Conflict
Executing `ALTER TABLE db.table MODIFY COLUMN col Type` (non-Nullable) on an existing column created as `Nullable(Type)` fails with
`Code: 36. DB::Exception: Cannot convert column from Nullable to non-Nullable because it may contain NULL values.`
If retried indefinitely, this halts the entire replication pipeline.

### 3.2 Translation Policy for ALTER (PR #1455 & #1459)
1. **`ADD COLUMN ... NOT NULL`** (and the constraints that imply it: `AUTO_INCREMENT`, `SERIAL`): honoured; the column is created non-Nullable (no existing rows can violate it).
2. **`MODIFY COLUMN ... NOT NULL` / `CHANGE COLUMN ... NOT NULL` on existing columns**: the ClickHouse column **keeps its existing nullability**, read from the target table (`TargetSchemaLookup.columnNullability`, clean identifiers):
   - an existing `Nullable(T)` column stays `Nullable(T')`: converting it to non-Nullable requires a DEFAULT expression or fails with Code 36, and `Nullable(T')` holds every value of `T'`, so nothing is lost and the pipeline never stalls;
   - an existing non-Nullable column stays **non-Nullable** (`MODIFY COLUMN c Int64`, not `Nullable(Int64)`). The previous rule widened every `NOT NULL` MODIFY to `Nullable`, which is not what the source says, is a real type change ClickHouse has to apply, and — on a table created by this translator with a `NOT NULL` source column — turned a restatement into a rewrite;
   - unknown schema (no target lookup) → `Nullable`, which holds every value.
   Pinned by `testModifyNotNullKeepsNonNullableColumn` (non-Nullable stays non-Nullable, Nullable stays Nullable, CHANGE likewise) and, for the unknown-schema case, `testAlterModifyColumnNotNullStaysNullable`.
3. **No explicit `NULL`/`NOT NULL`**: the current nullability is read from the target table the same way; when unknown, `Nullable`. An explicit `NULL` always yields `Nullable` (a widening ClickHouse accepts without a DEFAULT).

### 3.3 CREATE TABLE: primary-key columns are NOT NULL
MySQL makes every `PRIMARY KEY` column implicitly `NOT NULL`, whether the key is declared at column level (`id INT PRIMARY KEY`) or at table level (`PRIMARY KEY (id, tenant)`), and whether or not the column itself is written `NOT NULL`. The translator pre-scans the `createDefinitions` for a table-level `PRIMARY KEY` and forces those columns non-Nullable, so the emitted `ORDER BY` never names a `Nullable` column (`Code: 44 ILLEGAL_COLUMN`). Column names are matched case-insensitively and with backticks stripped; `ASC`/`DESC` and prefix lengths in the key list are ignored. Pinned by `testCreateTableTableLevelPrimaryKeyForcesNotNull`.

### 3.4 MODIFY / CHANGE / RENAME of a sorting-key column (Code 524)
ClickHouse rejects **every** type change of a sorting-key column — widening, narrowing, adding `Nullable`, and renaming — with `Code: 524 ALTER_OF_COLUMN_IS_FORBIDDEN`; only re-stating the identical type succeeds (measured on 24.8.14). Because policy 3.2 maps `MODIFY ... NOT NULL` to `Nullable(T)`, even a no-op re-declaration of a key column would otherwise be emitted as a type change and fail, taking every other clause of the same statement down with it.

Rule, evaluated per clause once the target table's sorting key has been resolved (`TargetSchemaLookup.sortingKeyTypes`, clean identifiers):
1. **Not a sorting-key column, or key unknown** → translate as in §3.2.
2. **Sorting-key column, requested type same family and not wider than the existing ClickHouse type** → emit **nothing** for that clause and log at WARN (`key column type change not representable; keeping <existing type>`). This is loss-free: the existing column already holds every value of a same-or-narrower source type. `Nullable` wrapping is ignored in the comparison (key columns are `NOT NULL` on the source). **Exception — the clause carries a `FIRST` / `AFTER c` position**: the position is what the clause is for, so the column is restated with its EXISTING type and the position (`MODIFY COLUMN c <existing type> FIRST`); ClickHouse accepts a `MODIFY` that restates the identical type with a position on a sorting-key column as a metadata-only reorder (measured on 24.8.14), while any other type is `Code: 524`. The requested type is never emitted. Skipping the whole clause silently dropped the reorder (`MODIFY x INT FIRST` on a keyless table left `x` where it was). Pinned by `testModifyKeyColumnSameTypeWithPositionIsRestated`.
3. **Sorting-key column, requested type WIDER than the existing type, or not comparable, or the clause renames the column** → MySQL rebuilds its clustered index for such a change, and so does the replica: with `ddl.primary.key.rebuild=true` (default) the clause is **deferred to the rebuilt table** — the translator records a `PrimaryKeyRebuildPlan` whose identity is the current key (renamed where the clause renames a key column), emits the statement's other clauses as usual, and the rebuild of Spec 06.09 applies the deferred `MODIFY`/`CHANGE`/`RENAME` to the empty rebuilt table before copying the rows (a widening or rename is then an ordinary column change, and the copy converts the values). With `ddl.primary.key.rebuild=false` the former behaviour applies: `DDLReplicationException` naming the table, the column, both types and the required manual rebuild, nothing emitted for the statement (Invariant I9). Sending the clause to the existing table would fail with Code 524 after every retry, so it is never emitted against it. The deferred type keeps the nullability §3.2 assigns it: an explicit `NULL` on a keyless table's key column (Spec 06.05 §3.6 — a declared `PRIMARY KEY` column can never be made nullable on the source, error 1171) yields `Nullable(T')`, which the rebuilt table declares as such under `allow_nullable_key = 1` (Spec 06.09 §3.3.1 step 3) — the source column now holds NULL, so the replica must too. A `FIRST`/`AFTER` position on the deferred clause travels with it and is restated on the empty rebuilt table (Spec 06.09 §3.1.1).

Type-width order used for "wider":
- Signed integers: `Int8 < Int16 < Int32 < Int64 < Int128 < Int256`; unsigned likewise.
- Requested unsigned into an existing signed column: `UIntN` fits `IntM` iff `N < M` (`UInt16` fits `Int32`; `UInt32` does **not** fit `Int32` — same width counts as widening the maximum).
- Requested signed into an existing unsigned column: never fits (negative values).
- `Decimal(p, s)`: comparable only with equal scale; then by precision.
- Everything else: comparable only when the normalised type strings are identical.

Normalisation (`KeyColumnTypeChange.normalise`) strips whitespace, the
`Nullable`/`LowCardinality` wrappers, and the **timezone argument** of
`DateTime`/`DateTime64` together with the translator's `, 0` placeholder:
`DateTime64(6, 'UTC')` (how an auto-created or `clickhouse.datetime.timezone`
column renders in `system.columns`), `DateTime64(6, 0)` (what the translator
emits without a configured zone) and `DateTime64(6)` are one type. The zone
is display metadata — the stored instant, the width and the scale are the
same — so a `MODIFY` of a `DateTime64(6, 'UTC')` key column to `DATETIME(6)`
is a restatement and must be suppressed, not refused as "not comparable"
(which stalled every such no-op `MODIFY` of an auto-created table). A
different scale (`DateTime64(3)` vs `DateTime64(6)`) is still not comparable
and stays loud.

Formalised as `wider_key_change_rebuilds` in `DdlTranslation.lean` (a loud clause elsewhere in the statement still wins: `rebuild_only_when_not_loud`; the model's `modifyKeyColumnSameOrNarrower` covers the position-carrying restatement too — it changes no type and no value). Pinned by `testModifyKeyColumnSameOrNarrowerIsSuppressed`, `testModifyKeyColumnSameTypeWithPositionIsRestated`, `testModifyKeyColumnWiderPlansRebuild`, `testChangeKeyColumnRenamePlansRebuild`, `testDeferredKeyColumnClauseCarriesPosition`, `testKeyColumnChangeIsLoudWhenRebuildDisabled`, `testModifyDateTimeKeyWithTimezoneIsSuppressed`.

### 3.4.1 Parity with the record-schema path
The record-schema path (Kafka-mode auto-create and `schema.evolution`
`ADD COLUMN`, Spec 08.05 §3.1.1) declares the same ClickHouse type as this
translator for the same MySQL column type whenever Debezium's propagated
source metadata is present — including nullability: an optional unsigned
integer is `Nullable(UIntN)` there exactly as `ADD COLUMN ... UNSIGNED NULL`
is here. `RecordSchemaVsDdlTypeAgreementTest` pins the two paths against each
other; the residual differences it excludes (`BOOL` → `Bool` here vs `Int8`
there; the DateTime zone argument) are listed in Spec 08.05 §3.1.1.

### 3.5 DEFAULT clauses
Literal defaults are carried to ClickHouse in ClickHouse literal syntax; on `ADD COLUMN` a `CURRENT_TIMESTAMP` default becomes the DDL event's instant and an `ENUM ... NOT NULL` without a default gets its first member, while any other non-literal default is refused loudly; on `MODIFY`/`CHANGE` a non-literal default is dropped (Spec 06.04 §3.2). A `DEFAULT` never changes a replicated value: the row image carries the source value, and ClickHouse binds it explicitly (Spec 04.03); it only decides the back-fill of rows that pre-date an added column.

### 3.6 CREATE TABLE without a declared identity: the all-columns sorting key
A source table may declare neither a `PRIMARY KEY` nor a `UNIQUE` key whose
every column is `NOT NULL` (a nullable `UNIQUE` key is not a row identity: MySQL
does not treat NULLs as equal, so it admits any number of NULL-keyed rows).
The DDL translator used to create such a table with `ORDER BY tuple()`, under
which every row compares equal and `ReplacingMergeTree` keeps exactly ONE row
for the whole table — total, silent data loss, invisible while the table holds
one row (Spec 08.05 §3.2 measured it). The record-schema auto-create path
stopped doing that (`ClickHouseAutoCreateTable.keylessSortingKey`, Spec 08.05
§3.2 rule 3); the DDL path must produce the same table, so the two creation
paths cannot disagree about the identity of the same source table.

Rule (`enterColumnCreateTable`), evaluated after the `PRIMARY KEY` and the
non-null `UNIQUE` key have both been found absent, and never when the schema
override `primary_key` for the table is configured (that override wins on both
paths):
1. The sorting key is **every non-generated source column, in declaration
   order**, rendered as `ORDER BY (c1,...,cn)` with the names as written in the
   source DDL (`orderedColumnNames`). Generated columns are excluded: they are a
   pure function of the stored columns and add nothing to row identity. The
   connector's own columns (`_version`, `is_deleted`/`_is_deleted`, `_sign`,
   the history columns) are never part of it. With
   `replication.history.enable=true` the key is `(c1,...,cn,_valid_to)` like
   the declared-key form.
2. When any column of that key is nullable the statement ends with
   `SETTINGS allow_nullable_key=1` (appended to, never replacing, the user's
   `settings`, and not duplicated if already present) — ClickHouse otherwise
   rejects the CREATE with `Code: 44 ILLEGAL_COLUMN`, which is retried and
   stalls the stream. A declared key never gets the setting: a `PRIMARY KEY` is
   `NOT NULL` by MySQL's rule and a `UNIQUE` key is adopted only when fully
   `NOT NULL`.
3. `AUTO_INCREMENT` implies `NOT NULL` (MySQL forbids a nullable
   `AUTO_INCREMENT` column), on the CREATE path and on `ADD COLUMN`. So
   `id INT AUTO_INCREMENT UNIQUE` is a non-null `UNIQUE` key and is adopted as
   the identity (rule 3.2 of Spec 06.05 §3.3 already does this for
   `PRIMARY KEY`).
4. `KeylessTableWarning.banner()` is logged at ERROR, naming the table and the
   fix at the source (`ADD COLUMN my_row_id ... INVISIBLE PRIMARY KEY`).
5. A CREATE whose column list holds no non-generated column cannot be given a
   key and is refused with `DDLReplicationException` rather than created with
   `ORDER BY tuple()` (MySQL itself rejects such a table, so this is a guard,
   not a path).

Known limits of the value-derived key are those of Spec 08.05 §3.2.2 (two rows
identical in every column collapse; a column added later is not in the key;
ClickHouse forbids `MODIFY`/`RENAME`/`DROP` of a key column, which the
sorting-key policy of §3.4 turns into a suppressed clause or a loud rebuild).
They are all strictly better than losing every row but one, and the only real
fix is the one the banner names. Formalised as `sorting_key_nonempty`,
`primary_key_wins`, `fallback_key_is_every_stored_column` and
`declared_key_never_needs_nullable_setting` in
`formal_specs/lean/Replication/CreateTable.lean`.

---

## 4. Invariants Preserved
- **Continuous Replication (I5)**: no ALTER is emitted that ClickHouse is known to reject (Code 36, Code 44, Code 524).
- **Value-Level Type Equivalence (I7)**: a suppressed key-column MODIFY keeps a ClickHouse type that holds every source value; a change that could not be held is reported loudly, never narrowed silently.
- **Loud Failure (I9)**: widening or renaming a sorting-key column raises `DDLReplicationException`.

---

## 5. Verification Criteria
- `AlterTableModifyColumnIT.testAlterAddPrimaryKeyAndModifyNotNull()`
- `MySqlDDLParserListenerImplTest.testAlterModifyColumnNotNullStaysNullable()`, `testModifyNotNullKeepsNonNullableColumn()` (§3.2 rule 2; pre-fix code emits `Nullable(Int64)` for a non-Nullable column), `testCreateTableTableLevelPrimaryKeyForcesNotNull()`, `testModifyKeyColumnSameOrNarrowerIsSuppressed()`, `testModifyKeyColumnSameTypeWithPositionIsRestated()` (§3.4 rule 2 exception: `MODIFY x INT FIRST` on key column `x Int32` emits `MODIFY COLUMN x Int32 FIRST`; pre-fix code emitted nothing and lost the reorder), `testDeferredKeyColumnClauseCarriesPosition()` (§3.4 rule 3: the position of a deferred `CHANGE`/`MODIFY` lands in `PrimaryKeyRebuildPlan.positionedColumns()`, and `MODIFY x VARCHAR(100) NULL` defers `Nullable(String)`), `testModifyKeyColumnWiderPlansRebuild()`, `testChangeKeyColumnRenamePlansRebuild()`, `testKeyColumnChangeIsLoudWhenRebuildDisabled()`, `testModifyColumnNameIsCaseResolvedAgainstTarget()`, `testModifyDateTimeKeyWithTimezoneIsSuppressed()` (§3.4 normalisation: `DateTime64(6, 'UTC')` key vs requested `DATETIME(6)` with and without a configured zone → suppressed; a different scale is not comparable and is deferred to a same-identity rebuild per rule 3; pre-fix code raised "not comparable")
- §3.6: `MySqlDDLParserListenerImplTest.testCreateTableKeylessOrdersByAllColumns()` (no `PRIMARY KEY`, no `UNIQUE`: `ORDER BY (every column)` plus `SETTINGS allow_nullable_key=1`, never `ORDER BY tuple()`; pre-fix code emits `ORDER BY tuple()`), `MySqlDDLParserListenerImplTest.testAutoIncrementColumnIsNotNull()` (`id INT AUTO_INCREMENT UNIQUE` is `NOT NULL` and becomes the sorting key), `CreateTableNoKeySortKeyTest` (the keyless shapes: single-column, multi-column, GIPK table; the PK/UNIQUE cases untouched), `CreateTableUniqueKeySortKeyTest` (a nullable `UNIQUE` key falls through to the all-columns key with the setting; a `NOT NULL` one is adopted without it).
- Formal: `wider_key_change_rebuilds` in `formal_specs/lean/Replication/DdlTranslation.lean`; `Replication.CreateTable.sorting_key_nonempty`, `Replication.CreateTable.primary_key_wins`, `Replication.CreateTable.fallback_key_is_every_stored_column`, `Replication.CreateTable.declared_key_never_needs_nullable_setting` in `formal_specs/lean/Replication/CreateTable.lean`.
- `RecordSchemaVsDdlTypeAgreementTest.bothPathsDeclareTheSameType()` — §3.4.1.

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
1. **`ADD COLUMN ... NOT NULL`**: honoured; the column is created non-Nullable (no existing rows can violate it).
2. **`MODIFY COLUMN ... NOT NULL` / `CHANGE COLUMN ... NOT NULL` on existing columns**: the ClickHouse column stays `Nullable(Type)`. MySQL already enforces the constraint on the source; `Nullable(T)` holds every value of `T`, so nothing is lost and the pipeline never stalls on Code 36.
3. **No explicit `NULL`/`NOT NULL`**: the current nullability is read from the target table (`TargetSchemaLookup.columnNullability`) using clean identifiers (Spec 06.03 §3.3); when unknown, `Nullable`.

### 3.3 CREATE TABLE: primary-key columns are NOT NULL
MySQL makes every `PRIMARY KEY` column implicitly `NOT NULL`, whether the key is declared at column level (`id INT PRIMARY KEY`) or at table level (`PRIMARY KEY (id, tenant)`), and whether or not the column itself is written `NOT NULL`. The translator pre-scans the `createDefinitions` for a table-level `PRIMARY KEY` and forces those columns non-Nullable, so the emitted `ORDER BY` never names a `Nullable` column (`Code: 44 ILLEGAL_COLUMN`). Column names are matched case-insensitively and with backticks stripped; `ASC`/`DESC` and prefix lengths in the key list are ignored. Pinned by `testCreateTableTableLevelPrimaryKeyForcesNotNull`.

### 3.4 MODIFY / CHANGE / RENAME of a sorting-key column (Code 524)
ClickHouse rejects **every** type change of a sorting-key column — widening, narrowing, adding `Nullable`, and renaming — with `Code: 524 ALTER_OF_COLUMN_IS_FORBIDDEN`; only re-stating the identical type succeeds (measured on 24.8.14). Because policy 3.2 maps `MODIFY ... NOT NULL` to `Nullable(T)`, even a no-op re-declaration of a key column would otherwise be emitted as a type change and fail, taking every other clause of the same statement down with it.

Rule, evaluated per clause once the target table's sorting key has been resolved (`TargetSchemaLookup.sortingKeyTypes`, clean identifiers):
1. **Not a sorting-key column, or key unknown** → translate as in §3.2.
2. **Sorting-key column, requested type same family and not wider than the existing ClickHouse type** → emit **nothing** for that clause and log at WARN (`key column type change not representable; keeping <existing type>`). This is loss-free: the existing column already holds every value of a same-or-narrower source type. `Nullable` wrapping is ignored in the comparison (key columns are `NOT NULL` on the source).
3. **Sorting-key column, requested type WIDER than the existing type, or not comparable, or the clause renames the column** → throw `DDLReplicationException` naming the table, the column, both types and the required manual rebuild (re-create the table with the new key type and re-snapshot). Nothing is emitted for the statement. Sending the statement would fail with Code 524 after every retry; failing before emission is the loud, non-retried form of the same outcome (Invariant I9) and does not lose the neighbouring clauses to a retry loop.

Type-width order used for "wider":
- Signed integers: `Int8 < Int16 < Int32 < Int64 < Int128 < Int256`; unsigned likewise.
- Requested unsigned into an existing signed column: `UIntN` fits `IntM` iff `N < M` (`UInt16` fits `Int32`; `UInt32` does **not** fit `Int32` — same width counts as widening the maximum).
- Requested signed into an existing unsigned column: never fits (negative values).
- `Decimal(p, s)`: comparable only with equal scale; then by precision.
- Everything else: comparable only when the normalised type strings are identical.

Formalised as `wider_key_change_is_loud` in `DdlTranslation.lean`. Pinned by `testModifyKeyColumnSameOrNarrowerIsSuppressed`, `testModifyKeyColumnWiderIsLoud`, `testChangeKeyColumnRenameIsLoud`.

### 3.5 DEFAULT clauses
Only literal defaults are carried to ClickHouse; function, expression and `ON UPDATE` forms are dropped (Spec 06.04 §3.2). A dropped `DEFAULT` never changes a replicated value: the row image carries the source value, and ClickHouse binds it explicitly (Spec 04.03).

---

## 4. Invariants Preserved
- **Continuous Replication (I5)**: no ALTER is emitted that ClickHouse is known to reject (Code 36, Code 44, Code 524).
- **Value-Level Type Equivalence (I7)**: a suppressed key-column MODIFY keeps a ClickHouse type that holds every source value; a change that could not be held is reported loudly, never narrowed silently.
- **Loud Failure (I9)**: widening or renaming a sorting-key column raises `DDLReplicationException`.

---

## 5. Verification Criteria
- `AlterTableModifyColumnIT.testAlterAddPrimaryKeyAndModifyNotNull()`
- `MySqlDDLParserListenerImplTest.testAlterModifyColumnNotNullStaysNullable()`, `testCreateTableTableLevelPrimaryKeyForcesNotNull()`, `testModifyKeyColumnSameOrNarrowerIsSuppressed()`, `testModifyKeyColumnWiderIsLoud()`, `testChangeKeyColumnRenameIsLoud()`, `testModifyColumnNameIsCaseResolvedAgainstTarget()`
- Formal: `wider_key_change_is_loud` in `formal_specs/lean/Replication/DdlTranslation.lean`.

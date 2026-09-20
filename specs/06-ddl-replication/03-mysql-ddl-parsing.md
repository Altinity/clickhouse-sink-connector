# Spec 06.03: ANTLR4 MySQL DDL Parser Architecture

## 1. Executive Summary & Purpose
Specifies the lexical and syntactic analysis of raw MySQL DDL statements using ANTLR4 grammars, and the listener that walks the resulting parse tree to emit ClickHouse DDL. This document describes the callbacks that actually exist and, for `ALTER TABLE`, which grammar alternatives are translated, which are deliberately skipped because ClickHouse has no equivalent, and which stop the pipeline loudly.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserService.java`
- **Listener Implementation**: `com.altinity.clickhouse.debezium.embedded.ddl.parser.MySqlDDLParserListenerImpl`
- **Target-schema lookup (injectable)**: `com.altinity.clickhouse.debezium.embedded.ddl.parser.TargetSchemaLookup`
- **Grammars**: `MySqlLexer.g4`, `MySqlParser.g4` (`alterSpecification` alternatives, `defaultValue`, `indexColumnNames`)
- **Formal model**: `formal_specs/lean/Replication/DdlTranslation.lean`

---

## 3. Operational Specification

### 3.1 AST Parse Sequence
1. The raw DDL string from the binlog event is fed into `MySqlLexer` (case-insensitive char stream).
2. The token stream feeds `MySqlParser` to construct the parse tree.
3. `ParseTreeWalker.walk(listener, parser.root())` fires these listener callbacks (there are NO `enterAlterBy*` callbacks; every ALTER clause is dispatched inside `enterAlterTable`):
   - `enterCreateDatabase()`, `enterDropDatabase()`
   - `enterColumnCreateTable()` — `CREATE TABLE (...)`
   - `enterCopyCreateTable()` — `CREATE TABLE new LIKE old`
   - `enterAlterTable()` — iterates the `alterSpecification` children and dispatches on their concrete context class (see 3.2)
   - (`ADD CONSTRAINT ... CHECK (...)` is echoed by `enterAlterTable` in clause order; the former separate `enterAlterByAddCheckTableConstraint` callback appended it after the trailing-comma cleanup and lost its separator in multi-clause statements)
   - `enterDropTable()`, `enterRenameTable()`, `enterTruncateTable()`
4. The listener writes the ClickHouse statement(s) into a `StringBuffer`; multiple statements are separated by `"\n"` and executed one by one.

### 3.2 `ALTER TABLE` clause coverage (normative)
Each `alterSpecification` alternative of the grammar falls into exactly one class. The classification is total: a clause that is not translated and not in the skip list is a defect in this table.

| Class | Grammar alternatives | Behaviour |
|---|---|---|
| **Translated** | `alterByAddColumn`, `alterByAddColumns` (`ADD COLUMN (a INT, b INT)`), `alterByAddDefinitions` (column declarations only; embedded index/constraint declarations are skipped), `alterByModifyColumn`, `alterByChangeColumn`, `alterByRenameColumn`, `alterByDropColumn`, `alterByDropConstraintCheck`, `alterByAddCheckTableConstraint`, `alterByRename` | Emitted per Spec 06.04 |
| **Skipped (not representable, loss-free)** | `alterByAddPrimaryKey`, `alterByDropPrimaryKey`, `alterByAddIndex`, `alterByAddUniqueKey`, `alterByAddSpecialIndex`, `alterByAddForeignKey`, `alterByDropIndex`, `alterByDropForeignKey`, `alterByRenameIndex`, `alterByAlterIndexVisibility`, `alterByChangeDefault` (`ALTER COLUMN c SET/DROP DEFAULT`), `alterByConvertCharset`, `alterByDefaultCharset`, `alterByTableOption`, `alterBySetAlgorithm`, `alterByLock`, `alterByDisableKeys`, `alterByEnableKeys`, `alterByOrder`, `alterByForce`, `alterByValidate`, `alterByDiscardTablespace`, `alterByImportTablespace`, `alterByAlterColumnDefault`, `alterByAlterCheckTableConstraint`, and every partition operation (the `alterPartition` alternative wrapping `ADD/DROP/.../REORGANIZE PARTITION`, `REMOVE/UPGRADE PARTITIONING`) | Emits nothing for that clause, logged at INFO (`skipping clause … not representable in ClickHouse`). The clause separator that preceded it is dropped so no dangling comma remains. |
| **Loud** | `alterByModifyColumn` / `alterByChangeColumn` / `alterByRenameColumn` on a **sorting-key** column when the change is not loss-free (Spec 06.05 §3.4, Spec 06.07 §3.3) | `DDLReplicationException` naming the manual rebuild; nothing is emitted for the statement |

Rules that follow from the table:
- **No bare `ALTER TABLE`.** If every clause of a statement is in the skip class, the translated query is the empty string and `executeDDL` skips it. A bare `ALTER TABLE db.t` is rejected by ClickHouse with `Code: 62` and, being retried, stalls the stream. Formalised as `no_bare_alter` in `DdlTranslation.lean`.
- **Skipping a clause never drops its neighbours.** `ALTER TABLE t DROP PRIMARY KEY, MODIFY COLUMN id SMALLINT UNSIGNED NOT NULL, ADD COLUMN ref_id INT UNSIGNED NOT NULL AUTO_INCREMENT FIRST, ADD PRIMARY KEY (ref_id)` translates to `ALTER TABLE \`db\`.t ADD COLUMN IF NOT EXISTS ref_id UInt32 FIRST` (the MODIFY of the key column is suppressed per Spec 06.05 §3.4 because `UInt16` fits in the existing `Int32`). Formalised as `add_columns_preserved`.
- **Multi-clause statements are ONE ClickHouse `ALTER`** (plus separate `"\n"`-delimited statements for `RENAME TO` and for the rename half of `CHANGE COLUMN`). A failure of any emitted clause therefore fails the whole statement, which is why an unrepresentable clause must be suppressed or made loud *before* emission rather than sent and retried.

### 3.3 Identifier normalisation (target-schema lookups)
All lookups against ClickHouse `system.columns` (nullability fallback, sorting-key detection, column-name case resolution) use **clean identifiers**: backticks stripped, database prefix removed from the table name, and the connector's destination database name (after `clickhouse.database.override.map`). `cleanTableName` is recomputed in `enterAlterTable` from the table name found in the statement, never from the (empty) name passed by the caller. MySQL column names are case-insensitive while ClickHouse names are case-sensitive, so for `MODIFY`/`CHANGE`/`DROP`/`RENAME COLUMN` the source column name is resolved case-insensitively against the target table's actual column names and the ClickHouse spelling is emitted.

### 3.4 Injectable target schema
`TargetSchemaLookup` provides the target table's column nullability and its sorting-key columns with their ClickHouse types. The production implementation reads `system.columns` through `DBMetadata` on the writer's connection; unit tests inject a fixed answer so the sorting-key and case-resolution rules are testable without a server. When no lookup is available (no writer) the lookup answers "unknown" and the translator behaves as before: columns default to `Nullable`, no clause is treated as a key column.

---

## 4. Invariants Preserved
- **Invariant I9 (Loud Failure)**: an ALTER that cannot be represented in a loss-free way stops the pipeline with `DDLReplicationException` instead of emitting a statement that will be retried and fail.
- **Invariant I5 (DDL Barrier)**: every representable clause is emitted exactly once; skipping is per clause, never per statement.
- **Grammar conformity**: identifiers may be backtick-quoted, database-qualified, and any case; multi-clause statements are supported subject to the coverage table in §3.2. This spec no longer claims support for "all MySQL 8.0 DDL syntax variations" — the coverage table is the claim.

---

## 5. Verification Criteria
- `MySqlDDLParserListenerImplTest` (the full class; the tests named in Specs 06.04, 06.05, 06.07 are the regression pins for the rules above).
- `DdlReplayIdempotencyTest` (replay safety of every emitted statement shape).
- `formal_specs/lean/Replication/DdlTranslation.lean`: `no_bare_alter`, `wider_key_change_is_loud`, `add_columns_preserved` (zero `sorry`, standard axioms only).

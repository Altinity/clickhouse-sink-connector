# Spec 06.03: ANTLR4 MySQL DDL Parser Architecture

## 1. Executive Summary & Purpose
Specifies the lexical and syntactic analysis of raw MySQL DDL statements using ANTLR4 grammars, and the listener that walks the resulting parse tree to emit ClickHouse DDL. This document describes the callbacks that actually exist and, for `ALTER TABLE`, which grammar alternatives are translated, which are deliberately skipped because ClickHouse has no equivalent, and which stop the pipeline loudly.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySQLDDLParserService.java`
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
   - (`ADD CONSTRAINT ... CHECK (...)` and `DROP CONSTRAINT|CHECK k` are in the skip class since Spec 06.04 §3.3; the former separate `enterAlterByAddCheckTableConstraint` callback echoed the CHECK verbatim, which ClickHouse rejects for an unnamed constraint, a `NOT ENFORCED` clause or any MySQL function, and — when it did accept it — enforced on INSERT what MySQL had already validated)
   - `enterDropTable()`, `enterRenameTable()`, `enterTruncateTable()`
4. The listener writes the ClickHouse statement(s) into a `StringBuffer`; multiple statements are separated by `"\n"` and executed one by one.

### 3.2 `ALTER TABLE` clause coverage (normative)
Each `alterSpecification` alternative of the grammar falls into exactly one class. The classification is total: a clause that is not translated and not in the skip list is a defect in this table.

| Class | Grammar alternatives | Behaviour |
|---|---|---|
| **Translated** | `alterByAddColumn`, `alterByAddColumns` (`ADD COLUMN (a INT, b INT)`), `alterByAddDefinitions` (column declarations only; embedded index/constraint declarations are skipped), `alterByModifyColumn`, `alterByChangeColumn`, `alterByRenameColumn`, `alterByDropColumn`, `alterByRename` | Emitted per Spec 06.04 |
| **Skipped (not representable, loss-free)** | `alterByAddPrimaryKey`, `alterByDropPrimaryKey`, `alterByAddIndex`, `alterByAddUniqueKey`, `alterByAddSpecialIndex`, `alterByAddForeignKey`, `alterByDropIndex`, `alterByDropForeignKey`, `alterByRenameIndex`, `alterByAlterIndexVisibility`, `alterByChangeDefault` (`ALTER COLUMN c SET/DROP DEFAULT`), `alterByConvertCharset`, `alterByDefaultCharset`, `alterByTableOption`, `alterBySetAlgorithm`, `alterByLock`, `alterByDisableKeys`, `alterByEnableKeys`, `alterByOrder`, `alterByForce`, `alterByValidate`, `alterByDiscardTablespace`, `alterByImportTablespace`, `alterByAlterColumnDefault`, `alterByAddCheckTableConstraint` (`ADD [CONSTRAINT k] CHECK (...)`), `alterByDropConstraintCheck` (`DROP CONSTRAINT|CHECK k`), `alterByAlterCheckTableConstraint`, and every partition operation (the `alterPartition` alternative wrapping `ADD/DROP/.../REORGANIZE PARTITION`, `REMOVE/UPGRADE PARTITIONING`) | Emits nothing for that clause, logged at INFO (`skipping clause … not representable in ClickHouse`). The clause separator that preceded it is dropped so no dangling comma remains. |
| **Loud** | `alterByModifyColumn` / `alterByChangeColumn` / `alterByRenameColumn` on a **sorting-key** column when the change is not loss-free (Spec 06.05 §3.4, Spec 06.07 §3.3); `alterByAddPrimaryKey` / `alterByDropPrimaryKey` (and a column-level `PRIMARY KEY` in a column definition) when the target sorting key is known and the statement's net identity differs from it (Spec 06.07 §3.1 — a restatement or an unknown key stays in the skip class) | `DDLReplicationException` naming the manual rebuild; nothing is emitted for the statement |

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
- `formal_specs/lean/Replication/DdlTranslation.lean`: `no_bare_alter`, `wider_key_change_rebuilds`, `primary_key_change_rebuilds`, `add_columns_preserved` (zero `sorry`, standard axioms only).

---

## 6. Failure Modes & Recovery
The parser is pure and deterministic: the same statement always yields the same translation or the same exception, so a parser failure never heals by retry. What matters is that every statement ends in exactly one of three outcomes: a translation, a loud refusal that stops the pipeline, or an empty translation that is provably loss-free. FM-06.03-2 is a statement class that breaks that rule.

- **FM-06.03-1 Statement the grammar cannot parse**
  - **Trigger**: MySQL syntax newer than the bundled Debezium `MySqlParser.g4`, vendor extensions, a comment/charset form the lexer rejects.
  - **Behaviour**: Debezium parses the statement with the same generated grammar first; with `schema.history.internal.skip.unparseable.ddl=false` (the default in `deploy/ansible-systemd/defaults/main.yml`) a rejected statement stops Debezium itself before the sink sees it (Debezium behaviour, not verified in its source here). When it does reach the sink, `ErrorListenerImpl.syntaxError()` throws `RuntimeException("Error parsing DDL")` out of `MySQLDDLParserService.parseSql()`, before the retry loop of `performDDLOperation()`; the DDL branch wraps it in `DDLReplicationException`. Nothing is executed, the offset is not acknowledged. The exception carries no error code, so `handleEngineCompletion()` restarts the engine up to `errors.max.retries` (10) times, each re-delivering the same statement, then stops terminally.
  - **Detection**: ERROR `Error parsing` (no position, no statement), then `Engine stopped with an error: ...DDL replication failed for [<DDL>]; stopping the pipeline rather than advancing offsets past an unapplied schema change...`, `Restarting the engine - retry n of 10`, finally FATAL `Replication is STOPPED...` and exit code 3. First line within milliseconds; terminal after about 10 x 15-20 s.
  - **Blast radius**: all replication stops at the statement; nothing lost.
  - **Recovery**: take the statement from the `DDL replication failed for [...]` line. Apply the equivalent ClickHouse DDL by hand (the `sink-connector-client ddl_translate` command helps for the parts that do translate), so the replica matches MySQL. Add an `ignore.ddl.regex` entry matching exactly that statement, restart (`sink-connector-client restart` reloads the configuration), confirm in `show_replica_status` that the position moved past it, then remove the entry.
  - **RTO**: operator time + one restart; unmeasured. Detection to terminal stop takes about 3 min of futile engine restarts.
  - **Test**: `DdlTranslationFailureModesTest.unparseableStatementThrows()`, `DdlFailureModesTest.unparseableDdlHaltsWithoutAcknowledging()`.

- **FM-06.03-2 Data-changing partition or tablespace operation skipped as loss-free**
  - **Trigger**: `ALTER TABLE t DROP PARTITION p`, `TRUNCATE PARTITION p`, `EXCHANGE PARTITION p WITH TABLE t2`, `DISCARD/IMPORT [PARTITION] TABLESPACE`. MySQL removes or replaces rows; the binlog carries only the statement, never row events.
  - **Behaviour**: `MySqlDDLParserListenerImpl.isNoOpSpecification()` classifies every `AlterPartitionContext`, `AlterByDiscardTablespaceContext` and `AlterByImportTablespaceContext` as "not representable, loss-free" (§3.2); `enterAlterTable()` emits nothing, the empty translation is skipped by `executeDDL` and the offset is acknowledged.
  - **Detection**: INFO `ALTER TABLE clause [<clause>] is not representable in ClickHouse; skipping clause`. Nothing else; the divergence is found only by a value/count comparison (spec 11.02).
  - **Blast radius**: the replica keeps every row the source dropped or truncated (or lacks the rows swapped in), permanently; later events for other rows replicate normally.
  - **Recovery**: re-synchronise the table from MySQL with `ch-mysql-resync` (spec 11.04): it replaces the affected partitions atomically after count reconciliation and rewinds the connector to the captured binlog position.
  - **RTO**: table re-synchronisation, proportional to the table size; unmeasured.
  - **Test**: `DdlTranslationFailureModesTest.dataChangingPartitionOperationIsLoud()` (disabled; fails on 2.11.0: the statements translate to nothing). Control: `DdlTranslationFailureModesTest.dataPreservingPartitionOperationIsSkipped()`.
  - **DEFECT**: row-removing partition/tablespace operations are silently skipped; they must be refused with `DDLReplicationException` naming the table re-synchronisation (or translated to the equivalent ClickHouse row removal).

- **FM-06.03-3 Statement kind without a listener callback**
  - **Trigger**: `CREATE/DROP VIEW`, `CREATE/DROP INDEX`, routines and other statements the listener does not override.
  - **Behaviour**: the walk emits nothing; `performDDLOperation()` logs INFO `Executed Source DB DDL: <DDL>` (before, and regardless of, execution), `executeDDL` skips the empty string and the offset is acknowledged. These statements hold no rows, so nothing diverges.
  - **Detection**: the misleading INFO line only; acceptable because nothing is lost.
  - **Blast radius**: none on data.
  - **Recovery**: none.
  - **RTO**: 0.
  - **Test**: `DdlTranslationFailureModesTest.nonReplicatedStatementKindsTranslateToNothing()`.

- **FM-06.03-4 Target-schema lookup fails during translation**
  - **Trigger**: ClickHouse briefly unreachable, a `system.columns` query timeout, a missing privilege, while an ALTER is translated.
  - **Behaviour**: `MetadataTargetSchemaLookup` and `DBMetadata.getSortingKeyColumns()` log and answer "empty", which the translator cannot distinguish from "no lookup available" (§3.4): nullability falls back to `Nullable`, the sorting key to "unknown". Consequences are specified where they bite: spec 06.05 §6 FM-06.05-2 and spec 06.07 §6 FM-06.07-3.
  - **Detection**: ERROR `Error retrieving NULL column schema of <db>.<t> from ClickHouse` / `Error retrieving sorting key columns for <db>.<t>`; the statement then proceeds.
  - **Blast radius**: see the two referenced entries.
  - **Recovery**: see the two referenced entries.
  - **RTO**: see the two referenced entries.
  - **Test**: `DdlTranslationFailureModesTest.failedSortingKeyLookupSkipsPrimaryKeyChangeToday()`, `DdlTranslationFailureModesTest.failedNullabilityLookupYieldsNullableToday()`.

Summary: 4 failure modes, 1 DEFECT, 0 GAP.

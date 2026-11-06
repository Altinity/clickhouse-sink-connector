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
| **Skipped (not representable, loss-free)** | `alterByAddPrimaryKey`, `alterByDropPrimaryKey`, `alterByAddIndex`, `alterByAddUniqueKey`, `alterByAddSpecialIndex`, `alterByAddForeignKey`, `alterByDropIndex`, `alterByDropForeignKey`, `alterByRenameIndex`, `alterByAlterIndexVisibility`, `alterByChangeDefault` (`ALTER COLUMN c SET/DROP DEFAULT`), `alterByDefaultCharset`, `alterByTableOption`, `alterBySetAlgorithm`, `alterByLock`, `alterByDisableKeys`, `alterByEnableKeys`, `alterByOrder`, `alterByForce`, `alterByValidate`, `alterByAlterColumnDefault`, `alterByAddCheckTableConstraint` (`ADD [CONSTRAINT k] CHECK (...)`), `alterByDropConstraintCheck` (`DROP CONSTRAINT|CHECK k`), `alterByAlterCheckTableConstraint`, and every partition operation that moves no rows (the `alterPartition` alternative wrapping `ADD/DROP/TRUNCATE/COALESCE/REORGANIZE/ANALYZE/CHECK/OPTIMIZE/REBUILD/REPAIR PARTITION`, `REMOVE/UPGRADE PARTITIONING`; DROP and TRUNCATE PARTITION are ignored under `disable.drop.truncate`, Spec 06.08 §3.3) | Emits nothing for that clause, logged at INFO (`skipping clause … not representable in ClickHouse`). The clause separator that preceded it is dropped so no dangling comma remains. |
| **Loud** | `alterByModifyColumn` / `alterByChangeColumn` / `alterByRenameColumn` on a **sorting-key** column when the change is not loss-free (Spec 06.05 §3.4, Spec 06.07 §3.3); `alterByAddPrimaryKey` / `alterByDropPrimaryKey` (and a column-level `PRIMARY KEY` in a column definition) when the target sorting key is known and the statement's net identity differs from it (Spec 06.07 §3.1 — a restatement or an unknown key stays in the skip class); the clauses that change the stored values of existing rows: `alterByConvertCharset` (`CONVERT TO CHARACTER SET`, which re-encodes every string column in place), `alterByDiscardTablespace`, `alterByImportTablespace`, and the `alterPartition` forms `EXCHANGE PARTITION`, `DISCARD PARTITION`, `IMPORT PARTITION` | `DDLReplicationException` naming the manual rebuild or the clause (Spec 06.08 §3.1); nothing is emitted for the statement. The data-changing clauses are checked before the skip class, so they can never be skipped as a no-op. |

Rules that follow from the table:
- **No bare `ALTER TABLE`.** If every clause of a statement is in the skip class, the translated query is the empty string and `executeDDL` skips it. A bare `ALTER TABLE db.t` is rejected by ClickHouse with `Code: 62` and, being retried, stalls the stream. Formalised as `no_bare_alter` in `DdlTranslation.lean`.
- **Skipping a clause never drops its neighbours.** `ALTER TABLE t DROP PRIMARY KEY, MODIFY COLUMN id SMALLINT UNSIGNED NOT NULL, ADD COLUMN ref_id INT UNSIGNED NOT NULL AUTO_INCREMENT FIRST, ADD PRIMARY KEY (ref_id)` translates to `ALTER TABLE \`db\`.t ADD COLUMN IF NOT EXISTS ref_id UInt32 FIRST` (the MODIFY of the key column is suppressed per Spec 06.05 §3.4 because `UInt16` fits in the existing `Int32`). Formalised as `add_columns_preserved`.
- **Multi-clause statements are ONE ClickHouse `ALTER`** (plus separate `"\n"`-delimited statements for `RENAME TO` and for the rename half of `CHANGE COLUMN`). A failure of any emitted clause therefore fails the whole statement, which is why an unrepresentable clause must be suppressed or made loud *before* emission rather than sent and retried.

### 3.2a Grammar coverage and known parse gaps (normative)

The coverage table in §3.2 classifies the `alterSpecification` alternatives this
spec's listener dispatches on. It does not answer the question underneath it:
*is every statement shape in the shipped grammar exercised at all?* That is what
the coverage corpus and the sweep tests answer, and the answer is a claim this
spec makes.

**Which grammar is the subject.** The runtime parser is
`io.debezium.ddl.parser.mysql.generated.MySqlParser` from the
`debezium-ddl-parser` jar on the classpath (3.1.3.Final on 2.11.1): 362 rules,
44 labelled rules, 306 labelled alternatives. The repository's own
`src/main/antlr4/mysql/MySqlParser.g4` (358 rules) *is* compiled by
`antlr4-maven-plugin` into package `mysql`, and nothing imports it — no source
file references `mysql.MySqlParser`. It is not the grammar the connector runs,
so a change there fixes nothing at runtime, and this spec's claims are measured
against the jar. The two rule sets differ on 24 names — 10 rules exist only in
the repository grammar (`lockOption`, `waitNowaitClause`, `alterSequence`,
`createSequence`, `dropSequence`, `sequenceSpec`, `encryptedLiteral`, `https`,
`mathOperator`, `emptyStatement`) and 14 only in the jar (`addOperator`,
`multOperator`, `joinSpec`, `alterPartitionSpecification`, `tableStatement`,
`valuesStatement`, `withStatement`, `authOptionClause`, `engineNameBase`,
`hostName`, `simpleUserName`, `sequenceFunctionName`, `currentUserExpression`,
`emptyStatement_`) — so the two do not accept the same language: the jar has no
`lockOption`, and therefore rejects the `FOR UPDATE SKIP LOCKED` the repository
grammar defines. Any statement admitted as a corpus line must be proven against
the jar, never against the `.g4`.

**The corpus.** `src/test/resources/ddl-surface-corpus.txt` holds the statement
surface, one statement per line (245 statements when the sweep was pinned).
Beside it sit the three matrices already used by earlier specs —
`ddl-matrix-corpus.txt`, `ddl-grammar-corpus.txt`, `ddl-realworld-corpus.txt` —
which feed the clause-family, producer-family and expression-surface checks.
Every line must parse; a line that does not parse is a defect in the corpus,
not tolerance, because a failing line still walks the tree far enough to
register alternatives and would fake coverage.

**The two directions.** Coverage is only complete when both hold, and each has
its own test:

1. **Every alternative is reached, or measured unreachable.** The sweep
   enumerates every labelled alternative from the generated parser and requires
   each one to be entered by some corpus statement. The exceptions are the 13
   alternatives in the test's `UNREACHABLE` map, each recorded with the
   measurement that proves no real statement reaches it (an earlier alternative
   wins, or the rule is unreachable from `root`). Adding an entry requires that
   measurement; an entry is not a place to park an unmetered gap. The same test
   pins the enumeration itself (`EXPECTED_LABELLED_RULES = 44`,
   `EXPECTED_ALTERNATIVES = 306`), so a parser upgrade that renames, splits or
   collapses alternatives fails loudly instead of silently redefining what
   "everything is covered" means.
2. **What the parser rejects is recorded.** A statement that never parses never
   reaches an alternative, so it is invisible to (1) — the corpus simply has no
   line for it and nothing fails. `KnownParserGapsTest` is the complement: it
   lists real MySQL the shipped parser rejects today, and asserts per row that
   the statement is *outside* `MySqlDdlNormalizer.handlesStatementKind`, which
   is what makes the failure contained (warn and drop) instead of fatal (§6
   FM-06.03-1). The four recorded gaps are `EXPLAIN FOR CONNECTION <numeric
   connection id>`, `EXPLAIN ANALYZE`, and the `FOR UPDATE` modifiers
   `SKIP LOCKED` and `NOWAIT`. The test fails when a row starts parsing, so a
   parser upgrade or a normaliser rewrite cannot widen what the connector reads
   without a statement proving it: the row then moves to the corpus and is
   deleted from the map.

**The severity boundary the gaps sit on.** `MySqlDdlNormalizer.handlesStatementKind`
(§6 FM-06.03-1) decides whether a parse failure is a dropped statement or a
halted pipeline: inside the handled set the parser's `root()` call is unguarded,
so a syntax error throws and stops replication for every table on the host;
outside it the call is wrapped and costs one statement. Every recorded gap is
outside the set. The converse is asserted too: `ALTER TABLE t ADD COLUMN IF NOT
EXISTS x INT` is MariaDB-only (the MySQL 8.0 `ALTER TABLE` grammar has no
`IF NOT EXISTS`, and the repository grammar's own shorthand carries the comment
"ifNotExists is MariaDB-specific only"), it must stay unreadable on a MySQL
source, and because it *is* a handled kind it is pinned as the one row whose
rejection would be fatal.

**Harness trust.** A probe that installs a swallowing error listener and still
walks the tree registers alternatives from a line that did not parse, and a
normaliser that blanks a statement makes "fails to parse" meaningless. Both
tests therefore measure strictly: the sweep treats an unparseable corpus line
as a corpus defect, and the gap test returns "harness failure" — not "rejected"
— when the normaliser blanks a statement, so a blanked row can never pass as
evidence that the grammar rejects it.

### 3.3 Identifier normalisation (target-schema lookups)
All lookups against ClickHouse `system.columns` (nullability fallback, sorting-key detection, column-name case resolution) use **clean identifiers**: backticks stripped, database prefix removed from the table name, and the connector's destination database name (after `clickhouse.database.override.map`). `cleanTableName` is recomputed in `enterAlterTable` from the table name found in the statement, never from the (empty) name passed by the caller. MySQL column names are case-insensitive while ClickHouse names are case-sensitive, so for `MODIFY`/`CHANGE`/`DROP`/`RENAME COLUMN` the source column name is resolved case-insensitively against the target table's actual column names and the ClickHouse spelling is emitted.

### 3.4 Injectable target schema
`TargetSchemaLookup` provides the target table's column nullability and its sorting-key columns with their ClickHouse types. The production implementation reads `system.columns` through `DBMetadata` on the writer's connection; unit tests inject a fixed answer so the sorting-key and case-resolution rules are testable without a server. When no lookup is available (no writer) the lookup answers "unknown" and the translator behaves as before: columns default to `Nullable`, no clause is treated as a key column.

---

## 4. Invariants Preserved
- **Invariant I9 (Loud Failure)**: an ALTER that cannot be represented in a loss-free way stops the pipeline with `DDLReplicationException` instead of emitting a statement that will be retried and fail.
- **Invariant I5 (DDL Barrier)**: every representable clause is emitted exactly once; skipping is per clause, never per statement.
- **Grammar conformity**: identifiers may be backtick-quoted, database-qualified, and any case; multi-clause statements are supported subject to the coverage table in §3.2. This spec no longer claims support for "all MySQL 8.0 DDL syntax variations" — the coverage table is the claim, and §3.2a states what "measured against the shipped grammar" means: every labelled alternative reached or measured unreachable, and every rejected form recorded with its severity.

---

## 5. Verification Criteria
- `MySqlDDLParserListenerImplTest` (the full class; the tests named in Specs 06.04, 06.05, 06.07 are the regression pins for the rules above).
- `DdlReplayIdempotencyTest` (replay safety of every emitted statement shape).
- `GrammarAlternativeSweepTest.theCorpusReachesEveryReachableAlternative` (§3.2a direction 1: every labelled alternative of the shipped parser reached by a corpus statement or in the measured `UNREACHABLE` map).
- `GrammarAlternativeSweepTest.theEnumerationMatchesTheShippedParser` (§3.2a direction 1: the enumeration the sweep measures is pinned, so a parser upgrade cannot shrink it silently).
- `KnownParserGapsTest.everyKnownGapIsStillUnparseable` (§3.2a direction 2 and the severity boundary: each recorded gap is rejected *and* outside the handled set).
- `KnownParserGapsTest.mariaDbOnlySpellingsStayRejected` (§3.2a: `ADD COLUMN IF NOT EXISTS` stays unreadable and stays a handled kind).
- `StatementSurfaceCoverageTest.everyDdlAlternativeIsEitherHandledOrDocumented`, `StatementSurfaceCoverageTest.everyHandledStatementHasAHandler` (the DDL statement surface is classified with no silent third bucket).
- `DmlStatementSurfaceCoverageTest.everyDmlAlternativeIsProbed`, `DmlStatementSurfaceCoverageTest.everyDmlStatementParsesAndEmitsNothing` (the DML surface parses and emits nothing, so a DML statement never becomes DDL).
- `GrammarCorpusParseTest.everyGrammarStatementParsesOrIsADeclaredBlank`, `GrammarCorpusParseTest.everyRealWorldStatementParsesOrIsADeclaredBlank` (the clause and producer corpora are parseable; a corpus line is proven, never assumed).
- `E2eMatrixCorpusParseTest.everyMatrixStatementParsesOrIsADeclaredBlank` (the end-to-end matrix corpus, the same rule).
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

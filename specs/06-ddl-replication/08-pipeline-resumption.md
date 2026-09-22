# Spec 06.08: DDL Execution, Cache Invalidation & Pipeline Resumption

## 1. Executive Summary & Purpose
Specifies the final phase of DDL replication: executing the translated DDL on ClickHouse, invalidating metadata caches, committing the DDL offset, and unparking worker threads.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
- **Methods**: `performDDLOperation()`, `processEveryChangeRecord()` (DDL branch), `drainBeforeDDL()`, `checkIfDDLNeedsToBeIgnored()`, `sourceDatabaseName()`, `isDropOrTruncateDisabled()`
- **Capture filter**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DdlCaptureFilter.java` (`isCaptured(database, table, props)`)
- **Statement kind**: `MySqlDDLParserListenerImpl.isDropOrTruncateStatement()` (set by `enterDropTable`, `enterTruncateTable`, `enterDropDatabase`), `MySQLDDLParserService.isDropOrTruncateStatement(CommonTokenStream)`, `PostgreSQLDDLParserService.isDropOrTruncateStatement(CommonTokenStream)`
- **Failure type**: `DDLReplicationException` (same package)

---

## 3. Operational Specification

When DDL translation yields one or more ClickHouse SQL statements:
1. **Execution**:
   The statement is executed via `systemDbConnection.createStatement().execute(clickHouseQuery)`.
2. **Table Key Resolution**:
   Resolves affected table names from the AST or source event topic.
3. **Cache Invalidation**:
   - `CacheInvalidationManager.getInstance().invalidateTable(tableKey)` increments the table version counter.
   - Clears proven-absent column sets for the affected table.
   - If table resolution is ambiguous, calls `invalidateAll()` to bump `globalEpoch`.
4. **Offset Acknowledgment**:
   `committer.markProcessed(record)` acknowledges the DDL event offset.
5. **Worker Resumption**:
   Calls `executor.resume()`, setting `isPaused = false` and waking parked workers.
   The resume runs in a `finally` and is **guarded** by `executor != null`: in
   single-threaded mode there is no pool, so the guard prevents an NPE.
6. Post-DDL records now proceed against the newly altered table schema.

### 3.1 DDL Failure Is Terminal and Loud (not swallowed)

A DDL that cannot be applied must STOP the pipeline, never be skipped while the
row stream continues — continuing past an unapplied schema change writes every
later row against a ClickHouse schema that no longer matches MySQL, a silent,
count-clean divergence (violates the Prime Directive and Invariant I9).

1. **Wrapping**: The DDL branch of `processEveryChangeRecord()` wraps
   `drainBeforeDDL()` + `performDDLOperation()`. Any failure — a drain abort
   (`IllegalStateException`), or a failed DDL execution in
   `performDDLOperation()` (first attempt when `ddl.retry` is off, retry
   exhaustion when it is on; see §3.2) — is raised as `DDLReplicationException`.
2. **Non-swallowing**: `processEveryChangeRecord()` catches
   `DDLReplicationException` **ahead of** its generic `catch (Exception e)`
   catch-all (which exists only to keep one malformed DML record from killing
   the stream) and re-throws it. The exception therefore leaves
   `handleChangeEventBatch()` / the Debezium `handleBatch` consumer and halts
   the engine.
3. **Recoverable retry**: Because the engine halts before the DDL offset is
   committed, a restart re-delivers the DDL from the last committed position.
   This is the genuine retry the drain abort was always intended to enable; the
   prior behaviour logged the abort and let the row stream advance, which was
   neither a retry nor safe.
4. **Single-threaded mode**: `drainBeforeDDL()` returns immediately when
   `executor == null` (no worker pool; rows are persisted inline before the DDL
   record is handled), so the first DDL is applied rather than dropped by an NPE.

### 3.2 `ddl.retry` decides whether to RETRY, never whether a failure is loud

`performDDLOperation()` executes the translated statement(s) inside a bounded
retry loop (`MAX_RETRIES` attempts, 10 s apart). The `ddl.retry` property
(`SinkConnectorLightWeightConfig.DDL_RETRY`, default unset = `false`) controls
only whether further attempts are made after a failure. It never permits the
loop to be left normally after a failure.

1. **Error record first.** Every failed attempt is logged and written to the
   error table (`ErrorLogger.createErrorTable` + `ErrorLogger.logError`) BEFORE
   the retry decision, so the record exists whether or not the pipeline halts.
2. **`ddl.retry` unset / `false` (the default): terminal on the first attempt.**
   `DDLReplicationException` is thrown immediately, carrying the underlying
   exception as its cause. Leaving the retry loop normally is forbidden: it
   acknowledges the DDL offset and lets the row stream continue against a
   ClickHouse schema that no longer matches MySQL. This was observed in a
   production deployment — an `ALTER TABLE` rejected by ClickHouse was logged
   and skipped, the table stayed without the new column, and later rows were
   written count-clean against the stale schema.
3. **`ddl.retry=true`: bounded retries, then terminal.** After `MAX_RETRIES`
   failed attempts `DDLReplicationException` is thrown, carrying the LAST
   failure as its cause.
4. In both cases the exception leaves through §3.1 (re-thrown ahead of the
   catch-all, engine halts, offset not advanced, restart re-delivers the DDL).

---

### 3.3 Which DDL is ignored before execution (`checkIfDDLNeedsToBeIgnored`)
Before translation, `performDDLOperation` drops a DDL — logging it and setting
`lastIgnoredDDL`, executing nothing and committing nothing for it (the next
acknowledged record covers its offset) — when any of the following holds, in
this order:

1. `disable.ddl=true` — no DDL is replicated at all.
2. The statement matches an entry of `ignore.ddl.regex` (a `||`-separated list
   of regular expressions, `find` semantics) or one of the bundled patterns
   (`IgnoreDDLRegexLoader`).
3. **The DDL belongs to a table the connector does not capture.** The source
   database is read from the schema-change record (`sourceDatabaseName`: the
   key's `databaseName`/`db`, else the value's `source.db`; never the
   destination name, which may be prefixed/overridden) and the tables from
   `tableChanges[].id` / `source.table` plus both sides of a rename. Each
   `db.table` is judged by `DdlCaptureFilter.isCaptured` with Debezium's
   semantics: `database.include.list` / `database.exclude.list` and
   `table.include.list` / `table.exclude.list` are comma-separated regular
   expressions matched **in full and case-insensitively**; an include list,
   when set, is the whole rule and the exclude list is ignored. A multi-table
   DDL is kept when ANY of its tables is captured; a table-less DDL
   (`CREATE DATABASE`) is judged by the database lists only; an unknown
   database is never filtered; an uncompilable exclude pattern is ignored
   rather than treated as a match (conservative: never drop a DDL by guessing).
   **Why:** Debezium emits a schema-change event for every table of the
   captured databases unless
   `schema.history.internal.store.only.captured.tables.ddl=true`, and the sink
   applied every one of them — a `CREATE TABLE` outside `table.include.list`
   created a spurious ClickHouse table, and an `ALTER` on such a table failed
   on the missing target (`Code: 60`) and, being terminal (§3.1), halted the
   whole pipeline for a table nobody asked to replicate. Rows of such a table
   never reach the sink, so neither may its schema. Operators should still set
   `schema.history.internal.store.only.captured.tables.ddl=true` (and
   `...captured.databases.ddl=true`): it keeps Debezium's schema history and
   startup small; this filter is the sink-side guarantee for configurations
   that do not.
4. The DDL was emitted during the snapshot and `enable.snapshot.ddl` is not
   `true`.

Then, **after** `parseSql` (which is what computes the statement kind):

5. `disable.drop.truncate=true` and the statement is, at statement level, a
   `DROP TABLE`, `TRUNCATE [TABLE]` or `DROP DATABASE` (MySQL: decided by the
   parse tree via `MySqlDDLParserListenerImpl.enterDropTable` /
   `enterTruncateTable` / `enterDropDatabase`; the token helpers in both parser
   services test the first significant tokens). `ALTER TABLE ... DROP COLUMN`,
   `DROP INDEX` and `ALTER COLUMN ... DROP DEFAULT` are schema evolution and
   are never caught. The statement is logged at WARN and skipped.

   **Snapshot-phase DDL is exempt.** The suppression applies only to streaming
   DDL (`isSnapshotDDL(sr)` is false). With `enable.snapshot.ddl=true` Debezium
   bootstraps the target schema by emitting `DROP TABLE IF EXISTS` +
   `CREATE TABLE` for each captured table; that `DROP` is schema initialisation,
   not a source-initiated data drop during replication. Suppressing it would
   leave a stale target table (e.g. a pre-created one with a narrower column
   type) that the following `CREATE ... IF NOT EXISTS` cannot replace, so the
   replica would no longer match MySQL — the opposite of the option's purpose.
   `disable.drop.truncate` freezes drops seen WHILE STREAMING, never the
   snapshot schema.

   **Deliberate, operator-chosen divergence.** With `disable.drop.truncate=true`
   ClickHouse keeps rows and tables the source removed, so the replica is no
   longer equal to MySQL; the option exists for targets that must retain
   history and is off by default. Before this revision the option was dead: it
   was tested BEFORE `parseSql` set the flag it reads (always false), and the
   flag was raised by ANY `DROP` token, so had it worked it would also have
   swallowed column drops and index drops.

---

## 4. Invariants Preserved
- **Invariant I5 (DDL Barrier Quiescence)**: Cache invalidation takes effect before any post-DDL rows begin query formulation.
- **Capture parity (Prime Directive)**: the sink applies DDL for exactly the tables whose rows it replicates (§3.3 rule 3).
- **Invariant I9 (Loud Failure)**: A DDL that cannot be applied halts the pipeline instead of being swallowed while offsets advance past it.

---

## 5. Verification Criteria
- `DdlCaptureFilterTest` — §3.3 rule 3: include/exclude table lists, database lists, include-over-exclude, full case-insensitive match, unknown database kept, uncompilable patterns.
- `DdlIgnoreRulesTest.ddlOutsideIncludeListIsIgnored`, `DdlIgnoreRulesTest.excludeAndDatabaseListsApply`, `DdlIgnoreRulesTest.multiTableAndNoLists` — §3.3 rule 3 through `checkIfDDLNeedsToBeIgnored` with schema-change records carrying `databaseName` and `tableChanges`.
- `DdlIgnoreRulesTest.mysqlFlagIsStatementKind`, `DdlIgnoreRulesTest.postgresFlagIsStatementKind` — §3.3 rule 5: `DROP TABLE`/`TRUNCATE`/`DROP DATABASE|SCHEMA` set the flag; `DROP COLUMN`, `DROP INDEX`, `DROP DEFAULT` do not (pre-fix: any `DROP` token).
- `DdlIgnoreRulesTest.disableDropTruncateIsScopedAndLive` — §3.5 through `processEveryChangeRecord`: with `disable.drop.truncate=true` a STREAMING `DROP TABLE`/`TRUNCATE` returns without executing and is recorded as `lastIgnoredDDL`; a `DROP COLUMN` still reaches execution; without the property the `DROP TABLE` reaches execution (pre-fix: the property was dead).
- `DdlIgnoreRulesTest.disableDropTruncateExemptsSnapshotDdl` — §3.5 snapshot exemption: a snapshot `DROP TABLE` (source offset `snapshot=INITIAL`, `enable.snapshot.ddl=true`) reaches execution despite `disable.drop.truncate=true`, so the snapshot schema bootstrap is not frozen.
- `DdlFailureLoudTest.ddlFailurePropagatesInsteadOfBeingSwallowed()` — a DDL whose drain aborts throws `DDLReplicationException` out of `processEveryChangeRecord` rather than returning null.
- `DdlFailureLoudTest.drainIsNoOpWhenExecutorIsNull()` — single-threaded mode drains as a no-op instead of an NPE.
- `DdlFailureLoudTest.ddlExecutionFailureWithoutRetryIsLoud()` — `ddl.retry`
  unset, single-threaded (`executor == null`), a writer whose connection rejects
  the statement with a non-retryable ClickHouse error: `processEveryChangeRecord`
  throws `DDLReplicationException` whose cause is that `SQLException`. Fails on
  the pre-fix code, which left the retry loop normally and returned null.
- `DdlFailureLoudTest.ddlExecutionFailureAfterRetriesExhaustedIsLoud()` —
  `ddl.retry=true` with the retry budget exhausted: `DDLReplicationException`
  carrying the last failure as its cause.
- `DdlDrainDeadlockTest` — the drain still aborts a genuinely undrainable queue and stays quiescent+paused when it returns (spec 06.01).
- Mutation check: restoring `if (retryDDLProperty == false) { break; }` turns
  `ddlExecutionFailureWithoutRetryIsLoud` red.

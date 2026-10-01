# Spec 06.08: DDL Execution, Cache Invalidation & Pipeline Resumption

## 1. Executive Summary & Purpose
Specifies the final phase of DDL replication: executing the translated DDL on ClickHouse, invalidating metadata caches, committing the DDL offset, and unparking worker threads.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`
- **Methods**: `performDDLOperation()`, `processEveryChangeRecord()` (DDL branch), `drainBeforeDDL()`, `checkIfDDLNeedsToBeIgnored()`, `sourceDatabaseName()`, `isDropOrTruncateDisabled()`
- **Capture filter**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/DdlCaptureFilter.java` (`isCaptured(database, table, props)`; the list property names are its `DATABASE_INCLUDE_LIST` / `DATABASE_EXCLUDE_LIST` / `TABLE_INCLUDE_LIST` / `TABLE_EXCLUDE_LIST` constants, read from Debezium's `RelationalDatabaseConnectorConfig`)
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
Before the pre-DDL drain and before translation, the DDL branch of
`processEveryChangeRecord` drops a DDL — logging it once at INFO from the rule
that rejects it (the line names the rule; the branch itself adds only a DEBUG
line, so a multi-KB statement is never written twice) and setting
`lastIgnoredDDL`, draining nothing (spec 06.01 §3.5), executing nothing and
committing nothing for it (the next acknowledged record covers its offset) —
when any of the following holds, in this order:

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
   when set, is the whole rule and the exclude list is ignored. The four
   property names are never restated as literals in the sink: every reader
   (`DdlCaptureFilter`, `KeylessTablePreflight`, `VersionHighWaterMark`, the
   backfill resumption scan) takes them from Debezium's
   `RelationalDatabaseConnectorConfig` field definitions through the
   `DdlCaptureFilter` constants, so a rename on the Debezium side surfaces at
   the dependency bump instead of silently turning the filter into a no-op
   (`DdlCaptureFilterTest.propertyNamesComeFromDebezium`). A multi-table
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

---

## 6. Failure Modes & Recovery
The contract of this spec is binary: a DDL is either applied on ClickHouse and then acknowledged, or it halts the pipeline with its offset unacknowledged so a restart re-delivers it. Recovery from a halt is always the same shape: read the statement from the log, make ClickHouse match MySQL (fix the cause, or apply the DDL by hand and let the connector past it with `ignore.ddl.regex`), restart. The main defect below (FM-06.08-2) breaks the contract itself for every ClickHouse error outside a ten-code list.

- **FM-06.08-1 ClickHouse refuses the DDL with a non-retryable code**
  - **Trigger**: the translated statement fails with a code in `DBMetadata.NON_RETRYABLE_ERROR_CODES` (15, 44, 47, 57, 60, 62, 81, 82, 487, 497): syntax error, unknown identifier, table already exists, unknown table, access denied.
  - **Behaviour**: `DBMetadata.executeSystemQuery()` rethrows at once; `performDDLOperation()` records it in the error table (`ErrorLogger.logError`) and, with `ddl.retry` unset (default), throws `DDLReplicationException`; `processEveryChangeRecord()` rethrows it ahead of its catch-all; the engine stops with the offset unacknowledged. Codes 60, 81 and 497 are FATAL for `ClickHouseErrorClassifier`, so the stop is terminal at once; the others restart the engine `errors.max.retries` (10) times, 10 s apart, before the terminal stop.
  - **Detection**: ERROR `Non-retryable error executing query, giving up: <stmt>`, `Error executing DDL`, then `DDL failed and ddl.retry is not enabled, so it is not retried; halting the pipeline rather than skipping the schema change: [<DDL>]`; a row in the error table; FATAL `Replication is STOPPED...`; exit code 3 (immediately for FATAL codes, after about 10 x 15-20 s otherwise).
  - **Blast radius**: all replication stops at the DDL; nothing lost or duplicated.
  - **Recovery**: fix the cause if it is on ClickHouse (grant the privilege, create the missing database) and restart; otherwise apply the correct ClickHouse DDL by hand, add an `ignore.ddl.regex` entry matching exactly this statement, restart (`sink-connector-client restart` reloads the configuration), check `show_replica_status` moved past it, remove the entry.
  - **RTO**: operator time + one restart; unmeasured.
  - **Test**: `DdlFailureLoudTest.ddlExecutionFailureWithoutRetryIsLoud()`, `DdlFailureModesTest.ddlRejectedWithNonRetryableCodeIsNotAcknowledged()`, `DBMetadataExecuteSystemQueryExhaustionTest.nonRetryableRefusalIsRethrownAtOnce()`.

- **FM-06.08-2 ClickHouse refuses the DDL with any other code, or is unreachable: the DDL is acknowledged as applied**
  - **Trigger**: the statement fails with a code outside that list: `ALTER_OF_COLUMN_IS_FORBIDDEN` (524), `BAD_ARGUMENTS` (36, e.g. a `{uuid}` path outside `ON CLUSTER`), 10 (`MODIFY` of a missing column), `CANNOT_CONVERT_TYPE` (70), `UNKNOWN_FUNCTION` (46), `TIMEOUT_EXCEEDED` (159, `ON CLUSTER` queue), `TABLE_IS_READ_ONLY` (242, Keeper loss), a Keeper error; or ClickHouse is down or restarting when the DDL arrives (on a quiet stream the drain returns at once, so nothing proves ClickHouse is up).
  - **Behaviour**: `DBMetadata.executeSystemQuery()` treats every such error as retryable, retries `MAX_RETRIES` (10) times with a linear sleep (0+1+...+9 s = 45 s), then leaves its loop and RETURNS NORMALLY. `executeDDL()` therefore returns, `performDDLOperation()` never reaches its catch, invalidates the caches and calls `DebeziumOffsetManagement.acknowledgeRecords()`: the DDL offset is committed and the stream continues against the unchanged schema. The same happens when `writer.getConnection()` is null and no pool can supply one (the `break` in the same method). `ddl.retry` does not help: no exception reaches its loop.
  - **Detection**: ERROR `Error executing query: Retrying (n/10)` ten times, then INFO/WARN as if applied; for an `ADD COLUMN` the schema waiter adds WARN `Timeout (30000ms) waiting for columns to appear in <db>.<t>: [...]. Proceeding anyway -- data loss may occur for these columns...`. No error-table row, no stop. Consequences surface later or never: a missing ADD COLUMN or RENAME COLUMN fails the first row carrying the column (`MissingTargetColumnException`, spec 08.04); a missing CREATE fails with `UNKNOWN_TABLE`; a missing MODIFY (widening), TRUNCATE, DROP COLUMN or DROP TABLE is never detected.
  - **Blast radius**: silent schema divergence of the table; for TRUNCATE the replica keeps rows the source deleted; for a widening MODIFY later values may not fit the old type (spec 07); a PRIMARY KEY rebuild plan still runs its swap afterwards (spec 06.09), so for those the swap's own failure is loud.
  - **Recovery**: from the retry ERRORs, identify the statement; compare the table with `SHOW CREATE TABLE` on MySQL and apply the missing change by hand (ON CLUSTER in replicated mode); if rows were affected (TRUNCATE, widening, rename) re-synchronise the table with `ch-mysql-resync` (spec 11.04), which also rewinds the connector to the captured binlog position.
  - **RTO**: operator time + table re-synchronisation; detection itself is unbounded; unmeasured.
  - **Test**: `DdlFailureModesTest.ddlRejectedWithRetryableCodeIsLoudAndNotAcknowledged()` and `DBMetadataExecuteSystemQueryExhaustionTest.retryableRefusalIsRethrownAfterRetries()` (both disabled; both fail on 2.11.0), `DBMetadataExecuteSystemQueryExhaustionTest.retryableRefusalReturnsNormallyToday()`.
  - **DEFECT**: `executeSystemQuery` returns normally after exhausting its retries, so any ClickHouse refusal outside ten error codes, and any ClickHouse outage longer than about 45 s at DDL time, silently skips the DDL and commits its offset (violates §3.1, Invariant I9 and I15).

- **FM-06.08-3 `ddl.retry=true` holds the barrier for minutes**
  - **Trigger**: `ddl.retry=true` and a DDL that fails with an exception (non-retryable code, or a translator/rebuild exception thrown inside the loop).
  - **Behaviour**: the loop in `performDDLOperation()` retries `MAX_RETRIES` (`errors.max.retries`, 10) times, sleeping 10 s (`SLEEP_TIME`) between attempts, with the worker pool paused, then throws `DDLReplicationException("Max retries exceeded applying DDL to ClickHouse: [...]")`. An interrupt during the sleep is logged (`Error sleeping`) and swallowed, so a shutdown does not cut the loop short. `DDLReplicationException`s from the rebuild are not retried by this loop.
  - **Detection**: ERROR `Error executing DDL` per attempt, error-table rows, then the terminal message; about 100 s after the first failure.
  - **Blast radius**: every table stops for the whole retry sequence (about 100 s per DDL), then the pipeline halts; nothing lost.
  - **Recovery**: as FM-06.08-1. Prefer `ddl.retry` unset: a deterministic refusal gains nothing from ten attempts.
  - **RTO**: about 100 s of retries + FM-06.08-1 recovery; unmeasured.
  - **Test**: `DdlFailureLoudTest.ddlExecutionFailureAfterRetriesExhaustedIsLoud()`.

- **FM-06.08-4 Long-running DDL on ClickHouse**
  - **Trigger**: a DDL whose ClickHouse execution takes long: a type-changing `MODIFY COLUMN` on a large table, an `ON CLUSTER` statement waiting in the distributed DDL queue (`distributed_ddl_task_timeout`), a `Replicated*` ALTER waiting on Keeper or a lagging replica.
  - **Behaviour**: `executeDDL()` blocks the Debezium thread inside `executeSystemQuery()` with the pool paused (spec 06.01); no table replicates until the statement returns. If the JDBC socket timeout fires first, the resulting exception carries no non-retryable code: the statement is re-sent (possibly queuing a second identical change) and after the budget FM-06.08-2 applies. Whether ClickHouse returns before the data rewrite of a `MODIFY` completes depends on its `mutations_sync`/`alter_sync` settings (not verified here).
  - **Detection**: INFO `ClickHouse DDL: <stmt>` and then silence; replication lag grows; no WARN or metric while the statement runs.
  - **Blast radius**: all tables stall for the duration; possible silent skip via FM-06.08-2.
  - **Recovery**: watch `system.processes` / `system.mutations` / `system.distributed_ddl_queue` on ClickHouse; let it finish; when it was swallowed, verify the table against MySQL and apply by hand.
  - **RTO**: the ClickHouse execution time of the statement, unbounded by the connector; unmeasured.
  - **Test**: GAP: a unit test with a connection whose execute blocks, asserting the DDL path logs a periodic WARN/ERROR naming the statement and does not re-send it after a socket timeout.
  - **DEFECT**: a slow DDL stalls every table with no signal at all, and a timeout turns into a re-send and then the silent skip of FM-06.08-2.

- **FM-06.08-5 Unlogged source schema change (schema reload with `sql_log_bin=0`)**
  - **Trigger**: a DBA drops and recreates a schema or table and reloads it with `SET sql_log_bin=0`, restores a dump, or alters a table on a replica that becomes the source after a failover.
  - **Behaviour**: no DDL event exists, so nothing in this spec runs; the ClickHouse table keeps the old definition and the old rows. Row events that follow carry the new table shape; how Debezium reacts to a row image that disagrees with its schema history is Debezium behaviour (not verified here). New columns fail the first row that carries them (`MissingTargetColumnException`, spec 08.04); dropped or re-typed columns and the reloaded rows are not detected.
  - **Detection**: none from the DDL path; the value-level checksum (spec 11.02) is the detector.
  - **Blast radius**: the reloaded tables diverge in schema and rows, count-clean in the worst case.
  - **Recovery**: apply the new definition on ClickHouse by hand (`SHOW CREATE TABLE` on MySQL, translated with `sink-connector-client ddl_translate`), then `ch-mysql-resync` (spec 11.04) for every reloaded table: it captures the binlog position first, reloads with count reconciliation and rewinds the connector.
  - **RTO**: proportional to the size of the reloaded tables; unmeasured.
  - **Test**: GAP: an integration test that reloads a table with `sql_log_bin=0` (added column + changed rows) and asserts the connector stops or reports the drift within a stated time instead of continuing.
  - **DEFECT**: an unlogged schema reload is invisible to the connector; nothing detects it except an external checksum.

- **FM-06.08-6 A needed DDL is dropped by an ignore rule or the capture filter**
  - **Trigger**: an over-broad `ignore.ddl.regex` (or a bundled pattern), `disable.ddl=true`, `disable.drop.truncate=true`, or capture lists that exclude a table whose rows are nevertheless replicated.
  - **Behaviour**: `checkIfDDLNeedsToBeIgnored()` drops the statement before the drain (§3.3), records it in `lastIgnoredDDL`, commits nothing for it (the next acknowledged record covers its offset).
  - **Detection**: one INFO naming the rule (WARN for `disable.drop.truncate`: `Ignoring DROP/TRUNCATE statement because disable.drop.truncate=true...`). Later rows fail loudly only for a missing column (spec 08.04).
  - **Blast radius**: silent schema divergence of the affected tables (deliberate for `disable.drop.truncate`).
  - **Recovery**: narrow the rule, restart, apply the dropped DDL by hand on ClickHouse, re-synchronise the table if rows were affected (spec 11.04).
  - **RTO**: operator time; unmeasured.
  - **Test**: `DdlIgnoreRulesTest.ignoredDdlIsLoggedOnce()`, `DdlIgnoreRulesTest.disableDropTruncateIsScopedAndLive()`.

- **FM-06.08-7 Shadow-table cut-over with table-level capture lists (gh-ost, pt-online-schema-change)**
  - **Trigger**: `table.include.list` names `db.t` only; the tool creates `_t_gho`/`_t_new`, alters and fills it (none of it captured), then cuts over with `RENAME TABLE t TO _t_del, _t_gho TO t`.
  - **Behaviour**: rule 3 keeps a multi-table DDL when ANY of its tables is captured (`DdlCaptureFilter.isCaptured`), so the rename is applied: `RENAME TABLE IF EXISTS db.t to db._t_del, db._t_gho to db.t`; the shadow table never existed on ClickHouse, so (under `IF EXISTS`, ClickHouse behaviour not measured) `t` is moved to `_t_del` and no `t` remains. The migration's ALTER was applied to the uncaptured shadow table and is lost.
  - **Detection**: the next row of `t` fails with `UNKNOWN_TABLE` (Code 60, FATAL, exit code 3), unless the record-schema auto-create of spec 08.05 creates an empty `t`, in which case the pre-cut-over rows are silently orphaned in `_t_del`.
  - **Blast radius**: replication stops at the next row of `t` (or `t` silently loses its history).
  - **Recovery**: on ClickHouse `RENAME TABLE db._t_del TO db.t` (dropping an auto-created empty `t` first), apply the migration's ALTER by hand to match `SHOW CREATE TABLE` on MySQL, restart; verify with spec 11.02. Preventive: capture the shadow-table names too (`table.include.list` regex `db\.(_?t(_gho|_new|_del|_old)?)`), or use database-level lists.
  - **RTO**: operator time + restart; unmeasured.
  - **Test**: GAP: a DdlIgnoreRulesTest-style unit test for a rename whose target is captured and whose source is not, asserting the connector refuses it loudly with the remedy instead of applying it.
  - **DEFECT**: a rename that moves an uncaptured table onto a captured name is applied half-way and breaks the table's replication.

Summary: 7 failure modes, 4 DEFECT, 3 GAP.

# Spec 08.01: DbWriter Schema Cache & Metadata Resolution

## 1. Executive Summary & Purpose
Specifies the in-memory caching of ClickHouse table schemas inside `DbWriter`, including column data types, table engines, and sorting key lists.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Sources**:
  - `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DbWriter.java`
  - `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DBMetadata.java`
- **Fields** (`DbWriter`):
  - `private Map<String, String> columnNameToDataTypeMap` (a `LinkedHashMap`)
  - `private List<String> sortingKeyColumns`
  - `private DBMetadata.TABLE_ENGINE engine`
- **Metadata readers** (`DBMetadata`): `getColumnsDataTypesForTable(...)`, `getSortingKeyColumns(Connection conn, String database, String tableName)`, engine detection from `SHOW CREATE TABLE` / `system.tables`

---

## 3. Operational Specification

### 3.1 Metadata Querying
On initialization or cache eviction:
1. Queries ClickHouse `system.columns` for the writable column map (`name`, `type`; ClickHouse-owned `MATERIALIZED` / `ALIAS` columns are filtered out of the writable map by design — spec 08.03).
2. Reads the sorting key via `DBMetadata.getSortingKeyColumns`, which selects `name` from `system.columns` where `is_in_sorting_key = 1` ordered by `position` (it does not parse `system.tables.sorting_key`, whose rendered expression may contain functions).
3. Resolves the table engine (`REPLACING_MERGE_TREE`, `REPLICATED_REPLACING_MERGE_TREE`, `COLLAPSING_MERGE_TREE`, ...) and, from its engine clause, the engine columns: `versionColumn` and `replacingMergeTreeDeleteColumn` for a ReplacingMergeTree (`ReplacingMergeTree(ver, removed)`; for the old-style `ReplacingMergeTree(ver)` the delete column is the configured `replacingmergetree.delete.column`), `signColumn` for a CollapsingMergeTree. These resolved names are what query construction and binding use (spec 04.02 §3.1) — never the default constants alone.
4. The owning writer thread records the `CacheInvalidationManager.getVersion(tableKey)` it built against and rebuilds the `DbWriter` when that version changes (spec 08.02).

### 3.2 A ReplacingMergeTree the connector cannot version is refused; one it cannot delete from refuses only its delete markers
After step 3, `DbWriter.requireReplacingMergeTreeColumns` checks a ReplacingMergeTree target:
- it throws `IllegalStateException` — which the constructor rethrows, so the batch fails with the reason and is retried loudly rather than written — when the engine clause names no version column (a bare `ReplacingMergeTree()`) or names one the table does not have. Without it rows are ordered by insertion, and a redelivered or out-of-order row silently replaces the current one (Invariant I2). Every row written to such a table is wrong, so the table is refused;
- it logs a WARN, and does **not** refuse the table, when (with `ignore_delete=false`) the delete column (resolved, else configured) is not a column of the table. INSERTs and UPDATEs replicate correctly to such a table, and old-style `ReplacingMergeTree(ver)` tables without a delete column are common; only a row that needs the delete marker is wrong. That row is refused where it is bound: `PreparedStatementFieldMapper.requireDeleteColumn` throws `IllegalStateException` for a DELETE record (`requireDeleteColumnForDelete`) and for the sorting-key relocation tombstone (`insertTombstonePreparedStatement`, spec 05.02) of a ReplacingMergeTree / ReplicatedReplacingMergeTree target whose delete column is not in the column map. Written as is, either row would be a LIVE row with a higher version at its key and resurrect it (Invariant I3). Replication-history mode retires rows through its own SCD Type 2 statement and is exempt, exactly like the bind-time placeholder backstop (spec 04.02 §3.1).

Both used to be skipped silently at bind time (the column was bound "if present"). Names are matched case-insensitively in the writer check, like the rest of the column map. Each message names the table, the resolved column and the table's columns, and states the remediation (declare `ReplacingMergeTree(<version>, <delete>)` with both columns present, point `replacingmergetree.delete.column` at the existing column, or set `ignore_delete=true`).

---

## 4. Invariants Preserved
- **Schema Parity**: Ensures query generation uses current ClickHouse physical column types.

---

## 5. Verification Criteria
- `DbWriterTest.testGetColumnsDataTypesForTable()`, `DbWriterTest.testGetEngineTypeUsingSystemTables()` (`testGetEngineType` is `@Disabled`, spec 11.03 §6).
- `DBMetadataTest` — sorting-key extraction via `is_in_sorting_key`.
- `DbWriterEngineColumnsTest.rmtWithoutVersionColumnIsRefused()`, `DbWriterEngineColumnsTest.rmtWithMissingVersionColumnIsRefused()`, `DbWriterEngineColumnsTest.rmtTargetWithoutDeleteColumnIsAcceptedAtOpen()`, `DbWriterEngineColumnsTest.rmtWithoutDeleteColumnIsAcceptedWhenDeletesAreIgnored()`, `DbWriterEngineColumnsTest.resolvedNamesMatchCaseInsensitively()` — §3.2, the writer side.
- `PreparedStatementFieldMapperEngineColumnTest.testDeleteForTableWithoutDeleteColumnIsRefused()`, `PreparedStatementFieldMapperEngineColumnTest.testInsertForTableWithoutDeleteColumnIsAccepted()`, `PreparedStatementFieldMapperEngineColumnTest.testDeleteForTableWithoutDeleteColumnIsAcceptedWhenDeletesAreIgnored()`, `PreparedStatementFieldMapperEngineColumnTest.testRelocationTombstoneForTableWithoutDeleteColumnIsRefused()` — §3.2, the per-row refusal of the delete marker.
- `PostgresSchemaDriftIT` (`sink-connector-lightweight`) — a pre-existing `ReplacingMergeTree(_version)` target without a delete column keeps replicating INSERTs and schema changes.
- `StaleSchemaCacheIT`, `AlterTableDropColumnCacheIT` — the cache is rebuilt after a schema change.

---

## 6. Failure Modes & Recovery
The writer's schema cache is refreshed only by DDL the connector applies itself (spec 08.02) and by the record-witness probe (spec 08.03); a ClickHouse-side change made out of band is discovered only when an INSERT fails, and recovery then runs through a worker death, a terminal exit and a supervisor restart. A writer whose construction failed part-way is cached and never rebuilt, and the catalog DDL helper reports retry exhaustion as success: both are open defects.

- **FM-08.01-1 Out-of-band removal or rename of a cached column**
  - **Trigger**: a DBA drops or renames column `X` in ClickHouse (or recreates the table without it) while the source still has `X`.
  - **Behaviour**: nothing moves the table's cache version (`CacheInvalidationManager` is bumped only by connector DDL and by the probe), so the cached map still lists `X` and the INSERT names it. ClickHouse rejects it (code 16 NO_SUCH_COLUMN_IN_TABLE is the expected server code; not reproduced against a live server in this run). `ClickHouseErrorClassifier.classify` returns FATAL (16 is in `FATAL_ERROR_CODES`, also through wrappers), `ClickHouseBatchRunnable.run` rethrows with `currentBatch` kept, `DebeziumChangeEventCapture.failIfWorkerDied` stops the engine on its next batch, and `handleEngineCompletion` takes the terminal path at once (`isDeterministicFailure`). After the restart the fresh writer lacks `X` while the record carries it: with `schema.evolution=false` the batch fails with `MissingTargetColumnException` on every attempt (spec 08.04 FM-08.04-1); with `true` the connector re-adds `X`, and after a rename the renamed column stops receiving values.
  - **Detection**: ERROR `******* ERROR inserting Batch Database(%s), Table(%s) *****************`, ERROR `FATAL ClickHouse error (Code: 16) -- this batch will never succeed. ...`, ERROR `Sink worker %d of %d is dead: ...`, FATAL `Replication is STOPPED: ...`, exit code 3; within one Debezium batch (at most `heartbeat.interval.ms`, 5 s, on an idle source). Metric `clickhouse_sink_topics_error_records_total` increments.
  - **Blast radius**: the engine stops, so every table stops. Nothing is lost (the batch's unit stays outstanding, no offset passes it). After a rename with evolution on, the renamed column diverges silently.
  - **Recovery**: restore `X` in ClickHouse, or make the same rename at the source; or set `schema.evolution=true` if the column should simply come back. systemd restarts the service (`Restart=always`, `RestartSec=30`). Rows written while a renamed column diverged: `ch-mysql-resync dump`/`patch` for the table (spec 11.04).
  - **RTO**: detection <= 5 s, restart 30 s + engine start, then <= 30 s backoff once the column is back: about 2 minutes plus the operator's fix; unmeasured -- no harness alters a ClickHouse table under load.
  - **Test**: `ClickHouseErrorClassifierFailureModesTest.codeQuotedInsideAWrapperIsFound()` (code 16 is FATAL through wrappers), `DeadWorkerRetryIsTerminalTest.deadWorkerIsTerminalAtOnce()` (the terminal path); GAP: an integration test that removes a column in ClickHouse under load and asserts exit 3, then convergence after the column is restored.

- **FM-08.01-2 Out-of-band type change of a cached column**
  - **Trigger**: a DBA runs `MODIFY COLUMN` on a replicated column (narrowing precision, changing a type).
  - **Behaviour**: the cached type keeps driving value conversion and binding until the writer is rebuilt (a DDL on the table or a process restart). ClickHouse either rejects the bound value (53 TYPE_MISMATCH / 27 CANNOT_PARSE_TEXT: FATAL, as FM-08.01-1) or converts it under the new type; which pairs convert silently was not enumerated.
  - **Detection**: for rejected values as FM-08.01-1; for values ClickHouse accepts, none.
  - **Blast radius**: silent value change (rounding, truncation) on the column for every row written after the change; row counts unchanged.
  - **Recovery**: revert the type or make the matching change at the source; restart the service so writers re-read metadata; re-synchronise the table (spec 11.04) for the rows written in between.
  - **RTO**: restart 30 s + start; the silent case is unbounded until found by `db_compare` (spec 11.02); unmeasured.
  - **Test**: GAP: a unit test that rebuilds a writer against a changed column type and asserts the cached type is not used after the change.
  - **DEFECT**: the connector never compares its cached column types with ClickHouse's after startup, so an out-of-band narrowing is written through silently.

- **FM-08.01-3 Target table dropped or recreated out of band**
  - **Trigger**: a DBA drops the target table (or the database) while the connector runs.
  - **Behaviour**: the next INSERT fails with 60 UNKNOWN_TABLE / 81 UNKNOWN_DATABASE, FATAL, and the process exits 3 as in FM-08.01-1. On restart, `DbWriter.initializeTableEngine` finds no engine: with `auto.create.tables=true` the table is created empty from the first record (spec 08.05 FM-08.05-2); with `false` the batch is refused on every attempt. `ClickHouseBatchRunnable`'s static `ENSURED_DATABASES` set would skip re-creating a dropped database within one process, which the restart clears.
  - **Detection**: as FM-08.01-1, then either INFO `**** AUTO CREATE TABLE for database(%s), Query :%s)` (auto-create) or ERROR `********* AUTO CREATE DISABLED, Table does not exist, please enable it by setting auto.create.tables=true` and ERROR `*** TABLE METADATA not retrieved for Database(%s), table(%s), retrying on next attempt` on every retry.
  - **Blast radius**: with auto-create, every row that existed before the drop is missing from the new table while replication looks healthy; without it, the worker stalls every table hashed to it.
  - **Recovery**: stop the service; restore the table from a ClickHouse backup, or recreate it and run `ch-mysql-resync dump`, `patch` and `rewind-sql` for it (spec 11.04); start the service.
  - **RTO**: proportional to the table (dump and load), not bounded by the 5-minute RTO; unmeasured.
  - **Test**: `ClickHouseBatchWriterMissingTableTest.missingTableFailsTheBatchInsteadOfDroppingIt()` (a missing table is never acknowledged); GAP: an integration test that drops a populated target table out of band and asserts the connector refuses to recreate it silently.
  - **DEFECT**: after an out-of-band drop, auto-create silently rebuilds an empty table and replication continues on a partial replica.

- **FM-08.01-4 ClickHouse unreachable or slow while a writer is built**
  - **Trigger**: ClickHouse down, restarting, or refusing queries (202 TOO_MANY_SIMULTANEOUS_QUERIES) when a writer is first built or rebuilt after a DDL.
  - **Behaviour**: `DBMetadata.getColumnsDataTypesForTable` retries `errors.max.retries` (default 10) times with no pause and returns an empty map (its result map is not cleared between attempts, so an attempt that failed mid-read can leave a partial map). `BaseDbWriter.createDestinationDatabase` retries with a sleep of `attempt x 5 s` (5+10+...+50 = 275 s at the default budget), blocking the worker, and leaves its loop early only when it created the database: when the database exists by the time of a retry it sleeps out every remaining attempt and then throws `Error creating Database`, which leaves a half-built writer (FM-08.01-5). An empty map makes `processBatchRecords` return false and the batch is retried with backoff (spec 10.02); a partial map is caught by the record-witness probe of spec 08.03.
  - **Detection**: ERROR `Exception retrieving Column Metadata, retrying ({}/{}), use error.max.retries to configure`, ERROR `Error creating Database: <db>` / `Retry Number: n of N  Error creating Database: <db>`, ERROR `*** TABLE METADATA not retrieved for Database(%s), table(%s), retrying on next attempt` and WARN `Batch not written to ClickHouse; retrying the same batch in {} ms (consecutive failures: {})` on every attempt.
  - **Blast radius**: the worker's tables stall; other workers continue until the handoff cap (spec 01.05). No loss.
  - **Recovery**: self-heals when ClickHouse answers: the next attempt, at most `batch.retry.backoff.max.ms` (30 s) later, re-reads the metadata -- unless the build went through the database-creation path, which is FM-08.01-5.
  - **RTO**: <= 30 s after ClickHouse returns, plus up to 275 s asleep in `createDestinationDatabase` if the outage began inside it: up to ~5 minutes; unmeasured.
  - **Test**: `ClickHouseBatchWriterMissingTableTest.missingTableFailsTheBatchInsteadOfDroppingIt()` (an unreachable server acknowledges nothing); GAP: a unit test of `createDestinationDatabase` that fails once and then finds the database present, asserting it returns without sleeping out the budget.
  - **DEFECT**: `BaseDbWriter.createDestinationDatabase` throws after sleeping up to 275 s when the database exists on retry, because its loop only succeeds on a create.

- **FM-08.01-5 A writer whose construction failed part-way is cached for the life of the process**
  - **Trigger**: any exception in the `DbWriter` constructor other than `IllegalStateException` / `ColumnTypeOverrideMismatchException` (FM-08.01-4, a failing `SELECT VERSION()`, a failing `CREATE TABLE`), or a target table that is missing at the first batch and created later by an operator.
  - **Behaviour**: the constructor logs and returns; `ClickHouseBatchRunnable.getDbWriterForTable` caches the half-built writer under the current cache version and serves it until a DDL moves that version. The retry path re-reads only the column map, engine and sorting key (`DbWriter.updateColumnNameToDataTypeMap`); auto-create, `configureEngineSpecificColumns` and `requireReplacingMergeTreeColumns` never run again. Either the table is never auto-created (the batch is refused forever), or the table appears and rows are written with `versionColumn` / `replacingMergeTreeDeleteColumn` null: `PreparedStatementFieldMapper.handleVersionColumn` binds no version while the INSERT still lists `_version` and `is_deleted` by their default names (what the driver sends for the unbound placeholder was not verified), and every DELETE is refused by `requireDeleteColumn`.
  - **Detection**: one ERROR `***** DBWriter error initializing ****` at the first build; afterwards either `*** TABLE METADATA not retrieved ...` on every attempt, or nothing specific.
  - **Blast radius**: the table stalls (and its worker's other tables with it), or it is written without the engine-column resolution and guard of section 3.2.
  - **Recovery**: restart the service (`systemctl restart`), which empties the per-worker cache; any DDL on the table also forces a rebuild.
  - **RTO**: unbounded without an operator; restart 30 s + engine start once noticed; unmeasured.
  - **Test**: `DbWriterPartialInitCacheTest.partiallyInitialisedWriterIsRebuilt()` (`@Disabled`, confirmed red on 2.11.0).
  - **DEFECT**: a transient failure during the first build of a writer disables auto-create, engine-column resolution and the ReplacingMergeTree guard for that table until the process restarts.

- **FM-08.01-6 Catalog DDL retry exhaustion is reported as success**
  - **Trigger**: a statement run through `DBMetadata.executeSystemQuery` fails with a retryable error on every attempt (ClickHouse restarting, 159 TIMEOUT_EXCEEDED on an `ON CLUSTER` statement, a lost connection).
  - **Behaviour**: after `errors.max.retries` attempts, sleeping `1 s x attempt` between them (45 s at the default), the loop ends and the method returns null without throwing. Its callers -- auto-create `CREATE TABLE`, `CREATE DATABASE`, schema-evolution `ADD COLUMN`, the MATERIALIZED-to-DEFAULT conversion, the offset and status tables, and the replicated DDL itself (`DebeziumChangeEventCapture.executeDDL`, spec 06.01) -- proceed as if it had run. Non-retryable codes (15, 44, 47, 57, 60, 62, 81, 82, 487, 497) are thrown.
  - **Detection**: ERROR `Error executing query: Retrying ({}/{})` for each attempt, then nothing; for a replicated DDL, a WARN `Timeout ({}ms) waiting for columns to appear in {}.{}: ...` from `DDLSchemaChangeWaiter` after `ddl.schema.change.timeout.ms` (30 s).
  - **Blast radius**: a replicated DDL is acknowledged without having run: later rows of that table fail loudly (an added column, spec 08.04) or are written against the old type silently (a modified column).
  - **Recovery**: apply the missed DDL by hand in ClickHouse (translate it with `sink-connector-client ddl_translate`), then re-synchronise the table for the rows written in between (spec 11.04).
  - **RTO**: unbounded -- the failure is silent; unmeasured.
  - **Test**: `DBMetadataRetryExhaustionTest.retryExhaustionIsThrown()` (`@Disabled`, confirmed red on 2.11.0); `DBMetadataRetryExhaustionTest.nonRetryableErrorIsThrown()` pins the non-retryable half.
  - **DEFECT**: `executeSystemQuery` returns normally after exhausting its retries, so a CREATE, ALTER or replicated DDL that never ran is treated as applied.

Summary: 6 failure modes, 5 DEFECT, 4 GAP.

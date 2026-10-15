# Spec 12.01: Replication History Operating Modes — Flag Matrix & Write Routing

## 1. Executive Summary & Purpose
The connector has three operating modes, selected by two boolean keys:
`replication.history.enable` and `replication.history.replication_log_only`.
Until this domain existed, mode 2 was described in fragments across six specs
and mode 3 was not specified at all. This spec is the single normative
statement of **which mode a configuration selects, what each mode writes, and
where** (database routing). The two history modes are then specified in
12.02–12.05; this spec is deliberately about the matrix, not the row-level
protocol.

The modes are:

| Mode | `replication.history.enable` | `replication.history.replication_log_only` | Data tables | SCD2 columns on data tables | Audit table `<history db>.<history table>` | Data-table database |
|---|---|---|---|---|---|---|
| **1 Standard** | `false` (default) | `false` (default) | written, `ReplacingMergeTree(_version, is_deleted)` | no | not written, not created | source database (after prefix / suffix / override map) |
| **2 Per-table SCD2 history** | `true` | `false` | written, one row per version (spec 12.03) | yes (`_valid_from`, `_valid_to`, `_operation`, `is_deleted`, spec 12.02) | written: every DML record and every replayed DDL (spec 12.04) | **the history database** (`replication.history.database.name`), for every table |
| **3 Replication-log-only** | `true` | `true` | **not written** on either execution engine (spec 12.05) | n/a | written: every DML record and every DDL (spec 12.04) | n/a (nothing is routed) |
| *(degenerate)* | `false` | `true` | written (standard) | no | not written | source database, on both engines (§3.4) |

A fourth combination (`enable=false, log_only=true`) is not a mode: the code
gates every history behaviour on `enable`, so it behaves exactly as mode 1 on
both execution engines (§3.4).

---

## 2. Codebase Mapping on 2.11.0
- **Keys**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/ClickHouseSinkConnectorConfigVariables.java` — `REPLICATION_HISTORY_ENABLE` (`replication.history.enable`), `REPLICATION_HISTORY_TABLE_NAME` (`replication.history.table.name`), `REPLICATION_HISTORY_DATABASE_NAME` (`replication.history.database.name`), `REPLICATION_HISTORY_TTL` (`replication.history.ttl`), `REPLICATION_HISTORY_REPLICATION_LOG_ONLY` (`replication.history.replication_log_only`).
- **Defaults and doc strings**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/ClickHouseSinkConnectorConfig.java` (`.define(...)` blocks for the five keys). The lightweight module defines no history key of its own (`sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/config/SinkConnectorLightWeightConfig.java` has none); both paths read the definitions above.
- **Multi-threaded executor**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchRunnable.java` — `processBatch` (audit write, then the mode-3 skip), `addRecordsToHistoryTable`, `resolveDatabaseName` (routes on `enable`).
- **Single-threaded executor**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchWriter.java` — `persistRecords` (audit write, then the same mode-3 skip), `addRecordsToHistoryTable`, `resolveDatabaseName` (routes on `enable`).
- **Lightweight engine**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java` — history connection (`setReplicationHistoryDbConnection`), startup creation of the history database and audit table in `connectorStarted` (loud on failure), the mode banner, the DDL database override and the `replication_log_only` gate in `performDDLOperation`.
- **Database-level DDL**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java` — `enterCreateDatabase`, `enterDropDatabase` (ignored when `enable=true`).
- **Startup DDL**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/operations/ClickHouseAutoCreateTable.java` — `createHistoryDatabase`, `createHistoryTable`.
- **Formal model**: `formal_specs/lean/Replication/History.lean` — `ModeFlags`, `runnableDataWrites`, `writerDataWrites`, `historyWrites`, `runnableDatabase`, `writerDatabase`, `DdlKind`, `ddlApplied`; old behaviour as `oldWriterDataWrites`, `oldWriterDatabase`.

---

## 3. Operational Specification

### 3.1 Configuration keys

| Key | Type | Default | Read by |
|---|---|---|---|
| `replication.history.enable` | boolean | `false` | every history behaviour: audit write, SCD2 columns and statements, DDL database override, history connection, startup creation, PK-rebuild refusal |
| `replication.history.replication_log_only` | boolean | `false` | `ClickHouseBatchRunnable.processBatch` and `ClickHouseBatchWriter.persistRecords` (skip data tables, only together with `enable`), `DebeziumChangeEventCapture.performDDLOperation` (skip DDL execution), the startup banner (§3.5) |
| `replication.history.database.name` | string | `binlog_history` | database of the audit table AND of every data table in modes 2 and 3 (§3.3); the history JDBC connection's default database |
| `replication.history.table.name` | string | `history` | name of the audit table (spec 12.04) |
| `replication.history.ttl` | int (days) | `30` | TTL of the audit table; TTL of SCD2 data tables created by the **DDL translator** only (spec 12.02 §3.4) |

`connectorStarted` reads the database and table names through
`props.getProperty(key, literal)` with the literals `"binlog_history"` /
`"history"`; every other reader goes through the `ConfigDef` defaults, which
are the same values. The config doc strings state the modes as specified
here: `enable` — every table is written as an SCD2 history table in the
history database and every record is appended to the audit table;
`replication_log_only` — together with `enable`, only the audit table is
written, regular tables are not written and DDL is not executed, binlog
position tracking continues (resolved Gap G-12.05-2).

### 3.2 What each mode writes, per batch
Both executors process a batch in the same order:
1. **Audit write** — `addRecordsToHistoryTable(batch)`: when `enable=true`,
   every record of the batch is inserted into
   `<database.name>.<table.name>` with the raw source `DDL` argument `""`
   (spec 12.04 §3.3). Gated on `enable` alone: modes 2 and 3 both do it,
   mode 1 never does.
2. **Data-table write** — `processRecordsByTopic` for each topic: skipped
   on **both** executors when `replicationLogOnly && replicationHistoryEnabled`
   (mode 3), with the DEBUG log `"Replication log only mode is enabled,
   skipping the processing of records"`; otherwise executed (modes 1 and 2,
   and the degenerate combination). `ClickHouseBatchRunnable.processBatch`
   and `ClickHouseBatchWriter.persistRecords` carry the same branch
   (resolved Gap G-12.05-1; `Replication.History.both_engines_skip_data_in_log_only`).
3. **Acknowledgement** — unchanged by the mode: the batch is acknowledged
   through `DebeziumOffsetManagement` once step 2 (or, in mode 3, step 1
   alone) has completed (spec 09.01). In mode 3 the durable offset therefore
   certifies the **audit rows**, not data rows.

In mode 2 the data-table write is the SCD2 protocol of spec 12.03 (the
`PreparedStatementExecutor` constructs a `ReplicationHistoryHandler` when
`enable=true`); in mode 1 it is the standard protocol of specs 04.x / 05.x.

### 3.3 Database routing
`resolveDatabaseName(topic, firstRecord)` decides the target database of a
data table. With `enable=true` it returns `replication.history.database.name`
**before** applying `clickhouse.common.database.prefix`, the schema-suffix
template and `clickhouse.database.override.map` — all three are bypassed:

- `ClickHouseBatchRunnable.resolveDatabaseName`: `if (enable) return
  history database`.
- `ClickHouseBatchWriter.resolveDatabaseName`: the same predicate, `enable`
  alone (resolved Gap G-12.01-1; `Replication.History.both_engines_route_alike`).
- `DebeziumChangeEventCapture.performDDLOperation`: `if (enable) databaseName
  = history database` before constructing the DDL translator, so a replayed
  `CREATE TABLE` / `ALTER TABLE` is executed in the history database as well
  (mode 2). The override map is still applied by the translator to **that**
  name (`MySqlDDLParserListenerImpl.overrideDatabaseName`), so an override
  keyed by a source database has no effect in modes 2/3, while one keyed by
  the history database name would remap it. Cache invalidation after the DDL
  is computed from the source database plus prefix/override (not from the
  history override) — the code comments this explicitly.
- **Database-level DDL is ignored** (`CREATE DATABASE`, `DROP DATABASE`):
  with `enable=true` `MySqlDDLParserListenerImpl.enterCreateDatabase` /
  `enterDropDatabase` emit nothing (an INFO log names the skipped
  statement), `performDDLOperation` executes nothing for the empty
  translation, and the DDL row is still audited (12.04 §3.4). The history
  database is fixed by configuration, never derived from the source, so a
  source database statement names nothing of the connector's — and
  translated verbatim it was dangerous: Debezium's initial snapshot replays
  `DROP DATABASE IF EXISTS <src>` + `CREATE DATABASE <src>` for every
  captured database, and a mode-2 connector executing that `DROP` removed a
  database that a standard connector on the same ClickHouse was replicating
  into (found by the end-to-end suite). Table-level DDL is applied as
  before. Formal:
  `Replication.History.database_ddl_ignored_in_history_mode`.

Consequence (verified by `BinLogHistoryIT`): with
`clickhouse.database.override.map=employees:employees2` and `enable=true`,
the SCD2 copy of `employees.newtable` is `binlog_history.newtable`, not
`employees2.newtable`. **Every replicated table of a mode-2/3 connector
lives in the single history database, alongside the audit table.** A
connector whose data must land in a dedicated database therefore sets
`replication.history.database.name` to that database.

### 3.4 The degenerate combination (`enable=false, log_only=true`)
No history behaviour is enabled (§3.1 gates on `enable`), so **both**
execution engines behave as mode 1: data tables are written to the
source-derived database, no audit row is written, DDL is executed
(`Replication.History.log_only_flag_alone_is_standard_on_runnable`,
`Replication.History.both_engines_route_alike`). The previous revision's
writer routed this combination to the history database
(`Replication.History.old_routing_diverged_on_log_only_alone`, resolved Gap
G-12.01-1). The combination is still not a supported mode: it does nothing
the flags suggest, and the end-to-end suite runs it only to pin that it is
standard replication.

### 3.5 Startup and connections (lightweight engine)
When `enable=true`:
- a second JDBC connection is opened with the history database as its
  default database (`setReplicationHistoryDbConnection`); the DDL audit rows
  of 12.04 §3.3 are written through it;
- `connectorStarted` calls `createHistoryDatabase` (`CREATE DATABASE IF NOT
  EXISTS <history database>`) and `createHistoryTable` (spec 12.04 §3.1) on
  the system connection. A failure is logged (`"Error creating history
  database or audit table"`) and **rethrown** as a `RuntimeException` naming
  the database and table: thrown out of the callback it ends the engine run
  and reaches the completion handler as the engine's error, so the start
  fails loudly instead of running without the audit table and failing on
  the first audit insert (resolved Gap G-12.01-2, I9) — in mode 3 the audit
  table is the only output;
- the banner `"************** HISTORY MODE ENABLED **************"` is
  followed by a line naming the mode: `"replication-log-only: only the
  audit table will be written"` in mode 3, `"SCD2 history: data tables and
  the audit table will be written"` in mode 2 (resolved Gap G-12.01-3).

### 3.6 Behaviours gated on `enable` elsewhere (index)
- SCD2 columns on auto-created and DDL-translated tables — spec 12.02.
- SCD2 UPDATE / DELETE statements, tombstone suppression, engine-column
  literal exemptions — spec 12.03.
- Primary-key change rebuild refused (`PrimaryKeyRebuild.swap`:
  `"replication.history.enable=true: SCD2 history tables key on _deleted_time
  and are not rebuilt"`) — spec 06.09 §3.2 item 1; the DDL is loud, the
  pipeline halts.
- Keyless-table fallback sorting key excludes the history columns — specs
  06.05 §3.6, 08.05 §3.2.

---

## 4. Invariants Preserved
- **Invariant I8 (Durable Offset Quiescence)**: the acknowledgement path is
  mode-independent; what "written" certifies changes with the mode (data
  rows in 1/2, audit rows in 3) and is stated in 12.05 §3.2.
- **Invariant I9 (Loud Failure)**: the startup creation failure fails the
  engine start (§3.5); the DDL audit insert failure fails the DDL attempt
  (12.04 §3.4).
- **Invariant I10 (Structural Separation of Concerns)**: the history database
  is a routing decision, not a transformation; no row content changes with
  the mode except the SCD2 metadata columns of 12.02. Database-level source
  DDL never reaches ClickHouse in history mode (§3.3).
- **Spec 03.02 parity contract**: the two execution engines behave
  identically for every flag combination — the same mode-3 skip and the same
  routing predicate. Formal statement:
  `Replication.History.both_engines_skip_data_in_log_only`,
  `Replication.History.both_engines_route_alike`,
  `Replication.History.scd2_routes_every_table_to_history_database`,
  `Replication.History.standard_mode_writes_no_history`; the old divergence
  is kept as `Replication.History.old_writer_path_ignored_log_only` and
  `Replication.History.old_routing_diverged_on_log_only_alone`.

---

## 5. Verification Criteria
- `ClickHouseBatchWriterDatabaseResolutionTest.testResolveDatabaseNameReplicationHistoryProtected()` — §3.3: with `enable=true` the history database wins over `clickhouse.database.override.map`.
- `ClickHouseBatchWriterDatabaseResolutionTest.testResolveDatabaseNameLogOnlyAloneIsStandard()` — §3.4: `enable=false, log_only=true` resolves the source-derived database on the single-threaded engine, like the multi-threaded one.
- The end-to-end history suite (three connectors side by side, kill -9 restart, single-threaded variants, degenerate combination) — §3.2 step 2 on the single-threaded engine: in mode 3 the audit rows are written and no data table is created or processed.
- `MySqlDDLParserListenerImplTest.testReplicationHistorySkipsDatabaseLevelDdl()` — §3.3: with `enable=true` a source `CREATE DATABASE` / `DROP DATABASE` translates to nothing; table-level DDL still translates.
- `BinLogHistoryIT.testBinLogHistory()` — §3.3 end to end: the SCD2 data table is created inside the history database despite an override map for its source database; the audit table is created at startup with 19 columns.
- `ReplicationLogOnlyIT.testReplicationLogOnlyWritesToBinlogHistoryOnly()` — §3.2 mode 3: the source-database table holds zero rows, the audit table holds rows, and no data table was created in the history database.
- `GroupInsertQueryHistoryMultiRowTest.historyModeStillEmitsOneRowPerUpdate()` — grouping is mode-independent (one entry per UPDATE in both modes).
- The end-to-end history suite (three connectors side by side, kill -9 restart, single-threaded variants, degenerate combination) — a standard, a mode-2 and a mode-3 connector against one source and one ClickHouse: the standard connector's database survives the mode-2 connector's snapshot (§3.3), mode 3 writes only the audit table on both engines, the degenerate combination is standard replication, and every mode resumes correctly after a kill -9.
- Lean: `Replication.History.log_only_runnable_writes_no_data_rows`, `Replication.History.log_only_history_still_written`, `Replication.History.log_only_flag_alone_is_standard_on_runnable`, `Replication.History.both_engines_skip_data_in_log_only`, `Replication.History.both_engines_route_alike`, `Replication.History.scd2_routes_every_table_to_history_database`, `Replication.History.standard_mode_writes_no_history`, `Replication.History.database_ddl_ignored_in_history_mode` — the matrix of §3.2–§3.4, machine-checked; `Replication.History.old_writer_path_ignored_log_only`, `Replication.History.old_routing_diverged_on_log_only_alone` — the resolved divergences.
- **Coverage gap**: no unit test provokes the startup creation failure of §3.5 (the loud path is covered by inspection of the rethrow only).

---

## 6. Gaps resolved in this change
| Id | Where | Old behaviour | New behaviour | New-property theorem | Old witness |
|---|---|---|---|---|---|
| G-12.01-1 | `ClickHouseBatchWriter.resolveDatabaseName` vs `ClickHouseBatchRunnable.resolveDatabaseName` | routing predicate `enable \|\| log_only` vs `enable`: different databases for `enable=false, log_only=true` | both route on `enable` alone (§3.3, §3.4) | `Replication.History.both_engines_route_alike` | `Replication.History.old_routing_diverged_on_log_only_alone` |
| G-12.01-2 | `DebeziumChangeEventCapture.connectorStarted` | creation failure caught and logged; engine started without the audit table | failure rethrown; the engine start fails (§3.5) | — | — |
| G-12.01-3 | `DebeziumChangeEventCapture` startup banner | "only history will be tracked" printed in mode 2 | the banner names the mode (§3.5) | — | — |
| S8 (new) | `MySqlDDLParserListenerImpl.enterCreateDatabase` / `enterDropDatabase` | database-level DDL translated verbatim; a mode-2 snapshot dropped another connector's database | ignored in history mode (§3.3) | `Replication.History.database_ddl_ignored_in_history_mode` | — (found by the end-to-end suite) |

---

## 7. Failure Modes & Recovery
The mode matrix is decided from configuration at every batch and every DDL, with no persisted notion of "the mode these offsets were written under". The startup failure is loud and self-limiting. The dangerous failures are configuration mistakes the connector accepts silently: a mode change on a live deployment, or two source tables mapped onto one history table. None of them is recoverable from the offset store alone, because the target no longer matches what the offsets certify. Row-level failures of each mode are in 12.03 §7 (SCD2 writes), 12.04 §7 (audit table) and 12.05 §7 (log-only).

- **FM-12.01-1 The history database or the audit table cannot be created at start**
  - **Trigger**: the ClickHouse account lacks `CREATE DATABASE`/`CREATE TABLE`, the server is read-only, or Keeper is down for a replicated audit table.
  - **Behaviour**: `connectorStarted` logs `Error creating history database or audit table` and throws `RuntimeException("replication.history.enable=true: could not create the history database ... the engine must not start without the audit table (Invariant I9)")`. The engine run ends. `handleEngineCompletion` recreates the engine up to `errors.max.retries` times (default `MAX_RETRIES = 10`), `SLEEP_TIME` 10 s apart. The failure has no ClickHouse FATAL code, so it draws on that budget. When the budget is spent, it logs FATAL `Replication is STOPPED: the engine failed ...` and exits with code 3 (`TERMINAL_FAILURE_EXIT_CODE`) unless `exit.on.terminal.failure=false`.
  - **Detection**: the ERROR line at the first start. Process exit code 3 after about 10 × (10 s + engine start).
  - **Blast radius**: nothing is replicated and no offset moves, so no data is lost. In mode 3 there is no output at all.
  - **Recovery**: grant the privilege or restore Keeper, then restart the service. It resumes from the last committed offset. A cause cleared within the retry budget recovers on its own at the next retry.
  - **RTO**: time to fix the cause plus one start (about 20 s). Self-healing is bounded by the budget of about 100 s plus the start times.
  - **Test**: GAP: a unit test driving `connectorStarted` with a failing `ClickHouseAutoCreateTable.createHistoryTable` and asserting the rethrow (§5 coverage gap).

- **FM-12.01-2 The mode is changed on a deployment that already has offsets**
  - **Trigger**: the operator flips `replication.history.enable` (false→true or true→false), or `replication.history.replication_log_only` (true→false), and restarts. The stored offset is kept.
  - **Behaviour**: routing (`resolveDatabaseName`) and the data-table skip (`processBatch` / `persistRecords`) follow the new flags from the next batch. Nothing compares them with the mode that wrote the existing target.
    - Switching to mode 2 creates SCD2 tables in the history database on first touch. They hold only keys changed after the flip. An UPDATE of an older key writes an after row with no close row, and a DELETE of an older key writes nothing (12.03 §7 FM-12.03-4). The previous standard tables in the source databases stop receiving changes.
    - Leaving mode 2 or 3 writes into source-database tables that missed every change made while the connector was in history mode.
  - **Detection**: none. The startup banner states the new mode (§3.5) but not that it changed.
  - **Blast radius**: silent divergence of every replicated table. There is no data loss on the source.
  - **Recovery**: treat it as a new target.
    - For the new mode's tables: stop the connector, run `sink-connector-client delete_offsets` and `delete_schema_history`, set `snapshot.mode=initial` and start. This is a full snapshot into the new target.
    - For standard tables left stale by an excursion into history mode: rewind to the position of the flip and repair each table with `ch-mysql-resync` (spec 11.04).
  - **RTO**: a full snapshot or resync. Hours on large sources, which exceeds the Invariant I15 target.
  - **Test**: GAP: a startup check that compares the configured mode with a mode marker stored next to the offset, and refuses a change without an explicit override.
  - **DEFECT**: a mode change is accepted silently although it invalidates the target.

- **FM-12.01-3 Two source databases with a same-named table write into one SCD2 table**
  - **Trigger**: a mode-2 connector captures several databases that share table names (sharded schemas `shop1.orders`, `shop2.orders`).
  - **Behaviour**: `resolveDatabaseName` returns `replication.history.database.name` for every table, and the table name is the bare source name (`getTableFromTopic`). Both source tables therefore resolve to `binlog_history.orders`. Rows of both land in one table, and an UPDATE or DELETE of key `k` in one shard closes the open row of `k` from the other shard.
  - **Detection**: none when the column sets match. When they differ, the bind-time schema check fails loudly (`StaleSchemaCacheException`, spec 08.03) and the batch retries.
  - **Blast radius**: the current state and the history of both tables are corrupted silently. The audit table still records the source database (12.04 §3.3).
  - **Recovery**: run one connector per source database, each with its own `replication.history.database.name`. Rebuild the mixed tables: their current state by a snapshot, their history only from the audit table (12.03 §7 FM-12.03-8).
  - **RTO**: a snapshot of the affected tables plus the history rebuild (unmeasured).
  - **Test**: `HistoryModeTableCollisionTest.sameNamedTablesOfTwoSourceDatabasesGetDistinctTargets()` (disabled, DEFECT). Today's routing is pinned by `HistoryModeTableCollisionTest.everySourceDatabaseIsRoutedToTheOneHistoryDatabase()`.
  - **DEFECT**: history-mode routing drops the source database from a table's identity.

- **FM-12.01-4 The history database already holds standard (non-SCD2) tables**
  - **Trigger**: `replication.history.database.name` names a database a standard connector writes into, or tables created before history mode was enabled.
  - **Behaviour**: `CREATE TABLE IF NOT EXISTS` keeps the existing shape (12.02 §3.7). INSERTs bind only the columns ClickHouse has, so they succeed. The first UPDATE or DELETE runs the history statement, whose predicate names `_valid_to`. ClickHouse refuses it with an unknown-identifier error. That code is not in `ClickHouseErrorClassifier`'s FATAL set, so `ClickHouseBatchRunnable.run()` retries the batch forever (`RetryBackoff` has no attempt cap).
  - **Detection**: repeating `Retriable ClickHouse error (Code: ..., Category: ...) -- the same batch will be retried` WARN lines, and `ClickHouseBatchRunnable exception` ERROR lines naming the statement, from the first UPDATE on.
  - **Blast radius**: every table routed to that worker stalls, and the standard tables receive SCD2-mode INSERT rows.
  - **Recovery**: point `replication.history.database.name` at a dedicated database and restart. Then repair the touched standard tables with `ch-mysql-resync` (spec 11.04).
  - **RTO**: a restart once noticed. The stall itself is unbounded.
  - **Test**: GAP: a test that classifies ClickHouse's unknown-identifier error on a history statement as FATAL.
  - **DEFECT**: a deterministic schema mismatch stalls instead of stopping.

- **FM-12.01-5 `replication_log_only=true` without `enable=true`**
  - **Trigger**: the operator sets only `replication.history.replication_log_only`, expecting a binlog recorder.
  - **Behaviour**: nothing history-related is enabled (§3.4). Both engines replicate data as mode 1, and no audit row is written.
  - **Detection**: none. The history banner is absent, and no line says the flag is ineffective.
  - **Blast radius**: data is replicated where the operator expected none, and no recording exists. Nothing diverges.
  - **Recovery**: set `replication.history.enable=true` and restart. Tables created meanwhile are the operator's to keep or drop.
  - **RTO**: a restart (about 20 s).
  - **Test**: `ClickHouseBatchWriterDatabaseResolutionTest.testResolveDatabaseNameLogOnlyAloneIsStandard()`.
  - **DEFECT**: an ineffective flag combination starts without a warning.

Summary: 5 failure modes, 4 DEFECT, 3 GAP.

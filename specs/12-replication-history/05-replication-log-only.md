# Spec 12.05: Replication-Log-Only Mode

## 1. Executive Summary & Purpose
Mode 3 (`replication.history.enable=true` **and**
`replication.history.replication_log_only=true`, spec 12.01) turns the
connector into a **binlog recorder**: every source DML record and every
captured DDL is appended to the audit table of spec 12.04, the source
offset advances exactly as in the other modes, and **no data table is
written and no DDL is executed**. It is the configuration behind binlog
analytics and lag measurement, and the mode in which the audit table is the
replica. Before this spec the mode existed only as a config doc string, an
integration test and two commented lines in the sample configs. This spec
states what is and is not done on each path, what the durable offset
certifies, and that the two execution engines behave identically (the
parity defect of the previous revision is resolved and kept as an `old_`
witness).

---

## 2. Codebase Mapping on 2.11.0
- **Data-table skip (multi-threaded)**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchRunnable.java` — `processBatch`: `if (replicationLogOnly && replicationHistoryEnabled) { log.debug("Replication log only mode is enabled, skipping the processing of records"); } else { processRecordsByTopic ... }`.
- **Data-table skip (single-threaded)**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchWriter.java` — `persistRecords` (the same branch and DEBUG log as the multi-threaded engine); `resolveDatabaseName` (routes to the history database on `enable` alone).
- **DDL gate**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java` — `performDDLOperation`: `if (!REPLICATION_HISTORY_REPLICATION_LOG_ONLY) { cancelPrimaryKeyBackfillsSupersededBy ...; executeDDL(...); <primary-key rebuild swap> }`; the DDL audit insert and the offset acknowledgement follow outside the gate.
- **Audit write**: spec 12.04 (`addRecordsToHistoryTable` on both executors; DDL rows in `performDDLOperation`).
- **Config**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/ClickHouseSinkConnectorConfig.java` — `replication.history.replication_log_only`, boolean, default `false`, "If enabled together with replication.history.enable, only the replication-history audit table is written (every DML record and every captured DDL); regular tables are not written and DDL is not executed. Binlog position tracking continues."
- **Database-level DDL**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java` — `enterCreateDatabase`, `enterDropDatabase` (ignored when `enable=true`, 12.01 §3.3).
- **Sample configs**: `sink-connector-lightweight/docker/config.yml`, `sink-connector-lightweight/docker/config_local.yml` (`#replication.history.replication_log_only: "true"`, commented).
- **Formal model**: `formal_specs/lean/Replication/History.lean` — `logOnlyMode`, `runnableDataWrites`, `writerDataWrites`, `historyWrites`, `runnableDatabase`, `writerDatabase`, `ddlApplied`; old behaviour as `oldWriterDataWrites`, `oldWriterDatabase`.

---

## 3. Operational Specification

### 3.1 Per-batch behaviour (multi-threaded engine, `thread.pool.size ≥ 1`, `single.threaded=false`)
For each dequeued batch, `ClickHouseBatchRunnable.processBatch`:
1. `addRecordsToHistoryTable(batch)` — every record inserted into the audit
   table (12.04 §3.4), gated on `enable`.
2. Data-table processing **skipped** because both flags are true: no
   `DbWriter` is obtained for any data table, no table is auto-created, no
   INSERT is bound, no SCD2 statement runs. `result` stays `true`.
3. The batch is handed to the offset FIFO as written
   (`DebeziumOffsetManagement.checkIfBatchCanBeCommitted`, 09.01 §3.2) and
   acknowledged in handoff order.

Formal: `Replication.History.log_only_runnable_writes_no_data_rows`,
`Replication.History.log_only_history_still_written`.

The skip requires **both** flags: `log_only=true` with `enable=false` is the
degenerate combination of 12.01 §3.4 — data tables are written as in mode 1
(`Replication.History.log_only_flag_alone_is_standard_on_runnable`).

### 3.2 What the durable offset certifies
In mode 3 a batch is "written" (09.01) when its audit rows are durably in
the audit table. Invariant I8 therefore reads: **the committed source
offset never passes a DML record that is not in the audit table** — the
no-loss guarantee of 10.06 holds for the recording, and restarts redeliver
audit rows at least once, which the coordinate sorting key of 12.04 §3.2
collapses under `FINAL`. Nothing is certified about data tables: none exist
for this connector unless another connector writes them.

### 3.3 Single-threaded engine — parity holds
`ClickHouseBatchWriter.persistRecords` carries the same mode-3 branch as
`ClickHouseBatchRunnable.processBatch`: after the audit write, when both
flags are true, no `processRecordsByTopic` call is made, no data table is
auto-created or routed, and `result` stays `true` so the batch is
acknowledged on the strength of its audit rows (§3.2). A mode-3 connector
behaves identically with `single.threaded=true` and `false`
(`Replication.History.both_engines_skip_data_in_log_only`,
`Replication.History.both_engines_route_alike`; spec 03.02 parity). The
previous revision's writer had no such branch and replicated data into
`<history database>.<table>` as SCD2 tables — resolved Gap G-12.05-1, kept
as `Replication.History.old_writer_path_ignored_log_only`.

### 3.4 DDL
`performDDLOperation` in mode 3:
- the DDL is parsed and translated as usual (parser errors behave as in
  06.03/06.08 — an unparseable DDL is still loud), with the database
  override of 12.01 §3.3; database-level DDL (`CREATE DATABASE`, `DROP
  DATABASE`) translates to nothing in history mode (12.01 §3.3,
  `Replication.History.database_ddl_ignored_in_history_mode`) — it would not
  be executed here anyway, but the translation is empty on both history
  modes alike;
- **not executed**: `executeDDL`, the superseded-backfill cancellation and
  the primary-key rebuild swap are all inside `if (!log_only)`; the mode-2
  refusal of a key change (06.09 §3.2 item 1) is therefore never reached;
- schema-cache invalidation still runs (harmless: no cached data tables);
- the DDL audit row is inserted (12.04 §3.3) **before** the offset is
  acknowledged; its failure fails the DDL attempt (12.04 §3.4) — in this
  mode the audit table is the only output, so a swallowed audit failure
  was a silent loss.
Consequence: `binlog_history.history` holds the DDL text of every captured
DDL, and no ClickHouse table changes shape.

### 3.5 Snapshots
`snapshot.mode` is honoured as in the other modes: with `initial` the
snapshot rows (`op = r`) are recorded in the audit table like streamed rows
(`_operation = READ`); with `schema_only` / `no_data` only the stream is
recorded. The snapshot-completion control record commits under the
quiescence rule of 09.04 / I12 unchanged.

### 3.6 Startup
Identical to mode 2 (12.01 §3.5): history connection, `CREATE DATABASE IF
NOT EXISTS`, audit-table creation (loud on failure: the engine does not
start without the audit table), and the banner, whose second line reads
`"replication-log-only: only the audit table will be written"` in this
mode.

### 3.7 Configuration contract
A mode-3 connector sets exactly:
```
replication.history.enable: "true"
replication.history.replication_log_only: "true"
replication.history.database.name: "<audit database>"   # default binlog_history
replication.history.table.name: "<audit table>"         # default history
replication.history.ttl: "<days>"                        # default 30
```
`single.threaded` may take either value (§3.3). `auto.create.tables`,
`clickhouse.database.override.map`, prefix and suffix settings have no
effect in this mode on either engine: nothing is routed.

---

## 4. Invariants Preserved
- **Invariant I8 (Durable Offset Quiescence)**: preserved with the audit
  table as the written target (§3.2); the handoff FIFO of 09.01 and the
  control-record gating of 09.04 are unchanged.
- **Invariant I9 (Loud Failure)**: a DML audit insert failure fails the
  batch loudly and a DDL audit insert failure fails the DDL attempt (12.04
  §3.4); the startup creation failure fails the engine start (§3.6) — all
  three matter most in this mode, because the audit table is the only
  output.
- **Invariant I12 (Snapshot Completion)**: unchanged.
- **Spec 03.02 parity contract**: holds (§3.3).
- **Spec 10.06 (no-loss is parameter-independent)**: applies to the audit
  rows: no flush/buffer/pool/retry parameter can move the committed offset
  past an un-audited record.

---

## 5. Verification Criteria
- `ReplicationLogOnlyIT.testReplicationLogOnlyWritesToBinlogHistoryOnly()` — §3.1 with `snapshot.mode=initial`: `employees.log_only_test` holds zero rows, `binlog_history.history` exists and holds rows, and no `binlog_history.log_only_test` table was created; the assertion messages name the table each query counts (resolved Gap G-12.05-3).
- The end-to-end history suite (single-threaded variants) — §3.3: on the single-threaded engine in mode 3 the audit database holds only the audit table; `processRecordsByTopic` is never reached (no unit test can observe the skip without a live audit-table connection).
- `ClickHouseBatchWriterDatabaseResolutionTest.testResolveDatabaseNameReplicationHistoryProtected()`, `ClickHouseBatchWriterDatabaseResolutionTest.testResolveDatabaseNameLogOnlyAloneIsStandard()` — the single-threaded routing predicate: `enable` routes to the history database; `log_only` alone does not (12.01 §3.4).
- `MySqlDDLParserListenerImplTest.testReplicationHistorySkipsDatabaseLevelDdl()` — §3.4: database-level DDL translates to nothing in history mode.
- The end-to-end history suite (three connectors side by side, kill -9 restart, single-threaded variants, degenerate combination) — a mode-3 connector on each engine writes only the audit table, executes no DDL, and resumes after a kill -9 with its offset behind no un-audited record.
- Lean: `Replication.History.log_only_runnable_writes_no_data_rows`, `Replication.History.log_only_history_still_written`, `Replication.History.log_only_flag_alone_is_standard_on_runnable`, `Replication.History.both_engines_skip_data_in_log_only`, `Replication.History.both_engines_route_alike`, `Replication.History.database_ddl_ignored_in_history_mode`; old-behaviour witnesses `Replication.History.old_writer_path_ignored_log_only`, `Replication.History.old_routing_diverged_on_log_only_alone`.
- **Coverage gaps**: no unit test asserts the DDL audit row in mode 3 or checks offset progress after a restart in mode 3 outside the end-to-end suite.

---

## 6. Gaps resolved in this change
| Id | Where | Old behaviour | New behaviour | New-property theorem | Old witness |
|---|---|---|---|---|---|
| G-12.05-1 | `ClickHouseBatchWriter.persistRecords` | no `log_only` skip on the single-threaded path: a mode-3 connector with `single.threaded=true` replicated data as SCD2 tables into the history database | the same skip as the multi-threaded engine (§3.3) | `Replication.History.both_engines_skip_data_in_log_only` | `Replication.History.old_writer_path_ignored_log_only` |
| G-12.05-2 | `ClickHouseSinkConnectorConfig` doc strings | "only track binlog position without inserting data" omitted the audit table; `enable` described "history tables" without saying which | both doc strings state the modes of 12.01 §1 (§2) | — | — |
| G-12.05-3 | `ReplicationLogOnlyIT` | assertion message named `binlog_history.log_only_test` while the query counted `binlog_history.history`; no assertion that no data table was created | messages name the counted table; asserts the absence of `binlog_history.log_only_test` (§5) | — | — |

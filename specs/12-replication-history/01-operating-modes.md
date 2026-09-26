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
| **3 Replication-log-only** | `true` | `true` | **not written** on the multi-threaded path (spec 12.05) | n/a | written: every DML record and every DDL (spec 12.04) | history database (only relevant for the single-threaded gap, 12.05 §3.3) |
| *(degenerate)* | `false` | `true` | written | no | not written | Runnable: source database; Writer: history database (§3.4) |

A fourth combination (`enable=false, log_only=true`) is not a mode: the code
gates every history behaviour on `enable`, so it behaves as mode 1 except for
one routing divergence recorded in §3.4.

---

## 2. Codebase Mapping on 2.11.0
- **Keys**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/ClickHouseSinkConnectorConfigVariables.java` — `REPLICATION_HISTORY_ENABLE` (`replication.history.enable`), `REPLICATION_HISTORY_TABLE_NAME` (`replication.history.table.name`), `REPLICATION_HISTORY_DATABASE_NAME` (`replication.history.database.name`), `REPLICATION_HISTORY_TTL` (`replication.history.ttl`), `REPLICATION_HISTORY_REPLICATION_LOG_ONLY` (`replication.history.replication_log_only`).
- **Defaults and doc strings**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/ClickHouseSinkConnectorConfig.java` (`.define(...)` blocks for the five keys). The lightweight module defines no history key of its own (`sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/config/SinkConnectorLightWeightConfig.java` has none); both paths read the definitions above.
- **Multi-threaded executor**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchRunnable.java` — `processBatch` (audit write, then the mode-3 skip), `addRecordsToHistoryTable`, `resolveDatabaseName`.
- **Single-threaded executor**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchWriter.java` — `persistRecords`, `addRecordsToHistoryTable`, `resolveDatabaseName`.
- **Lightweight engine**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java` — history connection (`setReplicationHistoryDbConnection`), startup creation of the history database and audit table in `connectorStarted`, the DDL database override and the `replication_log_only` gate in `performDDLOperation`.
- **Startup DDL**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/operations/ClickHouseAutoCreateTable.java` — `createHistoryDatabase`, `createHistoryTable`.
- **Formal model**: `formal_specs/lean/Replication/History.lean` — `ModeFlags`, `runnableDataWrites`, `writerDataWrites`, `historyWrites`, `runnableDatabase`, `writerDatabase`.

---

## 3. Operational Specification

### 3.1 Configuration keys

| Key | Type | Default | Read by |
|---|---|---|---|
| `replication.history.enable` | boolean | `false` | every history behaviour: audit write, SCD2 columns and statements, DDL database override, history connection, startup creation, PK-rebuild refusal |
| `replication.history.replication_log_only` | boolean | `false` | `ClickHouseBatchRunnable.processBatch` (skip data tables, only together with `enable`), `ClickHouseBatchWriter.resolveDatabaseName` (routing only), `DebeziumChangeEventCapture.performDDLOperation` (skip DDL execution) |
| `replication.history.database.name` | string | `binlog_history` | database of the audit table AND of every data table in modes 2 and 3 (§3.3); the history JDBC connection's default database |
| `replication.history.table.name` | string | `history` | name of the audit table (spec 12.04) |
| `replication.history.ttl` | int (days) | `30` | TTL of the audit table; TTL of SCD2 data tables created by the **DDL translator** only (spec 12.02 §3.4) |

`connectorStarted` reads the database and table names through
`props.getProperty(key, literal)` with the literals `"binlog_history"` /
`"history"`; every other reader goes through the `ConfigDef` defaults, which
are the same values. The config doc string of `replication_log_only` ("only
track binlog position without inserting data to ClickHouse") understates the
mode: the audit table IS written (12.05 §3.1).

### 3.2 What each mode writes, per batch
Both executors process a batch in the same order:
1. **Audit write** — `addRecordsToHistoryTable(batch)`: when `enable=true`,
   every record of the batch is inserted into
   `<database.name>.<table.name>` with the raw source `DDL` argument `""`
   (spec 12.04 §3.3). Gated on `enable` alone: modes 2 and 3 both do it,
   mode 1 never does.
2. **Data-table write** — `processRecordsByTopic` for each topic:
   - `ClickHouseBatchRunnable.processBatch`: skipped when
     `replicationLogOnly && replicationHistoryEnabled` (mode 3), with a
     DEBUG log `"Replication log only mode is enabled, skipping the
     processing of records"`; otherwise executed (modes 1 and 2, and the
     degenerate combination).
   - `ClickHouseBatchWriter.persistRecords`: **always executed** — the
     single-threaded path has no mode-3 skip (12.05 §3.3, Gap G-12.05-1).
3. **Acknowledgement** — unchanged by the mode: the batch is acknowledged
   through `DebeziumOffsetManagement` once step 2 (or, in mode 3 on the
   Runnable, step 1 alone) has completed (spec 09.01). In mode 3 the durable
   offset therefore certifies the **audit rows**, not data rows.

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
- `ClickHouseBatchWriter.resolveDatabaseName`: `if (enable || log_only)
  return history database`.
- `DebeziumChangeEventCapture.performDDLOperation`: `if (enable) databaseName
  = history database` before constructing the DDL translator, so a replayed
  `CREATE TABLE` / `ALTER TABLE` is executed in the history database as well
  (mode 2). The override map is still applied by the translator to **that**
  name (`MySqlDDLParserListenerImpl.overrideDatabaseName`), so an override
  keyed by a source database has no effect in modes 2/3, while one keyed by
  the history database name would remap it. Cache invalidation after the DDL
  is computed from the source database plus prefix/override (not from the
  history override) — the code comments this explicitly.

Consequence (verified by `BinLogHistoryIT`): with
`clickhouse.database.override.map=employees:employees2` and `enable=true`,
the SCD2 copy of `employees.newtable` is `binlog_history.newtable`, not
`employees2.newtable`. **Every replicated table of a mode-2/3 connector
lives in the single history database, alongside the audit table.** A
connector whose data must land in a dedicated database therefore sets
`replication.history.database.name` to that database.

### 3.4 The degenerate combination (`enable=false, log_only=true`)
No history behaviour is enabled (§3.1 gates on `enable`), so:
- Runnable: data tables written to the source-derived database (standard).
- Writer: `resolveDatabaseName` returns the history database (its predicate
  is `enable || log_only`), so the same records are written to
  `<history database>.<table>` — the two execution engines disagree
  (`Replication.History.routing_diverges_on_log_only_alone`). This is a
  parity defect under spec 03.02's contract, recorded as **Gap G-12.01-1**;
  the combination is not a supported mode and must not be configured.

### 3.5 Startup and connections (lightweight engine)
When `enable=true`:
- a second JDBC connection is opened with the history database as its
  default database (`setReplicationHistoryDbConnection`); the DDL audit rows
  of 12.04 §3.3 are written through it;
- `connectorStarted` calls `createHistoryDatabase` (`CREATE DATABASE IF NOT
  EXISTS <history database>`) and `createHistoryTable` (spec 12.04 §3.1) on
  the system connection. A failure is logged (`"Error creating history
  table"`) and **swallowed** — the engine starts without the audit table and
  the first audit insert fails instead (**Gap G-12.01-2**, I9);
- the banner `"************** HISTORY MODE ENABLED **************"` /
  `"only history will be tracked"` is printed for both modes 2 and 3; in
  mode 2 data tables are written too, so the second line is misleading
  (**Gap G-12.01-3**).

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
- **Invariant I9 (Loud Failure)**: violated by the swallowed startup failure
  (§3.5, Gap G-12.01-2) — recorded, not endorsed.
- **Invariant I10 (Structural Separation of Concerns)**: the history database
  is a routing decision, not a transformation; no row content changes with
  the mode except the SCD2 metadata columns of 12.02.
- **Spec 03.02 parity contract**: the two execution engines must behave
  identically; the mode-3 skip and the routing predicate differ between them
  (Gaps G-12.01-1, G-12.05-1). Formal statement:
  `Replication.History.writer_path_ignores_log_only`,
  `Replication.History.routing_diverges_on_log_only_alone`,
  `Replication.History.scd2_routes_every_table_to_history_database`,
  `Replication.History.standard_mode_writes_no_history`.

---

## 5. Verification Criteria
- `ClickHouseBatchWriterDatabaseResolutionTest.testResolveDatabaseNameReplicationHistoryProtected()` — §3.3: with `enable=true` the history database wins over `clickhouse.database.override.map`.
- `BinLogHistoryIT.testBinLogHistory()` — §3.3 end to end: the SCD2 data table is created inside the history database despite an override map for its source database; the audit table is created at startup with 19 columns.
- `ReplicationLogOnlyIT.testReplicationLogOnlyWritesToBinlogHistoryOnly()` — §3.2 mode 3: the source-database table holds zero rows, the audit table holds rows.
- `GroupInsertQueryHistoryMultiRowTest.historyModeStillEmitsOneRowPerUpdate()` — grouping is mode-independent (one entry per UPDATE in both modes).
- Lean: `Replication.History.log_only_runnable_writes_no_data_rows`, `Replication.History.log_only_history_still_written`, `Replication.History.log_only_flag_alone_is_standard_on_runnable`, `Replication.History.writer_path_ignores_log_only`, `Replication.History.routing_diverges_on_log_only_alone`, `Replication.History.scd2_routes_every_table_to_history_database`, `Replication.History.standard_mode_writes_no_history` — the matrix of §3.2–§3.4, machine-checked.
- **Coverage gap**: no test exercises the degenerate combination, the single-threaded path in mode 3, or the swallowed startup failure.

---

## 6. Gaps recorded (not fixed here)
| Id | Where | Behaviour | Consequence |
|---|---|---|---|
| G-12.01-1 | `ClickHouseBatchWriter.resolveDatabaseName` vs `ClickHouseBatchRunnable.resolveDatabaseName` | routing predicate `enable \|\| log_only` vs `enable` | the two engines write different databases for `enable=false, log_only=true` (spec 03.02 parity) |
| G-12.01-2 | `DebeziumChangeEventCapture.connectorStarted` | history database/table creation failure is caught and logged | engine starts without the audit table; failure surfaces later as an insert error (I9) |
| G-12.01-3 | `DebeziumChangeEventCapture` startup banner | "only history will be tracked" printed in mode 2 | operator-facing message contradicts mode-2 behaviour |

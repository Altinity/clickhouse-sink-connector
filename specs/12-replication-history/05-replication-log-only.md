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
certifies, and the one execution-engine parity defect.

---

## 2. Codebase Mapping on 2.11.0
- **Data-table skip (multi-threaded)**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchRunnable.java` — `processBatch`: `if (replicationLogOnly && replicationHistoryEnabled) { log.debug("Replication log only mode is enabled, skipping the processing of records"); } else { processRecordsByTopic ... }`.
- **No skip (single-threaded)**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchWriter.java` — `persistRecords` (always calls `processRecordsByTopic`); `resolveDatabaseName` (routes to the history database when `enable || log_only`).
- **DDL gate**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java` — `performDDLOperation`: `if (!REPLICATION_HISTORY_REPLICATION_LOG_ONLY) { cancelPrimaryKeyBackfillsSupersededBy ...; executeDDL(...); <primary-key rebuild swap> }`; the DDL audit insert and the offset acknowledgement follow outside the gate.
- **Audit write**: spec 12.04 (`addRecordsToHistoryTable` on both executors; DDL rows in `performDDLOperation`).
- **Config**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/ClickHouseSinkConnectorConfig.java` — `replication.history.replication_log_only`, boolean, default `false`, "If enabled, only track binlog position without inserting data to ClickHouse".
- **Sample configs**: `sink-connector-lightweight/docker/config.yml`, `sink-connector-lightweight/docker/config_local.yml` (`#replication.history.replication_log_only: "true"`, commented).
- **Formal model**: `formal_specs/lean/Replication/History.lean` — `logOnlyMode`, `runnableDataWrites`, `writerDataWrites`, `historyWrites`, `runnableDatabase`, `writerDatabase`.

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

### 3.3 Single-threaded engine — parity defect (Gap G-12.05-1)
`ClickHouseBatchWriter.persistRecords` has **no** mode-3 branch: after the
audit write it calls `processRecordsByTopic` for every topic exactly as in
modes 1 and 2, and `resolveDatabaseName` routes those writes to the history
database (predicate `enable || log_only`). A mode-3 connector configured
with `single.threaded=true` therefore **does** replicate data — into
`<history database>.<table>` as SCD2 tables, with auto-creation — contrary
to the multi-threaded engine and to the mode's definition. This violates
the parity contract of spec 03.02 and is witnessed by
`Replication.History.writer_path_ignores_log_only`. The mode's integration
test runs the multi-threaded engine (`single.threaded` is commented out in
`ReplicationLogOnlyIT`), so the defect is uncovered by tests.

### 3.4 DDL
`performDDLOperation` in mode 3:
- the DDL is parsed and translated as usual (parser errors behave as in
  06.03/06.08 — an unparseable DDL is still loud), with the database
  override of 12.01 §3.3;
- **not executed**: `executeDDL`, the superseded-backfill cancellation and
  the primary-key rebuild swap are all inside `if (!log_only)`; the mode-2
  refusal of a key change (06.09 §3.2 item 1) is therefore never reached;
- schema-cache invalidation still runs (harmless: no cached data tables);
- the DDL audit row is inserted (12.04 §3.3) and the offset is acknowledged.
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
NOT EXISTS`, audit-table creation (swallowed on failure, G-12.01-2), and the
"only history will be tracked" banner — which is accurate in this mode.

### 3.7 Configuration contract
A mode-3 connector sets exactly:
```
replication.history.enable: "true"
replication.history.replication_log_only: "true"
replication.history.database.name: "<audit database>"   # default binlog_history
replication.history.table.name: "<audit table>"         # default history
replication.history.ttl: "<days>"                        # default 30
```
and must **not** set `single.threaded: "true"` (§3.3). `auto.create.tables`,
`clickhouse.database.override.map`, prefix and suffix settings have no
effect on the multi-threaded path (nothing is routed) and only the
override-map bypass of 12.01 §3.3 on the single-threaded one.

---

## 4. Invariants Preserved
- **Invariant I8 (Durable Offset Quiescence)**: preserved with the audit
  table as the written target (§3.2); the handoff FIFO of 09.01 and the
  control-record gating of 09.04 are unchanged.
- **Invariant I9 (Loud Failure)**: an audit insert failure fails the batch
  loudly (12.04 §3.4); the DDL audit swallow (G-12.04-1) applies here too
  and is the more serious in this mode, because the audit table is the only
  output.
- **Invariant I12 (Snapshot Completion)**: unchanged.
- **Spec 03.02 parity contract**: **violated** (G-12.05-1), recorded.
- **Spec 10.06 (no-loss is parameter-independent)**: applies to the audit
  rows: no flush/buffer/pool/retry parameter can move the committed offset
  past an un-audited record.

---

## 5. Verification Criteria
- `ReplicationLogOnlyIT.testReplicationLogOnlyWritesToBinlogHistoryOnly()` — §3.1 on the multi-threaded engine with `snapshot.mode=initial`: `employees.log_only_test` holds zero rows, `binlog_history.history` exists and holds rows. It does **not** assert the row count (3), offset progress, or the absence of a `binlog_history.log_only_test` table.
- `ClickHouseBatchWriterDatabaseResolutionTest.testResolveDatabaseNameReplicationHistoryProtected()` — the single-threaded routing predicate (§3.3, `enable` half).
- Lean: `Replication.History.log_only_runnable_writes_no_data_rows`, `Replication.History.log_only_history_still_written`, `Replication.History.log_only_flag_alone_is_standard_on_runnable`, `Replication.History.writer_path_ignores_log_only`, `Replication.History.routing_diverges_on_log_only_alone`.
- **Coverage gaps**: no test runs mode 3 on the single-threaded engine (G-12.05-1), asserts that DDL is not executed (§3.4), asserts the DDL audit row in mode 3, or checks offset progress after a restart in mode 3.

---

## 6. Gaps recorded (not fixed here)
| Id | Where | Behaviour | Consequence | Witness |
|---|---|---|---|---|
| G-12.05-1 | `ClickHouseBatchWriter.persistRecords` | no `log_only` skip on the single-threaded path | with `single.threaded=true` a mode-3 connector replicates data as SCD2 tables into the history database | `Replication.History.writer_path_ignores_log_only` |
| G-12.05-2 | `ClickHouseSinkConnectorConfig` doc string | "only track binlog position without inserting data" | omits that the audit table is written; the mode is otherwise undocumented in `doc/` | — |
| G-12.05-3 | `ReplicationLogOnlyIT` | assertion message names `binlog_history.log_only_test`, the query counts `binlog_history.history` | misleading failure message; no assertion that no data table was created | — |

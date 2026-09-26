# Spec 12.04: Binlog History Audit Table (`<history database>.<history table>`)

## 1. Executive Summary & Purpose
Whenever `replication.history.enable=true` (modes 2 and 3, spec 12.01) the
connector keeps a second kind of history that is unrelated to the SCD2 data
tables of 12.02/12.03: **one append-only audit table for the whole connector**,
holding one row per source DML record and one row per replayed DDL, with the
before/after images as JSON, the raw Debezium payload, and the binlog
coordinates. It is the data behind binlog analytics, lag measurement and
audit, and in mode 3 it is the **only** thing written. This spec fixes its
schema, its row contents per column, who writes it and when, and its
retention.

---

## 2. Codebase Mapping on 2.11.0
- **Schema and row values**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/history/BinLogHistory.java` — `HISTORY_COLUMNS`, `createHistoryTableSyntax`, `addRecordsToHistoryTable`, `executeInsertWithStructs`, `getValueFromStruct`.
- **Creation at startup**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/operations/ClickHouseAutoCreateTable.java` — `createHistoryDatabase`, `createHistoryTable` (server timezone resolution); called from `connectorStarted` in `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`.
- **DML rows**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchRunnable.java` and `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchWriter.java` — `addRecordsToHistoryTable` (first step of `processBatch` / `persistRecords`).
- **DDL rows**: `DebeziumChangeEventCapture.performDDLOperation` (after translation, outside the `replication_log_only` gate, through the history connection).
- **Insert statement**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/QueryFormatter.java` — `getInsertQueryUsingInputFunction` (the `input()`-function INSERT over `HISTORY_COLUMNS`).
- **Formal model**: `formal_specs/lean/Replication/History.lean` — `historyWrites` (which batches reach this table per mode).

---

## 3. Operational Specification

### 3.1 DDL
Created once at engine start (`connectorStarted`, when `enable=true`) as
`CREATE DATABASE IF NOT EXISTS <replication.history.database.name>` followed
by (golden text from `BinLogHistoryTest.testCreateHistoryTableSyntax()`, with
`ttl = replication.history.ttl` and `tz` = `clickhouse.datetime.timezone` if
set, else the server's `timezone()`):

```
CREATE TABLE IF NOT EXISTS `<db>`.`<table>`(
  `gtid` String, `database` LowCardinality(String), `table` LowCardinality(String), `ddl` String,
  `before` String, `after` String, `_raw` String,
  `_time` DateTime64(9, '<tz>'),
  `is_deleted` UInt8, `_operation` LowCardinality(String), `_version` UInt64,
  `host` LowCardinality(String), `logfile` LowCardinality(String), `position` UInt64,
  `primary_host` LowCardinality(String), `server_id` UInt32, `row` UInt32, `sequence` UInt64,
  `db_time` DateTime MATERIALIZED now())
ENGINE = ReplacingMergeTree(_version, is_deleted)
ORDER BY (server_id, logfile, position, sequence, _time)
PARTITION BY toDate(`_time`)
TTL toDate(`_time`) + toIntervalDay(<ttl>);
```

Nineteen columns. `db_time` is `MATERIALIZED now()` — the ClickHouse
ingestion time. This is a connector-owned table, so a MATERIALIZED column
here does not conflict with Invariant I6 (no source column of that name
exists); `end-to-end lag = db_time - _time` is the intended use. A
`ttl_only_drop_parts` setting is present only as a comment and is **not**
emitted.

### 3.2 Row identity and engine
Sorting key `(server_id, logfile, position, sequence, _time)`:
- `sequence` is the lightweight engine's per-record sequence number when one
  was assigned, else the record's **row index within its event**
  (`ClickHouseStruct.getRow()`) — the Kafka Connect path assigns no sequence
  number, and binding its `-1` sentinel made every row of a multi-row
  statement share one key and collapse (02.01 §3.5 d,
  `BinLogHistoryTest.kafkaPathUsesRowIndexAsSequence()`).
- `is_deleted` is **always 0** (`getValueFromStruct`): the table is
  append-only and a DELETE event must remain visible under `FINAL`; the
  operation is carried by `_operation`.
- `_version = SnowFlakeId.generate(ts_ms, gtid, false)`; it only matters
  for redelivered duplicates of the same coordinates, which `FINAL`
  collapses.

### 3.3 Row contents per column
| Column | DML row (`addRecordsToHistoryTable(..., DDL="", batch)`) | DDL row (`performDDLOperation`) |
|---|---|---|
| `gtid` | `struct.getGtid()` | same (the DDL record's struct) |
| `database` | `struct.getDatabase()` (source database, **not** the routed one) | same |
| `table` | last dot-separated segment of the topic (`server.db.table` → `table`), `""` if none | same |
| `ddl` | `""` | the raw source DDL text |
| `before` | `beforeModifiedFieldsToJson()` or `""` | `""` |
| `after` | `afterModifiedFieldsToJson()` or `""` | `""` |
| `_raw` | `sourceRecordToJson()` — the full Debezium record | same |
| `_time` | `ts_ms` converted to `DateTime64` from the source timezone to the server timezone (`TimestampConverter.convert`) | same |
| `is_deleted` | `0` | `0` |
| `_operation` | the `CDC_OPERATION` **enum name** (`CREATE`, `UPDATE`, `DELETE`, `READ`, `TRUNCATE`), `""` if unknown | as parsed for the DDL record |
| `_version` | Snowflake of `(ts_ms, gtid)` | same |
| `host`, `primary_host` | `database.hostname` from the configuration (both columns) | same |
| `logfile` | `struct.getFile()` or `""` | same |
| `position` | `struct.getPos()` | same |
| `server_id` | `struct.getServerId()` | same |
| `row` | `struct.getRow()` | same |
| `sequence` | §3.2 | same |

`_operation` spelling differs from the SCD2 tables (12.03 §3.7). The
timezones default to UTC when unset.

### 3.4 When rows are written
- **DML**: the **first** step of every batch on both executors
  (`ClickHouseBatchRunnable.processBatch`,
  `ClickHouseBatchWriter.persistRecords`), before any data-table write, on a
  `DbWriter` obtained for `<history db>.<history table>` — gated on
  `enable` alone, so in mode 3 as well. The whole batch is inserted with one
  `input()` INSERT and `executeBatch()`. A failure propagates: the batch is
  not acknowledged and is retried (10.02), so **an audit-table failure stops
  data replication in mode 2** — by design of the ordering, and the
  correct behaviour for mode 3 where the audit table is the replica.
- **DDL**: after translation (and in mode 2 after execution), the DDL
  record is inserted through the dedicated history connection with the raw
  DDL string. DDL that is **ignored** (`checkIfDDLNeedsToBeIgnored`: not in
  the capture list, snapshot DDL without `enable.snapshot.ddl`,
  `disable.drop.truncate`) is **not recorded** — the audit trail contains the
  DDL the connector acted on, not every DDL in the binlog. A failure of the
  DDL audit insert is logged (`"Error adding DDL records to history table"`)
  and **swallowed**, and the DDL's offset is acknowledged (**Gap G-12.04-1**,
  I9): the audit trail can silently miss a DDL.

### 3.5 Retention
`TTL toDate(_time) + toIntervalDay(replication.history.ttl)` — a sliding
window of `ttl` days of source events (default 30), partitioned per day of
`_time`. The TTL is on the **event time**, so a lagging connector inserting
old events inserts them into partitions that are already close to expiry.

### 3.6 Failure isolation between the two kinds of history
The audit insert and the SCD2 statements of 12.03 are separate statements on
separate connections and are not atomic with each other: a batch whose audit
insert succeeded and whose data write failed is retried in full, so the audit
table receives the same coordinates again — collapsed by `FINAL` (§3.2).

---

## 4. Invariants Preserved
- **Invariant I8 (Durable Offset Quiescence)**: DML audit rows are written
  before the batch can be acknowledged, so in every mode the durable offset
  never passes an un-audited DML record; the DDL audit row is the exception
  of Gap G-12.04-1.
- **Invariant I9 (Loud Failure)**: DML audit failures are loud (batch fails
  and retries); the DDL audit failure and the startup creation failure
  (12.01 G-12.01-2) are swallowed — recorded.
- **Invariant I6 (Column Authority)**: `db_time MATERIALIZED now()` is on a
  connector-owned table with no source counterpart.
- **Spec 10.05 / 02.04 (redelivery)**: identical coordinates collapse under
  `ReplacingMergeTree` on the coordinate sorting key; no row-key
  de-duplication is involved.

---

## 5. Verification Criteria
- `BinLogHistoryTest.testCreateHistoryTableSyntax()`, `BinLogHistoryTest.testCreateHistoryTableSyntaxWithDifferentNames()` — §3.1 golden DDL.
- `BinLogHistoryTest.kafkaPathUsesRowIndexAsSequence()` — §3.2 `sequence` fallback.
- `BinLogHistoryIT.testBinLogHistory()` — end to end in mode 2: the table exists with exactly 19 columns of the expected names, `ddl != ''` rows exist for replayed DDL, `_operation = 'DELETE'` rows exist under `FINAL` for a source DELETE (its `_time`-equals-`ts_sec` check is present but not invoked).
- `ReplicationLogOnlyIT.testReplicationLogOnlyWritesToBinlogHistoryOnly()` — §3.4 in mode 3: rows are written to the audit table while the data table stays empty.
- `DBMetadataRetryClassificationTest.testTableAlreadyExistsIsNotRetryable()` — `TABLE_ALREADY_EXISTS` / `DATABASE_ALREADY_EXISTS` on the history database are permanent, not retried.
- Lean: `Replication.History.log_only_history_still_written`, `Replication.History.standard_mode_writes_no_history` — §3.4 gating on `enable`.
- **Coverage gaps**: no test asserts the per-column values of §3.3 (only schema and existence), the DDL-row swallow of Gap G-12.04-1, or the exclusion of ignored DDL.

---

## 6. Gaps recorded (not fixed here)
| Id | Where | Behaviour | Consequence |
|---|---|---|---|
| G-12.04-1 | `DebeziumChangeEventCapture.performDDLOperation` | the DDL audit insert's exception is caught and logged; the offset is acknowledged | the audit trail can silently omit a DDL (I9) |
| G-12.04-2 | `BinLogHistory.getValueFromStruct` | `_operation` stored as the enum name; SCD2 tables store the letter code | two spellings across the two history tables (02.01 §3.5 c) |
| G-12.04-3 | `createHistoryTableSyntax` | `ttl_only_drop_parts` commented out | TTL expiry is row-level rewriting inside daily partitions instead of part drops |

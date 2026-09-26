# Spec 12.02: SCD2 History Table Shape — Both Creation Paths

## 1. Executive Summary & Purpose
In mode 2 (`replication.history.enable=true`, spec 12.01) every replicated
table is an SCD Type 2 table: one physical row per **version** of a source
row, delimited by `_valid_from` / `_valid_to`, tagged with `_operation`, and
retired through `is_deleted`. This spec fixes the exact DDL the connector
emits for such a table on **both** creation paths — the Kafka-record path
(`ClickHouseAutoCreateTable`, first record of an unknown table) and the DDL
translator path (`MySqlDDLParserListenerImpl`, replayed `CREATE TABLE`) —
which now emit the **same** shape (column types, defaults, timezone, sorting
key, TTL); the differences the previous revision recorded are resolved in §6.
The row-level write protocol is spec
12.03; the audit table is spec 12.04 (a different table with a different
schema — the two are often confused because both live in the history
database).

---

## 2. Codebase Mapping on 2.11.0
- **Record path**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/operations/ClickHouseAutoCreateTable.java` — `createTableSyntax` (history columns, engine, `PARTITION BY`, `ORDER BY`, `TTL` from `replication.history.ttl`), `historyDateTimeColumnType` (`DateTime[('<tz>')] DEFAULT '<sentinel>'` for both validity columns).
- **DDL path**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java` — `enterColumnCreateTable` (history columns with `DEFAULT`, `PARTITION BY`, `TTL`), `appendOrderBy` / `stripEnclosingParentheses` (`_valid_to` appended on every `ORDER BY` branch as a flat tuple), `isConnectorColumn` (excludes the history columns from identity checks).
- **Column names, types and sentinel**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/ClickHouseDbConstants.java` — `DELETED_FROM_TIME_COLUMN` (`_valid_from`), `DELETED_TIME_COLUMN` (`_valid_to`), `DELETED_TIME_COLUMN_DATA_TYPE` (`DateTime DEFAULT '<sentinel>'`), `DELETED_TIME_COLUMN_TO_DATE`, `OPERATION_COLUMN` (`_operation`), `OPERATION_COLUMN_DATA_TYPE` (`LowCardinality(String)`), `IS_DELETED_COLUMN`, `VERSION_COLUMN`; `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/metadata/DataTypeRange.java` — `DATETIME32_MAX_TTL` (2100-01-01 00:00:00 UTC as epoch seconds), `epochSecondsToDateString`.
- **Sorting-key selection shared with standard mode**: specs 06.05 §3.6 and 08.05 §3.2; formal model `formal_specs/lean/Replication/CreateTable.lean`.
- **Formal model of the rows this shape stores**: `formal_specs/lean/Replication/History.lean` (`HRow`, `openEnd`, `finalAt`, `liveAt`, `openRow`).

---

## 3. Operational Specification

### 3.1 The sentinel
The open (current) version of a row carries `_valid_to` equal to the
sentinel **`2100-01-01 00:00:00`** (`DataTypeRange.DATETIME32_MAX_TTL`,
rendered by `epochSecondsToDateString` in UTC). It is not the `DateTime`
maximum (2106-02-07); it is a fixed instant chosen so that
`toDate(_valid_to)` of every open row is the single partition `2100-01-01`.
All closed versions have `_valid_to` < sentinel and fall into the partition
of the day they were superseded. Formal: `Replication.History.openEnd`.

Because the sentinel is written both as a DDL `DEFAULT` literal and as a
statement literal (12.03), and both are rendered "without timezone
adjustment" from the same epoch value, the open-row predicate
`` `_valid_to` = toDateTime('2100-01-01 00:00:00', '<server tz>') `` used by
12.03 matches the value the DDL default and the field mapper store **as long
as the server timezone used in the statement equals the column's timezone**.
Both come from `clickhouse.datetime.timezone` when set, else the server's
`timezone()`. A table whose `_valid_to` column carries a different explicit
timezone than the one the connector resolves at runtime would never match
its own open rows (**Gap G-12.02-1**: unverified by any test).

### 3.2 Columns appended in history mode
Appended after the source columns, in this order, **identically on both
paths**:

| Column | Type (record path `ClickHouseAutoCreateTable` = DDL path `MySqlDDLParserListenerImpl`) |
|---|---|
| `_valid_from` | `DateTime[('<tz>')] DEFAULT '2100-01-01 00:00:00'` |
| `_valid_to` | `DateTime[('<tz>')] DEFAULT '2100-01-01 00:00:00'` |
| `_operation` | `LowCardinality(String)` |
| `is_deleted` | `UInt8` (record path: emitted here, then **not** repeated with the engine columns; DDL path: emitted with the engine columns, as in standard mode) |
| `_version` | `UInt64` — every row of the table, whichever statement or release wrote it, carries the **history version domain** of 12.03 §3.5.1: the snowflake encoding of the event's ordering key (`ReplicationHistoryHandler.historyVersion`) |

Rules:
1. `is_deleted` is renamed `_is_deleted` when the source table has a column
   named `is_deleted` (both paths; the engine clause uses the renamed column).
2. Both validity columns carry the sentinel `DEFAULT` on both paths
   (record path: `historyDateTimeColumnType`; DDL path: the
   `DELETED_TIME_COLUMN_DATA_TYPE` literal) — resolved difference D-1. The
   connector always binds `_valid_from` explicitly (12.03 §3.1), so the
   default is only observable for rows inserted by something other than the
   connector.
3. Both paths add the `DateTime` timezone suffix when
   `clickhouse.datetime.timezone` is configured (DDL path:
   `DataTypeConverter.addTimeZoneToDateTimeType`; record path: the same rule
   in `historyDateTimeColumnType`, an unparseable timezone logged and
   ignored on both) — resolved difference D-2; a table gets the same column
   type whichever path created it (08.05 §3.1.1).
4. `ALIAS` columns from `column_type_override.alias.*` are appended after the
   history columns (record path: before; DDL path: after — cosmetic).
5. The comment in the DDL path (`deleted_time DateTime DEFAULT '2149-06-06'`)
   is stale; the emitted literal is the sentinel of §3.1.

### 3.3 Engine
`ReplacingMergeTree(_version, is_deleted)` (or the renamed delete column;
`ReplicatedReplacingMergeTree(...)` when `auto.create.tables.replicated=true`
/ the DDL path's replicated flag). The legacy sign-based engine is never
combined with history mode on the DDL path; on the record path it would emit
`_sign Int8, _version UInt64` **after** the history columns and
`ReplacingMergeTree(_version)` — untested (**Gap G-12.02-2**).

### 3.4 Partition, sorting key, TTL

| Clause | Record path | DDL path |
|---|---|---|
| `PARTITION BY` | `toDate(`_valid_to`)` — **overrides** any schema-override `partition_by` | `toDate(`_valid_to`)` — overrides `partition_by` and the source `PARTITION BY` |
| `ORDER BY` | `(k1,...,kn,`_valid_to`)` where `k1..kn` is the standard-mode key (declared PK, else keyless all-columns fallback, spec 08.05 §3.2) | `(k1,...,kn,`_valid_to`)` on **every** branch — declared key, schema-override `primary_key`, and the `name(N)` regex clean-up (`appendOrderBy`; resolved Gap G-12.02-3) — always as a **flat** tuple: a composite key `(a,b)` renders `(a,b,`_valid_to`)`, not the nested `((a,b),`_valid_to`)` the previous revision emitted (`stripEnclosingParentheses`; resolved Gap G-12.02-4) |
| `PRIMARY KEY` | `(k1,...,kn)` when a declared key exists (without `_valid_to`) | not emitted separately |
| `TTL` | `` `_valid_to` + toIntervalDay(<replication.history.ttl>) `` (resolved difference D-3; it used to hardcode 30) | `` `_valid_to` + toIntervalDay(<replication.history.ttl>) `` |

The sorting key `(k, _valid_to)` is what makes the table an SCD2 table under
`ReplacingMergeTree`: rows of one source key with different `_valid_to`
never collapse into each other, while the open row `(k, sentinel)` is
superseded by any later row at the same `(k, sentinel)` with a higher
`_version` (12.03). The history columns are excluded from the keyless
fallback key (`isConnectorColumn`; 06.05 §3.6 item 1, 08.05 §3.2 item 3).

Golden DDL-path output (from
`MySqlDDLParserListenerImplTest.testReplicationHistoryEnabledDDLTranslation()`;
`replication.history.ttl` at its default 30):

```
CREATE TABLE if not exists `employees`.`test_table`(
  `id` Int32 NOT NULL, `name` String NOT NULL, `created_at` DateTime64(0,'America/Chicago') NOT NULL,
  `_valid_from` DateTime('America/Chicago') DEFAULT '2100-01-01 00:00:00',
  `_valid_to`   DateTime('America/Chicago') DEFAULT '2100-01-01 00:00:00',
  `_operation` LowCardinality(String), `_version` UInt64, `is_deleted` UInt8)
Engine=ReplacingMergeTree(_version,is_deleted)
PARTITION BY toDate(`_valid_to`) ORDER BY (`id`,`_valid_to`) TTL `_valid_to` + toIntervalDay(30)
```

### 3.5 TTL semantics — history retention is bounded
`TTL _valid_to + toIntervalDay(N)` deletes a **closed** version N days after
it was superseded. Open rows (sentinel) never expire. An SCD2 table is
therefore a **sliding window of history**, not an unbounded archive: a
version that was current for a year and was superseded yesterday is dropped
N days from now. `ttl_only_drop_parts` is not set, so expiry is row-level
inside the daily `toDate(_valid_to)` partitions.

### 3.6 ALTER TABLE
There is no history-specific ALTER logic: a replayed `ALTER TABLE ... ADD
COLUMN` (06.04) is applied to the SCD2 table like any other. Historical
(closed) versions receive the new column's `DEFAULT` — the history is not
backfilled, exactly as in standard mode. A primary-key-changing ALTER is
refused in history mode (06.09 §3.2 item 1) because the rebuild copies only
the FINAL live rows and would drop the closed versions.

### 3.7 Idempotency
Both paths emit `CREATE TABLE IF NOT EXISTS` in history mode
(`CreateTableIdempotentTest.testHistoryModeCreateTableIsGuarded()`); a
`TABLE_ALREADY_EXISTS` from a race is classified non-retryable
(`DBMetadataRetryClassificationTest.testTableAlreadyExistsIsNotRetryable()`).

---

## 4. Invariants Preserved
- **Invariant I3 (Eventual Convergence)** holds for the **open rows**: for
  every key, the live row at `(k, sentinel)` under `FINAL` is the current
  MySQL row (12.03, `Replication.History.update_supersedes_open_row`,
  `Replication.History.delete_hides_open_row`). Closed versions are extra
  rows by design and are excluded from the convergence statement; a
  value-level comparison of a mode-2 table must filter
  `_valid_to = sentinel AND is_deleted = 0` (11.02).
- **Invariant I6 (Column Authority)**: the history columns are
  connector-owned metadata, never source columns; a source column named
  `is_deleted` forces the rename of rule 3.2-1 rather than shadowing.
- **Sorting-key rule of 06.05 §3.6 / 08.05 §3.2** (`ORDER BY tuple()` never
  emitted) applies unchanged; history mode only appends `_valid_to` — on
  every branch, as a flat tuple, so the SCD2 property (one row per version
  under `ReplacingMergeTree`) holds for every table the connector creates.
- **Spec 08.05 §3.1.1 (one column type per column)**: both creation paths
  emit the same `_valid_from` / `_valid_to` type, default and TTL.

---

## 5. Verification Criteria
- `MySqlDDLParserListenerImplTest.testReplicationHistoryEnabledDDLTranslation()` — §3.2/§3.4 DDL path golden output (timezone suffix, defaults, engine, partition, sorting key with `_valid_to`, TTL from `replication.history.ttl`).
- `MySqlDDLParserListenerImplTest.testReplicationHistoryAppendsValidToWithPrimaryKeyOverride()` — §3.4: `_valid_to` is appended on the schema-override `primary_key` branch.
- `MySqlDDLParserListenerImplTest.testReplicationHistoryCompositeKeyIsAFlatTuple()` — §3.4: a composite key renders `ORDER BY (a,b,`_valid_to`)`, not a nested tuple.
- `ClickHouseAutoCreateTableHistoryDdlTest.historyModeEmitsDefaultsTimezoneAndConfiguredTtl()` — §3.2/§3.4 record path: both validity columns carry `DateTime('<tz>') DEFAULT '2100-01-01 00:00:00'`, and the TTL uses the configured `replication.history.ttl`.
- `ClickHouseAutoCreateTableHistoryDdlTest.standardModeEmitsNoHistoryColumns()` — the history columns, partition and TTL appear only when `replication.history.enable=true`.
- `CreateTableIdempotentTest.testHistoryModeCreateTableIsGuarded()` — §3.7.
- `DBMetadataRetryClassificationTest.testTableAlreadyExistsIsNotRetryable()` — §3.7.
- `BinLogHistoryIT.testBinLogHistory()` — record/DDL path end to end: the data table inside the history database carries `_valid_to`, `_operation`, `_version`, `is_deleted` (its `validateTemporalTrackingColumns` does not check `_valid_from`).
- `VersionHistoryIT.testValidToValidFromColumnsOnUpdateDelete()` — an inserted row has `is_deleted = 0`, `_valid_to` in year 2100, `_valid_from` set (§3.1 sentinel observable).
- `ClickHouseAutoCreateTableIT.testCreateMergeTreeHistoryTable()` — record path; **`@Disabled`**, asserts only the absence of an exception (11.03 §6.1).
- Lean: `Replication.History.at_most_one_live_row_per_sort_key` — the sorting key `(k, _valid_to)` yields at most one live row per key and validity end.
- The end-to-end history suite (three connectors side by side, kill -9 restart, single-threaded variants, degenerate combination) — tables created by either path accept the write protocol of 12.03 and survive a restart.
- **Coverage gap**: no test exercises the timezone mismatch of Gap G-12.02-1 or the legacy sign engine with history columns (Gap G-12.02-2).

---

## 6. Differences between the two creation paths and gaps
| Id | Status | Old behaviour | New behaviour | Witness |
|---|---|---|---|---|
| D-1 | **Resolved** | record path `_valid_from DateTime` without default | both paths `_valid_from ... DEFAULT '<sentinel>'` (§3.2 rule 2) | `ClickHouseAutoCreateTableHistoryDdlTest.historyModeEmitsDefaultsTimezoneAndConfiguredTtl()` |
| D-2 | **Resolved** | record path bare `DateTime`; DDL path `DateTime('<tz>')` | both paths add the configured timezone (§3.2 rule 3) | same test |
| D-3 | **Resolved** | record path `TTL ... toIntervalDay(30)` hardcoded | both paths `toIntervalDay(replication.history.ttl)` (§3.4) | same test |
| G-12.02-1 | Open | both paths: the open-row predicate depends on the statement timezone equalling the column timezone | unchanged; untested | — |
| G-12.02-2 | Open | legacy sign engine + history columns on the record path | unchanged; untested | — |
| G-12.02-3 | **Resolved** | DDL path: `_valid_to` not appended to `ORDER BY` on the `primary_key`-override and regex branches, so every version of a key collapsed | `_valid_to` appended on every branch (§3.4, `appendOrderBy`) | `MySqlDDLParserListenerImplTest.testReplicationHistoryAppendsValidToWithPrimaryKeyOverride()` |
| G-12.02-4 | **Resolved** | DDL path rendered a composite key as the nested tuple `((a,b),`_valid_to`)` (server sorting key `(a,b),_valid_to`) | flat tuple `(a,b,`_valid_to`)` (§3.4, `stripEnclosingParentheses`) | `MySqlDDLParserListenerImplTest.testReplicationHistoryCompositeKeyIsAFlatTuple()` |

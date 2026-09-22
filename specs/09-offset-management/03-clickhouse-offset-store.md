# Spec 09.03: ClickHouse-Backed Durable Offset Storage (`replica_source_info`)

## 1. Executive Summary & Purpose
Specifies how replication offsets are persisted in a ClickHouse table (conventionally `replica_source_info`) through Debezium's own JDBC offset store, how the connector configures and reads that table, and which parts of the contract are configuration rather than code.

---

## 2. Codebase Mapping on 2.11.0
- **Offset store implementation**: Debezium's `io.debezium.storage.jdbc.offset.JdbcOffsetBackingStore`, selected by the `offset.storage` property. The connector ships **no** offset backing store class of its own (there is no `JdbcOffsetBackingStore` under `sink-connector-lightweight/`).
- **Table name**: property `offset.storage.jdbc.table.name` (Debezium `JdbcOffsetBackingStoreConfig.PROP_TABLE_NAME`), e.g. `altinity_sink_connector.replica_source_info`.
- **Table DDL**: property `offset.storage.jdbc.offset.table.ddl` (with `%s` for the table name) — examples in `sink-connector-lightweight/docker/config_postgres_local.yml` (line 74, ReplacingMergeTree) and `sink-connector-lightweight/docker/config_keepermap_storage.yml` (line 23, KeeperMap `ON CLUSTER`); the bundled default `sink-connector-lightweight/src/main/resources/config.properties` uses the older key spelling `offset.storage.jdbc.table.ddl`.
- **Connector-side reader**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumOffsetStorage.java` — `getDebeziumLatestRecordTimestamp(Properties, Connection)`, `getDebeziumStorageStatusQuery(Properties, Connection)` / `offsetValueQuery(Properties)` / `isKeeperMapOffsetTable(Properties)`, plus `updateBinLogInformation` / `updateLsnInformation` / `deleteOffsetStorageRow` for the REST API's position edits (§3.4).
- **REST position edits**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/api/DebeziumEmbeddedRestApi.java` — `POST /binlog`, `POST /lsn` (§3.4).
- **Database bootstrap**: `DebeziumJdbcStorageOperations.createDatabaseForDebeziumStorage(Connection, Properties)` in `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumJdbcStorageOperations.java` creates the database that hosts the table; Debezium creates the table itself from the configured DDL.

---

## 3. Operational Specification

### 3.1 Table DDL (configuration, not code)
The connector does not hardcode the offset table's shape. The ReplacingMergeTree example shipped for local use is:
```sql
CREATE TABLE if not exists %s
(
    `id` String,
    `offset_key` String,
    `offset_val` String,
    `record_insert_ts` DateTime,
    `record_insert_seq` UInt64,
    `_version` UInt64 MATERIALIZED toUnixTimestamp64Nano(now64(9))
)
ENGINE = ReplacingMergeTree(_version)
ORDER BY offset_key
```
The KeeperMap example uses `ENGINE = KeeperMap('/asc_offsets201', 10) PRIMARY KEY offset_key` on cluster. Every shipped DDL sorts (or keys) by `offset_key`, which is what makes the latest checkpoint win.

### 3.2 Reads
- **Debezium load on start**: performed by `JdbcOffsetBackingStore` using the configured `offset.storage.jdbc.table.select` (the shipped `docker/config.yml` sets `SELECT id, offset_key, offset_val FROM %s FINAL ORDER BY record_insert_ts, record_insert_seq`).
- **Connector status reads** (`DebeziumOffsetStorage`):
  - `select max(record_insert_ts) from <table>` (`getDebeziumLatestRecordTimestamp`, used by the restart monitor) — no `FINAL` needed: the maximum over unmerged duplicates is the newest row.
  - `getDebeziumStorageStatusQuery` (the base row for a REST position edit) reads the **newest** row for the key, built by `offsetValueQuery`:
    `select offset_val from <table> FINAL where offset_key='<key>' order by record_insert_ts desc, record_insert_seq desc limit 1`.
    On a ReplacingMergeTree with unmerged parts a plain `select ... where offset_key=...` returned an arbitrary one of several checkpoints, and the REST edit then rewrote the table from a STALE base. The ordering is the one Debezium's own load query uses (`ORDER BY record_insert_ts, record_insert_seq`, last row wins). When the configured offset DDL (`offset.storage.jdbc.table.ddl`, or the pre-2.7.1 `offset.storage.jdbc.offset.table.ddl`) declares a `KeeperMap` engine — one row per key, `FINAL` rejected — the same query is issued without `FINAL` (`isKeeperMapOffsetTable`).
- The offset key is `["<connector name>",{"server":"embeddedconnector"}]` (`getOffsetKey`), matching the topic prefix Debezium's embedded engine writes.

### 3.3 Writes
Debezium's `JdbcOffsetBackingStore` inserts a new row per flush (`offset_key`, `offset_val`, `record_insert_ts`, `record_insert_seq`, and an `id`); the connector never writes the table directly except through the REST API position-edit path (`deleteOffsetStorageRow` followed by Debezium's next flush). Under ReplacingMergeTree the newest row per `offset_key` supersedes earlier checkpoints on merge / `FINAL`.

---

### 3.4 REST position edits are validated (`POST /binlog`, `POST /lsn`)
Both endpoints refuse (`400`) while replication is running, read the stored row (§3.2), apply the edit in memory, delete the row and insert the result (§3.3). The edit itself is validated in `DebeziumOffsetStorage`; a malformed edit is answered `400 {"error": ...}` and nothing is written — previously it was written verbatim and Debezium failed at the next start, or worse, started from the wrong place:

| Edit | Rule |
|---|---|
| nothing given | refused |
| `binlog_file` without `binlog_position`, or vice versa | refused |
| `binlog_position` | must be a non-negative integer; stored as a JSON **number** (was stored as a string) |
| `binlog_file` + `binlog_position` | stored; `row` and `event` are **reset to 0** — they count progress inside the OLD position's event/transaction and, kept, made Debezium skip that many rows of the first event at the NEW position; without a `gtid` in the same request the stored `gtids` is **removed**, because Debezium resumes from a GTID set whenever the offset carries one and a stale set silently overrode the file/position |
| `gtid` alone | replaces `gtids`; stored file/position kept |
| `lsn` | required; decimal, or PostgreSQL `X/Y` = `(X << 32) \| Y` in hex (the earlier parser kept only `Y`) |

The `sink-connector-client` CLI performs no validation of its own; the server side is authoritative.

---

## 4. Invariants Preserved
- **Crash Recovery Parity**: because offsets are flushed only after rows are acknowledged as written (specs 09.01, 09.02), restarting resumes from the last durably acknowledged position; anything after it is redelivered (spec 02.04).
- **Invariant I11**: the table format and Debezium version are unchanged across 2.8.0 → 2.11.0 (spec 02.06 §3.2).

---

## 5. Verification Criteria
- `OffsetTableDdlSortKeyTest.everyOffsetTableDdlSortsByOffsetKey()`, `OffsetTableDdlSortKeyTest.offsetDdlKeepsRequiredColumns()` — every shipped offset DDL keys by `offset_key` and keeps the required columns.
- `OffsetStorageDatabaseNameTest` — database/table name resolution for the store.
- `DebeziumJdbcStorageOperationsTest.createDatabaseForDebeziumStorage_throwsOnMissingOffsetTableName()`, `DebeziumJdbcStorageOperationsTest.getLatestRecordTimestamp_returnsSentinelWhenQueryHasNoResult()`.
- `DebeziumChangeEventCaptureTest.testUpdateBingLogInformation()` (INVERTED: it pinned the string position and the stale skip counters; it now expects a numeric position and `row`/`event` reset), `DebeziumChangeEventCaptureTest.testUpdateLsn()` — REST position edits on the stored offset JSON.
- `OffsetEditValidationTest` — §3.4: file+position edit is numeric, drops `gtids` and resets the skip counters; GTID-only edit keeps file/position; non-numeric / negative position, half a coordinate and an empty edit are refused; `X/Y` LSN honours the high word, malformed LSN refused; §3.2: `offsetValueQuery` uses `FINAL ORDER BY record_insert_ts DESC, record_insert_seq DESC LIMIT 1`, and no `FINAL` for a KeeperMap DDL.
- `OffsetManagementIT` — end to end restart from the stored offset.

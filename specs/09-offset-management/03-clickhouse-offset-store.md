# Spec 09.03: ClickHouse-Backed Durable Offset Storage (`replica_source_info`)

## 1. Executive Summary & Purpose
Specifies how replication offsets are persisted in a ClickHouse table (conventionally `replica_source_info`) through Debezium's own JDBC offset store, how the connector configures and reads that table, and which parts of the contract are configuration rather than code.

---

## 2. Codebase Mapping on 2.11.0
- **Offset store implementation**: Debezium's `io.debezium.storage.jdbc.offset.JdbcOffsetBackingStore`, selected by the `offset.storage` property. The connector ships **no** offset backing store class of its own (there is no `JdbcOffsetBackingStore` under `sink-connector-lightweight/`).
- **Table name**: property `offset.storage.jdbc.table.name` (Debezium `JdbcOffsetBackingStoreConfig.PROP_TABLE_NAME`), e.g. `altinity_sink_connector.replica_source_info`.
- **Table DDL**: property `offset.storage.jdbc.offset.table.ddl` (with `%s` for the table name) — examples in `sink-connector-lightweight/docker/config_postgres_local.yml` (line 74, ReplacingMergeTree) and `sink-connector-lightweight/docker/config_keepermap_storage.yml` (line 23, KeeperMap `ON CLUSTER`); the bundled default `sink-connector-lightweight/src/main/resources/config.properties` uses the older key spelling `offset.storage.jdbc.table.ddl`.
- **Connector-side reader**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumOffsetStorage.java` — `getDebeziumLatestRecordTimestamp(Properties, Connection)` (line 129) and `getDebeziumStorageStatusQuery(Properties, Connection)` (line 151), plus `updateBinLogInformation` / `updateLsnInformation` / `deleteOffsetStorageRow` for the REST API's position edits.
- **Database bootstrap**: `DebeziumJdbcStorageOperations.createDatabaseForDebeziumStorage(Connection, Properties)` in `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumJdbcStorageOperations.java` creates the database that hosts the table; Debezium creates the table itself from the configured DDL.
- **Version high-water mark (connector-owned, same database)**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/VersionHighWaterMark.java` — table `replica_version_high_water` (§3.4), created and written by the connector, read by `DebeziumChangeEventCapture.seedVersionFloor` at engine start (spec 02.02 §3.5).

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
- **Connector status reads** (`DebeziumOffsetStorage`): plain selects **without `FINAL`** —
  `select max(record_insert_ts) from <table>` (`getDebeziumLatestRecordTimestamp`, used by the restart monitor) and
  `select offset_val from <table> where offset_key='<key>'` (`getDebeziumStorageStatusQuery`, used by the REST status endpoint). On a ReplacingMergeTree table these may observe several unmerged rows for one key; callers take the value the driver returns for the first row.
- The offset key is `["<connector name>",{"server":"embeddedconnector"}]` (`getOffsetKey`), matching the topic prefix Debezium's embedded engine writes.

### 3.3 Writes
Debezium's `JdbcOffsetBackingStore` inserts a new row per flush (`offset_key`, `offset_val`, `record_insert_ts`, `record_insert_seq`, and an `id`); the connector never writes the table directly except through the REST API position-edit path (`deleteOffsetStorageRow` followed by Debezium's next flush). Under ReplacingMergeTree the newest row per `offset_key` supersedes earlier checkpoints on merge / `FINAL`.

### 3.4 The version high-water table (`replica_version_high_water`)
Next to the offset table, in the same database, the connector keeps the durable
version horizon of spec 02.02 §3.5. It is connector-owned code, not
configuration:
```sql
CREATE TABLE IF NOT EXISTS <offset database>.replica_version_high_water
(
    `offset_table`       String,
    `high_water_version` UInt64,
    `updated_at`         DateTime64(3) DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(high_water_version)
ORDER BY offset_table
```
- **Key**: the fully qualified offset table name (`offset.storage.jdbc.table.name`), so several connectors sharing one offset database keep separate marks. No new configuration key exists; the database is the one parsed from `offset.storage.jdbc.table.name` by `DebeziumJdbcStorageOperations`.
- **Write** (`VersionHighWaterMark.cover`, dispatch thread, before handoff): `INSERT INTO ... (offset_table, high_water_version) VALUES (?, ?)` with the new horizon, only when an assigned version exceeds the persisted horizon — at most once per ~5 s of source time under load, never on an idle source. The insert is synchronous and retried; if it cannot succeed the batch fails and the engine stops (spec 02.02 §3.5 (1)).
- **Read** (`VersionHighWaterMark.load`, engine start): `SELECT max(high_water_version) FROM ... WHERE offset_table = ?` — the maximum, not `FINAL`, so unmerged rows are harmless. An absent row reads as `0` and triggers the target scan of spec 02.02 §3.5 (2).
- **Meaning of the value**: an upper bound on every `_version` this connector has ever handed to the writers (sequence-domain value, spec 02.01 §3.2), not the last version written. It may legitimately exceed every stored `_version` by up to the horizon head-room.
- **Not replicated**: the table is created with a plain `ReplacingMergeTree`. If the connector is pointed at another ClickHouse replica the mark is absent there and the first start falls back to the target scan (the targets are replicated); the table is then created on that replica.
- **Downgrade**: older releases neither read nor write the table (spec 02.06 §3.2 item 5).

---

## 4. Invariants Preserved
- **Crash Recovery Parity**: because offsets are flushed only after rows are acknowledged as written (specs 09.01, 09.02), restarting resumes from the last durably acknowledged position; anything after it is redelivered (spec 02.04).
- **Invariant I2 across a restart**: the high-water table gives the new run its version floor (§3.4, spec 02.02 §3.5).
- **Invariant I11**: the offset table format and Debezium version are unchanged across 2.8.0 → 2.11.0 (spec 02.06 §3.2); the high-water table is additive.

---

## 5. Verification Criteria
- `VersionHighWaterMarkTest.tableIsCreatedNextToTheOffsetTable()` — the DDL of §3.4 is issued against the offset database, keyed by the offset table name.
- `VersionHighWaterMarkTest.horizonIsWrittenAheadOfHandoffAndReusedUntilExceeded()`, `VersionHighWaterMarkTest.loadReadsTheHighestPersistedMark()`, `VersionHighWaterMarkTest.horizonWriteFailureIsLoud()` — write, read and failure behaviour of §3.4.
- `OffsetTableDdlSortKeyTest.everyOffsetTableDdlSortsByOffsetKey()`, `OffsetTableDdlSortKeyTest.offsetDdlKeepsRequiredColumns()` — every shipped offset DDL keys by `offset_key` and keeps the required columns.
- `OffsetStorageDatabaseNameTest` — database/table name resolution for the store.
- `DebeziumJdbcStorageOperationsTest.createDatabaseForDebeziumStorage_throwsOnMissingOffsetTableName()`, `DebeziumJdbcStorageOperationsTest.getLatestRecordTimestamp_returnsSentinelWhenQueryHasNoResult()`.
- `DebeziumChangeEventCaptureTest.testUpdateBingLogInformation()`, `DebeziumChangeEventCaptureTest.testUpdateLsn()` — REST position edits on the stored offset JSON.
- `OffsetManagementIT` — end to end restart from the stored offset.

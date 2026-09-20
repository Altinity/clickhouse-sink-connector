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

---

## 4. Invariants Preserved
- **Crash Recovery Parity**: because offsets are flushed only after rows are acknowledged as written (specs 09.01, 09.02), restarting resumes from the last durably acknowledged position; anything after it is redelivered (spec 02.04).
- **Invariant I11**: the table format and Debezium version are unchanged across 2.8.0 → 2.11.0 (spec 02.06 §3.2).

---

## 5. Verification Criteria
- `OffsetTableDdlSortKeyTest.everyOffsetTableDdlSortsByOffsetKey()`, `OffsetTableDdlSortKeyTest.offsetDdlKeepsRequiredColumns()` — every shipped offset DDL keys by `offset_key` and keeps the required columns.
- `OffsetStorageDatabaseNameTest` — database/table name resolution for the store.
- `DebeziumJdbcStorageOperationsTest.createDatabaseForDebeziumStorage_throwsOnMissingOffsetTableName()`, `DebeziumJdbcStorageOperationsTest.getLatestRecordTimestamp_returnsSentinelWhenQueryHasNoResult()`.
- `DebeziumChangeEventCaptureTest.testUpdateBingLogInformation()`, `DebeziumChangeEventCaptureTest.testUpdateLsn()` — REST position edits on the stored offset JSON.
- `OffsetManagementIT` — end to end restart from the stored offset.

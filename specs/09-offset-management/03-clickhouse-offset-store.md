# Spec 09.03: ClickHouse-Backed Durable Offset Storage (`replica_source_info`)

## 1. Executive Summary & Purpose
Specifies how replication offsets are persisted in a ClickHouse table (conventionally `replica_source_info`) through Debezium's own JDBC offset store, how the connector configures and reads that table, and which parts of the contract are configuration rather than code.

---

## 2. Codebase Mapping on 2.11.0
- **Offset store implementation**: Debezium's `io.debezium.storage.jdbc.offset.JdbcOffsetBackingStore`, selected by the `offset.storage` property. The connector ships **no** offset backing store class of its own (there is no `JdbcOffsetBackingStore` under `sink-connector-lightweight/`).
- **Table name**: property `offset.storage.jdbc.table.name` (Debezium `JdbcOffsetBackingStoreConfig.PROP_TABLE_NAME`), e.g. `altinity_sink_connector.replica_source_info`. Debezium 3.1.3 still reads the pre-2.7.1 spellings `offset.storage.jdbc.offset.table.*` and `schema.history.internal.jdbc.schema.history.table.*`, but logs `Validation error ... Using deprecated config option` at ERROR for each of them on every start; the shipped configuration files, deployment templates and test environments use the current names, and only `DebeziumOffsetStorage.isKeeperMapOffsetTable` still consults the old DDL key for configurations written before 2.7.1.
- **Table DDL**: property `offset.storage.jdbc.table.ddl` (with `%s` for the table name) — examples in `sink-connector-lightweight/docker/config_postgres_local.yml` (line 74, ReplacingMergeTree) and `sink-connector-lightweight/docker/config_keepermap_storage.yml` (line 23, KeeperMap `ON CLUSTER`); the bundled default `sink-connector-lightweight/src/main/resources/config.properties` uses the older key spelling `offset.storage.jdbc.table.ddl`.
- **Connector-side reader**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumOffsetStorage.java` — `getDebeziumLatestRecordTimestamp(Properties, Connection)`, `getDebeziumStorageStatusQuery(Properties, Connection)` / `offsetValueQuery(Properties)` / `isKeeperMapOffsetTable(Properties)`, plus `updateBinLogInformation` / `updateLsnInformation` / `deleteOffsetStorageRow` for the REST API's position edits (§3.4).
- **REST position edits**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/api/DebeziumEmbeddedRestApi.java` — `POST /binlog`, `POST /lsn` (§3.4).
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
- **Connector status reads** (`DebeziumOffsetStorage`):
  - `select max(record_insert_ts) from <table>` (`getDebeziumLatestRecordTimestamp`, used by the restart monitor) — no `FINAL` needed: the maximum over unmerged duplicates is the newest row.
  - `getDebeziumStorageStatusQuery` (the base row for a REST position edit) reads the **newest** row for the key, built by `offsetValueQuery`:
    `select offset_val from <table> FINAL where offset_key='<key>' order by record_insert_ts desc, record_insert_seq desc limit 1`.
    On a ReplacingMergeTree with unmerged parts a plain `select ... where offset_key=...` returned an arbitrary one of several checkpoints, and the REST edit then rewrote the table from a STALE base. The ordering is the one Debezium's own load query uses (`ORDER BY record_insert_ts, record_insert_seq`, last row wins). When the configured offset DDL (`offset.storage.jdbc.table.ddl`, or the pre-2.7.1 `offset.storage.jdbc.table.ddl`) declares a `KeeperMap` engine — one row per key, `FINAL` rejected — the same query is issued without `FINAL` (`isKeeperMapOffsetTable`).
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
- **Logging** (`VersionHighWaterMark.cover`): a horizon move is reported at INFO **once per `HORIZON_LOG_INTERVAL_MS` (300 000 ms) of the connector clock** while the horizon keeps moving — the first move of a run at once, each later line carrying the number of moves since the previous one and the horizon now in force — and at DEBUG for every move. The horizon is a monotonic counter that moves once per ~5 s of source time under load and once per heartbeat row on an idle source; a line per move was 9–17 thousand INFO lines a day per connector that said nothing new (the paced form of spec 01.05 §3.4 item 6). Pacing never skips a persist: every move is written before the row is handed off, logged or not.
- **Read** (`VersionHighWaterMark.load`, engine start): `SELECT max(high_water_version) FROM ... WHERE offset_table = ?` — the maximum, not `FINAL`, so unmerged rows are harmless. An absent row reads as `0` and the start is seeded from the connector clock plus head-room (spec 02.02 §3.5 (2)); no target table is read (Invariant I14).
- **Meaning of the value**: an upper bound on every `_version` this connector has ever handed to the writers (sequence-domain value, spec 02.01 §3.2), not the last version written. It may legitimately exceed every stored `_version` by up to the horizon head-room.
- **Not replicated**: the table is created with a plain `ReplacingMergeTree`. If the connector is pointed at another ClickHouse replica the mark is absent there and the first start is seeded from the connector clock (spec 02.02 §3.5 (2)); the table is then created on that replica.
- **Downgrade**: older releases neither read nor write the table (spec 02.06 §3.2 item 5).

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
- **Invariant I2 across a restart**: the high-water table gives the new run its version floor (§3.4, spec 02.02 §3.5).
- **Invariant I11**: the offset table format and Debezium version are unchanged across 2.8.0 → 2.11.0 (spec 02.06 §3.2); the high-water table is additive.

---

## 5. Verification Criteria
- `VersionHighWaterMarkTest.tableIsCreatedNextToTheOffsetTable()` — the DDL of §3.4 is issued against the offset database, keyed by the offset table name.
- `VersionHighWaterMarkTest.horizonIsWrittenAheadOfHandoffAndReusedUntilExceeded()`, `VersionHighWaterMarkTest.loadReadsTheHighestPersistedMark()`, `VersionHighWaterMarkTest.horizonWriteFailureIsLoud()` — write, read and failure behaviour of §3.4.
- `VersionHighWaterMarkTest.horizonMovesAreLoggedPacedNotPerMove()` — the "Logging" rule of §3.4: twelve moves inside one interval produce one INFO line (the first move) and eleven DEBUG lines while every move is still persisted; the first move after the interval is one INFO line carrying the count since the previous line.
- `OffsetTableDdlSortKeyTest.everyOffsetTableDdlSortsByOffsetKey()`, `OffsetTableDdlSortKeyTest.offsetDdlKeepsRequiredColumns()` — every shipped offset DDL keys by `offset_key` and keeps the required columns.
- `OffsetStorageDatabaseNameTest` — database/table name resolution for the store.
- `DebeziumJdbcStorageOperationsTest.createDatabaseForDebeziumStorage_throwsOnMissingOffsetTableName()`, `DebeziumJdbcStorageOperationsTest.getLatestRecordTimestamp_returnsSentinelWhenQueryHasNoResult()`.
- `DebeziumChangeEventCaptureTest.testUpdateBingLogInformation()` (INVERTED: it pinned the string position and the stale skip counters; it now expects a numeric position and `row`/`event` reset), `DebeziumChangeEventCaptureTest.testUpdateLsn()` — REST position edits on the stored offset JSON.
- `OffsetEditValidationTest` — §3.4: file+position edit is numeric, drops `gtids` and resets the skip counters; GTID-only edit keeps file/position; non-numeric / negative position, half a coordinate and an empty edit are refused; `X/Y` LSN honours the high word, malformed LSN refused; §3.2: `offsetValueQuery` uses `FINAL ORDER BY record_insert_ts DESC, record_insert_seq DESC LIMIT 1`, and no `FINAL` for a KeeperMap DDL.
- `OffsetManagementIT` — end to end restart from the stored offset.

---

## 6. Failure Modes & Recovery
The offset store is Debezium's `JdbcOffsetBackingStore` writing into a ClickHouse table that the connector does not own and barely checks. Its failure modes are mostly silent, because Debezium reports a failed flush at WARN and carries on, and they are expensive, because a missing, stale or unusable offset turns the next start into an unbounded replay or a full reload. The connector's own contribution — the REST position edit — is currently the most dangerous way to touch the table. Until the DEFECTs below are fixed, the operator procedure for any re-position is: stop the PROCESS, write the new offset with an insert-only statement (the spec 11.04 `rewind-sql` form), start the process, verify with `show_replica_status`. Offset key: `["<name>",{"server":"embeddedconnector"}]` (`DebeziumOffsetStorage.getOffsetKey`), where `<name>` is the connector's `name` property.

- **FM-09.03-1 Offset writes fail while rows keep replicating**
  - **Trigger**: the offset table rejects or times out the insert while the target tables accept theirs: `TOO_MANY_PARTS` on the offset table (one insert per acknowledged unit, see Behaviour), a read-only replicated or KeeperMap offset table after Keeper loss, `offset.storage.jdbc.url` pointing at another server that is down, permissions, disk full on that volume.
  - **Behaviour**: the engine is built with `OffsetCommitPolicy.always()` (`DebeziumChangeEventCapture.setupDebeziumEventCapture`), so each acknowledged unit requests a flush; `JdbcOffsetBackingStore.save` runs the configured delete and inserts one row per key, retried by `RetriableConnection` (5 attempts, 3 000 ms apart — Debezium defaults read from the 3.1.3 jar); `AsyncEmbeddedEngine.commitOffsets` waits `offset.flush.timeout.ms` (5 000 ms), then cancels the flush and returns — no exception reaches the connector. Rows keep being written; the durable position stays at the last successful flush; `DebeziumOffsetManagement.acknowledgements` still counts the units, so the engine's retry budget reads it as progress. Throughput collapses under the lock (spec 09.02 FM-09.02-2).
  - **Detection**: Debezium WARN `Attempt n to call 'Saving offset' failed.`, WARN `Flush of the offsets failed, canceling the flush.`, and ERROR `SQL Exception while trying to reconnect: ...` only when the connection itself fails. No connector ERROR, no metric. DEFECT.
  - **Blast radius**: no loss while the process lives; a restart replays everything since the last successful flush — unbounded, the opposite of Invariant I15's "the one in-flight transaction". The replay converges (spec 02.04 FM-02.04-1).
  - **Recovery**: fix the offset table (let merges catch up or `OPTIMIZE TABLE <offset table> FINAL`, restore Keeper, free disk, grant `INSERT`); the next acknowledged unit writes the current position — self-healing, no restart. Do not restart the connector while the flush is failing.
  - **RTO**: cause removed → one flush (≤ 5 s); if a restart intervened, replay of the whole failure window (unbounded); unmeasured.
  - **Test**: GAP: a test that repeated offset-flush failures while rows are acknowledged are reported at ERROR (and stop the engine after a bounded number of failures).
  - **DEFECT**: a frozen durable offset under a running pipeline is visible only as Debezium WARN lines.

- **FM-09.03-2 An older checkpoint wins the read**
  - **Trigger**: several rows per key exist (one per flush) and the one the store reads is not the newest. With the shipped ReplacingMergeTree DDL (`_version UInt64 MATERIALIZED toUnixTimestamp64Nano(now64(9))`, `SELECT ... FINAL`) the winner is decided by the ClickHouse SERVER clock at insert: a clock stepped back, inserts spread over replicas with skewed clocks, or a read served by a replica that has not yet received the latest insert (replicated table, no `insert_quorum`, a load balancer). With a select that omits `FINAL` (Debezium's default `ORDER BY record_insert_ts, record_insert_seq`), the winner is decided by the CONNECTOR clock at second precision (`record_insert_ts` is `DateTime`) and by `record_insert_seq`, a per-store counter that restarts at 1 at every engine start: a restart within the same second, or a connector clock stepped back, puts an older row last.
  - **Behaviour**: `JdbcOffsetBackingStore.load` puts every returned row into a map in result order; the last row per key wins. The engine resumes from the older position.
  - **Detection**: none. DEFECT. Debezium logs the loaded position (`Found previous offset {...}`); comparing it with the last `***** BATCH marked as processed to debezium ****Binlog file:... Binlog position:... GTID:...` INFO line before the stop is manual.
  - **Blast radius**: a replay from the older position (converges, spec 02.04 FM-02.04-1); if that binlog is purged, FM-09.03-6.
  - **Recovery**: before starting after an incident, read the stored position (`show_replica_status`, or `SELECT offset_val FROM <offset table> FINAL WHERE offset_key = '<key>'`) and compare it with the last acknowledged position in the log; if it is behind and the replay is too long, write the logged position with the insert-only statement of FM-09.03-3. Prevention: NTP on the ClickHouse servers; a KeeperMap offset table (`docker/config_keepermap_storage.yml`) or `insert_quorum` for a replicated one; keep `FINAL` in `offset.storage.jdbc.table.select`.
  - **RTO**: replay proportional to the staleness, unbounded; unmeasured.
  - **Test**: `OffsetEditValidationTest.offsetReadTakesTheNewestRow()`, `OffsetEditValidationTest.keeperMapOffsetReadHasNoFinal()` (the connector's own read), `OffsetTableDdlSortKeyTest.everyOffsetTableDdlSortsByOffsetKey()`; GAP: Debezium's load against rows whose `_version` order disagrees with their insert order.
  - **DEFECT**: the resume point depends on server clocks and replica freshness, silently.

- **FM-09.03-3 No offset for the key at start**
  - **Trigger**: the row is gone or unreachable: table truncated or dropped, `name` changed (a different offset key), `offset.storage.jdbc.table.name` changed, the connector pointed at a replica without the table, `delete_offsets` (FM-09.03-5), a failed REST edit (FM-09.03-4).
  - **Behaviour**: Debezium finds no offset (`No previous offset found`, level not verified) and applies `snapshot.mode`: the shipped `initial` re-snapshots every included table (hours at production size; rows converge); a no-data mode streams from the source's CURRENT position — every change between the lost offset and now is skipped. The connector does not check whether the targets already hold replicated data.
  - **Detection**: Debezium's `No previous offset found` and the snapshot start; no connector ERROR. DEFECT.
  - **Blast radius**: with `initial`, a full reload (duplicates collapse); with a no-data mode, silent loss of the gap.
  - **Recovery**: stop the process before it starts streaming. Take the last acknowledged position from the connector log (`***** BATCH marked as processed to debezium ****Binlog file:<f> Binlog position:<p> GTID:<g>`) and insert it: `INSERT INTO <offset table> (id, offset_key, offset_val, record_insert_ts, record_insert_seq) VALUES (generateUUIDv4(), '["<name>",{"server":"embeddedconnector"}]', '{"ts_sec":<s>,"file":"<f>","pos":<p>,"row":0,"event":0,"server_id":<id>}', now(), 1)` (add `"gtids":"<set>"` in GTID mode); start the process; verify with `show_replica_status`. If no position is known: re-snapshot, or resync table by table (spec 11.04) after positioning at the current source position.
  - **RTO**: manual insert ~5 min + restart ~20 s + replay since that position; without a known position, a full reload (unbounded). Unmeasured.
  - **Test**: GAP: a start with an empty offset table and non-empty target tables must refuse (or require an explicit override) instead of snapshotting or skipping.
  - **DEFECT**: losing one offset row silently turns a restart into a full reload or a gap.

- **FM-09.03-4 REST position edit deletes the offset and inserts elsewhere**
  - **Trigger**: `POST /binlog` or `POST /lsn` — `sink-connector-client change_replication_source` / `lsn` (`sink-connector-client/main.go`), or the REST call directly — with a database-qualified `offset.storage.jdbc.table.name`, which every shipped configuration uses.
  - **Behaviour**: `DebeziumJdbcStorageOperations.updateDebeziumStorageStatus` reads the base row, runs `DebeziumOffsetStorage.deleteOffsetStorageRow` (`delete from <db>.<table> where offset_key='<key>'`), then `updateDebeziumStorageRow` with the table name WITHOUT its database (`splitTableName`) on the REST connection, whose default database is `system` (`DebeziumEmbeddedRestApi.getDatabaseConnection`). The INSERT targets `system.<table>` and fails after the DELETE succeeded; even when it resolves, a failure between the two statements leaves the key with no row.
  - **Detection**: the handler catches only `IllegalArgumentException`; the `SQLException` becomes an HTTP 500. The client treats only 400 as failure (`handleUpdateBinLogAction` prints the body and returns success). The next start shows FM-09.03-3's `No previous offset found`.
  - **Blast radius**: the connector's offset row is gone: FM-09.03-3 at the next start.
  - **Recovery**: do not use `change_replication_source` / `lsn` on 2.11.0; write offsets with the insert-only statement of FM-09.03-3 (the spec 11.04 `rewind-sql` form) while the process is stopped. If the edit already ran, insert the intended position the same way before starting.
  - **RTO**: manual insert ~5 min + restart ~20 s; unmeasured.
  - **Test**: `OffsetPositionEditTargetTableTest.binlogEditDeletesFromTheQualifiedOffsetTable()`; `OffsetPositionEditTargetTableTest.binlogEditInsertsIntoTheTableItDeletedFrom()`, `OffsetPositionEditTargetTableTest.lsnEditInsertsIntoTheTableItDeletedFrom()`, `OffsetPositionEditTargetTableTest.failedEditLeavesTheStoredOffsetInPlace()` (disabled; fail on 2.11.0).
  - **DEFECT**: the documented re-positioning command destroys the offset it is meant to change.

- **FM-09.03-5 `delete_offsets` while replication is running**
  - **Trigger**: `DELETE /offsets` (`sink-connector-client delete_offsets`) without stopping replication first.
  - **Behaviour**: unlike `POST /binlog`, the handler does not check `ReplicationStatusSingleton.isReplicationRunning()`; `deleteOffsets` deletes the row. The running engine still holds the offset in memory and its next flush — the next acknowledged unit, or the next quiescent heartbeat (≤ 5 s) — writes it back. If the process stops before that flush, the next start has no offset.
  - **Detection**: the client prints `Offsets deleted successfully` either way; nothing reports the rewrite or the missing row. DEFECT.
  - **Blast radius**: either nothing happened (the operator believes otherwise), or FM-09.03-3 at the next start.
  - **Recovery**: `stop_replica`; confirm `Replica_Running` is false in `show_replica_status`; `delete_offsets`; confirm the row is gone; restart the process.
  - **RTO**: procedural; unmeasured.
  - **Test**: GAP: a REST test that `DELETE /offsets` is refused with 400 while replication is running.
  - **DEFECT**: a destructive offset operation is accepted in a state where its effect is undefined.

- **FM-09.03-6 Stored position unusable on the source**
  - **Trigger**: the binlog file or GTIDs of the stored offset were purged (retention shorter than the outage), or the source failed over while the connector ran in file/position mode, so the stored file name belongs to the old primary's log namespace.
  - **Behaviour**: Debezium refuses to start streaming: `Connector requires binlog file '<f>', but server only has ...` (file/position) or `Some of the GTIDs needed to replicate have been already purged` (GTID); the engine fails, `handleEngineCompletion` retries `errors.max.retries` times (10 by default, 20 in the shipped configuration), `SLEEP_TIME` 10 s apart, then exits with code 3. After a failover in file/position mode where the new primary has a file of the same name, Debezium starts at a byte position of an unrelated log — behaviour not verified here (a parse failure, or silently wrong events). In GTID mode Debezium resumes from the GTID set across a failover (Debezium behaviour; not read in this repository).
  - **Detection**: the Debezium messages above in the ERROR `Engine stopped with an error: ...` lines, within one engine start; FATAL `Replication is STOPPED: ...` after the retries (~2–4 min). None for the same-name failover case. DEFECT.
  - **Blast radius**: replication stops; nothing lost yet, but the gap between the stored position and the oldest available one can no longer be replayed.
  - **Recovery**: failover: run GTID mode (`gtid_mode=ON`) so the connector follows by GTID; in file/position mode, find the equivalent position on the new primary and insert it (FM-09.03-3 statement, `gtids` omitted, `row`/`event` 0), then resync (spec 11.04) the tables written in the uncertain window. Purged: re-snapshot, or position at the current source position and resync every table (spec 11.04).
  - **RTO**: unbounded (resync or reload); unmeasured.
  - **Test**: GAP: an integration test that purges the stored binlog and checks the stop is terminal with the purge named.
  - **DEFECT**: once the source no longer holds the position, the only recoveries are a full reload or a per-table resync; the same-name failover case is not detected.

- **FM-09.03-7 Offset delete statement left at Debezium's default**
  - **Trigger**: a configuration without `offset.storage.jdbc.table.delete` (every shipped configuration sets `select * from %s`).
  - **Behaviour**: `JdbcOffsetBackingStore.save` executes Debezium's default `DELETE FROM %s` before every offset insert — at every acknowledged unit. How ClickHouse treats an unconditional `DELETE FROM` (lightweight deletes take a `WHERE`) is not verified here: either every flush fails (FM-09.03-1) or every flush is a delete mutation followed by an insert, non-atomically (a crash between them is FM-09.03-3). The connector validates nothing about this key.
  - **Detection**: if the statement fails, Debezium WARN `Attempt n to call 'Saving offset' failed.` per flush; otherwise none. DEFECT.
  - **Blast radius**: FM-09.03-1 or FM-09.03-3.
  - **Recovery**: set `offset.storage.jdbc.table.delete: "select * from %s"` and restart the process.
  - **RTO**: restart ~20 s; unmeasured.
  - **Test**: GAP: a start-up validation test that refuses a JDBC offset store without an explicit, non-destructive `offset.storage.jdbc.table.delete`.
  - **DEFECT**: an unsafe default of the library is accepted silently.

Summary: 7 failure modes, 7 DEFECT, 6 GAP.

# Spec 09.05: Optional `extra_info` Column of the Offset Table

## 1. Executive Summary & Purpose
Writes the static facts about the running connector as one JSON text into an optional `extra_info` column of the offset table (`replica_source_info*`). The facts are the version, the jar location, Java and OS, the two explicit time zones and the process start. A dashboard can then show which version each connector runs without a side channel.

The column is optional by construction. It never decides whether the connector starts, never touches a replicated row, and never changes the offset value that Debezium produces. When anything about it fails, the connector starts and replicates as before and `extra_info` stays empty.

---

## 2. Codebase Mapping on 2.11.1
- **Decision and ALTER**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/OffsetExtraInfo.java`: `prepare()`, `insertStatement()`, `enabled()`, `clearOwnOverride()`.
- **JSON content**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/ConnectorStaticInfo.java`: `collect()`, `toJson()`, `resolveVersion()`.
- **Call site**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java`: `setupDebeziumEventCapture()`, right after `ensureHeartbeatInterval()` and before the engine is built.
- **Key registry**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/config/ConfigKeyCheck.java`: `offset.extra.info.enable`.
- **Writer of the rows**: Debezium's `JdbcOffsetBackingStore`, which runs the statement in `offset.storage.jdbc.table.insert` at every checkpoint and binds five parameters.

---

## 3. Operational Specification

### 3.1 What is written
One JSON object, plain ASCII, at most 2 048 characters, built from a fixed whitelist: `schema`, `connector_version`, `version_source`, `connector_name`, `jar_path`, `docker_tag`, `deployment`, `java_version`, `java_vendor`, `os_name`, `os_arch`, `jvm_time_zone`, `source_time_zone`, `clickhouse_time_zone`, `jdbc_driver`, `debezium_version`, `host`, `pid`, `started_utc`, `started_epoch_s`. The code never walks the configuration, so no password, user name or URL can reach it. Every value is cut at 256 characters. Past 2 048 characters only `schema`, `connector_version` and `version_source` are written. Every time is an ISO instant with `Z` plus epoch seconds.

The version is the Docker image tag (`DOCKER_TAG`), else a release number in one of the three directories nearest the jar (`.../clickhouse-sink-connector-2.11.1/bin/...`), else `unknown`. `version_source` names which one answered. The jar and the Maven build carry no release number.

### 3.2 When it is written
`OffsetExtraInfo.prepare()` runs once per engine build, before Debezium reads the properties. It sets `offset.storage.jdbc.table.insert` to the default insert plus a sixth column, `extra_info`, whose value is a SQL literal, and only when all of these hold:
1. `offset.extra.info.enable` is not set, or is `true` (default on; any other value is off).
2. The offsets are kept by `JdbcOffsetBackingStore`, not by another store, and the offset table is not a KeeperMap table.
3. The operator has not set `offset.storage.jdbc.table.insert` or its pre-2.7.1 spelling `offset.storage.jdbc.offset.table.insert`. An operator statement is never replaced.
4. The offset table exists on the endpoint the offset store itself uses (`offset.storage.jdbc.url`, with its own user).
5. The column exists on that endpoint. When it does not, one `ALTER TABLE ... ADD COLUMN IF NOT EXISTS extra_info String DEFAULT ''` runs on the connector's own ClickHouse connection (metadata only, never retried), and the column must then be visible on the offset store's endpoint.
6. The JSON was built without an exception.

Any other outcome leaves every property as it was and ends in one state, logged once as `OFFSET-EXTRA-INFO state=<STATE> <reason>; extra_info is not populated`: `DISABLED`, `NOT_JDBC_STORE`, `KEEPER_MAP`, `USER_INSERT`, `STORE_UNAVAILABLE`, `TABLE_ABSENT`, `COLUMN_ABSENT`, `ERROR`. `ACTIVE` is the one state that sets the property. `prepare()` catches every exception, so it never throws.

### 3.3 Why the five binds are untouched
The literal is the only new thing in the statement. Debezium still binds `id`, `offset_key`, `offset_val`, `record_insert_ts` and `record_insert_seq` exactly as before. The stored offset value is therefore byte-for-byte the one Debezium produced.

Debezium runs `String.format` on the statement, so every `%` of the literal is doubled. The literal escapes backslash and apostrophe. The JSON writer already writes the apostrophe (U+0027) and the question mark (U+003F) as six-character JSON Unicode escapes. The driver sees neither a quote nor a placeholder inside the literal.

### 3.4 Restarts, mixed versions, readers
- An in-process engine rebuild runs `prepare()` again on the same properties. The statement it set is remembered, so the second run tells it from an operator statement, refreshes it, or removes it when the column is gone.
- The column has a default of the empty string. A connector that does not know the column (any version before 2.11.1 sharing the table, or the REST position edit, which uses the five-column insert) still writes valid rows, and its rows carry `''`. The default `show_replica_status` view still works after the ALTER and when it is created after the column exists.
- A reader takes the latest row per `offset_key` and treats `''` as unknown. It must not search older rows for a non-empty value: after a rollback that reports a version that no longer runs.
- A reader must not rely on `merge()` over offset tables of mixed generations on ClickHouse 24.8. There `merge()` fails (Code 10 or Code 47) when the first table lacks the column or a later one has it. 25.8 builds the union of the columns.

### 3.5 Limits stated, not hidden
- A new connector on a new offset table is filled from its second start: the table does not exist at the first `prepare()`.
- The column removed while the connector runs makes the next offset insert fail like any schema drift of the offset table (see FM-09.05-6). A restart detects the missing column and falls back to the five-column insert.

---

## 4. Invariants Preserved
- **Invariant I8 (Durable Offset Quiescence)**: the offset value, the commit order and the commit timing are unchanged. The feature adds one literal column to a statement that Debezium already runs.
- **Invariant I9 (Loud Failure)**: the only failures absorbed are the optional column's own probes and its one ALTER, and each is logged at WARN with its state. No replication exception and no offset write failure is absorbed; FM-09.05-6 is loud by design.
- **Invariant I11 (Drop-in Upgrade Safety)**: an older connector keeps working on a table that has the column, and the new connector keeps working on a table that does not.
- **Invariant I15**: every failure mode below has a Detection, a Recovery and an RTO.

---

## 5. Verification Criteria
- `OffsetExtraInfoTest`: a fake server records every statement with its connection. The cases are column present, column added, ALTER failing and ALTER not visible on the store endpoint. They also cover operator insert, restarts, kill switch, other stores, table absent, store unavailable and any other exception. The last cases are closed connections, name resolution, statement shape and escaping.
- `ConnectorStaticInfoTest`: whitelist, no secret, bounded size, ASCII-only JSON without `'` or `?`, explicit times, version resolution.
- `ConfigKeyCheckTest`: the key `offset.extra.info.enable` is registered and a misspelling of it is reported.
- End to end, on a real Percona 8.4 source with ROW binlog and GTID, a real ClickHouse 24.8.8.17 and a real ClickHouse 25.8.16.20002, with the V2 JDBC driver. The V1 driver is refused at start by this branch (spec 03.08), so the feature never meets it. A throwaway harness ran eight scenarios against each server and made 62 checks per server. Every check passed on both servers.
  - **S1**: a first start finds no offset table (`TABLE_ABSENT`). The next start adds the column and writes the JSON (`ACTIVE`). The JSON is valid and names version 2.11.1, `version_source` `jar_path`, both zones and the connector name. The default `show_replica_status` view returns rows. A third start runs no second ALTER, and the final MySQL and ClickHouse row checksums are equal.
  - **S2**: a real 2.9.1 release replicates first and the new jar takes over. A pre-feature jar then runs on the table that has the column. Its latest `extra_info` is empty, replication stays correct, and the new jar fills the column again.
  - **S3**: the connector user has no `ALTER`. The state is `COLUMN_ABSENT`, one failed ALTER is recorded, the column stays absent and rows still replicate with an equal checksum. After the grant the next start is `ACTIVE`. The pre-feature jar prints the same ERROR lines for this user, so they do not come from this feature.
  - **S4**: an operator `offset.storage.jdbc.table.insert`, and the same statement under the old key spelling, are left as written (`USER_INSERT`, empty `extra_info`, equal checksum).
  - **S5**: `offset.extra.info.enable=false` leaves a present column empty (`DISABLED`).
  - **S6**: `clickhouse.jdbc.v1=true` is refused at start, before any offset work.
  - **S7**: a connector name with an apostrophe, a question mark, `%s` and a backslash round-trips through `String.format` and the driver into the JSON.
  - **S8**: a column added by hand makes no connector ALTER and the JSON is written.
  - The offset value keeps `file`, `pos` and `ts_sec` in every phase. The 2.9.1 release logs 20 ERROR lines of its own in S2, and the phases of the new jar in S2 log none.
  - The harness needs a Percona root file system and ClickHouse binaries, so it is not part of this repository.

---

## 6. Failure Modes & Recovery
The feature fails closed to "not populated". Replication and the offset value are the same in every state.

- **FM-09.05-1 The column cannot be added**
  - **Trigger**: the offset store user lacks `ALTER`, the server is read-only, or the table is not alterable.
  - **Behaviour**: state `COLUMN_ABSENT`, five-column insert, one WARN.
  - **Detection**: WARN `OFFSET-EXTRA-INFO state=COLUMN_ABSENT ... ADD COLUMN extra_info failed`.
  - **Blast radius**: the dashboard shows no version for this connector.
  - **Recovery**: grant `ALTER` to the offset user, or add the column once by hand, then restart.
  - **RTO**: one restart; replication is not interrupted by the failure.
  - **Test**: `OffsetExtraInfoTest.alterFails()`.

- **FM-09.05-2 The column is not visible on the offset store endpoint**
  - **Trigger**: the ALTER succeeds on the connector's connection but the offset store's own URL reaches a different table or replica.
  - **Behaviour**: state `COLUMN_ABSENT`, five-column insert.
  - **Detection**: WARN `... is not visible on ... through the offset store endpoint after the ALTER`.
  - **Blast radius**: none on replication; the column stays empty.
  - **Recovery**: point both URLs at the same endpoint, or accept the empty column.
  - **RTO**: one restart.
  - **Test**: `OffsetExtraInfoTest.alterInvisibleOnTheStoreEndpoint()`.

- **FM-09.05-3 The offset table does not exist yet**
  - **Trigger**: the first start of a new connector.
  - **Behaviour**: state `TABLE_ABSENT`; Debezium creates the table with its default DDL.
  - **Detection**: WARN `OFFSET-EXTRA-INFO state=TABLE_ABSENT`.
  - **Blast radius**: the column is filled from the second start.
  - **Recovery**: restart once after the first start, or none.
  - **RTO**: one restart.
  - **Test**: `OffsetExtraInfoTest.tableAbsent()`.

- **FM-09.05-4 The operator set the offset insert statement**
  - **Trigger**: `offset.storage.jdbc.table.insert`, or its pre-2.7.1 spelling `offset.storage.jdbc.offset.table.insert`, is in the user file.
  - **Behaviour**: state `USER_INSERT`; the statement is left as written.
  - **Detection**: INFO `OFFSET-EXTRA-INFO state=USER_INSERT`.
  - **Blast radius**: none.
  - **Recovery**: remove the operator statement to let the feature run, or keep it.
  - **RTO**: one restart.
  - **Test**: `OffsetExtraInfoTest.operatorInsertWins()`, `OffsetExtraInfoTest.operatorInsertUnderTheOldSpelling()`, `OffsetExtraInfoTest.restartsTellOurStatementFromTheOperators()`.

- **FM-09.05-5 The offset store cannot be read**
  - **Trigger**: the endpoint refuses the connection, the metadata query is denied, or the name or URL is missing.
  - **Behaviour**: state `STORE_UNAVAILABLE`; no ALTER is tried. The same condition fails the offset store itself loudly, as before.
  - **Detection**: WARN `OFFSET-EXTRA-INFO state=STORE_UNAVAILABLE`.
  - **Blast radius**: none added by this feature.
  - **Recovery**: fix the endpoint or the grant on `system.tables` and `system.columns`.
  - **RTO**: as for the offset store itself.
  - **Test**: `OffsetExtraInfoTest.storeUnavailable()`, `OffsetExtraInfoTest.anythingElseIsAbsorbed()`.

- **FM-09.05-6 The column is removed while the connector runs**
  - **Trigger**: an operator removes `extra_info` from the offset table while an engine writes the six-column insert.
  - **Behaviour**: the offset insert fails with an unknown-column error, through the same path as any schema drift of the offset table (spec 09.03). The failure is not absorbed.
  - **Detection**: the offset flush ERROR of spec 09.03; the replication status view stops advancing.
  - **Blast radius**: no loss; the durable offset stays at the last committed position and rows are redelivered at the next start (Invariant I8).
  - **Recovery**: restart. `prepare()` finds the column missing, tries the ALTER, and falls back to the five-column insert if it cannot add it. Setting `offset.extra.info.enable=false` also removes the sixth column.
  - **RTO**: one restart (~20 s). Unmeasured.
  - **Test**: `OffsetExtraInfoTest.ownStatementIsRemovedWhenTheColumnIsGone()` (the restart half).

- **FM-09.05-7 An older connector shares the table, or the connector is rolled back**
  - **Trigger**: a connector before 2.11.1, or the REST position edit, writes an offset row.
  - **Behaviour**: the five-column insert is valid; the column takes its default `''`.
  - **Detection**: not applicable; no error.
  - **Blast radius**: none; readers treat `''` as unknown (section 3.4).
  - **Recovery**: none needed.
  - **RTO**: 0.
  - **Test**: `OffsetExtraInfoTest.statementShape()`.

Summary: 7 failure modes, 0 DEFECT, 0 GAP.

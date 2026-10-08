# Spec 09.05: Schema-History Store Layout (Debezium 3.3 record parts)

## 1. Executive Summary & Purpose
Specifies the ClickHouse table behind Debezium's `JdbcSchemaHistory` and the start-up preflight that keeps it able to hold every schema-history record. A record longer than 65000 characters (the full `tableChanges` of one table; ~200 MySQL columns is enough) is stored as several rows. Debezium 3.1.3 gave each part its own id and recovered each row as its own JSON document, so the first restart after such a table was captured failed permanently with `JsonEOFException ... column: 65001` (Altinity/clickhouse-sink-connector#1450). Debezium 3.3.0 (DBZ-8979) fixed recovery by writing all parts of one record under ONE id; on the layout every deployment was created with — `ReplacingMergeTree(record_insert_seq) ORDER BY id` — those parts share the sorting key and the version, so ClickHouse keeps only one of them and the record is destroyed. This spec defines the required layout, the automatic migration of existing tables, and when the connector refuses to start instead.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/SchemaHistoryStorePreflight.java`
- **Call site**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java` (`setup(...)`, after the queue-bytes preflight, before the engine is built)
- **Not this repository's code**: Debezium 3.3.0+ (3.7.0.Final shipped; same format, and 3.7's own default DDL adds `PRIMARY KEY (id, history_data_seq)`) `io.debezium.storage.jdbc.history.JdbcSchemaHistory` (`storeRecord`: one `UUID` id, one `record_insert_ts` and one `record_insert_seq` for all 65000-character parts of a record, `history_data_seq` 0, 1, 2...; `recoverRecords`: default `table.select` `SELECT id, history_data FROM %s ORDER BY record_insert_ts, record_insert_seq, id, history_data_seq`, consecutive rows of one id concatenated before parsing) and `JdbcSchemaHistoryConfig` (the table name is split on `.`, and the DDL, SELECT and INSERT are formatted with the bare table name, so they run in the JDBC URL's database).
- **Key methods**:
  - `SchemaHistoryStorePreflight.apply(Properties)` — runs only for `schema.history.internal=io.debezium.storage.jdbc.history.JdbcSchemaHistory` with a `jdbc:clickhouse` URL (`schema.history.internal.jdbc.connection.url`, or the alias `schema.history.internal.jdbc.url`).
  - `SchemaHistoryStorePreflight.plan(TableState)` — the pure decision (§3.2).
  - `SchemaHistoryStorePreflight.countLegacyRecordIds(Connection, TableState)` — 3.1.3-format oversized records.
  - `SchemaHistoryStorePreflight.rekeyInsert(String, String)` — the re-keying copy (§3.3).
  - `SchemaHistoryStorePreflight.verifyRecordsParse(Connection, String)` — Debezium-exact recovery read of the copy.
- **Shipped DDL** (every config, template and test resource that declares the table): `sink-connector-lightweight/docker/config.yml`, `sink-connector-lightweight/src/main/resources/config.properties`, `sink-connector-lightweight/helm/sink-connector-lightweight/templates/configmap.yaml`, `sink-connector-lightweight/k8s/config.yml`, `deploy/ansible-systemd/templates/config.yml.j2`, `doc/state_storage.md`.

---

## 3. Operational Specification

### 3.1 Required layout
A collapsing MergeTree-family engine (`ReplacingMergeTree`, `CollapsingMergeTree`, `VersionedCollapsingMergeTree`, `AggregatingMergeTree`, `SummingMergeTree`, `CoalescingMergeTree`, `GraphiteMergeTree`, with or without `Replicated`/`Shared`) MUST have both `id` and `history_data_seq` in its sorting key. The shipped DDL is:

```sql
CREATE TABLE if not exists %s (`id` VARCHAR(36) NOT NULL, `history_data` VARCHAR(65000),
  `history_data_seq` INTEGER, `record_insert_ts` TIMESTAMP NOT NULL, `record_insert_seq` INTEGER NOT NULL)
ENGINE = ReplacingMergeTree(record_insert_seq) ORDER BY (id, history_data_seq)
```

A plain `MergeTree` never merges rows by key and is safe with any sorting key. A key-value engine (`KeeperMap`, `EmbeddedRocksDB`, `Redis`) MUST have both columns in its primary key. The collapse is immediate, not eventual: rows of one INSERT block with an equal sorting key are already reduced to one when the part is written (verified on ClickHouse 24.8.8.17: two parts `('d', 0)` / `('d', 1)` with equal version inserted into `ORDER BY id` left only part 1).

Rows written by Debezium 3.1.3 (each part its own id) stay valid under this key: a record that fit in one row is a single row with `history_data_seq = 0`, which 3.3 recovers unchanged.

### 3.2 Decision at start-up
1. If the table does not exist in the URL's database, the preflight executes the configured `schema.history.internal.jdbc.table.ddl` (alias `...jdbc.schema.history.table.ddl`; Debezium's default DDL when neither is set) formatted with the bare table name — exactly what Debezium's `initializeStorage()` would run — and continues with the created table. An operator DDL that still says `ORDER BY id` is therefore corrected before the first record is written (§3.3 on an empty table).
2. It reads `engine`, `engine_full`, `sorting_key`, `primary_key` from `system.tables` and the database engine from `system.databases`, and counts **legacy record ids**: ids that have continuation parts (`history_data_seq > 0`) but no part 0 — 3.1.3-format oversized records that 3.3 cannot recover (two bounded reads: `count()` of continuation rows, then, only when non-zero, a `GROUP BY id` restricted to those ids).
3. Decision (`plan`):
   - key-value engine: NONE when the primary key has `id` and `history_data_seq` and there is no legacy record, otherwise REFUSE;
   - layout safe (§3.1) and no legacy record: NONE;
   - otherwise MIGRATE when the engine is a non-replicated MergeTree-family engine in an `Atomic` database and the `ORDER BY` clause of `engine_full` can be located exactly once (`ORDER BY <key>` or `ORDER BY (<key>)`, followed by a space or the end); the new storage clause is `engine_full` with that clause replaced by `ORDER BY (<key columns>[, id], history_data_seq)` (unchanged when only legacy records require the migration);
   - anything else REFUSE: replicated or shared engines, non-Atomic databases, non-MergeTree engines, an `ORDER BY` that cannot be located.
4. REFUSE throws `IllegalStateException` from `setup()` naming the table, the reason and the manual procedure: the engine never starts against a store that would destroy records. A ClickHouse that answers `UNKNOWN_DATABASE` (Code 81) for the database in the JDBC URL's path is reachable, and on a first start that database (by convention the offset store's) does not exist yet: `setup()` creates it only later (`createDatabaseForDebeziumStorage`). The preflight creates it once, the same way (`CREATE DATABASE IF NOT EXISTS`, over the same URL without the database path), and continues; a failed creation is reported and retried like an unreachable ClickHouse. Before this, a fresh ClickHouse made every first start wait 5 minutes and then refuse. An unreachable ClickHouse is retried every 5 s (WARN per attempt) for up to 5 minutes (`clickhouse.sink.internal.schema.history.preflight.wait.ms`), so a slowly starting ClickHouse still works; if it stays unreachable the start is refused with `IllegalStateException` -- the check is never skipped, because a ClickHouse that is down for the preflight and back for Debezium would otherwise let the collapsing layout through unverified.

### 3.3 Migration
Executed before the engine exists, so nothing writes the table concurrently. Names carry a timestamp `yyyyMMddHHmmss`; nothing is dropped or truncated.
1. `CREATE TABLE <t>_dbz33_new_<ts> AS <t> ENGINE = <new storage clause>` (same columns, same engine and settings).
2. Copy: `INSERT ... SELECT` of the five columns; when legacy records exist, `rekeyInsert` instead: a record starts at every part 0 in Debezium's recovery order (`sum(history_data_seq = 0) OVER (ORDER BY record_insert_ts, record_insert_seq, id, history_data_seq ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)`), and every part of that group takes the `id`, `record_insert_ts` and `record_insert_seq` of its first part — the 3.3 format. The window runs over the narrow columns only and is joined back to the data by `(id, history_data_seq)`.
3. Verify: `uniqExact(id, history_data_seq)`, `countIf(history_data_seq = 0)` and `sum(length(history_data))` equal on both tables, then every record of the copy is read exactly as Debezium 3.3 recovers it and parsed with Debezium's `DocumentReader`. Any difference or parse failure throws; the copy is left for inspection and the original is untouched (REFUSE).
4. `EXCHANGE TABLES <t> AND <t>_dbz33_new_<ts>` (atomic in an `Atomic` database), then `RENAME TABLE <t>_dbz33_new_<ts> TO <t>_pre_dbz33_<ts>`; a failed rename after the exchange is reported at WARN (the configured name already holds the verified copy).
5. The result is logged at WARN: new storage clause, records parsed, the backup table the operator drops once the connector has restarted cleanly.

Cost (measured on a store of 4,776,366 rows, 1.42 GiB on disk, 10.09 GiB uncompressed): one copy of the table plus one full read — the same read Debezium performs on every start. Disk: the copy needs the table's on-disk size free.

### 3.4 Downgrade
A connector built on Debezium 3.1.3 runs on a migrated table: it writes a distinct id per part, which `ORDER BY (id, history_data_seq)` keeps. It cannot recover a record of more than 65000 characters in either layout — the condition under which 3.1.3 never worked. The `<t>_pre_dbz33_<ts>` table is the exact pre-migration state.

---

## 4. Invariants Preserved
- **Invariant I9 (Loud Failure)**: a store that would collapse record parts never reaches the engine — it is migrated or the start is refused with the reason; a copy that does not verify is never swapped in.
- **Invariant I11 (Drop-in Upgrade Safety)**: the existing schema history stays readable across the Debezium 3.1.3 → 3.3 boundary — 3.1.3 single-part records are read unchanged, 3.1.3 oversized records are re-keyed into the 3.3 format, and the layout the shipped DDL created before this change (non-replicated `ReplacingMergeTree` in an `Atomic` database) is migrated automatically with the original kept. Layouts the connector cannot migrate safely stop the start instead of corrupting the history later (I9 takes precedence).
- **Invariant I14 (Bounded Bookkeeping)**: the preflight reads only the connector's own schema-history table, once per start (continuation-row count) and once per migration (copy verification), marked `I14-scan-allowed: spec 09.05`.

---

## 5. Verification Criteria
- `SchemaHistoryStorePreflightTest.defaultLayoutIsMigrated()` — §3.2: `ReplacingMergeTree(record_insert_seq) ORDER BY id` in an Atomic database is migrated to `ORDER BY (id, history_data_seq)` with settings preserved.
- `SchemaHistoryStorePreflightTest.fixedLayoutIsLeftAlone()` — §3.1: a key with both columns needs nothing.
- `SchemaHistoryStorePreflightTest.legacyRecordsOnFixedLayoutAreRekeyed()`, `SchemaHistoryStorePreflightTest.legacyRecordsOnDefaultLayout()` — §3.3 step 2.
- `SchemaHistoryStorePreflightTest.plainMergeTreeIsSafe()` — §3.1.
- `SchemaHistoryStorePreflightTest.replicatedIsRefused()`, `SchemaHistoryStorePreflightTest.ordinaryDatabaseIsRefused()`, `SchemaHistoryStorePreflightTest.keyValueKeyedByIdIsRefused()`, `SchemaHistoryStorePreflightTest.unlocatableOrderByIsRefused()` — §3.2 step 3 REFUSE cases.
- `SchemaHistoryStorePreflightTest.multiColumnKey()`, `SchemaHistoryStorePreflightTest.clauseMatchIsExact()` — §3.2 step 3 clause rewrite.
- `SchemaHistoryStorePreflightTest.rekeyStatementShape()` — §3.3 step 2 grouping.
- `SchemaHistoryStorePreflightTest.otherStoresAreIgnored()`, `SchemaHistoryStorePreflightTest.unreachableStoreRefusesTheStart()`, `SchemaHistoryStorePreflightTest.waitBound()` — §3.2 scope and step 4.
- `SchemaHistoryStorePreflightTest.urlDatabaseParsing()`, `SchemaHistoryStorePreflightTest.unknownDatabaseDetection()` — §3.2 step 4 first start: the URL's database is located and only `UNKNOWN_DATABASE` creates it. End to end (Percona 8.0 + ClickHouse 24.8, isolated sandbox, the CI `docker/config.yml` with no `altinity_sink_connector` database): the preflight creates it and the snapshot converges in seconds; the previous build waited 5 minutes and refused to start.
- End to end (real MySQL 8.4 + ClickHouse 24.8 + connector, boxcar sandbox): a 202-column table captured, connector restarted after `OPTIMIZE ... FINAL` of the history table — fails with `JsonEOFException` on the 3.1.3 jar, converges value-exact on the 3.7.0 jar; a 3.1.3-written store with an oversized record is migrated and re-keyed by the 3.7.0 jar, which then survives `ADD COLUMN` and its own restart value-exact. Downgrading to the 3.1.3 jar afterwards fails on the oversized record with the same `JsonEOFException` (§3.4).

---

## 6. Failure Modes & Recovery

Recovery posture: every failure here stops the start before Debezium touches the store; nothing is lost and the original table is never modified until the verified copy is swapped in atomically. Procedures P-FIX-TYPE / P-SKIP / P-RESYNC and the retry-vs-stop rule are defined in spec 07.01 §6.

- **FM-09.05-1 Store layout the connector does not migrate**
  - **Trigger**: replicated/shared collapsing engine, non-Atomic database, key-value engine keyed by `id`, unlocatable `ORDER BY`.
  - **Behaviour**: `setup()` throws `IllegalStateException("Refusing to start: schema-history table ... (spec 09.05). Manual procedure: ...")`; the process exits through the normal start-failure path.
  - **Detection**: the ERROR naming the table and engine; the connector does not start.
  - **Blast radius**: this connector only; ClickHouse data untouched.
  - **Recovery**: the manual procedure in the message — new table `ORDER BY (id, history_data_seq)` (ON CLUSTER with a new replication path for a Replicated engine), `INSERT ... SELECT` (re-keying legacy records per §3.3 step 2), compare, `EXCHANGE TABLES`, start.
  - **RTO**: minutes of operator work plus the copy.
  - **Test**: `SchemaHistoryStorePreflightTest.replicatedIsRefused()`.

- **FM-09.05-2 Migration statement refused or copy does not verify**
  - **Trigger**: missing privilege (`CREATE TABLE`, `INSERT`, `EXCHANGE`), disk full during the copy, a legacy record whose parts cannot be regrouped (an orphaned continuation part).
  - **Behaviour**: `SQLException` wrapped in `IllegalStateException` naming the statement or the mismatching counts / the record that does not parse; the scratch table `<t>_dbz33_new_<ts>` is left, the original untouched.
  - **Detection**: the ERROR at start.
  - **Blast radius**: this connector only.
  - **Recovery**: grant the privilege / free disk and restart (a new scratch name is used); for an unparseable record, inspect the scratch table — the history itself is damaged (it would not recover under 3.1.3 either) and needs `sink-connector-client delete_schema_history` plus Debezium schema recovery (spec 10.06).
  - **RTO**: restart after the fix.
  - **Test**: end-to-end upgrade scenario (§5).

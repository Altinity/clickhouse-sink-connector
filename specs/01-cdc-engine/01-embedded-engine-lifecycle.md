# Spec 01.01: Embedded CDC Engine Lifecycle & Bootstrap

## 1. Executive Summary & Purpose
Specifies the startup, dependency injection, runtime orchestration and shutdown of the standalone embedded CDC application (`ClickHouseDebeziumEmbeddedApplication`). In lightweight mode the connector runs as a self-contained process (embedded Debezium engine) without a Kafka broker.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ClickHouseDebeziumEmbeddedApplication.java`
- **Dependency Injection**: `com.altinity.clickhouse.debezium.embedded.AppInjector` (Guice module; there is no `injector` sub-package)
- **Engine wrapper**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java` (`setup(...)`, `stop()`, `drainBeforeStop()`, `stopDrainTimeoutMs`)
- **Offset FIFO reset on restart**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/DebeziumOffsetManagement.java` (`reset()`, `outstandingCount()`; spec 09.01 §3.8)
- **Restart-monitor idleness source**: `ClickHouseDebeziumEmbeddedApplication.effectiveLastRecordTimestamp(long, long)`
- **Row-image preflight**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/BinlogRowImagePreflight.java` (`check(Properties)`, `check(Properties, Connection)`, `QUERY`, `SKIP_PROPERTY`)
- **Key methods** — all `static`. The application class keeps process-wide state (`props`, `injector`, `debeziumChangeEventCapture`, `monitoringTimer`) in static fields; it is not instantiated once per engine:
  - `public static void main(String[] args)`
  - `public static void start(DebeziumRecordParserService recordParserService, Properties props, boolean forceStart)`
  - `public static void stop()`
  - `public static CompletableFuture<String> startDebeziumEventLoop(Injector injector, Properties props)` (REST-API driven force restart)
  - `private static void setupMonitoringThread(ClickHouseSinkConnectorConfig config, Properties props)`
  - `private static void loadPropertiesFile(String filePath)`

---

## 3. Operational Specification

### 3.1 Configuration Resolution Sequence
`main(String[] args)` resolves configuration in exactly one of two ways:
1. **Configuration file given (`args[0]`)**: `loadPropertiesFile(path)` clears the static `props`, loads the bundled defaults from `config.properties` (`PropertiesHelper.getProperties`), then overlays the file parsed by `ConfigLoader.loadFromFile(path)`. File values win over bundled defaults. A parse failure logs the usage line and calls `System.exit(-1)`.
2. **No argument**: `props = injector.getInstance(ConfigurationService.class).parse()` — the Guice-bound `ConfigurationService` (`EnvironmentConfigurationService`, bound in `AppInjector`) supplies the whole property set from the environment.

There is no additional command-line override layer. The `LOGGING_LEVEL` environment variable sets the root log level (default `INFO`). Hardcoded defaults for connector keys live in `ClickHouseSinkConnectorConfig` (sink-connector module) and, for lightweight-only keys, `SinkConnectorLightWeightConfig`.

### 3.2 Start Sequence
1. `main` installs the log4j JUL bridge, loads `com.clickhouse.jdbc.ClickHouseDriver`, builds the Guice injector (`AppInjector`), prints the `DOCKER_TAG` environment variable if set, and resolves configuration (3.1).
2. `setupMonitoringThread(config, props)`: only when `ClickHouseSinkConnectorConfigVariables.RESTART_EVENT_LOOP` is true, a daemon `Timer` runs every `RESTART_EVENT_LOOP_TIMEOUT_PERIOD` seconds. It measures idleness from `effectiveLastRecordTimestamp(inMemory, stored)`: the in-memory `ReplicationStatusSingleton.getLastRecordTimestamp()` when one has been observed, otherwise the newest `record_insert_ts` of the offset table (`DebeziumJdbcStorageOperations.getLatestRecordTimestamp`), otherwise `-1`. (The earlier test `if (stored == -1) lastRecordTimestamp = stored` adopted the stored value only when it was the sentinel, so a valid stored timestamp was never used and the monitor restarted the engine on every tick until the first record arrived.) If the delta exceeds the timeout it calls `debeziumChangeEventCapture.stop()`, sleeps, and calls `start(..., forceStart = true)`, which re-reads the configuration file from disk.
3. `start(recordParserService, props, forceStart)`: when `forceStart` is true the configuration file is reloaded; then a new `DebeziumChangeEventCapture` is constructed and `setup(props, recordParserService, forceStart)` is called. `setup` FIRST refuses (`IllegalStateException`) if `DebeziumOffsetManagement.hasUnwrittenBatches()` — a previous engine in this process left handed-off batches unacknowledged and `stop()` has not abandoned them; starting on top of them would park every new unit behind them forever (spec 09.01 §3.8). It then creates the offset-storage database (`DebeziumJdbcStorageOperations.createDatabaseForDebeziumStorage`), creates the replica-status view when `replica.status.view` is configured (spec 10.03), runs the MySQL keyless-table preflight (`KeylessTablePreflight.check(props)`, warning-only, never blocks startup), runs the MySQL **row-image preflight** (`BinlogRowImagePreflight.check(props)`, below) and starts the embedded Debezium engine with `handleChangeEventBatch` as the batch consumer (spec 01.03).

   **Row-image preflight (MySQL only).** The connector turns every row event into a full-row insert whose newest version replaces the previous one column for column; that is only correct when every row event carries every column. With `binlog_row_image=MINIMAL` an UPDATE's after-image omits the columns the statement did not change, so every update of every table is replicated with those columns as NULL (or a ClickHouse default) — a value-level divergence on every update with row counts intact; with `NOBLOB` the same happens to every BLOB/TEXT column. Nothing downstream can recover a value the source never logged, so unlike the keyless report this is all-or-nothing: `BinlogRowImagePreflight.check` reads `SELECT @@GLOBAL.binlog_row_image` (the `SHOW GLOBAL VARIABLES LIKE 'binlog_row_image'` value, issued as a SELECT so it passes the read-only allowlist) on a read-only connection and **refuses to start** (`IllegalStateException`, ERROR banner naming the value and the fix `SET GLOBAL binlog_row_image = FULL`) when the value is readable and not `FULL`. A source that cannot be asked (unreachable, permission denied, empty result) is logged at WARN and allowed through, like the keyless check. `binlog.row.image.check.skip=true` lets a non-FULL source through with a WARN banner on every start. Non-MySQL connectors are not checked.
4. `DebeziumEmbeddedRestApi.startRestApi(...)` starts the REST API. A failure there is logged and does not stop the engine.

### 3.3 Stop Sequence
`stop()` delegates to `DebeziumChangeEventCapture.stop()`, which runs these steps in this order; each step's failure is logged and the next step still runs:
1. **Close the engine** (`engine.close()`) — the producer stops first, so nothing more is handed off while the workers are still running.
2. Shut down the single-threaded Debezium event executor (`shutdown()`, `awaitTermination(60, SECONDS)`); `engine.run()` returns once the engine is closed.
3. **Drain** (`drainBeforeStop()`): while the pool is still running, wait until `isPipelineQuiescent()` (legacy queue empty, every routed queue empty, `!hasUnwrittenBatches()`), bounded by `stopDrainTimeoutMs` (60 s). Work a live worker can still finish is written and acknowledged rather than abandoned to a needless redelivery. A timeout is logged (naming the backlog via `describePendingHandoff()`) and tolerated. Skipped when there is no pool (single-threaded mode).
4. Shut down the batch executor pool (`shutdown()`, `awaitTermination(60, SECONDS)`).
5. **Reset the offset FIFO** (`DebeziumOffsetManagement.reset()`): every unit still outstanding is abandoned and the count is returned by `stop()` and logged at WARN. Nothing abandoned was acknowledged, so the next engine redelivers it from the last committed offset — the cost of a restart is redelivery, never loss and never a rolled-back offset (`Replication.OffsetFifo.acked_never_rolled_back`).

**Why this order (the failure it prevents).** The FIFO in `DebeziumOffsetManagement` is static, but the engine is restarted INSIDE the process by REST `/restart`, by `/start` after `/stop`, and by the restart monitor: a new `DebeziumChangeEventCapture` on the same FIFO. The previous `stop()` shut the pool down FIRST (cancelling the periodic workers and abandoning every queued batch), closed the engine LAST, and never touched the FIFO. A unit the old engine had handed off but no worker had written stayed the FIFO head for the life of the JVM: every unit of the new engine parked behind it, no offset was ever acknowledged again, `hasUnwrittenBatches()` stayed true (no control-record commit; every DDL drain timed out into a restart loop), rows kept being inserted while the durable offset froze, and the parked units' record lists leaked (`Replication.OffsetFifo.old_restart_poisons_fifo`).

The application registers **no JVM shutdown hook**. On SIGTERM the process relies on the embedded engine's own shutdown handling and on at-least-once redelivery from the last committed offset at the next start (specs 02.04, 09.03). Offsets are only ever committed by the writer path after rows are in ClickHouse (specs 09.01, 09.02), so an abrupt stop costs redelivery, never data.

---

## 4. Invariants Preserved
- **Invariant I8 (Durable Offset Quiescence)**: the durable position cannot advance past unwritten data on shutdown because nothing on the shutdown path commits offsets.
- **Fail-Fast Boot**: an unparseable configuration file exits the process with a non-zero code.

---

## 5. Verification Criteria
- `KeylessTablePreflightTest` — the keyless-table preflight that `setup` runs (`testCheckNeverThrowsWhateverTheSourceLooksLike`, `testSkipPropertyBypassesTheCheck`).
- `DebeziumEmbeddedRestApiDoubleStartTest` — the REST-driven restart path.
- `EngineRestartFifoResetTest.stopThenStartNewInstanceIsNotPoisoned` — a unit handed off and never written; `stop()`; a NEW capture's heartbeat is committed and its first written unit is acknowledged with nothing parked (fails on the old `stop()`: the ghost stays outstanding).
- `EngineRestartFifoResetTest.stopLeavesNothingOutstanding` — after `stop()` the outstanding set, the unwritten-group map and the parked-unit map are empty and the pool is shut down.
- `EngineRestartFifoResetTest.stopClosesEngineBeforeShuttingThePool` — the engine's `close()` observes a still-running pool; the pool is shut down afterwards.
- `EngineRestartFifoResetTest.stopDrainsInFlightWorkBeforeShuttingThePool` — a unit a live worker finishes 300 ms later is acknowledged by the drain, `stop()` returns 0 abandoned.
- `EngineRestartFifoResetTest.setupRefusesWhileBatchesAreOutstanding` — `setup()` throws `IllegalStateException` while a unit is outstanding.
- `RestartMonitorTimestampTest` — `effectiveLastRecordTimestamp(-1, stored) == stored`, in-memory wins, `(-1, -1) == -1`.
- `BinlogRowImagePreflightTest.minimalIsRefused`, `BinlogRowImagePreflightTest.noblobIsRefused` — a stub source answering `MINIMAL` / `NOBLOB` makes `check` throw `IllegalStateException` naming the variable, the value and the fix.
- `BinlogRowImagePreflightTest.fullPasses`, `BinlogRowImagePreflightTest.unreadableIsWarnedNotRefused`, `BinlogRowImagePreflightTest.skipIsLoud`, `BinlogRowImagePreflightTest.nonMysqlAndUnreachablePassThrough`, `BinlogRowImagePreflightTest.queryIsReadOnly`.
- `Replication.OffsetFifo.restart_quiescent`, `Replication.OffsetFifo.acked_never_rolled_back`, `Replication.OffsetFifo.old_restart_poisons_fifo`, `Replication.OffsetFifo.restart_unblocks_next_engine`.
- Verification: bootstrap/configuration-resolution and graceful shutdown on SIGTERM are not yet covered by an automated test (gap).

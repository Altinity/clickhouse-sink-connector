# Spec 01.01: Embedded CDC Engine Lifecycle & Bootstrap

## 1. Executive Summary & Purpose
Specifies the startup, dependency injection, runtime orchestration and shutdown of the standalone embedded CDC application (`ClickHouseDebeziumEmbeddedApplication`). In lightweight mode the connector runs as a self-contained process (embedded Debezium engine) without a Kafka broker.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ClickHouseDebeziumEmbeddedApplication.java`
- **Dependency Injection**: `com.altinity.clickhouse.debezium.embedded.AppInjector` (Guice module; there is no `injector` sub-package)
- **Engine wrapper**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java` (`setup(...)`, `stop()`)
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
2. `setupMonitoringThread(config, props)`: only when `ClickHouseSinkConnectorConfigVariables.RESTART_EVENT_LOOP` is true, a daemon `Timer` runs every `RESTART_EVENT_LOOP_TIMEOUT_PERIOD` seconds. If no record has been observed for that long (`ReplicationStatusSingleton.getLastRecordTimestamp()`, falling back to `DebeziumJdbcStorageOperations.getLatestRecordTimestamp` when the in-memory value is `-1`), it calls `debeziumChangeEventCapture.stop()`, sleeps, and calls `start(..., forceStart = true)`, which re-reads the configuration file from disk.
3. `start(recordParserService, props, forceStart)`: when `forceStart` is true the configuration file is reloaded; then a new `DebeziumChangeEventCapture` is constructed and `setup(props, recordParserService, forceStart)` is called. `setup` creates the offset-storage database (`DebeziumJdbcStorageOperations.createDatabaseForDebeziumStorage`), creates the replica-status view when `replica.status.view` is configured (spec 10.03), runs the MySQL keyless-table preflight (`KeylessTablePreflight.check(props)`, warning-only, never blocks startup) and starts the embedded Debezium engine with `handleChangeEventBatch` as the batch consumer (spec 01.03).
4. `DebeziumEmbeddedRestApi.startRestApi(...)` starts the REST API. A failure there is logged and does not stop the engine.

### 3.3 Stop Sequence
`stop()` delegates to `DebeziumChangeEventCapture.stop()`, which shuts down the batch executor pool (`shutdown()` then `awaitTermination(60, SECONDS)`), the single-threaded Debezium event executor when present, and closes the Debezium engine. Each step's failure is logged and the next step still runs.

The application registers **no JVM shutdown hook**. On SIGTERM the process relies on the embedded engine's own shutdown handling and on at-least-once redelivery from the last committed offset at the next start (specs 02.04, 09.03). There is no explicit "flush in-flight batches, await quiescence, commit final offsets" step in the application code; offsets are only ever committed by the writer path after rows are in ClickHouse (specs 09.01, 09.02), so an abrupt stop costs redelivery, never data.

---

## 4. Invariants Preserved
- **Invariant I8 (Durable Offset Quiescence)**: the durable position cannot advance past unwritten data on shutdown because nothing on the shutdown path commits offsets.
- **Fail-Fast Boot**: an unparseable configuration file exits the process with a non-zero code.

---

## 5. Verification Criteria
- `KeylessTablePreflightTest` — the keyless-table preflight that `setup` runs (`testCheckNeverThrowsWhateverTheSourceLooksLike`, `testSkipPropertyBypassesTheCheck`).
- `DebeziumEmbeddedRestApiDoubleStartTest` — the REST-driven restart path.
- Verification: bootstrap/configuration-resolution and graceful shutdown on SIGTERM are not yet covered by an automated test (gap).

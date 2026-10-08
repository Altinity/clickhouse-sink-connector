# Spec 01.01: Embedded CDC Engine Lifecycle & Bootstrap

## 1. Executive Summary & Purpose
Specifies the startup, dependency injection, runtime orchestration and shutdown of the standalone embedded CDC application (`ClickHouseDebeziumEmbeddedApplication`). In lightweight mode the connector runs as a self-contained process (embedded Debezium engine) without a Kafka broker.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ClickHouseDebeziumEmbeddedApplication.java`
- **Dependency Injection**: `com.altinity.clickhouse.debezium.embedded.AppInjector` (Guice module; there is no `injector` sub-package)
- **Engine wrapper**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java` (`setup(...)`, `stop()`, `drainBeforeStop()`, `stopDrainTimeoutMs`)
- **System/offset database connection retry**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/SystemDbConnectionRetry.java` (`createSystemDbConnectionWithRetry`, `connectWithRetry`, `SYSTEM_DB_CONNECT_ATTEMPTS`, `SYSTEM_DB_CONNECT_RETRY_MS`; section 3.5)
- **Offset FIFO reset on restart**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/DebeziumOffsetManagement.java` (`reset()`, `outstandingCount()`; spec 09.01 §3.8)
- **Restart-monitor idleness source**: `ClickHouseDebeziumEmbeddedApplication.effectiveLastRecordTimestamp(long, long)`
- **Row-image preflight**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/BinlogRowImagePreflight.java` (`check(Properties)`, `check(Properties, Connection)`, `QUERY`, `SKIP_PROPERTY`)
- **Binlog keep-alive preflight**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/BinlogKeepAlivePreflight.java` (`apply(Properties)`, `PROPERTY`, `SAFE_VALUE`; spec 01.07)
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
3. `start(recordParserService, props, forceStart)`: when `forceStart` is true the configuration file is reloaded; then a new `DebeziumChangeEventCapture` is constructed and `setup(props, recordParserService, forceStart)` is called. `setup` FIRST looks at `DebeziumOffsetManagement.hasUnwrittenBatches()`: if a previous engine in this process left handed-off batches unacknowledged and that engine is still alive (`activeEngine.isAlive()`, its pool can still write them) it refuses (`IllegalStateException`) — `stop()` has not abandoned them and starting on top of them would park every new unit behind them forever; if that engine terminated without `stop()` it abandons them at WARN and starts from a quiescent FIFO (spec 09.01 §3.8). It then creates the offset-storage database (`DebeziumJdbcStorageOperations.createDatabaseForDebeziumStorage`), creates the replica-status view when `replica.status.view` is configured (spec 10.03), runs the MySQL keyless-table preflight (`KeylessTablePreflight.check(props)`, warning-only, never blocks startup), runs the MySQL **row-image preflight** (`BinlogRowImagePreflight.check(props)`, below), applies the **binlog keep-alive preflight** (`BinlogKeepAlivePreflight.apply(props)`, spec 01.07: `connect.keep.alive` is set to `false` when the configuration does not choose it, so a lost binlog connection stops the engine and the restart resumes from the durable offset — a transaction boundary — instead of the client reconnecting from its last-read byte offset inside a statement and Debezium skipping the rest of that statement; an explicit `true` is kept and warned about) and starts the embedded Debezium engine with `handleChangeEventBatch` as the batch consumer (spec 01.03).

   **Row-image preflight (MySQL only).** The connector turns every row event into a full-row insert whose newest version replaces the previous one column for column; that is only correct when every row event carries every column. With `binlog_row_image=MINIMAL` an UPDATE's after-image omits the columns the statement did not change, so every update of every table is replicated with those columns as NULL (or a ClickHouse default) — a value-level divergence on every update with row counts intact; with `NOBLOB` the same happens to every BLOB/TEXT column. Nothing downstream can recover a value the source never logged, so unlike the keyless report this is all-or-nothing: `BinlogRowImagePreflight.check` reads `SELECT @@GLOBAL.binlog_row_image` (the `SHOW GLOBAL VARIABLES LIKE 'binlog_row_image'` value, issued as a SELECT so it passes the read-only allowlist) on a read-only connection and **refuses to start** (`IllegalStateException`, ERROR banner naming the value and the fix `SET GLOBAL binlog_row_image = FULL`) when the value is readable and not `FULL`. A source that cannot be asked (unreachable, permission denied, empty result) is logged at WARN and allowed through, like the keyless check. `binlog.row.image.check.skip=true` lets a non-FULL source through with a WARN banner on every start. Non-MySQL connectors are not checked.
4. `DebeziumEmbeddedRestApi.startRestApi(...)` starts the REST API. A failure there is logged and does not stop the engine.

### 3.3 Stop Sequence
`stop()` delegates to `DebeziumChangeEventCapture.stop()`, which runs these steps in this order; each step's failure is logged and the next step still runs:
1. **Close the engine** (`engine.close()`) — the producer stops first, so nothing more is handed off while the workers are still running.
2. Shut down the single-threaded Debezium event executor (`shutdown()`, `awaitTermination(60, SECONDS)`); `engine.run()` returns once the engine is closed.
3. **Drain** (`drainBeforeStop()`): while the pool is still running, wait until `isPipelineQuiescent()` (legacy queue empty, every routed queue empty, `!hasUnwrittenBatches()`), bounded by `stopDrainTimeoutMs` (60 s). Work a live worker can still finish is written rather than left in a queue. It is not acknowledged: the engine's offset store closed with the engine in step 1, and the engine's `connectorStopped` callback (`onConnectorStopped()`) has already retired every unit it handed off (spec 09.01 §3.8 item 5) — a worker that reports one of them written is answered `false` and moves on, and the rows are redelivered from the last committed offset at the next start (idempotent, spec 02.04). Before retirement the same report reached the closed store through the stopped engine's committer (`NullPointerException`, then an `OffsetStorageWriter` stuck "already flushing", then a dead worker). A timeout is logged (naming the backlog via `describePendingHandoff()`) and tolerated. Skipped when there is no pool (single-threaded mode).
4. Shut down the batch executor pool (`shutdown()`, `awaitTermination(60, SECONDS)`).
   4a. **Close each worker's connections** (`ClickHouseBatchRunnable.closeConnections()` on every runnable the engine scheduled, then forget the runnables). The pool has terminated, so no worker can touch its connections again; every engine restart discards the pool and schedules a new one, and before this step the discarded workers' per-database and system connections were never closed — `thread.pool.size` × databases of them leaked per restart, for the life of the process. The step closes three kinds of handle: the per-database connections, the system connection, and **the connection each cached table writer currently holds** (`BaseDbWriter.heldConnection()`, read without re-acquiring). The last is not always one of the first: a writer is built on the per-database connection, but `BaseDbWriter.getConnection()` replaces a closed or evicted handle with a fresh pool checkout that only the writer knows — closing the per-database map alone returned the stale original (a no-op) and left the replacement checked out of the pool for the life of the process, one pool slot per reconnected writer per restart, until the pool ran dry. A connection that fails to close is logged and skipped; the step never throws.
5. **Reset the offset FIFO** (`DebeziumOffsetManagement.reset()`): every unit still outstanding is abandoned and the count is returned by `stop()` and logged at WARN. Nothing abandoned was acknowledged, so the next engine redelivers it from the last committed offset — the cost of a restart is redelivery, never loss and never a rolled-back offset (`Replication.OffsetFifo.acked_never_rolled_back`).

**Why this order (the failure it prevents).** The FIFO in `DebeziumOffsetManagement` is static, but the engine is restarted INSIDE the process by REST `/restart`, by `/start` after `/stop`, and by the restart monitor: a new `DebeziumChangeEventCapture` on the same FIFO. The previous `stop()` shut the pool down FIRST (cancelling the periodic workers and abandoning every queued batch), closed the engine LAST, and never touched the FIFO. A unit the old engine had handed off but no worker had written stayed the FIFO head for the life of the JVM: every unit of the new engine parked behind it, no offset was ever acknowledged again, `hasUnwrittenBatches()` stayed true (no control-record commit; every DDL drain timed out into a restart loop), rows kept being inserted while the durable offset froze, and the parked units' record lists leaked (`Replication.OffsetFifo.old_restart_poisons_fifo`).

### 3.4 REST operations act on the engine that is running now
The REST server (`DebeziumEmbeddedRestApi.startRestApi`) is started once per JVM, while `/start`, `/restart` and the restart monitor replace the engine with a new `DebeziumChangeEventCapture` (`ClickHouseDebeziumEmbeddedApplication.start`). An engine reference captured at server start is therefore stale after the first restart.
1. `/flush` and `/resume` resolve the engine at request time through `DebeziumEmbeddedRestApi.liveEngine`, which returns `ClickHouseDebeziumEmbeddedApplication.currentEventCapture()` (the instance passed to `startRestApi` only when the application holds none). The application's engine field is `volatile`: it is written by REST pool and monitor threads and read by handler threads.
2. `DebeziumChangeEventCapture.flushAndPause()` and `resumeAfterFlush()` refuse (`IllegalStateException`, HTTP 500) when the batch executor is missing or shut down. Pausing a stopped engine's pool pauses nothing; answering 200 "flushed" would tell the checksum tool that writes stopped while the live engine keeps writing.
3. Every REST handler that opens a ClickHouse connection (`/status`, `DELETE /offsets`, `DELETE /schema-history`, `/show-slave-status`, `/binlog`, `/lsn`) closes it with try-with-resources, including when the storage operation throws: a failing monitoring poll must not leak one connection per call.

The application registers **no JVM shutdown hook**. On SIGTERM the process relies on the embedded engine's own shutdown handling and on at-least-once redelivery from the last committed offset at the next start (specs 02.04, 09.03). Offsets are only ever committed by the writer path after rows are in ClickHouse (specs 09.01, 09.02), so an abrupt stop costs redelivery, never data.

### 3.5 System-database connection retry (`SystemDbConnectionRetry`)
`setupDebeziumEventCapture` obtains the system-database connection exactly once per
engine, via `DebeziumChangeEventCapture.setSystemDbConnection` ->
`SystemDbConnectionRetry.createSystemDbConnectionWithRetry`. That connection is then
reused for the Debezium offset-storage database (`offset.storage.jdbc.url` points at
the same system database), for the Debezium storage-database creation and for the
ClickHouse version lookup. With `connection.pool.disable=true` (the docker-compose
stacks) there is no pool to obtain a replacement from later, so a `null` here is
terminal for the process: every later query fails and the connector never creates the
destination tables.

`SystemDbConnectionRetry.connectWithRetry(supplier, retryMs)` retries up to
`SYSTEM_DB_CONNECT_ATTEMPTS` (30) times, `SYSTEM_DB_CONNECT_RETRY_MS` (2000 ms) apart,
returning the first non-null connection or `null` after the budget is spent (logged at
ERROR). This tolerates a cold start where compose's healthcheck passes moments before
ClickHouse is actually serving: verified against clickhouse-jdbc 0.9.8, the V2 driver
returns a connection object lazily even when nothing is listening, while the V1 driver
throws `Connection refused`, which `BaseDbWriter.createConnection` converts to `null`.
The 30 x 2000 ms budget (60 s) exceeds the compose healthcheck `start_period` (30 s)
with margin.

---

## 4. Invariants Preserved
- **Invariant I8 (Durable Offset Quiescence)**: the durable position cannot advance past unwritten data on shutdown because nothing on the shutdown path commits offsets.
- **Fail-Fast Boot**: an unparseable configuration file exits the process with a non-zero code.

---

## 5. Verification Criteria
- `KeylessTablePreflightTest` — the keyless-table preflight that `setup` runs (`testCheckNeverThrowsWhateverTheSourceLooksLike`, `testSkipPropertyBypassesTheCheck`).
- `DebeziumEmbeddedRestApiDoubleStartTest` — the REST-driven restart path.
- `FlushTargetsLiveEngineTest.handlersResolveTheEngineRunningNow` — §3.4 item 1: with the application holding a new engine, `liveEngine` returns it, not the instance the server was started with (pre-fix handlers used the startup instance).
- `FlushTargetsLiveEngineTest.handlersFallBackToTheStartupInstance` — §3.4 item 1: no application engine → the startup instance; neither → `IllegalStateException`.
- `FlushTargetsLiveEngineTest.flushOfAStoppedEngineIsRefused` — §3.4 item 2: a shut-down pool → `flushAndPause()` and `resumeAfterFlush()` throw (pre-fix: returned normally).
- `FlushTargetsLiveEngineTest.flushOfARunningEnginePausesItsPool` — §3.4 item 2: a running pool is paused and released.
- `EngineRestartFifoResetTest.stopThenStartNewInstanceIsNotPoisoned` — a unit handed off and never written; `stop()`; a NEW capture's heartbeat is committed and its first written unit is acknowledged with nothing parked (fails on the old `stop()`: the ghost stays outstanding).
- `EngineRestartFifoResetTest.stopLeavesNothingOutstanding` — after `stop()` the outstanding set, the unwritten-group map and the parked-unit map are empty and the pool is shut down.
- `EngineRestartFifoResetTest.stopClosesEngineBeforeShuttingThePool` — the engine's `close()` observes a still-running pool; the pool is shut down afterwards.
- `EngineRestartFifoResetTest.stopDrainsInFlightWorkBeforeShuttingThePool` — a unit a live worker finishes 300 ms later is acknowledged by the drain, `stop()` returns 0 abandoned.
- `SystemDbConnectRetryTest` — section 3.5: `testReturnsImmediatelyWhenFirstAttemptSucceeds`, `testRetriesUntilConnectionAvailable`, `testGivesUpAfterConfiguredAttempts`, `testRetryBudgetCoversARealisticColdStart`.
- `StoppedEngineRetiresHandoffsTest.connectorStoppedRetiresTheEnginesUnits` — the `connectorStopped` callback (`onConnectorStopped()`) retires every unit the engine handed off and marks replication not running; `StoppedEngineRetiresHandoffsTest.completionCallbackRetiresTheStoppedEnginesUnitsBeforeRetrying`, `StoppedEngineRetiresHandoffsTest.cleanCompletionRetiresTheEnginesUnits` — the completion callback retires them before it retries, and on a clean completion too (spec 09.01 §3.8 item 5).
- `EngineRestartFifoResetTest.liveEngineStillRefusesASecondEngine` — `setup()` throws `IllegalStateException` while a unit of a still-alive engine is outstanding; `EngineRestartFifoResetTest.deadEngineWithoutStopIsAbandonedNotPoisoning` — a previous engine that terminated without `stop()` has its leftovers abandoned by `setup()` instead (spec 09.01 §3.8).
- `RestartMonitorTimestampTest` — `effectiveLastRecordTimestamp(-1, stored) == stored`, in-memory wins, `(-1, -1) == -1`.
- `BinlogRowImagePreflightTest.minimalIsRefused`, `BinlogRowImagePreflightTest.noblobIsRefused` — a stub source answering `MINIMAL` / `NOBLOB` makes `check` throw `IllegalStateException` naming the variable, the value and the fix.
- `BinlogRowImagePreflightTest.fullPasses`, `BinlogRowImagePreflightTest.unreadableIsWarnedNotRefused`, `BinlogRowImagePreflightTest.skipIsLoud`, `BinlogRowImagePreflightTest.nonMysqlAndUnreachablePassThrough`, `BinlogRowImagePreflightTest.queryIsReadOnly`.
- `BinlogKeepAlivePreflightTest.absentIsDefaultedToFalse`, `BinlogKeepAlivePreflightTest.explicitTrueIsKeptAndWarned`, `BinlogKeepAlivePreflightTest.nonBinlogConnectorsAreUntouched` — the keep-alive preflight `setup` applies (spec 01.07).
- `Replication.OffsetFifo.restart_quiescent`, `Replication.OffsetFifo.acked_never_rolled_back`, `Replication.OffsetFifo.old_restart_poisons_fifo`, `Replication.OffsetFifo.restart_unblocks_next_engine`.
- Verification: bootstrap/configuration-resolution and graceful shutdown on SIGTERM are not yet covered by an automated test (gap).

---

## 6. Failure Modes & Recovery
The lifecycle's recovery posture is "stop loudly, restart from the durable offset": every engine failure reaches `handleEngineCompletion`, which recreates the engine inside the process a bounded number of times and then exits with code 3 for the supervisor (the ansible systemd unit: `Restart=always`, `RestartSec=30`, `StartLimitBurst=5` per `StartLimitInterval=300`). The gaps are the paths that leave the JVM up with nothing replicating, the restart monitor, and the absence of any heap-exhaustion exit.

- **FM-01.01-1 Engine failure: in-process retry budget, then terminal exit**
  - **Trigger**: any engine error — a lost source connection (spec 01.07 FM-01.07-1), an exception out of `handleChangeEventBatch` (DDL, unconvertible row, hard-cap timeout, dead worker), an offset-store failure.
  - **Behaviour**: `DebeziumChangeEventCapture.handleEngineCompletion` first retires the stopped engine's handoffs (`retireHandoffsOfStoppedEngine`), then: a FATAL classification (`isDeterministicFailure`) or a dead worker (`hasDeadWorker`) is terminal at once; anything else sleeps `SLEEP_TIME` (10 s) and recreates the engine (`setupDebeziumEventCapture`) up to `MAX_RETRIES` (`errors.max.retries`, default 10) times, the count refilled only by an acknowledged offset (`DebeziumOffsetManagement.acknowledgements()`); when spent, `onTerminalFailure` marks replication stopped and calls `terminalFailureHook` (`System.exit(3)`) unless `exit.on.terminal.failure=false`. Every Debezium 3.1.3 MySQL producer failure reaches this callback: `MySqlErrorHandler.isRetriable` walks the cause chain to its end and returns `super.isRetriable(null)` = false, so nothing is retried inside the embedded engine (read in the bytecode of `debezium-connector-mysql-3.1.3.Final.jar`).
  - **Detection**: ERROR `Engine stopped with an error: <throwable> Message: <message>` at once, ERROR `Restarting the engine - retry N of M` per attempt, FATAL `Replication is STOPPED: the engine failed N time(s) in a row (errors.max.retries=N) and will not be restarted.` and exit code 3 after ≈ 10 × (10 s + engine start); a production deployment logged retry cycles 13–19 s apart (comment in `handleEngineCompletion`), so ≈ 2–3 min.
  - **Blast radius**: all tables stop; no loss (nothing is acknowledged past the failure, spec 09.01); the redelivered tail is rewritten and collapses under ReplacingMergeTree.
  - **Recovery**: self-heals if the cause clears within the budget; otherwise systemd restarts the process 30 s after exit and it resumes from the durable offset. Fix the cause named in the last `Engine stopped with an error:` line.
  - **RTO**: after the cause clears ≤ 10 s + engine start (3–9 s observed) + redelivery of the in-flight units (≤ `sink.connector.handoff.max.outstanding.records`), or 30 s + process start between processes; unmeasured end to end — no harness times it.
  - **Test**: `TerminalFailureExitTest.exitHookFiresAfterMaxRetries()`, `TerminalFailureExitTest.progressResetsTheBudget()`, `EngineFailureClassificationTest.sourceConnectionLossStillRetries()`.

- **FM-01.01-2 Refusal at startup (fail-fast boot) meets the supervisor's start limit**
  - **Trigger**: an unparseable configuration file, a source with `binlog_row_image` not FULL, an unresolvable source session time zone (spec 07.03), a compression decoder that does not work (spec 01.08).
  - **Behaviour**: `main` calls `System.exit(-1)` on a parse failure (`loadPropertiesFile`); a preflight (`BinlogRowImagePreflight.check`, `ConnectionTimeZonePreflight.check`, `BinlogTransactionCompressionPreflight.check`) throws `IllegalStateException` out of `setup` → `start` → `main`, before `Metrics.initialize` and the REST API start any non-daemon thread, so the JVM ends (exit status 1 for an uncaught exception in `main`; that no other non-daemon thread exists at that point is inferred, not verified). systemd restarts it every 30 s; the 5th start inside 300 s trips `StartLimitBurst` and the unit stays failed.
  - **Detection**: ERROR `Error parsing configuration file, USAGE: ...` or the preflight's refusal (for the row-image preflight the exception text `Refusing to start: ...`), within seconds of each start; `systemctl status` shows the unit failed with `start-limit-hit` after ≈ 2.5 min.
  - **Blast radius**: nothing replicates; nothing is written or acknowledged, so nothing can be lost.
  - **Recovery**: fix the named cause (e.g. `SET GLOBAL binlog_row_image = FULL`, or `binlog.row.image.check.skip=true` knowingly), then `systemctl [--user] reset-failed <unit>` and start it.
  - **RTO**: 30 s + process start once fixed; unmeasured.
  - **Test**: `BinlogRowImagePreflightTest.minimalIsRefused()`; GAP: no test asserts the process exit status of a refused start.

- **FM-01.01-3 The restart monitor restarts a healthy engine**
  - **Trigger**: `restart.event.loop=true` (code default false; the ansible `config.yml.j2` sets it true with `restart.event.loop.timeout.period.secs: "3000"`) while the connector is more than the timeout behind the source (catch-up after an outage, a long transaction whose statements executed long before its commit), or while the source writes no rows for the timeout (only heartbeats arrive).
  - **Behaviour**: the monitor's `TimerTask` (`setupMonitoringThread`) computes `now − ReplicationStatusSingleton.getLastRecordTimestamp()`, and `processEveryChangeRecord` sets that clock to `ClickHouseStruct.getTs_ms()` — the source STATEMENT time of the last row, not its arrival — while heartbeats never touch it. A lagging or idle connector therefore reads as idle and is stopped (`stop()`, up to 3 × 60 s) and restarted (`start(..., true)`, re-reading the config file) on every tick, every 3000 s, for as long as the lag or the idleness lasts; each restart abandons the in-flight units and re-reads the source from the durable offset (and the in-flight transaction from its BEGIN, spec 01.04 FM-01.04-1).
  - **Detection**: INFO `******* Restarting Event Loop ********` preceded by INFO `Last Record Timestamp: <ms> Delta: <s> Restart Event Loop Timeout: <s>` on each tick; no WARN or metric.
  - **Blast radius**: replication pauses for each restart; no loss (redelivery only); throughput of a catch-up drops by the restart cost every 50 min.
  - **Recovery**: none automatic; to stop the churn set `restart.event.loop: "false"` (ansible: define `skip_restart_event_loop`) and restart — which also removes the only in-process stall watchdog (FM-01.07-2).
  - **RTO**: each spurious restart costs stop (≤ 180 s) + 3 s + start + replay of the in-flight transaction; unmeasured.
  - **Test**: `RestartMonitorLivenessTest.laggingRowRefreshesTheMonitorClock()` (disabled, fails on 2.11.0); `RestartMonitorTimestampTest` pins only the stored-timestamp fallback.
  - **DEFECT**: the watchdog measures source statement time instead of arrival time, so it restarts healthy lagging or idle engines and cannot be tuned to detect a stall in minutes.

- **FM-01.01-4 Engine left stopped with the JVM up (zombie process)**
  - **Trigger**: (a) the monitor's restart fails after its `stop()` succeeded — the configuration file has since become unparseable, or the process was configured from the environment (no file: `loadPropertiesFile(null)` throws `NullPointerException` from `FileInputStream`); (b) any exception inside the monitor's tick (for example while it reads the stored timestamp from ClickHouse); (c) building or submitting the engine throws inside `setupDebeziumEventCapture`; (d) `exit.on.terminal.failure=false`.
  - **Behaviour**: the `TimerTask` catches the exception, logs it and rethrows a `RuntimeException`, which terminates the `java.util.Timer` thread and cancels every later tick (JDK `Timer` semantics); in case (a) the engine stays stopped. In case (c) the catch-all `catch (Exception e) { log.error("Exception", e); ... }` ends the start with no engine running, no retry and no exit — on the completion-callback path this silently ends the retry loop. Case (d) is the declared, loud variant (`onTerminalFailure`).
  - **Detection**: (a)(b) one ERROR `**** ERROR: Restarting Event Loop ****`, then nothing; (c) one ERROR `Exception` with a stack trace; `/status` `Replica_Running=false` after a stop (`onConnectorStopped`); the process stays up, so a supervisor or liveness probe watching the PID sees nothing. (d) FATAL `Replication is STOPPED ...`.
  - **Blast radius**: nothing replicates until a human acts; no loss (the durable offset stands); lag grows without bound.
  - **Recovery**: fix the configuration file, then restart the service (or `sink-connector-client start_replica`).
  - **RTO**: unbounded — detection depends on a human reading one log line.
  - **Test**: `TerminalFailureExitTest.exitDisabledIsALoudLivenessFailure()` pins (d); GAP: no test drives the monitor tick or a failing `setupDebeziumEventCapture`.
  - **DEFECT**: three code paths stop replication for good while the process keeps running and exits nothing a supervisor can act on.

- **FM-01.01-5 Process killed (SIGTERM, kill -9, OOM killer, host reboot)**
  - **Trigger**: the process ends without `stop()`.
  - **Behaviour**: no JVM shutdown hook is registered; offsets are committed only after rows are in ClickHouse (spec 09.01), so the next start resumes from the last committed offset and rewrites what was in flight — bounded by the hard cap (`sink.connector.handoff.max.outstanding.records` 500 000 / `.bytes` ¼ heap, spec 01.05) plus Debezium's queue.
  - **Detection**: systemd journal `Main process exited` with the signal, 0 s; the connector log ends without `stop()` lines.
  - **Blast radius**: all tables pause; no loss; duplicates of the redelivered tail collapse under ReplacingMergeTree (spec 10.05).
  - **Recovery**: automatic under systemd (`Restart=always`, 30 s). The shipped docker-compose files set `restart: "no"` (test stacks); a container deployment must set its own restart policy.
  - **RTO**: 30 s + process start (schema-history recovery from ClickHouse, binlog connect) + redelivery of at most the cap; unmeasured — the kill -9 cases of spec 01.08 §5 (L3, H9) prove convergence but record no time.
  - **Test**: `EngineRestartFifoResetTest.deadEngineWithoutStopIsAbandonedNotPoisoning()` (the in-process analogue); GAP: no process-level kill test in this module.

- **FM-01.01-6 Heap exhaustion: GC thrash or OutOfMemoryError does not end the process**
  - **Trigger**: live heap near `-Xmx` (ansible default `-Xmx4G -Xms4G`): rows the byte cap under-charges (spec 01.05 FM-01.05-2), a cap set to 0, many wide tables.
  - **Behaviour**: neither the systemd unit nor the Dockerfile passes `-XX:+ExitOnOutOfMemoryError`; the JDK 17 default collector (G1) has no GC-overhead limit, so a heap held full by reachable rows spins in back-to-back full collections without throwing. A thrown `OutOfMemoryError` in a sink worker passes `ClickHouseBatchRunnable.run`'s `catch (Exception e)`, kills the worker's scheduled task, and the next source batch stops the engine as a dead worker (terminal, exit 3); in the Debezium event thread it becomes a handler failure on the retry path (`isDeterministicFailure` ignores `Error`s); in other threads (binlog reader, REST, monitor) the effect is unverified.
  - **Detection**: none for GC thrash — no GC-time metric, no log line, `/status` still reports running; a thrown OOM appears as the dead-worker ERROR (spec 03.01).
  - **Blast radius**: all tables stall; the source eventually aborts the undrained dump; no loss.
  - **Recovery**: restart; then lower `sink.connector.handoff.max.outstanding.records` / `.bytes`, `max.batch.size` or `buffer.max.bytes`, or raise `-Xmx`; add `-XX:+ExitOnOutOfMemoryError` to `sink_connector_java_opts` so a thrown OOM becomes a supervisor restart.
  - **RTO**: unbounded while thrashing (no detector); 30 s + start after an exit; unmeasured.
  - **Test**: GAP: no test runs the connector against a heap it can exhaust.
  - **DEFECT**: a GC death spiral is neither detected nor turned into an exit.

- **FM-01.01-7 In-process restart (REST `/restart`, `/stop` then `/start`, the monitor) with work in flight**
  - **Trigger**: an operator or the monitor restarts the engine while units are queued, in flight or parked.
  - **Behaviour**: `stop()` closes the engine first, waits ≤ 60 s for the event thread, drains through the live pool ≤ `stopDrainTimeoutMs` (60 s), shuts the pool ≤ 60 s, closes worker connections and resets the FIFO; `setup()` refuses to start beside a live predecessor and abandons a dead one's leftovers. A reader parked at the hard cap is not interrupted by `engine.close()`: the FIFO reset wakes it and it may register one more unit into the discarded pool, which the next `setup()` abandons (inferred from `DebeziumOffsetManagement.reset()` notifying waiters; not reproduced).
  - **Detection**: WARN `stop(): N handed-off batch(es) were still unacknowledged when the worker pool terminated and have been abandoned.` or WARN `stop(): ... still pending after 60000 ms; shutting the pool down anyway.`; WARN `setup(): the previous engine in this process terminated without stop(); ...`.
  - **Blast radius**: replication pauses ≤ ~3 min; abandoned units are redelivered, never lost.
  - **Recovery**: automatic.
  - **RTO**: ≤ 180 s stop + 0.5 s (REST) or 3 s (monitor) + start + redelivery; unmeasured.
  - **Test**: `EngineRestartFifoResetTest.stopThenStartNewInstanceIsNotPoisoned()`, `EngineRestartFifoResetTest.stopDrainsInFlightWorkBeforeShuttingThePool()`, `EngineRestartFifoResetTest.liveEngineStillRefusesASecondEngine()`, `StoppedEngineRetiresHandoffsTest.connectorStoppedRetiresTheEnginesUnits()`.

Summary: 7 failure modes, 3 DEFECT, 4 GAP.

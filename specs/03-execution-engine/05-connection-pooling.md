# Spec 03.05: HikariCP Connection Pooling & Lifecycle Management

## 1. Executive Summary & Purpose
Specifies the JDBC connection lifecycle and HikariCP connection pool management (`HikariDbSource`) used by the writers to communicate with ClickHouse.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/HikariDbSource.java` (there is no `HikariCPConnectionPool` class)
- **Pool creation entry point**: `BaseDbWriter.createConnection(...)` in `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/BaseDbWriter.java` — builds a `SinkConnectorDataSource` and, unless pooling is disabled, calls `HikariDbSource.getInstance(dataSource, databaseName, config, userName, password)`
- **Key methods**:
  - `public static HikariDataSource getInstance(SinkConnectorDataSource, String databaseName, ClickHouseSinkConnectorConfig, String userName, String password)`
  - `private static HikariDataSource createConnectionPool(...)`
  - `public static Connection initiateNewConnectionIfClosed(String databaseName, String jdbcUrl)` (and the name-only overload)
  - `static String poolKey(String jdbcUrl, String databaseName)`; `static boolean isReachable(String endpoint)`
  - `public static void close()`, `public static void closeDatabaseConnection(String databaseName)`
- **Configuration** (`ClickHouseSinkConnectorConfigVariables`): `connection.pool.disable`, `connection.pool.max.size`, `connection.pool.timeout`, `connection.pool.min.idle` (read but not applied — `setMinimumIdle` is commented out), `connection.pool.max.lifetime`
- **Worker database bootstrap**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchRunnable.java`
  — `getClickHouseConnection(String)`, `ensureDatabaseExists(String)`,
  `openConnection(String, String)` (the single call site of
  `BaseDbWriter.createConnection`, `@VisibleForTesting`), static
  `ENSURED_DATABASES`.
- **Connection factory**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/BaseDbWriter.java`
  — `createConnection(...)`, `dropV1OnlyProperties(Properties)`, static
  `WARNED_V1_ONLY_PROPERTIES`.

---

## 3. Operational Specification

### 3.1 Pool Initialization
1. `BaseDbWriter.createConnection` builds the JDBC properties (`client_name`, `custom_settings`, user `clickhouse.jdbc.params`, and `http_connection_provider=HTTP_URL_CONNECTION` when pooling is enabled) and a `SinkConnectorDataSource` carrying the real `jdbc:clickhouse://host:port/db` URL. `custom_settings` is computed by `BaseDbWriter.customSettings(userSettings)`: the default list `input_format_null_as_default=0,allow_experimental_object_type=1,insert_allow_materialized_columns=1`, or the user's `clickhouse.jdbc.settings` with `input_format_null_as_default=0` appended when the user list does not mention that key (Spec 07.07 §3.2.1 — the server must never substitute a DEFAULT for a bound NULL).
2. When `connection.pool.disable = true`, a plain connection is taken from that data source; no pool exists.
3. Otherwise `HikariDbSource.getInstance` looks up (or creates via `createConnectionPool`) the pool for the key `host:port|database`. `createConnectionPool` sets `poolName = "clickhouse-" + databaseName`, `connectionTimeout`, `maximumPoolSize`, `maxLifetime`, `setDataSource(chDataSource)` (no `setJdbcUrl`/driver class), credentials, and registers the Prometheus meter registry when metrics are enabled.

### 3.2 Pool keying and server identity
Pools are held in a static map keyed by `host:port|database`, not by database name alone: more than one server can be addressed under the same database name at the same time, and a name-keyed cache would let each caller destroy the other's pool. `currentKey` remembers the pool most recently requested per database name for callers that only know the name.

### 3.3 Connection acquisition and dead-server handling
`initiateNewConnectionIfClosed(databaseName, jdbcUrl)`:
1. Returns `null` when pooling is disabled.
2. Resolves the pool key from the URL (or the last requested key for that database); an endpoint previously retired as dead fails fast with `SQLException` instead of retrying in a hot loop.
3. Falls back to the system-database pool on the SAME server when the database has no pool of its own; throws when neither exists.
4. Drops a closed pool and throws; otherwise `retireIfServerGone(key)` probes the endpoint (2 s TCP connect) if it was previously live, and on failure closes and drops every pool for that endpoint and marks it dead.
5. Returns `dbSource.getConnection()`.

Connections are used by one writer thread for the duration of a batch and returned to the pool when closed.

### 3.3 Worker database bootstrap (`ClickHouseBatchRunnable.getClickHouseConnection`)
Each worker caches one `Connection` per destination database in
`databaseToConnectionMap`. On a cache miss for database `D` on server `H:P`:
1. **Ensure-once.** `CREATE DATABASE IF NOT EXISTS \`D\`` (via
   `ClickHouseCreateDatabase.createNewDatabase`, `ON CLUSTER` when
   `auto.create.tables.replicated` is set) is issued at most ONCE per process
   per `(H, P, D)`. The process-level, worker-shared set `ENSURED_DATABASES`
   (key `H:P/D`) records it, and the key is added only after the statement
   succeeded. Consequences:
   - N workers issue the statement once, not N times;
   - the former SECOND unconditional `CREATE DATABASE IF NOT EXISTS` (a plain
     duplicate of the first, issued through `DBMetadata.executeSystemQuery`) is
     removed — exactly one statement per key;
   - a worker whose database connection keeps coming back null does NOT re-issue
     the statement on every batch: once ensured, only the connection is retried.
2. **Unreachable server.** If no system connection can be obtained
   (`openConnection` returns null), an ERROR naming the database and the server
   is logged, no statement is attempted and the key is NOT marked ensured. The
   database connection is still attempted (as before) so the caller's null
   handling and retry path are unchanged.
3. **Statement failure.** Logged at ERROR naming the database; the key is not
   marked (the statement is retried on the next miss); the database connection
   is still attempted — parity with the previous behaviour, so a user that may
   write to an existing database but lacks `CREATE DATABASE` is not blocked.
4. **Database connection null.** Logged at ERROR naming the database and the
   server; the null is NOT cached (`containsKey` would otherwise pin the miss)
   and is returned to the caller, which retries on the next tick. Silent
   retry-forever is not permitted (Invariant I9).
5. **Testing seam.** `openConnection(jdbcUrl, databaseName)` is the only place
   the runnable calls `BaseDbWriter.createConnection`; tests override it to
   supply recording or null connections.

### 3.4 V1-only JDBC property filtering is reported once per JVM
`BaseDbWriter.dropV1OnlyProperties` runs on EVERY `createConnection()` call and
removes the properties the V2 driver rejects (`keepalive.timeout`,
`max_buffer_size`). The removal happens every time; the WARN
("Ignoring JDBC property ...") is emitted once per property key per JVM
(static `WARNED_V1_ONLY_PROPERTIES`), and at DEBUG thereafter. With a worker
pool the call runs several times a minute for the life of the process, and a
WARN each time is noise that hides real warnings.

---

## 4. Invariants Preserved
- **No cross-server crossover**: two engines addressing the same database name on different servers never receive each other's pool.
- **Fail fast on a dead server**: once an endpoint is retired, requests fail immediately rather than rebuilding pools against an unreachable server.
- **Leak-Free Connection Hygiene**: All connections are closed or returned to the pool on failure, preventing connection pool exhaustion.
- **Invariant I9 (Loud Failure)**: a database that cannot be ensured or connected
  to is reported at ERROR naming the database, never retried silently.
- **Signal over noise**: bootstrap and compatibility messages are emitted once
  per process per subject, so WARN/ERROR lines remain actionable.

---

## 5. Verification Criteria
- `PoolServerIdentityTest` — pool keying by server endpoint and database.
- `ConcurrentServerPoolTest`, `ConcurrentServerPoolLiveIT` — concurrent servers under the same database name.
- `ClickHouseUnavailableTest` — behaviour when the server is unreachable.
- `ClickHouseBatchRunnableDatabaseBootstrapTest.createDatabaseIsIssuedOncePerDatabaseAcrossWorkersAndCalls`
  — two workers, five `getClickHouseConnection` calls each for the same
  database, recording connections: exactly one `CREATE DATABASE` statement is
  executed. Fails on the pre-fix code (two per worker).
- `ClickHouseBatchRunnableDatabaseBootstrapTest.unreachableServerLogsErrorNamingTheDatabase`
  — `openConnection` returns null: an ERROR from `ClickHouseBatchRunnable`
  names the database, no `CREATE DATABASE` is attempted, the database is not
  marked ensured, and null is returned. Fails on the pre-fix code (no such
  ERROR).
- `ClickHouseBatchRunnableDatabaseBootstrapTest.failedCreateDatabaseIsNotMarkedEnsured`
  — a failing statement is retried on the next miss and still logs at ERROR.
- `V1OnlyJdbcPropertiesTest.warnsOncePerPropertyKeyPerJvm` — two
  `dropV1OnlyProperties` calls with the same keys produce exactly one WARN per
  key; both calls still remove the properties. Fails on the pre-fix code (two
  WARNs per key).

---

## 6. Failure Modes & Recovery

The pool layer's posture is "a failed connection fails the batch, the worker retries": no connection failure is terminal by itself, and a worker that holds a usable handle recovers on its own when ClickHouse answers again. The risks are in the retirement shortcut (a dead endpoint is refused until something registers it again), in the unsynchronised static registry, and in the V2 client's defaults. Library behaviour below was read from the bytecode of HikariCP 6.0.0, clickhouse `client-v2` / `jdbc-v2` 0.9.8 in the local Maven repository; it is marked where it was not.

- **FM-03.05-1 ClickHouse down or restarting while the connector runs**
  - **Trigger**: ClickHouse restart, crash, host reboot, port closed.
  - **Behaviour**: the INSERT fails: `client-v2` logs WARN `Failed to connect to '<host:port>': <reason>` and `jdbc-v2` maps a `ConnectionInitiationException` to SQLState `08000` (`ExceptionUtils.toSqlState`). HikariCP 6.0.0 `ProxyConnection.checkException` marks a connection broken on any `08*` SQLState and replaces its delegate, so the handle reports `isClosed()`. The worker classifies the failure UNKNOWN and retries (spec 03.03 §6 FM-03.03-1). On the next attempt `BaseDbWriter.getConnection` finds the handle unusable and calls `HikariDbSource.initiateNewConnectionIfClosed`, whose `retireIfServerGone` probes the endpoint (2 s TCP connect) and, on failure, closes every pool of that endpoint and marks it dead (FM-03.05-2).
  - **Detection**: per attempt ERROR `ClickHouseBatchRunnable exception - Task(<id>)` and WARN `Retriable ClickHouse error (Code: -1, Category: UNKNOWN) ...`; WARN `<pool> - Connection <c> marked as broken because of SQLSTATE(08000), ErrorCode(0)` (HikariCP); WARN `Retiring connection pools for '<host:port>': the server is no longer reachable. Further requests fail fast until it is registered again.`; ERROR `Error retrieving new connection in getConnection` (the cause is not logged). Within one attempt.
  - **Blast radius**: all writes stop; the reader is paced by the handoff cap; no loss.
  - **Recovery**: automatic once a connection can be obtained again — which, after a retirement, depends on FM-03.05-2; otherwise restart the connector.
  - **RTO**: without a retirement: outage + ≤ 30 s backoff. With one: see FM-03.05-2. Unmeasured: the chaos harness of spec 01.08 §6 covers the source transport, not ClickHouse.
  - **Test**: `ClickHouseUnavailableTest.testInitiateNewConnectionWithoutPool()`, `ClickHouseBatchRunnableDatabaseBootstrapTest.unreachableServerLogsErrorNamingTheDatabase()`; GAP: stop and restart ClickHouse under a running connector and measure the time to the first successful write.

- **FM-03.05-2 A retired endpoint is never probed again**
  - **Trigger**: FM-03.05-1 retired the endpoint; ClickHouse comes back.
  - **Behaviour**: `initiateNewConnectionIfClosed` refuses a dead endpoint without probing it (`ClickHouse server <host:port> is no longer reachable; not retrying for database: <db>`). Only `HikariDbSource.getInstance` clears the mark, i.e. a caller opening a new connection through `BaseDbWriter.createConnection`. Inside a worker that happens when `ClickHouseBatchRunnable.systemConnection()` re-opens an unusable system connection — reached only from `getServerTimeZone`, and only when `clickhouse.datetime.timezone` is empty — or on a `getClickHouseConnection` cache miss (the map keeps the broken handle, so not after the first open); other threads (the DDL path) may also register it. With `clickhouse.datetime.timezone` set, a worker can stay refused after ClickHouse is healthy until a DDL or a restart (inferred from the code; not measured).
  - **Detection**: ERROR `Error retrieving new connection in getConnection` and the FM-03.03-1 retry lines continue while ClickHouse is healthy (`SELECT 1` succeeds from the connector host).
  - **Blast radius**: writes stay stopped after the cause is gone; no loss.
  - **Recovery**: restart the connector (`HikariDbSource.close` clears both endpoint sets).
  - **RTO**: restart (~1 min) once noticed; automatic recovery has no bound.
  - **Test**: `RetiredEndpointRecoveryTest.registeringTheServerAgainClearsItsRetirement()`; `RetiredEndpointRecoveryTest.aRetiredEndpointThatAcceptsConnectionsAgainIsReprobed()` (disabled, fails on 2.11.0: a listening endpoint is refused).
  - **DEFECT**: the dead mark has no expiry and no re-probe; recovery after ClickHouse returns depends on an incidental re-registration instead of a bounded retry.

- **FM-03.05-3 Stale keep-alive connection (server `keep_alive_timeout`)**
  - **Trigger**: an idle pooled HTTP connection outlives ClickHouse's `keep_alive_timeout`, or a load balancer drops it; the next request uses it.
  - **Behaviour**: `client-v2` 0.9.8 retries, up to `retry` = 3 times, the causes in `client_retry_on_failures` — by default `NoHttpResponse`, `ConnectTimeout`, `ConnectionRequestTimeout`, `ServerRetryable` (`ClientConfigProperties`, `HttpAPIClientHelper.shouldRetry`). A `SocketException` (`Broken pipe`, `Connection reset`) while the request body is written is not in that list: it reaches the worker as an exception without a code, classified UNKNOWN, and the whole batch is retried after `batch.retry.backoff.initial.ms` (500 ms). That the retry gets a fresh socket is Apache HttpClient pool behaviour, not verified here.
  - **Detection**: ERROR `ClickHouseBatchRunnable exception - Task(<id>)` with the `SocketException`, WARN `Retriable ClickHouse error (Code: -1, Category: UNKNOWN) ... (consecutive failures: 1)`; normally none for a `NoHttpResponse` retried inside the client.
  - **Blast radius**: one batch delayed; the re-sent rows collapse under ReplacingMergeTree (spec 03.03 §6 FM-03.03-2 for other engines).
  - **Recovery**: none needed. To avoid it, set the `client-v2` property `http_keep_alive_timeout` (ms, name verified in `ClientConfigProperties`) below the server's `keep_alive_timeout` through `clickhouse.jdbc.params` — effect unmeasured.
  - **RTO**: ≤ 500 ms + one batch; unmeasured.
  - **Test**: GAP: an HTTP stub that half-closes an idle keep-alive socket, asserting one retried batch and no stall.

- **FM-03.05-4 Connection pool exhausted**
  - **Trigger**: checked-out connections are not returned — a writer's re-acquired connection is closed only by `closeConnections` at engine stop (spec 01.01 §3.3 step 4a) — or more concurrent holders than `connection.pool.max.size` (500 per `host:port|database` pool).
  - **Behaviour**: HikariCP blocks `getConnection` for `connection.pool.timeout` (50,000 ms) and throws `SQLTransientConnectionException`; `BaseDbWriter.createConnection` logs it and returns null; `ClickHouseBatchRunnable.getClickHouseConnection` logs, does not cache the null, and the batch fails and is retried.
  - **Detection**: ERROR `Error creating ClickHouse connection<exception>` and ERROR ``Could not obtain a ClickHouse connection to database `<db>` on <host>:<port>; the batch will be retried on the next tick.``, one pair per ≥ 50 s; HikariCP Prometheus metrics (`hikaricp_connections_pending`, `hikaricp_connections_timeout_total`) when metrics are enabled.
  - **Blast radius**: every worker writing that database stalls; no loss.
  - **Recovery**: restart (all pools are closed at stop); raise `connection.pool.max.size` if the demand is real.
  - **RTO**: restart (~1 min); no self-healing while the connections stay checked out.
  - **Test**: `ClickHouseBatchRunnableDatabaseBootstrapTest.unreachableServerLogsErrorNamingTheDatabase()` (the null-connection path), `ClickHouseBatchRunnableCloseConnectionsTest`; GAP: a pool of size 1 held by one caller, asserting the second caller's ERROR and retry.

- **FM-03.05-5 The static pool registry is not thread-safe**
  - **Trigger**: several workers (and the Debezium thread) create, look up or retire pools at the same time — at start, after a retirement.
  - **Behaviour**: `instance`, `currentKey`, `liveEndpoints`, `deadEndpoints` are plain `HashMap` / `HashSet` read and written without a lock by `getInstance`, `initiateNewConnectionIfClosed`, `retireIfServerGone` and `dropPool`; `printConnectionInfo`, called by every `BaseDbWriter.getConnection`, iterates `instance.values()` (its DEBUG string is built eagerly). Two first-time `getInstance` calls for one key can both build a `HikariDataSource`, and the loser is never closed; an iteration concurrent with a put throws `ConcurrentModificationException` out of `getConnection`, outside its `try`.
  - **Detection**: CME: the FM-03.03-1 retry lines with `ConcurrentModificationException`. Leaked pool: none.
  - **Blast radius**: CME: one delayed batch. Leak: one pool (and its HikariCP housekeeping thread) per lost race, for the life of the process.
  - **Recovery**: CME self-heals on retry; the leak only by restart.
  - **RTO**: one backoff for the CME; n/a for the leak.
  - **Test**: GAP: concurrent `getInstance` for one key from N threads, asserting exactly one pool is created.
  - **DEFECT**: the registry is shared mutable state without synchronisation; a lost race leaks a pool silently.

- **FM-03.05-6 Credentials rejected**
  - **Trigger**: the ClickHouse password is rotated, the user is dropped or loses its grants.
  - **Behaviour**: the first statement fails with 516 AUTHENTICATION_FAILED or 497 ACCESS_DENIED, both FATAL: the worker stops and the engine exits terminally (spec 03.01 §6 FM-03.01-2). At start, `ensureDatabaseExists` logs and continues (the key is not marked ensured).
  - **Detection**: ERROR `FATAL ClickHouse error (Code: 516) ...` (or 497), exit code 3, on the first write.
  - **Blast radius**: all replication stops; no loss.
  - **Recovery**: fix `clickhouse.password` / the user's grants, restart.
  - **RTO**: time to fix + restart.
  - **Test**: `ClickHouseErrorClassifierTest.testClassifyFatal()`.

- **FM-03.05-7 `connection.pool.min.idle` is ignored**
  - **Trigger**: every pool the connector creates.
  - **Behaviour**: `HikariDbSource.createConnectionPool` reads `connection.pool.min.idle` but never calls `setMinimumIdle`; HikariCP 6.0.0 `HikariConfig.validateNumerics` then sets `minimumIdle = maximumPoolSize` (500), so each `host:port|database` pool's housekeeper keeps up to 500 idle connections and replaces them every `connection.pool.max.lifetime` (300,000 ms). What a V2 connection object costs to create and hold (heap, a request at open) is not measured here.
  - **Detection**: none (heap, connection counts on the server).
  - **Blast radius**: heap and server connection load grow with the number of destination databases — unmeasured.
  - **Recovery**: none by configuration in 2.11.0; lower `connection.pool.max.size` bounds the fill.
  - **RTO**: n/a.
  - **Test**: GAP: a pool built by `createConnectionPool` with `connection.pool.min.idle=2`, asserting `getMinimumIdle() == 2`.
  - **DEFECT**: a documented setting is silently ignored and the pool library's fallback fills every pool to its maximum.

Summary: 7 failure modes, 3 DEFECT, 5 GAP.

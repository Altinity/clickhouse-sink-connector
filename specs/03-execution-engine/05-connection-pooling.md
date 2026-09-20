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

---

## 3. Operational Specification

### 3.1 Pool Initialization
1. `BaseDbWriter.createConnection` builds the JDBC properties (`client_name`, `custom_settings`, user `clickhouse.jdbc.params`, and `http_connection_provider=HTTP_URL_CONNECTION` when pooling is enabled) and a `SinkConnectorDataSource` carrying the real `jdbc:clickhouse://host:port/db` URL.
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

---

## 4. Invariants Preserved
- **No cross-server crossover**: two engines addressing the same database name on different servers never receive each other's pool.
- **Fail fast on a dead server**: once an endpoint is retired, requests fail immediately rather than rebuilding pools against an unreachable server.

---

## 5. Verification Criteria
- `PoolServerIdentityTest` — pool keying by server endpoint and database.
- `ConcurrentServerPoolTest`, `ConcurrentServerPoolLiveIT` — concurrent servers under the same database name.
- `ClickHouseUnavailableTest` — behaviour when the server is unreachable.
- Verification: a unit test of pool acquisition/release accounting (leak-freedom) is not yet covered by an automated test (gap).

# Spec 03.05: HikariCP Connection Pooling & Lifecycle Management

## 1. Executive Summary & Purpose
Specifies the JDBC connection lifecycle and HikariCP connection pool configuration used by worker threads to communicate with ClickHouse.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/HikariCPConnectionPool.java`
- **Configuration**:
  - `connection.pool.size` (default matches `threadPoolSize` + overhead)
  - `connection.timeout`
  - `max.lifetime`
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
1. Configures `HikariConfig` with ClickHouse JDBC driver class: `com.clickhouse.jdbc.ClickHouseDriver`.
2. Sets `jdbcUrl` with configured host, port, credentials, and session settings.
3. Initializes `HikariDataSource`.

### 3.2 Thread-Confined Connection Usage
- A JDBC `Connection` checked out by a worker thread in `ClickHouseBatchRunnable` is strictly thread-confined for the duration of the batch execution.
- Connections are automatically returned to the pool upon batch completion or exception in a `try-with-resources` block.

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
- **Leak-Free Connection Hygiene**: All connections are closed or returned to the pool on failure, preventing connection pool exhaustion.
- **Invariant I9 (Loud Failure)**: a database that cannot be ensured or connected
  to is reported at ERROR naming the database, never retried silently.
- **Signal over noise**: bootstrap and compatibility messages are emitted once
  per process per subject, so WARN/ERROR lines remain actionable.

---

## 5. Verification Criteria
- `HikariCPConnectionPoolTest.testPoolAcquisitionAndRelease()`
- `HikariConnectionPoolIT`
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

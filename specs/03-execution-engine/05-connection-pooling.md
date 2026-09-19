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

---

## 3. Operational Specification

### 3.1 Pool Initialization
1. Configures `HikariConfig` with ClickHouse JDBC driver class: `com.clickhouse.jdbc.ClickHouseDriver`.
2. Sets `jdbcUrl` with configured host, port, credentials, and session settings.
3. Initializes `HikariDataSource`.

### 3.2 Thread-Confined Connection Usage
- A JDBC `Connection` checked out by a worker thread in `ClickHouseBatchRunnable` is strictly thread-confined for the duration of the batch execution.
- Connections are automatically returned to the pool upon batch completion or exception in a `try-with-resources` block.

---

## 4. Invariants Preserved
- **Leak-Free Connection Hygiene**: All connections are closed or returned to the pool on failure, preventing connection pool exhaustion.

---

## 5. Verification Criteria
- `HikariCPConnectionPoolTest.testPoolAcquisitionAndRelease()`
- `HikariConnectionPoolIT`

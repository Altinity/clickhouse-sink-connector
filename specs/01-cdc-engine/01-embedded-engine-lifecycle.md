# Spec 01.01: Embedded CDC Engine Lifecycle & Bootstrap

## 1. Executive Summary & Purpose
Specifies the startup, dependency injection, runtime orchestration, and graceful shutdown lifecycle of the standalone embedded CDC application (`ClickHouseDebeziumEmbeddedApplication`). In lightweight mode, the connector runs as a self-contained process without external Kafka broker dependencies.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ClickHouseDebeziumEmbeddedApplication.java`
- **Dependency Injection**: `com.altinity.clickhouse.debezium.embedded.injector.AppInjector`
- **Key Methods**:
  - `ClickHouseDebeziumEmbeddedApplication.main(String[] args)`
  - `ClickHouseDebeziumEmbeddedApplication.start()`
  - `ClickHouseDebeziumEmbeddedApplication.stop()`

---

## 3. Operational Specification

### 3.1 Configuration Resolution Sequence
Configuration properties are resolved hierarchically with the following precedence order (highest to lowest):
1. Command-line parameters passed to `main()`.
2. Environment variables via `EnvironmentConfigurationService`.
3. Configuration YAML files (`config.yml`).
4. Configuration properties file (`config.properties`).
5. Default fallback values defined in `SinkConnectorLightWeightConfig`.

### 3.2 Preflight Validation
Before the Debezium engine is initialized, the application performs:
1. **Connectivity Check**: Establishes a temporary JDBC connection to ClickHouse; fails fast if unreachable.
2. **Keyless Table Preflight**: For MySQL sources, queries `information_schema.tables` and `innodb_indexes` to identify tables without primary keys. Warns that keyless tables require Generated Invisible Primary Keys (GIPK) to avoid row collapse in ClickHouse `ReplacingMergeTree`.
3. **Database & Schema Initialization**: Verifies and creates `replica_source_info` (offset store), `replica_status_view`, and optional `binlog_history` database.

### 3.3 Shutdown Hook & Resource Cleanup
`Runtime.getRuntime().addShutdownHook()` ensures clean termination:
1. Signal Debezium capture loop to cease polling new binlog records.
2. Flush all in-flight batches in `records` queue.
3. Await worker task quiescence (`activeBatches == 0`).
4. Commit final durable offsets.
5. Close HikariCP connection pools and JDBC connections.

---

## 4. Invariants Preserved
- **Invariant I8 (Durable Offset Quiescence)**: No shutdown terminates the process while unwritten batches remain acknowledged.
- **Fail-Fast Boot**: Invalid credentials or unreachable target ClickHouse instances abort startup immediately with non-zero exit codes.

---

## 5. Verification Criteria
- `ClickHouseDebeziumEmbeddedApplicationTest.testBootstrapValidation()`
- Integration test checking graceful shutdown on SIGTERM without offset corruption.

# Spec 10.03: Replica Status View & Monitoring Metrics

## 1. Executive Summary & Purpose
Specifies the operator-facing replication status view (`<offset database>.show_replica_status`) that the lightweight connector creates over the offset table, and how it is configured.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumJdbcStorageOperations.java`
- **Method**: `void createViewForShowReplicaStatus(Connection conn, ClickHouseSinkConnectorConfig config, Properties props)` (lines 120–176 on 2.11.0), called once during `DebeziumChangeEventCapture` setup (`DebeziumChangeEventCapture.java` line 419).
- **Configuration key**: `replica.status.view` (`ClickHouseSinkConnectorConfigVariables.REPLICA_STATUS_VIEW`); the view SQL is configuration, formatted with `String.format(view, dbName, dbName + "." + tableName)`.
- **Default SQL**: `sink-connector-lightweight/src/main/resources/config.properties` line 24.

---

## 3. Operational Specification

### 3.1 View creation
1. Read `replica.status.view` from `props`; if absent or empty, log a warning and return — no view is created.
2. Resolve the offset table and database (`getDebeziumOffsetStorageDatabaseName(props)`), format the SQL (`%s` → offset database, second `%s` → `database.table`) and strip double quotes.
3. Parse the target `[database.]viewName` out of the DDL with `VIEW_NAME_PATTERN` (`CREATE [OR REPLACE] VIEW <identifier>`); the view name is never hardcoded.
4. If `system.tables` already lists that view, log and return without re-creating it. A failure of that check is logged and creation is attempted anyway.
5. Execute the formatted DDL through `DBMetadata.executeSystemQuery`.

### 3.2 View schema (shipped default)
The default in `config.properties` creates the view **in the offset database** (not in `system`) and reads the offset table with `FINAL`:
```sql
CREATE OR REPLACE VIEW %s.show_replica_status
(
    `seconds_behind_source` Int32, `duration_behind_source` String,
    `utc_time` DateTime('UTC'), `local_time` DateTime,
    `id` String, `offset_key` String, `offset_val` String,
    `record_insert_ts` DateTime, `record_insert_seq` UInt64
) AS
SELECT * FROM (
    SELECT now() - fromUnixTimestamp(JSONExtractUInt(offset_val, 'ts_sec')) AS seconds_behind_source,
           formatReadableTimeDelta(seconds_behind_source)                    AS duration_behind_source,
           toDateTime(fromUnixTimestamp(JSONExtractUInt(offset_val, 'ts_sec')), 'UTC') AS utc_time,
           fromUnixTimestamp(JSONExtractUInt(offset_val, 'ts_sec'))          AS local_time,
           *
    FROM %s FINAL
) AS U ORDER BY offset_key ASC
```
`seconds_behind_source` is derived from the `ts_sec` field of the stored Debezium offset JSON, i.e. the source timestamp of the last committed position, not from `record_insert_ts` and not via `argMax`.

### 3.3 Lag semantics
$$\text{seconds\_behind\_source} = \text{now()} - \text{ts\_sec(last committed offset)}$$
This measures how far the **committed** position trails the source clock; rows already written but not yet flushed to the offset table (spec 09.02) count as lag. The restart monitor (spec 01.01 §3.2) uses `max(record_insert_ts)` separately and does not read this view.

---

### 3.4 Process-lifetime memory of the metrics registry
The Prometheus registry (`Metrics`, `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/common/Metrics.java`) lives for the whole process on a fixed heap, so nothing registered in it may grow with source volume, DDL volume, binlog rotations or engine restarts:
1. **Fixed label cardinality.** A label value must come from a bounded set (a table, a topic, a partition, `true`/`false`). The DDL counter `clickhouse.sink.ddl` used to carry the DDL text and the wall-clock timestamp as tags, which made every DDL event a new series that was never removed — on a source refreshing its views thousands of times a day the registry, and every `/metrics` scrape, grew without bound. It now carries only `fail`, so it is two series; the statement is in the log at INFO and the timestamp is the scrape's.
2. **One child per live binlog file.** `clickhouse_sink_binlog_pos` is labelled by binlog file; the child of the file the reader has left is removed when the file changes, so the gauge holds one child, not one per rotation since start.
3. **The registry is released with the server.** `Metrics.initialize` runs on every engine start in the process (REST `/restart`, `/start`, the restart monitor); it now stops the previous registry first, and `Metrics.stop()` closes the `JvmGcMetrics` binder (whose GC-MXBean listeners otherwise keep the whole old registry — and every series ever registered in it — reachable) and the meter registry itself, then drops the references. Before this, each restart leaked one complete registry.

---

## 4. Invariants Preserved
- **Observability**: exposes replication status inside ClickHouse without any connector-side endpoint.
- **Configuration over code**: deployments may replace the view SQL through `replica.status.view`; the connector only formats, name-parses and executes it.

---

## 5. Verification Criteria
- `DebeziumStorageViewIT.debeziumStorageView()` — the view is created as `altinity_sink_connector.show_replica_status` in the offset database.
- `DebeziumJdbcStorageOperationsTest` — sibling storage operations of the same class.
- `MetricsLifecycleTest` — §3.4: three DDL events with distinct statements and timestamps register one `clickhouse.sink.ddl` series per `fail` value, never one per event (`ddlCounterHasFixedCardinality`); the binlog position gauge keeps one child across a file rotation (`binlogPositionKeepsOneChildAcrossRotation`); `stop()` closes the registry and a second `initialize()` closes the previous one before opening a new one (`registryIsReleasedOnStopAndOnReinitialize`).
- `ClickHouseBatchRunnableCloseConnectionsTest` — spec 01.01 §3.3 step 4a: `closeConnections()` closes the per-database and system connections the worker holds and forgets them; a connection that fails to close is skipped, and a second call is a no-op (`closeConnectionsClosesAndForgetsEverything`).
- Verification: unit coverage of `createViewForShowReplicaStatus` itself (skip when unconfigured, skip when existing, name parsing) is not yet covered by an automated test (gap).

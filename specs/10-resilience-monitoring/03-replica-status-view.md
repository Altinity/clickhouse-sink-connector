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

## 4. Invariants Preserved
- **Observability**: exposes replication status inside ClickHouse without any connector-side endpoint.
- **Configuration over code**: deployments may replace the view SQL through `replica.status.view`; the connector only formats, name-parses and executes it.

---

## 5. Verification Criteria
- `DebeziumStorageViewIT.debeziumStorageView()` — the view is created as `altinity_sink_connector.show_replica_status` in the offset database.
- `DebeziumJdbcStorageOperationsTest` — sibling storage operations of the same class.
- Verification: unit coverage of `createViewForShowReplicaStatus` itself (skip when unconfigured, skip when existing, name parsing) is not yet covered by an automated test (gap).

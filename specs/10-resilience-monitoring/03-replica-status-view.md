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
4. **A counter update with no open registry is dropped, never thrown.** Releasing the registry (item 3) opened a window the leaked registry used to hide: `updateCounters`, `updateErrorCounters` and `updateDdlMetrics` registered their meters through the static registry reference, which is `null` between `stop()` and the next `initialize()`, so an update arriving in that window — a worker draining its last batch while the engine stops, or, in the module's single-JVM test suite (`forkCount=0`), any test that runs after one which stopped the metrics — threw `NullPointerException` out of the write path. The three updates now check that the registry is enabled, present and not closed before registering; when it is not, the increment has nowhere to be scraped from and is dropped. A closed metrics registry never fails a batch (spec 10.04).

---

## 4. Invariants Preserved
- **Observability**: exposes replication status inside ClickHouse without any connector-side endpoint.
- **Configuration over code**: deployments may replace the view SQL through `replica.status.view`; the connector only formats, name-parses and executes it.

---

## 5. Verification Criteria
- `DebeziumStorageViewIT.debeziumStorageView()` — the view is created as `altinity_sink_connector.show_replica_status` in the offset database.
- `DebeziumJdbcStorageOperationsTest` — sibling storage operations of the same class.
- `MetricsLifecycleTest` — §3.4: three DDL events with distinct statements and timestamps register one `clickhouse.sink.ddl` series per `fail` value, never one per event (`ddlCounterHasFixedCardinality`); the binlog position gauge keeps one child across a file rotation (`binlogPositionKeepsOneChildAcrossRotation`); `stop()` closes the registry and a second `initialize()` closes the previous one before opening a new one (`registryIsReleasedOnStopAndOnReinitialize`); a counter update issued after `stop()` is dropped without a throw and the next `initialize()` counts again from zero (`counterUpdatesAfterStopAreDroppedNotThrown`, item 4 — goes red with `NullPointerException` the moment the registry check is removed from the three counter updates).
- `ClickHouseBatchRunnableCloseConnectionsTest` — spec 01.01 §3.3 step 4a: `closeConnections()` closes the per-database and system connections the worker holds and forgets them; a connection that fails to close is skipped, and a second call is a no-op (`closeConnectionsClosesAndForgetsEverything`).
- Verification: unit coverage of `createViewForShowReplicaStatus` itself (skip when unconfigured, skip when existing, name parsing) is not yet covered by an automated test (gap).

---

## 6. Failure Modes & Recovery
Of the connector's three lag signals only one sees a stall: `show_replica_status.seconds_behind_source` is computed at query time from the committed offset, so it grows whenever the committed position stands still. The Prometheus gauge `clickhouse_sink_db_lag` and the REST `/status` `Seconds_Behind_Source` are stored values written on progress and frozen without it; the shipped PostgreSQL view reads a field PostgreSQL offsets do not have; and a wrong or missing view is never repaired by the connector.

- **FM-10.03-1 The status view is missing**
  - **Trigger**: `replica.status.view` is absent from the properties the engine is started with (the shipped `docker/config.yml` does not set it; the `ClickHouseSinkConnectorConfig` default is not consulted, because `createViewForShowReplicaStatus` reads the raw properties), or the `CREATE VIEW` fails.
  - **Behaviour**: no view is created and startup continues. A failed create is logged without its exception, and a create that exhausts retryable errors returns normally (spec 08.01 FM-08.01-6).
  - **Detection**: WARN `Skipping creating view for replica_status as the query was not provided in configuration`, or ERROR `**** Error creating VIEW **** <sql>`, once at startup.
  - **Blast radius**: no ClickHouse-side lag signal; alerts built on the view query a missing table.
  - **Recovery**: set `replica.status.view` to the `ClickHouseSinkConnectorConfig` default SQL (it handles both `ts_sec` and `ts_usec`) and restart, or create the view by hand from that SQL.
  - **RTO**: restart 30 s + start; unmeasured.
  - **Test**: `ReplicaStatusViewFailureModesTest.unconfiguredViewIsSkipped()`, `ReplicaStatusViewFailureModesTest.missingViewIsCreated()`, `ReplicaStatusViewFailureModesTest.configDefDefaultReadsBothOffsetShapes()`.

- **FM-10.03-2 The Prometheus lag gauge freezes during a stall**
  - **Trigger**: every worker is retrying (spec 10.02 FM-10.02-1), ClickHouse is down, or a worker is dead and the engine has not yet stopped.
  - **Behaviour**: `Metrics.updateMetrics` -- the only writer of `clickhouse_sink_db_lag`, `clickhouse_sink_debezium_lag`, `clickhouse_sink_binlog_pos` and `clickhouse_sink_connector_uptime` -- runs only when the INSERT stage returned without throwing (`ClickHouseBatchRunnable.flushRecordsToClickHouse`), i.e. after a batch was written. With no write, every gauge keeps its last value; the lag stays at the (small) lag of the last successful batch. In routing mode `clickhouse_sink_binlog_pos` is set by whichever worker wrote last, so it can also move backwards.
  - **Detection**: none from the lag gauge; the alert `doc/Monitoring.md` recommends (on the Grafana lag panel, `clickhouse_sink_db_lag`) never fires. A stall is visible in Prometheus only indirectly, as `rate(clickhouse_sink_topics_num_records_total[5m]) == 0` (also true for an idle source) or a rising `clickhouse_sink_topics_error_records_total` (insert-stage failures only).
  - **Blast radius**: monitoring blind to a stall of any length.
  - **Recovery**: alert on `show_replica_status.seconds_behind_source` (MySQL) instead; the gauge resumes with the next written batch.
  - **RTO**: not applicable to replication; detection unbounded; unmeasured.
  - **Test**: `MetricsLagDuringStallTest.lagGaugeGrowsWhileNothingIsWritten()` (`@Disabled`, confirmed red on 2.11.0); `MetricsLagDuringStallTest.lagGaugeReportsTheLastWrittenBatch()` pins today's behaviour.
  - **DEFECT**: the lag metric is a snapshot taken at the last successful write, so it reports a healthy lag for a replica that has stopped.

- **FM-10.03-3 The shipped PostgreSQL view reads the wrong offset field**
  - **Trigger**: a PostgreSQL deployment using `docker/config_postgres.yml` (or any view SQL that reads only `ts_sec`). Debezium's `PostgresOffsetContext` stores `ts_usec` (verified in `debezium-connector-postgres-3.1.3.Final.jar`); MySQL offsets carry `ts_sec` (`BinlogOffsetContext`).
  - **Behaviour**: `JSONExtractUInt(offset_val, 'ts_sec')` is 0, so `seconds_behind_source = now() - 0`, about 1.8e9 s, permanently. Once created, the view is never replaced: `createViewForShowReplicaStatus` skips creation when `system.tables` lists it, so correcting the configuration changes nothing until the view is dropped.
  - **Detection**: none from the connector; the view reports an absurd constant lag.
  - **Blast radius**: a lag alert on the view is always firing and gets silenced, hiding real stalls.
  - **Recovery**: set `replica.status.view` to the `ClickHouseSinkConnectorConfig` default SQL, `DROP VIEW <offset db>.show_replica_status` in ClickHouse, restart the service.
  - **RTO**: restart 30 s + start; unmeasured.
  - **Test**: `ReplicaStatusViewFailureModesTest.shippedPostgresViewReadsTsUsec()` (`@Disabled`, confirmed red on 2.11.0), `ReplicaStatusViewFailureModesTest.existingViewIsNeverReplaced()` (pinned), `ReplicaStatusViewFailureModesTest.bundledMySqlViewReadsTsSec()`.
  - **DEFECT**: the shipped PostgreSQL configuration makes the only stall-aware lag signal permanently wrong, and a corrected definition is never applied over an existing view.

- **FM-10.03-4 `/status` reports a frozen lag and a running replica during a stall**
  - **Trigger**: the writers stall while the process is up (spec 10.02 FM-10.02-1).
  - **Behaviour**: `Seconds_Behind_Source` in `/status` (and in `sink-connector-client show_replica_status`) is `ReplicationStatusSingleton.getReplicationLag() / 1000`, a value the Debezium thread stores when it converts a row (`now - ts_ms` at that instant) and never recomputes; it measures capture, not commit. Once the reader is paused by the handoff cap it stops changing. `Replica_Running` stays true because the engine has not failed.
  - **Detection**: none from `/status`.
  - **Blast radius**: an operator or probe reading `/status` sees a healthy replica.
  - **Recovery**: read `show_replica_status` in ClickHouse; `/status` recovers with the next captured row.
  - **RTO**: not applicable to replication; detection unbounded; unmeasured.
  - **Test**: `StatusLagDuringStallTest.statusLagGrowsWhileNothingIsCaptured()` (`@Disabled`, confirmed red on 2.11.0); `StatusLagDuringStallTest.statusLagIsTheStoredCaptureValue()` pins today's behaviour.
  - **DEFECT**: `/status` has no liveness content -- its lag is a capture-time snapshot and `Replica_Running` only reflects a terminal failure.

- **FM-10.03-5 The metrics endpoint is down**
  - **Trigger**: `metrics.port` is taken by another process, or the scrape thread dies.
  - **Behaviour**: `Metrics.exposePrometheusPort` logs the `IOException` and continues; replication is unaffected and nothing retries the bind until the next engine start (`Metrics.initialize` stops the previous server and registry first).
  - **Detection**: ERROR `Cannot start HTTP server for Prometheus on /metrics` once; the scraper sees the target down (`up == 0`).
  - **Blast radius**: no metrics; replication continues.
  - **Recovery**: free the port or change `metrics.port`, then restart the service (or `sink-connector-client restart`).
  - **RTO**: restart 30 s + start; unmeasured.
  - **Test**: `MetricsLifecycleTest.registryIsReleasedOnStopAndOnReinitialize()`, `MetricsContentTypeTest.metricsEndpointDeclaresPrometheusContentType()`; GAP: a test binding the port first and asserting replication starts and the ERROR is logged.

- **FM-10.03-6 The view on an idle source**
  - **Trigger**: the MySQL source has no writes for longer than the alert threshold.
  - **Behaviour**: the view's lag grows unless heartbeat control-record commits (spec 09.04) store an offset whose `ts_sec` advances; whether Debezium 3.1's MySQL heartbeat offset carries a fresh `ts_sec` was not verified.
  - **Detection**: unverified -- possibly a false stall alert on an idle source.
  - **Blast radius**: alert noise only.
  - **Recovery**: none for replication; combine the view with a source-activity check when alerting.
  - **RTO**: not applicable.
  - **Test**: GAP: an integration test that idles the source past the heartbeat interval and records `seconds_behind_source`.

Summary: 6 failure modes, 3 DEFECT, 2 GAP.

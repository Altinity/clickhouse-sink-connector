# Spec 01.11: MySQL TRUNCATE Routing Under Debezium 3.3+ (3.7.0 shipped)

## 1. Executive Summary & Purpose
Specifies how a MySQL/MariaDB `TRUNCATE TABLE` keeps reaching ClickHouse after the embedded Debezium moved from 3.1.3 to 3.3. Debezium's binlog reader turns the statement into either a truncate change event (`op = t`) or a schema-change event. 3.1.3 emitted the schema-change event when `skipped.operations` contained `t` — the Debezium default — and the connector applied it through its DDL path. 3.3 emits nothing in that case. Without this spec the upgrade would make every MySQL `TRUNCATE` vanish silently, leaving ClickHouse with every row the source removed.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/MySqlTruncateRouting.java`
- **Call sites**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java` — `setup(...)` (`MySqlTruncateRouting.apply`, after the keep-alive and queue preflights, on the `Properties` every engine restart is rebuilt from), `handleChangeEventBatch` (a routed truncate is a DDL record: pending rows are handed off before it), `processEveryChangeRecord` (the routed truncate is replaced by its schema-change record before the DDL branch), `isTruncateRoutedToDdl(ChangeEvent)`.
- **Not this repository's code**: Debezium `BinlogStreamingChangeEventSource.handleQueryEvent`. 3.1.3: `if (tableId != null && !skipped.contains(TRUNCATE) && type == TRUNCATE) dispatchDataChangeEvent(... Operation.TRUNCATE ...) else dispatchSchemaChangeEvent(...)`. 3.3.2: `if (tableId != null && type == TRUNCATE) { if (!skipped.contains(TRUNCATE)) dispatchDataChangeEvent(...) } else dispatchSchemaChangeEvent(...)`. `CommonConnectorConfig.SKIPPED_OPERATIONS` default `t`; `determineSkippedOperations`: `none` = empty set, otherwise comma-separated codes.
- **Key methods**:
  - `MySqlTruncateRouting.apply(Properties)` — binlog connectors only; records the route in `clickhouse.sink.internal.mysql.truncate.route` (`ddl` / `row`) once, and hands Debezium the operator's set without `t` (`none` when empty). Idempotent.
  - `MySqlTruncateRouting.isTruncateEvent(SourceRecord)` — envelope with `op = t` and a `source` naming `db` and `table`.
  - `MySqlTruncateRouting.toSchemaChangeRecord(SourceRecord)` — the 3.1.3 schema-change record.

---

## 3. Operational Specification

### 3.1 Route selection (at setup)
The operator's effective set is `skipped.operations` or, when unset, Debezium's default `t`.
- Set contains `t` → **DDL route**, as under 3.1.3. Debezium receives the set without `t`.
- Set does not contain `t` (e.g. `none`) → **row route**, as under 3.1.3. Debezium receives the set unchanged.
Other codes (`c`, `u`, `d`) are passed through untouched. PostgreSQL and every non-binlog connector are not touched (they never had a schema-change form of TRUNCATE).

### 3.2 DDL route
Each truncate change event is replaced, before classification, by the record Debezium 3.1.3 emitted: key `{databaseName = source.db}`, value `{source = the event's source block, ts_ms = source.ts_ms (the binlog event time), databaseName = source.db, schemaName = null, ddl = "TRUNCATE TABLE `<source.table>`", tableChanges = []}`, same source partition and offset. It then goes through the unchanged DDL path (spec 06.01): pending rows of the batch are handed off first, the ignore rules and `disable.drop.truncate` (spec 06.08 §3.3) are applied, history mode turns it into the bulk close (spec 12.03 §3.4), a pending primary-key backfill of the table is cancelled (spec 06.09 §3.3.2 step 7), and the statement is applied to `<database>.<table>` with the database resolved exactly as for any DDL. The engine's own change event is what the DDL path acknowledges, so offset handling is that of a DDL record (spec 09.04).

The statement text differs from 3.1.3 in one way: 3.1.3 carried the text the source logged (e.g. `truncate t1`, `TRUNCATE TABLE db.t1`, with comments); the routed record carries the canonical `TRUNCATE TABLE `t1``. An `ignore.ddl.regex` written against the source's exact spelling of a TRUNCATE may need to match the canonical form.

### 3.3 Row route
Unchanged from 3.1.3: the truncate change event is a TRUNCATE segment of spec 04.05 (and spec 12.03's `op = t` record path in history mode).

---

## 4. Invariants Preserved
- **Invariant I1 (Log Sequence Monotonicity)**: the routed statement is processed at the event's binlog position, behind the rows handed off before it.
- **Invariant I9 (Loud Failure)**: a TRUNCATE is never dropped silently by the upgrade; a DDL-path failure stays terminal (`DDLReplicationException`).
- **Invariant I11 (Drop-in Upgrade Safety)**: with an unchanged configuration the connector applies a MySQL TRUNCATE through the same path, with the same suppression and history-mode rules, as the 3.1.3 build did; no configuration key changes meaning.

---

## 5. Verification Criteria
- `MySqlTruncateRoutingTest.defaultRoutesThroughDdl()` — §3.1: unset → DDL route, Debezium handed `none`.
- `MySqlTruncateRoutingTest.applyIsIdempotent()` — §3.1: a rebuilt engine keeps the route.
- `MySqlTruncateRoutingTest.otherCodesAreKept()`, `MySqlTruncateRoutingTest.noneKeepsRowRoute()`, `MySqlTruncateRoutingTest.postgresIsUntouched()` — §3.1.
- `MySqlTruncateRoutingTest.recognisesTruncateEvents()`, `MySqlTruncateRoutingTest.schemaChangeRecordShape()`, `MySqlTruncateRoutingTest.backtickIsEscaped()` — §3.2 record shape.
- `MySqlTruncateRoutingTest.ddlRouteReachesTheDdlPath()`, `MySqlTruncateRoutingTest.rowRouteReachesTheRowParser()` — §3.2 / §3.3: the same event reaches the DDL path on the DDL route and the row parser on the row route.
- End to end (real MySQL 8.4 + ClickHouse 24.8 + connector): `INSERT; TRUNCATE; INSERT` bursts converge value-exact with `skipped.operations` unset and with `none`; with `disable.drop.truncate=true` ClickHouse keeps the pre-truncate rows.

---

## 6. Failure Modes & Recovery

Recovery posture: the routed TRUNCATE inherits the DDL path's failure handling (spec 06.01 §6) — a statement ClickHouse refuses halts the engine with the offset behind it and is redelivered on restart. Procedures P-FIX-TYPE / P-SKIP / P-RESYNC and the retry-vs-stop rule are defined in spec 07.01 §6.

- **FM-01.11-1 Ignore rule written against the source spelling**
  - **Trigger**: `ignore.ddl.regex` matching e.g. `^truncate t1$` only.
  - **Behaviour**: the canonical `TRUNCATE TABLE `t1`` does not match; the statement is applied.
  - **Detection**: INFO `Executed Source DB DDL: TRUNCATE TABLE ...`.
  - **Blast radius**: the table is truncated in ClickHouse, matching MySQL (the replica stays correct; only the operator's suppression intent is missed).
  - **Recovery**: widen the regex to the canonical form, or use `disable.drop.truncate`.
  - **RTO**: config change + restart.
  - **Test**: `MySqlTruncateRoutingTest.ddlRouteReachesTheDdlPath()` (the canonical text is what the rules see).

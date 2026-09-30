# Spec 01.10: Binlog Content Debezium Drops Without a Signal -- XA Rollbacks (Reported) and Partial JSON Updates (Not Supported)

## 1. Executive Summary & Purpose
Specifies how the connector treats the two kinds of binlog content that Debezium 3.1.3 drops without an error, each of which leaves ClickHouse silently different from MySQL (measured, spec 01.08 section 5, edge cases E2 and E3):

1. **XA ROLLBACK after XA PREPARE -- reported.** MySQL writes an XA transaction's rows at `XA PREPARE` and its outcome later, as a separate `XA COMMIT` or `XA ROLLBACK` statement. Debezium dispatches the rows as they arrive and ignores every `XA` statement, so the rows of an XA transaction rolled back after PREPARE stay in ClickHouse. E3 on the 2.11.0 head: the rolled-back row replicated, the committed ones exact, no log line. The rows are already written when the rollback arrives, so the connector REPORTS it -- an ERROR naming the XID and the tables its PREPARE wrote, and a metric -- and replication of everything else continues; the repair is a re-synchronisation of those tables.
2. **`binlog_row_value_options=PARTIAL_JSON` -- not supported (a declared limitation).** A session with it logs an UPDATE of a JSON column as a `PARTIAL_UPDATE_ROWS` event (`Update_rows_partial`) carrying only the JSON diff. Debezium registers no handler for that event type and ignores it at TRACE, so the WHOLE row update -- the JSON change and every other column of the statement -- never reaches ClickHouse, with and without compression, and the connector logs no error. E2 on the 2.11.0 head: five partial updates, none replicated (including a plain `note` column changed in the same statement). The owning DBA's ruling (2026-09-30): this is expected and is documented as not supported (`doc/limitations.md`); sources run with `binlog_row_value_options=''`, the MySQL default. The connector does not detect or refuse it.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/BinlogEventAudit.java` -- a `BinaryLogClient.EventListener` on the task's binlog client, plus the static feed `BinlogEventAudit.observeInner(Event)` for inner events of compressed transactions.
  - `BinlogEventAudit.install(BinaryLogClient)` -- resets the XA state and registers the listener; called by the connector's copy of `io.debezium.connector.mysql.MySqlStreamingChangeEventSourceMetrics` (spec 01.09 §3.3), before Debezium registers its own listener.
  - `BinlogEventAudit.audit(Event)` -- TABLE_MAP (tables of the open XA), QUERY (`XA START` / `XA BEGIN` / `XA COMMIT` / `XA ROLLBACK`), `XA_PREPARE` (one-phase or two-phase). `BinlogEventAudit.onEvent(Event)` and `observeInner` log any exception inside the audit at ERROR and never propagate it to the reader.
  - `BinlogEventAudit.xid(String, String)` -- the XID text after the verb, `ONE PHASE` removed.
- **Payload decoder feed**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/payload/StreamedPayloadEvents.java` -- on the FIRST pass over a payload only (`auditClaimed`), every inner TABLE_MAP, QUERY and XA_PREPARE is handed to `BinlogEventAudit.observeInner` (QUERY and XA_PREPARE bodies parsed once with the inner-event deserializer for that purpose).
- **Metric**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/common/Metrics.java` -- `Metrics.incrementBinlogXaRollbackAfterPrepare()`; name `clickhouse_sink_binlog_xa_rollback_after_prepare` in `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/common/MetricsConstants.java`.
- **Limitation document**: `doc/limitations.md` (partial JSON updates), linked from `README.md`.
- **Not this repository's code**: Debezium 3.1.3 `BinlogStreamingChangeEventSource` (`ignoreEvent` at TRACE for event types without a handler, `handleQueryEvent`'s "This is an XA transaction, and we currently ignore these", `prepareTransaction` "do nothing"); mysql-binlog-connector-java 0.40.2 `BinaryLogClient.notifyEventListeners` (listeners in registration order, an exception from a listener is logged and swallowed), `XAPrepareEventDataDeserializer`, `EventType.PARTIAL_UPDATE_ROWS_EVENT`.

---

## 3. Operational Specification

### 3.1 Partial JSON updates: not supported
The connector's supported source configuration includes `binlog_row_value_options=''` (the MySQL default) -- globally and in every session. With `PARTIAL_JSON`, every UPDATE of a JSON column is logged as `Update_rows_partial`, which Debezium drops: the affected row updates are lost silently (all columns of the statement, not only the JSON). Nothing in the connector detects it. The limitation, the requirement and the repair (re-synchronise the affected tables from MySQL, `ch-mysql-resync`, spec 11.04) are stated in `doc/limitations.md`. The requirement applies to the server the connector reads: a MySQL replica re-logs the updates it applies with its own `binlog_row_value_options`, so a connector reading a replica that runs with `''` receives full row images even when the primary logs partial ones (measured: edge E2 through a replica hop, the partial updates arrived). The end-to-end harness (E2) reproduces the documented behaviour on every run, so a change in it -- Debezium starting to handle the event, or the connector starting to detect it -- is noticed.

### 3.2 XA ROLLBACK after XA PREPARE: reported, repaired by re-synchronisation
The audit tracks the open XA transaction (`XA START` / `XA BEGIN` → the tables of the TABLE_MAPs until `XA_PREPARE`), remembers every two-phase PREPARE with its tables (bounded, `MAX_PREPARED_XA` = 10,000, oldest forgotten), forgets it at `XA COMMIT`, and at `XA ROLLBACK` logs an ERROR naming the XID, the position, and the tables its PREPARE wrote -- or `UNKNOWN` when the PREPARE was read before this process started -- with the recovery (re-synchronise those tables, spec 11.04), and increments `clickhouse_sink_binlog_xa_rollback_after_prepare`. `XA COMMIT ... ONE PHASE` (an `XA_PREPARE` event with the one-phase flag) commits and is not remembered.

Two feeds, one state. The client listener sees the outer events: `XA_PREPARE` and the later `XA COMMIT` / `XA ROLLBACK`. With `binlog_transaction_compression=ON` the XA transaction's body (`XA START`, its TABLE_MAPs and rows, `XA END`) is INSIDE a `Transaction_payload` (observed: `SHOW BINLOG EVENTS` lists `XA START` at the payload's end position), which the client listener sees as one event -- the first end-to-end run without the decoder feed reported the rollback with its tables `UNKNOWN`. The payload decoder therefore hands the inner events of its first pass to `observeInner`. Both feeds run on the binlog reader thread in stream order (a payload is decoded while its event is read, before the client notifies its listeners), and a connector runs one reader, so the state is static and confined to that thread (methods `synchronized` for safety only).

Replication continues after the report: the rows are already written, and stopping everything else would not undo them. A process restart that re-reads the same `XA ROLLBACK` reports it again.

### 3.3 What does not change
Every other event and every transaction without an XA rollback is untouched; the audit only reads what the client already decoded, never throws into the reader, and adds one parse of each QUERY / XA_PREPARE inner event per payload.

---

## 4. Invariants Preserved
- **Invariant I9 (Loud Failure)**: an XA rollback after PREPARE is an ERROR and a metric instead of silent divergence. The partial-JSON case is a declared limitation of the supported configuration, stated in the user documentation.
- **Invariant I15 (Bounded, Declared Recovery)**: both carry a declared repair (re-synchronisation of the affected tables).
- **Read-only against the source**: the audit only observes.

---

## 5. Verification Criteria
- `BinlogEventAuditTest.xaRollbackAfterPrepareIsReportedWithItsTables()` -- the E3 sequence: only the rolled-back XA is reported, with both tables of its PREPARE.
- `BinlogEventAuditTest.compressedXaBodyFedFromThePayloadIsTracked()` -- the body through `observeInner`, the outcome through the listener.
- `StreamingTransactionPayloadTest.compressedXaBodyReachesTheAudit()` -- a real compressed payload carrying `XA START`, a TABLE_MAP and `XA END`, iterated three times like the binlog client and Debezium do, then outer XA_PREPARE and XA ROLLBACK: one ERROR naming the table. Mutation-checked: without the decoder feed it fails.
- `BinlogEventAuditTest.xaRollbackWhosePrepareWasNotSeenSaysSo()`, `BinlogEventAuditTest.ordinaryQueriesAndCommittedXaAreSilent()`, `BinlogEventAuditTest.xidTextIsTheSameForEveryVerb()`, `BinlogEventAuditTest.anAuditFailureNeverStopsTheReader()`.
- End to end, `sink-connector-lightweight/tests/e2e/binlog_transaction_compression_edge.sh`: E3 asserts the ERROR naming `test.e_xa`, that replication continued, then the re-synchronisation and a value-level match; E2 asserts that the source logged `Update_rows_partial` and reproduces the documented behaviour -- the partial updates do not reach ClickHouse when the connector reads the source, and do reach it through a replica hop whose own binlog is written in full -- then repairs by re-synchronisation. GTID on through the source and GTID off through a MySQL replica hop.

---

## 6. Failure Modes & Recovery
- **FM-01.10-1 XA ROLLBACK after XA PREPARE**.
  - **Detection**: ERROR "XA ROLLBACK <xid> at <file:pos> ... Tables written by its PREPARE: [...]" (or UNKNOWN), `clickhouse_sink_binlog_xa_rollback_after_prepare` +1.
  - **Blast radius**: the rolled-back rows are live in ClickHouse, the source does not have them; limited to the named tables; everything else replicates.
  - **Recovery**: `ch-mysql-resync` of the named tables (spec 11.04); for UNKNOWN, find the PREPARE with `mysqlbinlog` before the reported position.
  - **RTO**: the re-synchronisation of the named tables (spec 11.04); measured in E3 for a small table, seconds.
  - **Test**: `BinlogEventAuditTest.xaRollbackAfterPrepareIsReportedWithItsTables()`, `StreamingTransactionPayloadTest.compressedXaBodyReachesTheAudit()`, edge E3.
- **FM-01.10-2 Partial JSON updates on the source (unsupported configuration)**.
  - **Detection**: none in the connector (a declared limitation, `doc/limitations.md`); found by a value-level checksum (`db_compare`, spec 11.02) or by checking `SELECT @@GLOBAL.binlog_row_value_options` and the sessions' settings.
  - **Blast radius**: every UPDATE of a JSON column made under `PARTIAL_JSON` is lost for all columns of the statement; row counts stay equal.
  - **Recovery**: `SET PERSIST binlog_row_value_options = ''` (and fix the applications that set it per session); re-synchronise the affected tables (`ch-mysql-resync`, spec 11.04).
  - **RTO**: unmeasured -- bounded by how soon a checksum finds it, then the re-synchronisation.
  - **Test**: edge E2 reproduces the documented behaviour; GAP: no connector-side detection by design (owning DBA's ruling).
- **FM-01.10-3 The audit's hook is not installed** (Debezium's metrics class loaded instead of the connector's copy).
  - **Detection**: the WARN of spec 01.09 FM-01.09-3 (same class, same marker).
  - **Blast radius**: XA rollbacks whose outcome is an outer event are silent again.
  - **Recovery**: run the shaded lightweight jar; restart.
  - **RTO**: one restart after the fix.
  - **Test**: `BinlogConnectionGuardTest.theShadowCopyIsTheLoadedClass()`.

Summary: 3 failure modes, 0 DEFECT, 1 GAP.

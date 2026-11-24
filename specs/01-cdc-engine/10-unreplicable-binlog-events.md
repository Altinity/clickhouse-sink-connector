# Spec 01.10: Binlog Content Debezium Drops Without a Signal -- XA Rollbacks (Reported) and Partial JSON Updates (Refused, Stopped at the Event)

## 1. Executive Summary & Purpose
Specifies how the connector treats the two kinds of binlog content that Debezium 3.1.3 drops without an error, each of which leaves ClickHouse silently different from MySQL (measured, spec 01.08 section 5, edge cases E2 and E3):

1. **XA ROLLBACK after XA PREPARE -- reported.** MySQL writes an XA transaction's rows at `XA PREPARE` and its outcome later, as a separate `XA COMMIT` or `XA ROLLBACK` statement. Debezium dispatches the rows as they arrive and ignores every `XA` statement, so the rows of an XA transaction rolled back after PREPARE stay in ClickHouse. E3 on the 2.11.0 head: the rolled-back row replicated, the committed ones exact, no log line. The rows are already written when the rollback arrives, so the connector REPORTS it -- an ERROR naming the XID and the tables its PREPARE wrote, and a metric -- and replication of everything else continues; the repair is a re-synchronisation of those tables.
2. **`binlog_row_value_options=PARTIAL_JSON` -- refused at start, stopped at the event.** A session with it logs an UPDATE of a JSON column as a `PARTIAL_UPDATE_ROWS` event (`Update_rows_partial`) carrying only the JSON diff. Debezium registers no handler for that event type and ignores it at TRACE, so the WHOLE row update -- the JSON change and every other column of the statement -- never reaches ClickHouse, with and without compression, and no error is logged (E2 on the 2.11.0 head: five partial updates, none replicated). The connector refuses to start when the server's global `binlog_row_value_options` includes `PARTIAL_JSON`. It also stops the engine, with an ERROR naming the table, the binlog position and the remedy, at the first partial update event it reads from any session (section 3.1). The repair is to set the value empty and re-synchronise the affected table.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/BinlogEventAudit.java` -- a `BinaryLogClient.EventListener` on the task's binlog client, plus the static feed `BinlogEventAudit.observeInner(Event)` for inner events of compressed transactions.
  - `BinlogEventAudit.install(BinaryLogClient)` -- resets the XA state and registers the listener; called by the connector's copy of `io.debezium.connector.mysql.MySqlStreamingChangeEventSourceMetrics` (spec 01.09 §3.3), before Debezium registers its own listener.
  - `BinlogEventAudit.audit(Event)` -- TABLE_MAP (the most recent table, and the tables of the open XA), QUERY (`XA START` / `XA BEGIN` / `XA COMMIT` / `XA ROLLBACK`), `XA_PREPARE` (one-phase or two-phase), and `PARTIAL_UPDATE_ROWS_EVENT` (section 3.1). `BinlogEventAudit.onEvent(Event)` and `observeInner` log any exception inside the audit at ERROR and never propagate it to the reader.
  - `BinlogEventAudit.xid(String, String)` -- the XID text after the verb, `ONE PHASE` removed.
- **Payload decoder feed**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/payload/StreamedPayloadEvents.java` -- on the FIRST pass over a payload only (`auditClaimed`), every inner TABLE_MAP, QUERY, XA_PREPARE and partial update event is handed to `BinlogEventAudit.observeInner` (QUERY and XA_PREPARE bodies parsed once with the inner-event deserializer for that purpose; a partial update event needs its header only).
- **Start check**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/BinlogRowImagePreflight.java` (spec 01.01 §3.2), run at every engine creation.
- **Stop of the engine**: `BinlogConnectionGuard.PeerCloseIsAFailure` (the next read is refused while a stop is recorded, section 3.1) and `DebeziumChangeEventCapture.handleEngineCompletion` (the recorded stop is terminal, checked before any retry decision).
- **Metric**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/common/Metrics.java` -- `Metrics.incrementBinlogXaRollbackAfterPrepare()`; name `clickhouse_sink_binlog_xa_rollback_after_prepare` in `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/common/MetricsConstants.java`.
- **Limitation document**: `doc/limitations.md` (partial JSON updates), linked from `README.md`.
- **Not this repository's code**: Debezium 3.1.3 `BinlogStreamingChangeEventSource` (`ignoreEvent` at TRACE for event types without a handler, `handleQueryEvent`'s "This is an XA transaction, and we currently ignore these", `prepareTransaction` "do nothing"); mysql-binlog-connector-java 0.40.2 `BinaryLogClient.notifyEventListeners` (listeners in registration order, an exception from a listener is logged and swallowed), `XAPrepareEventDataDeserializer`, `EventType.PARTIAL_UPDATE_ROWS_EVENT` (type 39, deserialized as null by Debezium's fork).

---

## 3. Operational Specification

### 3.1 Partial JSON updates: refused at start, stopped at the event
The supported source configuration is `binlog_row_value_options=''` (the MySQL default), globally and in every session. Two checks enforce it.

1. **At every engine creation** (the start of the process, and before each in-process engine restart). `BinlogRowImagePreflight` reads `SELECT @@GLOBAL.binlog_row_value_options` on a read-only connection and refuses (spec 01.01 §3.2) when the value contains `PARTIAL_JSON`. A value that cannot be read refuses too. A server that has no such variable (MySQL 5.7, error 1193) cannot log partial updates and passes. The global value does not show a session's setting, so this check is not sufficient on its own.
2. **At every partial update event.** A row event of type `PARTIAL_UPDATE_ROWS_EVENT`, from any session, in a plain transaction or inside a compressed payload, is an ERROR and stops the engine:
   - `BinlogEventAudit` records the first such event; a later one does not replace it. It logs an ERROR naming the table (the database and table of the most recent TABLE_MAP event in the stream, which is the row event's own table map), the binlog position (`file:end-position`), and the remedy: set `binlog_row_value_options` to empty, globally and in each session that writes, then re-synchronise the named table (`ch-mysql-resync`, spec 11.04).
   - The binlog client's next read is refused, before any byte is consumed, with an IOException carrying the same text. The client reports a communication failure, and the engine completes with a failure.
   - `DebeziumChangeEventCapture.handleEngineCompletion` checks the recorded stop before any other decision. The stop is FATAL, so it is not retried: `onTerminalFailure` logs FATAL, sets replication status false, and exits with code 3 unless `exit.on.terminal.failure=false`. With that setting the process stays up with replication stopped.

The stop takes effect at the next read of the binlog stream. Inside a compressed transaction, the events of the same payload are dispatched before the stop takes effect, because the payload is one binlog event; they are replicated as usual, and the partial update itself is not. Rows of the same transaction that precede the event are replicated as usual.

The repair is the same for both checks: set `binlog_row_value_options` to empty (`SET PERSIST` for the global value, and fix any application that sets it per session), re-synchronise the named tables (`ch-mysql-resync`, spec 11.04), and restart the connector. The requirement applies to the server the connector reads: a MySQL replica re-logs the updates it applies with its own `binlog_row_value_options`, so a connector reading a replica that runs with `''` receives full row images even when the primary logs partial ones.

### 3.2 XA ROLLBACK after XA PREPARE: reported, repaired by re-synchronisation
The event observer tracks the open XA transaction (`XA START` / `XA BEGIN` → the tables of the TABLE_MAPs until `XA_PREPARE`), remembers every two-phase PREPARE with its tables (bounded, `MAX_PREPARED_XA` = 10,000, oldest forgotten), forgets it at `XA COMMIT`, and at `XA ROLLBACK` logs an ERROR naming the XID, the position, and the tables its PREPARE wrote -- or `UNKNOWN` when the PREPARE was read before this process started -- with the recovery (re-synchronise those tables, spec 11.04), and increments `clickhouse_sink_binlog_xa_rollback_after_prepare`. `XA COMMIT ... ONE PHASE` (an `XA_PREPARE` event with the one-phase flag) commits and is not remembered.

Two feeds, one state. The client listener sees the outer events: `XA_PREPARE` and the later `XA COMMIT` / `XA ROLLBACK`. With `binlog_transaction_compression=ON` the XA transaction's body (`XA START`, its TABLE_MAPs and rows, `XA END`) is INSIDE a `Transaction_payload` (observed: `SHOW BINLOG EVENTS` lists `XA START` at the payload's end position), which the client listener sees as one event -- the first end-to-end run without the decoder feed reported the rollback with its tables `UNKNOWN`. The payload decoder therefore hands the inner events of its first pass to `observeInner`. Both feeds run on the binlog reader thread in stream order (a payload is decoded while its event is read, before the client notifies its listeners), and a connector runs one reader, so the state is static and confined to that thread (methods `synchronized` for safety only).

Replication continues after the report: the rows are already written, and stopping everything else would not undo them. A process restart that re-reads the same `XA ROLLBACK` reports it again.

### 3.3 What does not change
Every other event and every transaction without an XA rollback or a partial update event is untouched. The XA event observer never throws into the reader: it reports. A partial update event is the only event whose audit changes the reader, and only by making its next read fail. The audit adds one parse of each QUERY / XA_PREPARE inner event per payload; a partial update event needs no parse, its header is enough.

### 3.4 Foreign-key cascades: not binlogged before MySQL 9.6, named at start
MySQL before 9.6 does not write to the binary log the child-row changes that an `ON DELETE CASCADE` or `ON UPDATE CASCADE` foreign key makes. The parent change is logged; the child rows it removed or re-keyed are not. The connector replicates only what the source logs, so the child table in ClickHouse keeps the rows MySQL cascaded away, and nothing fails at run time.

Verified on MySQL 8.0.46: deleting one parent removed two child rows on the source, and the value comparison found both still in ClickHouse (`child extra=2`).

The startup check, `ForeignKeyCascadePreflight.check`, runs once at start in the preflight chain of `DebeziumChangeEventCapture`, after the keyless-table check. It does not run again at an in-process engine restart, because the source's constraints do not change with the binlog reader's state.

1. The check connects read-only and reads `SELECT VERSION()`. A MariaDB version, or a version it cannot parse, is skipped with an INFO line. A MySQL version of 9.6 or later logs an INFO line and stops.
2. On MySQL below 9.6 it reads `information_schema.REFERENTIAL_CONSTRAINTS` for rows whose `DELETE_RULE` or `UPDATE_RULE` is `CASCADE`, in every schema except the system schemas. It keeps the child tables that `DdlCaptureFilter.isCaptured` accepts under `database.include.list`, `database.exclude.list`, `table.include.list` and `table.exclude.list`.
3. Each such captured child table is named in one WARN banner with its schema and table, the constraint name, the parent table and both rules. The banner states that the child table diverges on a parent DELETE or a key UPDATE, and gives the three remedies: upgrade to MySQL 9.6 or later, remove the cascading constraint, or re-synchronise the child table after each cascading change (`ch-mysql-resync`, spec 11.04).
4. The check never throws and never refuses the start. A failed read is a WARN that says the check could not run. Refusing would stop every correctly-behaving table on the source, and the operator decides whether the named tables are acceptable. The WARN does not repair the divergence; the table stays divergent until one of the three remedies is applied.

### 3.5 Session `sql_log_bin=0`: writes the binlog never records
A session that sets `sql_log_bin=0` writes rows the binary log does not record. The reader cannot see them, so ClickHouse never receives them and the table diverges. Verified on MySQL 8.0 (`missing=1, value_diffs=1`: one row never reached ClickHouse and one value differed). The connector does not detect this at run time and no startup check covers it; it is a documented limitation (`doc/limitations.md`). The remedy is to keep `sql_log_bin` on, the MySQL default, in every session that writes a replicated table.

---

## 4. Invariants Preserved
- **Invariant I9 (Loud Failure)**: an XA rollback after PREPARE is an ERROR and a metric instead of silent divergence. A partial update event is an ERROR and a stopped engine, and the global setting is refused at start, instead of silent loss.
- **Invariant I15 (Bounded, Declared Recovery)**: both carry a declared repair (re-synchronisation of the affected tables, after the value is set empty for partial updates).
- **Read-only against the source**: the event observer and the start check only read. The one effect on the binlog stream is the refused read after a partial update event, which is the stop.

---

## 5. Verification Criteria
- `BinlogEventAuditTest.xaRollbackAfterPrepareIsReportedWithItsTables()` -- the E3 sequence: only the rolled-back XA is reported, with both tables of its PREPARE.
- `BinlogEventAuditTest.compressedXaBodyFedFromThePayloadIsTracked()` -- the body through `observeInner`, the outcome through the listener.
- `StreamingTransactionPayloadTest.compressedXaBodyReachesTheAudit()` -- a real compressed payload carrying `XA START`, a TABLE_MAP and `XA END`, iterated three times like the binlog client and Debezium do, then outer XA_PREPARE and XA ROLLBACK: one ERROR naming the table. Mutation-checked: without the decoder feed it fails.
- `BinlogEventAuditTest.xaRollbackWhosePrepareWasNotSeenSaysSo()`, `BinlogEventAuditTest.ordinaryQueriesAndCommittedXaAreSilent()`, `BinlogEventAuditTest.xidTextIsTheSameForEveryVerb()`, `BinlogEventAuditTest.anAuditFailureNeverStopsTheReader()`.
- `BinlogRowImagePreflightTest`: `minimalIsRefused()`, `noblobIsRefused()`, `fullPasses()`, `unreadableRowImageRefuses()`, `missingValueRefuses()`, `skipIsLoud()`, `skipAllowsAnUnreadableSourceLoudly()`, `partialJsonRefuses()`, `unknownValueOptionsVariablePasses()`, `emptyValueOptionsPasses()`, `unreadableValueOptionsRefuses()`, `unreachableHostRefuses()`, `nonMysqlPassesThrough()`, `queryIsReadOnly()`, `valueOptionsQueryIsReadOnly()`.
- `BinlogEventAuditTest.partialUpdateEventStopsTheReadThroughTheGuard()` -- the event sets the stop; the next read of the guarded stream fails with the ERROR's text, and consumes no socket bytes.
- `BinlogEventAuditTest.partialUpdateInsideACompressedTransactionStopsTheReadToo()` -- the same stop through the payload decoder's feed (`observeInner`).
- `BinlogEventAuditTest.resetForgetsTheRecordedStop()` and `BinlogEventAuditTest.ordinaryRowEventsDoNotStopTheRead()` -- a new task clears the stop; ordinary row events never set it.
- `DebeziumChangeEventCaptureTest.partialUpdateEventFailsTheEngineWithoutARetry()` -- with the stop recorded, the engine's failure is terminal: no restart, the terminal exit code.
- `DebeziumChangeEventCaptureTest.unreadableSourceAtRestartSpendsARetry()` -- an unreadable source at restart is a failed attempt, bounded by the retry budget, not an immediate terminal failure.
- `BinlogRowImageRestartTest.restartRechecksRowFormatAndRefusesAnUpdatedSource()` -- the second engine creation (an in-process restart) re-reads the source; a source that has become MINIMAL refuses, and no restart runs.
- `BinlogRowImageRestartTest.restartProceedsWhenTheSourceIsFull()` -- a source that is FULL again restarts the engine.
- Not covered by a test: the payload decoder's type-39 feed in `StreamedPayloadEvents`. The stop state is package-private to `cdc`, so a payload-package test cannot reset it without leaking into other tests. Its only check is the code path and the review.
- End to end: a scenario that sets the global value to `PARTIAL_JSON` expects the refusal at start; a scenario that sets it for one session expects the ERROR naming the table and the position, and a stopped engine with exit code 3.

---

## 6. Failure Modes & Recovery
- **FM-01.10-1 XA ROLLBACK after XA PREPARE**.
  - **Detection**: ERROR "XA ROLLBACK <xid> at <file:pos> ... Tables written by its PREPARE: [...]" (or UNKNOWN), `clickhouse_sink_binlog_xa_rollback_after_prepare` +1.
  - **Blast radius**: the rolled-back rows are live in ClickHouse, the source does not have them; limited to the named tables; everything else replicates.
  - **Recovery**: `ch-mysql-resync` of the named tables (spec 11.04); for UNKNOWN, find the PREPARE with `mysqlbinlog` before the reported position.
  - **RTO**: the re-synchronisation of the named tables (spec 11.04); measured in E3 for a small table, seconds.
  - **Test**: `BinlogEventAuditTest.xaRollbackAfterPrepareIsReportedWithItsTables()`, `StreamingTransactionPayloadTest.compressedXaBodyReachesTheAudit()`, edge E3.
- **FM-01.10-2 Partial JSON updates on the source**.
  - **Detection**: at start, the refusal (`Refusing to start: binlog_row_value_options=PARTIAL_JSON ...`, or the unreadable-value refusal) with an ERROR banner; at runtime, ERROR "partial update event at <file:pos> ... table <db.table>", then FATAL "Replication is STOPPED", and the process exits with code 3 unless `exit.on.terminal.failure=false`.
  - **Blast radius**: none beyond the event. The partial update's rows are not written. Rows of the same transaction that precede the event, and rows of the same payload that are dispatched before the stop, are replicated as usual; the named table is the one to re-synchronise.
  - **Recovery**: `SET PERSIST binlog_row_value_options = ''` (and fix the applications that set it per session); re-synchronise the named table (`ch-mysql-resync`, spec 11.04); restart the connector.
  - **RTO**: one restart plus the re-synchronisation of the named table.
  - **Test**: the unit tests of section 5; the end-to-end scenarios of section 5.
- **FM-01.10-3 The event observer's hook is not installed** (Debezium's metrics class loaded instead of the connector's copy).
  - **Detection**: the WARN of spec 01.09 FM-01.09-3 (same class, same marker).
  - **Blast radius**: XA rollbacks whose outcome is an outer event are silent again; partial update events are not stopped, and the start check is the only guard against partial JSON.
  - **Recovery**: run the shaded lightweight jar; restart.
  - **RTO**: one restart after the fix.
  - **Test**: `BinlogConnectionGuardTest.theShadowCopyIsTheLoadedClass()`.

Summary: 3 failure modes, 0 DEFECT, 0 GAP.

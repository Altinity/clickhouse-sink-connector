# Spec 01.07: Binlog Connection Loss, Keep-Alive Reconnect & Transaction Boundaries

## 1. Executive Summary & Purpose
Specifies what happens when the connection to the MySQL/MariaDB binlog is lost while the sink is blocked, and why the connector turns Debezium's client-side keep-alive auto-reconnect OFF unless the operator asks for it. A reconnect that resumes from the client's last-read byte offset can land inside a transaction, after the statement's `TABLE_MAP`; the rows that follow are then skipped without an error. Every restart path the connector owns resumes from the durable offset — a transaction boundary — so the loss-free behaviour is to let a lost connection stop the engine and restart it (spec 10.04), never to let the client resume mid-transaction.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/BinlogKeepAlivePreflight.java`
- **Call site**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java` (`setup(...)`, immediately after the row-image preflight and before the engine is built from the same `Properties`; the completion-callback restart reuses that object, so one call covers every engine of the process)
- **Drain that blocks the reader**: `DebeziumChangeEventCapture.drainBeforeDDL()` (spec 06.01 §3.2) — the pre-DDL drain is bounded by worker liveness, not by time; it is the most common reason the Debezium event thread is blocked long enough for the source to abort the dump connection.
- **Key methods**:
  - `BinlogKeepAlivePreflight.apply(Properties props)` — for a binlog connector (`connector.class` naming MySQL or MariaDB): sets `connect.keep.alive=false` when the key is absent (returns `true`); keeps an explicit `true` and logs a WARN banner on every start; keeps an explicit `false` at INFO. Never touches a non-binlog connector.
  - `BinlogKeepAlivePreflight.isBinlogConnector(Properties)` — MySQL / MariaDB detection.
  - `BinlogKeepAlivePreflight.PROPERTY` = `connect.keep.alive` (Debezium's own key); `BinlogKeepAlivePreflight.SAFE_VALUE` = `false`.

---

## 3. Operational Specification

### 3.1 How the reader loses its connection
The Debezium event thread is single: while it is inside the sink's batch handler — the pre-DDL drain (`drainBeforeDDL`, spec 06.01 §3.2), a full handoff queue (spec 01.05), a slow or retrying ClickHouse write — the binlog reader thread keeps filling Debezium's internal queue (`max.queue.size`) and then stops reading the socket. The source's dump thread cannot write for `net_write_timeout` (MySQL default 60 s) and aborts the connection (`Aborted connection … (Failed on my_net_write())` in the source error log). Nothing notices until the event thread returns and the reader touches the socket again.

### 3.2 What the client-side keep-alive reconnect does (Debezium default, `connect.keep.alive=true`)
The binlog client's keep-alive thread reconnects on its own and requests streaming from the client's **last-read byte offset** — `Requesting streaming from position filename: <file>, position: <offset>` — not from Debezium's recorded offset, which is the position of the current transaction's `BEGIN` plus the events and rows already delivered (`last recorded offset: {file, pos=<BEGIN>, event=N, row=M}`). When the connection was lost while the sink was blocked, the two differ by whatever the reader had queued ahead of the handler: the resume point is inside a transaction and, for a multi-event statement, inside the statement.

1. MySQL writes one `TABLE_MAP` per statement, followed by as many `ROWS` events as the statement needs (`binlog_row_event_max_size` chunks). A resume point past the `TABLE_MAP` re-streams only `ROWS` events for that statement.
2. Every new binlog stream opens with a `ROTATE`; Debezium's `handleRotateLogsEvent` clears the table-number map it built from `TABLE_MAP` events (`clearTableMappings`).
3. The `ROWS` events that follow carry a table number that no longer maps to a table. Debezium treats an event whose table number is unknown as an event for a table it does not capture: skipped at DEBUG, counted as filtered, no WARN, no ERROR, offset advancing past it.
4. Every later statement of the transaction opens with its own `TABLE_MAP` and is delivered normally, so row counts of the other tables match and nothing in the connector's own accounting shows a gap.

Observed on a production connector during a catch-up: a pre-DDL drain held the event thread for 6.5 minutes; the source aborted the dump connection 65 s into it; at drain release the keep-alive thread resumed at a byte offset 464 KB past Debezium's recorded `BEGIN`, inside a 729-row statement of a multi-table transaction; the 202 rows read before the resume point were written, the 527 after it were dropped; every other table of the transaction was complete. The only signal was a row-count checksum hours later. The same mechanism is reported upstream as debezium/dbz#2359 and fixed in the client library by debezium/mysql-binlog-connector-java#28 (September 2026: the keep-alive thread rewinds to the start of an open non-GTID transaction before reconnecting); the Debezium release this connector builds against still ships the previous client.

### 3.3 What the connector does instead (`connect.keep.alive=false`, the connector default)
With the keep-alive thread off, the lost connection surfaces on the reader's next read as a communication failure: Debezium's lifecycle listener disconnects the client and fails the task (`onCommunicationFailure` → producer throwable), the embedded engine completes with an error, and the completion callback restarts it (spec 10.04 §3.5) from the **durable** offset — the `BEGIN` of the last transaction whose rows were acknowledged, plus the events and rows already written (Invariant I8, spec 09.01). The replay re-delivers the `TABLE_MAP`, skips the already-processed events and rows by count (`Skipping previously processed row event`), and delivers the rest of the statement. Cost: one engine restart and a redelivery bounded by what the reader had queued ahead of the handler; the queued records were never acknowledged, so they are re-read from the source rather than lost.

A restart triggered inside a pre-DDL drain does not loop: the drain had already written and acknowledged every pre-DDL batch (that is what it waits for), so the durable offset sits just before the DDL, the restarted engine reaches the DDL with an empty pipeline, and the drain is immediate.

### 3.4 The operator's override
`connect.keep.alive=true` in the configuration is kept — the connector never overwrites an explicit operator value — and a WARN banner is logged on every start naming the property, the mechanism (the `TABLE_MAP` cache cleared by the resume's `ROTATE`) and the two conditions under which the reconnect is safe: GTID auto-positioning (the client reconnects at a transaction boundary), or a binlog client carrying the upstream fix. `connect.keep.alive.interval.ms` is only read when the thread is on; it is not touched. Non-binlog connectors (PostgreSQL) have no binlog client and are never touched.

---

## 4. Invariants Preserved
- **Invariant I8 (Durable Offset Quiescence)**: the durable offset never advances past unwritten rows. The client-side reconnect broke the *delivery* side of that promise — rows the offset would later advance past were never delivered to the sink at all; restarting from the durable offset restores delivery of everything past it.
- **No-loss is a code property, not a parameter (spec 10.06)**: a connection loss costs redelivery or a loud stop, never a silent gap.
- **Fail loudly (spec 10.04)**: a lost binlog connection is an engine error and a restart in the log, not a `DEBUG` skip.
- **Operator sovereignty**: an explicit configuration value is never overwritten; it is warned about.

---

## 5. Verification Criteria
- `BinlogKeepAlivePreflightTest.absentIsDefaultedToFalse` — a MySQL configuration without the key gets `connect.keep.alive=false`, `apply` returns `true`, the default is logged at INFO and nothing at WARN.
- `BinlogKeepAlivePreflightTest.explicitTrueIsKeptAndWarned` — an explicit `true` is kept, `apply` returns `false`, a WARN names the property and the `TABLE_MAP` mechanism.
- `BinlogKeepAlivePreflightTest.explicitTrueIsRecognisedWhateverTheSpelling` — ` TRUE ` is an explicit true (kept verbatim, warned).
- `BinlogKeepAlivePreflightTest.explicitFalseIsKeptQuietly` — an explicit `false` is kept without a warning.
- `BinlogKeepAlivePreflightTest.mariaDbIsDefaultedToo` — MariaDB is a binlog connector and gets the default.
- `BinlogKeepAlivePreflightTest.nonBinlogConnectorsAreUntouched` — PostgreSQL and an empty configuration are left without the key.
- `BinlogKeepAlivePreflightTest.propertyIsDebeziumsKey` — the key is spelled exactly as Debezium reads it, so the default is not a no-op.
- Gap (tracked): an end-to-end reproduction (blocked sink, source-side abort, keep-alive resume mid-statement) needs a live MySQL and a controllable sink stall; it is covered upstream by the integration test attached to debezium/dbz#2359 and not duplicated here.

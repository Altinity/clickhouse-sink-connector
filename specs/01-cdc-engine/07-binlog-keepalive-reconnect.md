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

### 3.5 The resume replay is summarized, never dumped
Every start — the completion-callback restart of §3.3 included — resumes from the durable offset, which Debezium records as the position of the transaction's BEGIN plus the number of events already delivered. The binlog client re-reads the transaction from BEGIN and Debezium skips the events it has already delivered, logging EACH one at INFO from `io.debezium.connector.binlog.BinlogStreamingChangeEventSource` as `Skipping previously processed row event: Event{header=..., data=...{rows=[ ... ]}}` — the complete row image, some seventy lines per event. On one deployment a single start logged 5,227 such events: seven rotated 6 MB files in fourteen seconds, all of it row data, none of it an error. The operator's rule: do not print the row data; print the operation type and the count of that operation.

`ResumeReplayLogSummary` (`sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/ResumeReplayLogSummary.java`) is a log4j filter installed programmatically at `setup()` on that Debezium logger (idempotent per process; no deployment `log4j2.xml` change):
1. A line from that logger beginning `Skipping previously processed ` is DENIED — it is never written anywhere — and counted under the row operation its binlog event type carries (`WRITE_ROWS`/`EXT_WRITE_ROWS` → INSERT, `UPDATE_ROWS`/`EXT_UPDATE_ROWS` → UPDATE, `DELETE_ROWS`/`EXT_DELETE_ROWS` → DELETE, any other type under its own name); the first and last `nextPosition` are kept.
2. The replay ends at the first of two signals, and ONE INFO line then reports the total, the elapsed time, the position range and the counts per operation, and the counters reset:
   - **A row is delivered to the sink.** `handleChangeEventBatch` calls `ResumeReplayLogSummary.rowDelivered()` for every record it classifies as a row (not a DDL, not a control record). Debezium never delivers the rows it skips, so the first row after a skip run is past the resume point by construction. This is the signal that fires in production: once the skip run ends the streaming logger is silent at INFO for hours (one deployment logged its 120 s progress line and then nothing — the total was never reported), so the logger-based signal alone reported the replay only at the engine stop, with the process lifetime as its duration. **Rows only**: Debezium's `handleEvent` dispatches a heartbeat after EVERY binlog event, skipped ones included, so a heartbeat or a transaction marker delivered mid-replay must not end it. The call costs one volatile read per row while no replay is pending; it never takes the filter's lock on that path.
   - **The next line from that logger is NOT a skip** (the reader moved past the resume point straight into a quiet source) — the fallback for a resume that delivers no row.
   An engine stop flushes a replay still being counted (`flushInstalled`, `stop()` step 4c).
3. A replay still running after 60 s logs ONE progress line per 60 s with the same shape — never one per event.
4. Every other line from that logger passes unchanged; lines from every other logger are never inspected.

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
- `ResumeReplayLogSummaryTest` — §3.5: skip lines are denied and counted by operation and the next non-skip line from the same logger emits exactly one summary naming counts and positions with no row image, after which the counters are reset (`skipLinesAreCountedAndSummarisedOnce`); lines from other loggers are neutral and uncounted (`otherLoggersAreUntouched`); a long replay reports one progress line per interval and a stop flushes the final summary (`longReplayReportsProgress`); `install()` is idempotent and Debezium's real logger is routed through the filter so the row image is never written (`installIsIdempotentAndEffective`); the first delivered row ends a pending replay with exactly one summary naming the row-delivered reason, and a row with no replay pending — before or after — reports nothing (`deliveredRowEndsTheReplay`); binlog event types map to INSERT/UPDATE/DELETE, others by name (`operationMapping`).
- `ResumeReplaySummaryEndsOnDeliveredRowTest` — §3.5 through the real `handleChangeEventBatch`: the first row through the handler ends a pending replay with one summary, the counters reset and no row image is written (`firstDeliveredRowEndsTheReplay`); a heartbeat and a transaction-boundary record delivered mid-replay do not end it (`controlRecordsDoNotEndTheReplay`); a row with no replay pending reports nothing (`rowWithoutReplayIsSilent`).
- Gap (tracked): an end-to-end reproduction (blocked sink, source-side abort, keep-alive resume mid-statement) needs a live MySQL and a controllable sink stall; it is covered upstream by the integration test attached to debezium/dbz#2359 and not duplicated here.

---

## 6. Failure Modes & Recovery
With `connect.keep.alive=false` every connection failure the binlog client SEES becomes an engine restart from the durable offset — correct and bounded (spec 01.01 FM-01.01-1). What the client does not see it waits on forever: the socket has no read timeout, no TCP keep-alive, and the keep-alive thread that would have noticed the missing server heartbeats is the one this spec turns off. Facts below about Debezium 3.1.3 and mysql-binlog-connector-java 0.40.2 were read in their bytecode (`~/.m2`).

- **FM-01.07-1 Connection lost while the sink holds the Debezium thread (or dropped by the source)**
  - **Trigger**: the source aborts the dump after `net_write_timeout` because the reader stopped draining (pre-DDL drain, hard cap, retrying write — §3.1), or the source restarts, kills the dump thread, or the network resets the connection.
  - **Behaviour**: the client's next read fails; Debezium's `ReaderThreadLifecycleListener.onCommunicationFailure` calls `ErrorHandler.setProducerThrowable(wrap(e))`; `MySqlErrorHandler.isRetriable` ends in `super.isRetriable(null)` = false for every cause chain, so the task fails with `ConnectException` and the engine completes with an error; `handleEngineCompletion` recreates it after 10 s from the durable offset (§3.3), and the replay is summarized (§3.5). The retry budget refills on every acknowledged offset, so repeated aborts during a long catch-up never exhaust it.
  - **Detection**: ERROR `Producer failure` (Debezium), ERROR `Engine stopped with an error: org.apache.kafka.connect.errors.ConnectException: An exception occurred in the change event producer. This connector will be stopped. ...`, ERROR `Restarting the engine - retry 1 of 10`, then INFO `Resume replay done (...)` — when the Debezium thread next polls, i.e. once the blocking handler returns (≥ `net_write_timeout`, 60 s by MySQL default, after the stall began).
  - **Blast radius**: all tables pause for one restart; no loss (§3.3); the queued, unacknowledged records are re-read.
  - **Recovery**: automatic.
  - **RTO**: 10 s + engine start (3–9 s observed, spec 01.01 FM-01.01-1) + re-read of what the reader had queued; unmeasured end to end.
  - **Test**: `EngineFailureClassificationTest.sourceConnectionLossStillRetries()`, `ResumeReplaySummaryEndsOnDeliveredRowTest.firstDeliveredRowEndsTheReplay()`; the end-to-end reproduction is the tracked gap of §5.

- **FM-01.07-2 Half-open binlog connection**
  - **Trigger**: the path to the source dies without a FIN or RST reaching the connector — source host power loss or kernel panic, network partition, a firewall/NAT/load-balancer dropping the connection state, a VIP moved without resetting clients.
  - **Behaviour**: `BinaryLogClient` opens a plain `new Socket()` and never calls `setSoTimeout` or `setKeepAlive` (none in the class; Debezium's `BinlogStreamingChangeEventSource` neither), so the reader blocks in `read()` with an infinite timeout and TCP never probes an idle receive-only connection. Debezium asks the server for heartbeat events every 0.8 × `connect.keep.alive.interval.ms` (48 s; `setHeartbeatInterval` is called whatever `connect.keep.alive` says), but only the keep-alive thread checks that events keep arriving — and `BinlogKeepAlivePreflight` switches it off. No record, no heartbeat, no error ever reaches the sink. The restart monitor, when enabled (ansible default, 3000 s), restarts the engine 50–100 min later because no ROW arrived (spec 01.01 FM-01.01-3); with the code default (`restart.event.loop=false`) nothing ever does.
  - **Detection**: none. `/status` reports `Replica_Running=true`; `Seconds_Behind_Source` and the lag gauges (`clickhouse_sink_db_lag`, `clickhouse_sink_debezium_lag`) freeze at their last values because they are updated only when a row is processed or written; `clickhouse_sink_binlog_pos` stops moving — visible only against the source's `SHOW BINARY LOG STATUS`.
  - **Blast radius**: every table stops, indefinitely; no loss (nothing is acknowledged); the source may purge the binlog the connector still needs (FM-01.07-5).
  - **Recovery**: restart the service (`systemctl restart`); it reconnects from the durable offset. Mitigation available today: on a GTID source set `connect.keep.alive: "true"` explicitly (§3.4: the reconnect resumes at a GTID boundary), which restores a 60 s silence detector.
  - **RTO**: unbounded with code defaults; 50–100 min with the ansible monitor; after a manual restart 30 s + start; unmeasured.
  - **Test**: `RestartMonitorLivenessTest.heartbeatRefreshesTheMonitorClock()` (disabled, fails on 2.11.0: heartbeats do not count as liveness); GAP: a fake source that completes the handshake and then goes silent, asserting a loud stop within a stated bound.
  - **DEFECT**: a half-open source connection stalls replication silently and forever under the connector's own defaults.

- **FM-01.07-3 `connect.keep.alive=true` on a file/position source**
  - **Trigger**: the operator sets `connect.keep.alive: "true"` and the source is positioned by file/offset (no GTID auto-positioning), with a binlog client without debezium/mysql-binlog-connector-java#28.
  - **Behaviour**: `BinlogKeepAlivePreflight.apply` keeps the value; after a connection loss while the sink was blocked, the keep-alive thread resumes from its last-read byte offset and Debezium skips the rest of the in-flight statement at DEBUG (§3.2).
  - **Detection**: only the WARN banner at every start (`!!  connect.keep.alive=true -- BINLOG KEEP-ALIVE AUTO-RECONNECT ENABLED  !!`); the loss itself logs nothing; a row-count checksum finds it hours later.
  - **Blast radius**: silent loss of the remaining rows of one statement per such reconnect, in any table.
  - **Recovery**: remove the key (default false) and restart; checksum every table and `ch-mysql-resync` (spec 11.04) the diverged ones.
  - **RTO**: unbounded — the loss is silent; repair is per table.
  - **Test**: `BinlogKeepAlivePreflightTest.explicitTrueIsKeptAndWarned()`.
  - **DEFECT**: a configuration value that loses rows silently on non-GTID sources is accepted with a WARN instead of refused.

- **FM-01.07-4 Source down or unreachable for a long time**
  - **Trigger**: MySQL stopped, crashed, in maintenance, or the network to it cut with RST / refused connections.
  - **Behaviour**: each recreated engine fails to connect (binlog connect bounded by `connect.timeout.ms`, Debezium default 30 000; the JDBC connect timeout is unverified) and completes with an error; with no acknowledgement in between, the budget runs out after ≈ 10 × (10 s + up to 30 s + start) ≈ 2–7 min → exit 3 → systemd restarts after 30 s → the cycle repeats while the source is down.
  - **Detection**: ERROR `Engine stopped with an error:` per attempt (cause: the connection error), FATAL `Replication is STOPPED ...` and exit 3 every cycle; `/status` `Replica_Running` is set false at each stop (`onConnectorStopped`) and true again at each engine start (`markEngineStarted`), so it flickers rather than staying false.
  - **Blast radius**: all tables stop; no loss while the source keeps its binlog (FM-01.07-5 otherwise).
  - **Recovery**: automatic once the source is reachable.
  - **RTO**: source back → ≤ 10 s (in-process retry) or ≤ 30 s (systemd) + start; unmeasured.
  - **Test**: `TerminalFailureExitTest.exitHookFiresAfterMaxRetries()`, `EngineFailureClassificationTest.sourceConnectionLossStillRetries()`.

- **FM-01.07-5 The binlog the durable offset needs has been purged**
  - **Trigger**: the connector was down, stalled (FM-01.07-2) or lagging longer than the source's `binlog_expire_logs_seconds`, or someone ran `PURGE BINARY LOGS`; with GTID, the needed GTIDs are in `gtid_purged`.
  - **Behaviour**: at task start Debezium's `BaseSourceTask` logs WARN `Last recorded offset is no longer available on the server.` and, unless the snapshot mode snapshots on data errors (`when_needed`: `Attempting to snapshot data to fill the gap.`), continues; the dump then fails with server error 1236, which follows FM-01.07-1's path and is retried like a transient failure: 10 × (10 s + start) → exit 3 → systemd → the same, forever.
  - **Detection**: WARN `Last recorded offset is no longer available on the server.` then ERROR `Engine stopped with an error: cause: <server message> Error code: 1236; SQLSTATE: HY000.` at every start; FATAL and exit 3 every ≈ 3 min. No earlier warning while the lag approaches the retention.
  - **Blast radius**: every change between the durable offset and the oldest remaining binlog is unrecoverable from the log; all tables stop.
  - **Recovery**: the source tables are the only copy: either re-snapshot (`snapshot.mode: when_needed`, or `sink-connector-client delete_offsets` then `initial`), or `change_replication_source` to the source's current coordinates (`SHOW BINARY LOG STATUS`) followed by `ch-mysql-resync` (spec 11.04) of EVERY replicated table.
  - **RTO**: a full reload or a resync of every table — far beyond 5 minutes; unmeasured.
  - **Test**: `EngineFailureClassificationTest.purgedBinlogIsTerminalAtOnce()` (disabled, fails on 2.11.0: the engine is recreated); GAP: no check warns while lag approaches the source's binlog retention.
  - **DEFECT**: a deterministic purge is retried as transient, and nothing warns before the retention is crossed, so the only recovery is a full reload.

- **FM-01.07-6 Source failover to a promoted replica**
  - **Trigger**: the VIP or DNS name of `database.hostname` moves to another server.
  - **Behaviour**: the old connection drops (FM-01.07-1) or goes half-open (FM-01.07-2). With `gtid_mode=ON` the recreated task positions by the offset's executed GTID set (`BinlogStreamingChangeEventSource`: `Registering binlog reader with GTID set: '<set>'`) — a transaction boundary valid on any server of the topology. Without GTID it positions by the old server's file/offset (spec 01.02 FM-01.02-3). In both cases the in-memory version mark is not reset by the in-process restart (spec 01.02 FM-01.02-2).
  - **Detection**: the lines of FM-01.07-1, then INFO `Registering binlog reader with GTID set: ...` on a GTID source.
  - **Blast radius**: GTID: one restart, no loss; the version-floor gap of FM-01.02-2 if the new server's binlog numbering is lower. Without GTID: see FM-01.02-3.
  - **Recovery**: GTID: automatic, then restart the process once to re-seed the version floor (FM-01.02-2). Without GTID: the manual repositioning of FM-01.02-3.
  - **RTO**: GTID: one engine restart (FM-01.07-1) + one process restart ≈ 1 min; without GTID: manual, unbounded; unmeasured.
  - **Test**: GAP: a GTID failover between two MySQL servers with the connector running.

- **FM-01.07-7 Two connectors share one `database.server.id`**
  - **Trigger**: a copied configuration, a second instance started against the same source with the same `database.server.id`.
  - **Behaviour**: MySQL keeps one dump per replica server id and ends the older one when a new one registers (documented server behaviour, not reproduced here); each connector's loss is FM-01.07-1, whose restart ends the other's dump — a ping-pong in which each side re-reads from its durable offset every cycle. Progress between cycles refills the budget, so it never reaches the terminal exit.
  - **Detection**: the FM-01.07-1 lines repeating on both connectors every restart cycle (≈ 13–19 s) with no other cause; the source error log shows the replaced dump.
  - **Blast radius**: both pipelines crawl and re-read continuously; no loss.
  - **Recovery**: give every connector against one source a unique `database.server.id` (distinct from every real replica's `server_id`) and restart.
  - **RTO**: one restart after the fix; unmeasured.
  - **Test**: GAP: no check refuses a server id already registered on the source (`SHOW REPLICAS`).

Summary: 7 failure modes, 3 DEFECT, 4 GAP.

# Spec 01.09: Binlog Connection Guard -- Every Dead Binlog Connection Is a Loud Restart, Never a Silent Stall

## 1. Executive Summary & Purpose
Specifies how the lightweight connector detects a binlog connection that has died, in every way it can die, and turns it into the one path that recovers without loss: a communication failure, an engine stop, and the completion-callback restart from the durable offset (spec 10.04). With `connect.keep.alive=false` -- the connector's default, chosen because the binlog client's own reconnect can resume inside a statement and lose its rest (spec 01.07) -- two ways a connection dies were invisible to Debezium 3.1.3 and the binlog client 0.40.2, and each left replication standing still for as long as the process lived, with the engine reporting running and nothing logged at ERROR:

1. **The source closed the connection** -- mysqld killed or crashed, the host rebooted, a failover, a `KILL` of the dump thread. The client's read loop treats end of stream at a packet boundary as a normal end and never reports a communication failure. Measured (spec 01.08 section 6, phase F7, mysqld killed -9 under load): "Stopped reading binlog after N events" at INFO, then no progress and no log line for 15 minutes.
2. **The connection died silently** -- a network partition, a dropped firewall or NAT flow, a vanished host. The source's dump thread gives up after `net_write_timeout` and closes its side, but the close is lost; the client, which only reads, never provokes the RST that would tell it, and its socket has no read timeout. Measured (F3, 300 s partition under load, the source's dump thread gone after ~70 s): zero progress and no log line for the whole observation window.

The guard closes both in the socket the client reads from. It is the mechanism MySQL's own replica uses (`replica_net_timeout` plus source heartbeats), applied to the one socket Debezium does not configure.

---

## 2. Codebase Mapping on 2.11.0
- **Primary Source**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/BinlogConnectionGuard.java`
  - `BinlogConnectionGuard.configure(Properties)` -- resolves `binlog.read.timeout.ms` (§3.2) and records it; logs the resolved guard at INFO.
  - `BinlogConnectionGuard.install(BinaryLogClient)` -- `client.setSocketFactory(new GuardedSocketFactory(timeout))`; installed for every MySQL task, whatever the timeout.
  - `BinlogConnectionGuard.GuardedSocketFactory.createSocket()` -- a `GuardedSocket` with `SO_TIMEOUT` = the timeout and `SO_KEEPALIVE` on.
  - `BinlogConnectionGuard.GuardedSocket.getInputStream()` -- wraps the socket stream in `PeerCloseIsAFailure`.
  - `BinlogConnectionGuard.PeerCloseIsAFailure.read()` / `read(byte[], int, int)` -- the peer's end of stream is an `EOFException` (message `PEER_CLOSED`), a read timeout is re-thrown; each logs at ERROR and increments `clickhouse_sink_binlog_connection_lost`.
  - `BinlogConnectionGuard.shadowMarker()` / `BinlogConnectionGuard.shadowActive(Properties)` -- prove at start that the class the runtime loads under Debezium's name is the connector's copy (§3.3); WARN when it is not.
- **The hook into Debezium**: `sink-connector-lightweight/src/main/java/io/debezium/connector/mysql/MySqlStreamingChangeEventSourceMetrics.java` -- the connector's copy of Debezium 3.1.3.Final's class of the same fully qualified name: the same constructor delegating to `BinlogStreamingChangeEventSourceMetrics`, plus `BinlogConnectionGuard.install(taskContext.getBinaryLogClient())` and `BinlogEventAudit.install(...)` (spec 01.10). Carries `BINLOG_CONNECTION_GUARD` = `BinlogConnectionGuard.MARKER`.
- **Call site**: `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java` -- `setup(...)`, immediately after `BinlogKeepAlivePreflight.apply(props)`: `BinlogConnectionGuard.configure(props)`, `BinlogConnectionGuard.shadowActive(props)`.
- **Metric**: `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/common/Metrics.java` -- `Metrics.incrementBinlogConnectionLost()`; name `clickhouse_sink_binlog_connection_lost` in `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/common/MetricsConstants.java`.
- **Not this repository's code**: mysql-binlog-connector-java 0.40.2 `BinaryLogClient.connect` / `listenForEventPackets` (the read loop and its `onCommunicationFailure` / `onDisconnect` callbacks), `BinaryLogClient.setSocketFactory`; Debezium 3.1.3 `BinlogStreamingChangeEventSource.createBinaryLogClient` (sets the heartbeat interval), `BinlogStreamingChangeEventSource.ReaderThreadLifecycleListener.onCommunicationFailure` (turns the failure into a producer throwable), `BinlogTaskContext.getBinaryLogClient`.

---

## 3. Operational Specification

### 3.1 Why neither death reached Debezium
`BinaryLogClient.listenForEventPackets` loops `while (inputStream.peek() != -1)`: an end of stream at a packet boundary ends the loop NORMALLY, the channel is closed, and `connect()` reports `onDisconnect` only. Debezium's `onDisconnect` logs "Stopped reading binlog after N events" at INFO; only `onCommunicationFailure` -- reached solely from the loop's `catch` -- fails the task. So a source-side close stopped the reader and left the engine idling. `BinaryLogClient.openChannel` builds its socket with `new Socket()` unless a socket factory is set, and neither Debezium nor the client sets `SO_TIMEOUT`; with the keep-alive thread off nothing else checks for silence. So a connection that died silently blocked the reader in `read()` forever. A connection reset (RST) was always fine: the read throws, the loop's `catch` reports the failure (measured F2: recovered in 18-21 s).

### 3.2 The guard
The connector's socket factory gives the client a `GuardedSocket`:

1. **End of stream is an exception.** `PeerCloseIsAFailure` returns data unchanged and throws `EOFException(PEER_CLOSED)` where the socket stream would return `-1`. Debezium runs the client in blocking mode, where the source never ends a binlog stream on purpose (the non-blocking end is a `0xFE` packet, not a closed socket), so a peer close is always a lost connection. Thrown from `peek()`, it reaches the loop's `catch`, the client is still marked connected, and `onCommunicationFailure` runs. A close initiated on the connector's own side (an engine stop) is unchanged: `BinaryLogClient.disconnectChannel` marks the client disconnected before it closes the channel, the blocked read fails with `SocketException`, and the `catch` skips the failure callback exactly as before. A zero-length read returns 0.
2. **A read timeout.** `SO_TIMEOUT` = `binlog.read.timeout.ms`. Unset, it is twice `connect.keep.alive.interval.ms` (Debezium's key, default 60000): 120 s by default. The client always asks the source for a heartbeat every `0.8 x connect.keep.alive.interval.ms` (48 s by default; `BinlogStreamingChangeEventSource.createBinaryLogClient`, unconditional; `BinaryLogClient.setupConnection` → `enableHeartbeat`), and the source sends one whenever it has had nothing else to send for that long, so a live connection is never silent for 2.5 heartbeat periods. `SO_TIMEOUT` fires only while the reader is blocked inside `read()` with no byte arriving: a reader blocked on a full change-event queue behind a slow sink, or decoding a large payload (spec 01.08 §3.2.1), is not reading and is never timed out, and a large event arriving slowly over a thin link keeps delivering bytes. A configured value not longer than one heartbeat period would time out an idle, healthy source; it is raised to two periods with a WARN. `0` disables the timeout, with a WARN at every start; the end-of-stream half stays on. A value that is not a number refuses startup.
3. **TCP keep-alive** (`SO_KEEPALIVE`) is set as well; it is a backstop with the operating system's timers, not the mechanism.

Either failure is logged at ERROR by the guard ("the MySQL source closed the binlog connection ..." / "no byte from the MySQL source for N ms ..."), counted in `clickhouse_sink_binlog_connection_lost`, and reaches Debezium as `onCommunicationFailure`; the engine stops with the error and the completion callback restarts it from the durable offset within the retry budget (spec 10.04 §3.5). If the source stays unreachable the budget is spent and the process exits with code 3 -- loud, for the supervisor.

### 3.3 How the guard reaches the client
Debezium never hands the `BinaryLogClient` to the embedding application and never sets its socket factory; `setSocketFactory` is the client's only hook, and it must be called before `connect()`. The MySQL connector constructs `io.debezium.connector.mysql.MySqlStreamingChangeEventSourceMetrics` once per task with the task context whose `getBinaryLogClient()` is the client the streaming source then configures and connects. The connector ships its own class under that name -- Debezium's is a four-line constructor -- which installs the guard (and the event audit of spec 01.10). The lightweight jar's shade plugin keeps the project's class and drops the dependency's duplicate, the arrangement the streaming payload decoder already relies on (spec 01.08 §3.2.1). `shadowActive(Properties)` reads the marker field reflectively at every start: Debezium's own class has no such field, so a classpath on which it shadows the connector's is reported at WARN. If a Debezium upgrade changes the constructor, the copy no longer matches and the task fails at start with a linkage error -- loudly. MariaDB's connector constructs its own metrics class and is not covered.

---

## 4. Invariants Preserved
- **Invariant I9 (Loud Failure)**: both deaths are an ERROR line, a metric, and an engine stop; neither is a silent stall.
- **Invariant I8 (Durable Offset Quiescence)**: recovery is the existing restart from the durable offset; nothing is skipped, the in-flight transaction is redelivered (spec 01.08 §3.3 for a compressed one).
- **Invariant I15 (Bounded, Declared Recovery)**: a dead connection is detected within `binlog.read.timeout.ms` (source close: at once) and replication resumes by itself; measured 7-9 s after mysqld was killed -9 and 21-23 s after a partition healed (§6).
- **Read-only against the source**: the guard only changes how the connector's own socket reads.

---

## 5. Verification Criteria
- `BinlogConnectionGuardTest.defaultIsTwiceTheKeepAliveInterval()`, `BinlogConnectionGuardTest.operatorValueWinsAndZeroDisablesTheTimeout()`, `BinlogConnectionGuardTest.aTimeoutNotLongerThanTheHeartbeatIsRaised()`, `BinlogConnectionGuardTest.garbageIsRefused()`, `BinlogConnectionGuardTest.nonBinlogConnectorIsUntouched()` -- §3.2 item 2, the resolution of the timeout.
- `BinlogConnectionGuardTest.factorySocketsCarryTheTimeoutAndKeepAlive()` -- §3.2 items 2 and 3.
- `BinlogConnectionGuardTest.endOfStreamIsAFailureNotMinusOne()`, `BinlogConnectionGuardTest.aPeerCloseOnARealSocketThrows()` -- §3.2 item 1, on a stream and on a real socket closed by the peer.
- `BinlogConnectionGuardTest.aSilentPeerFailsTheClientWithinTheTimeout()` -- a real `BinaryLogClient` against a peer that never sends a byte fails with `SocketTimeoutException` within the 600 ms timeout. Mutation-checked: without `setSoTimeout` the test fails.
- `BinlogConnectionGuardTest.installAlwaysSetsTheGuardedFactory()`, `BinlogConnectionGuardTest.theShadowCopyIsTheLoadedClass()` -- §3.3.
- End to end, `sink-connector-lightweight/tests/e2e/binlog_transaction_compression_chaos.sh` (spec 01.08 section 6): phase F3 asserts that a dead, silent connection was actually formed (the source's dump thread gone, the fault proxy holding the connector's side open) and that replication recovers by itself; phase F7 kills mysqld -9 under load; phase I0 keeps the source idle for 200 s, longer than the timeout, and asserts zero engine and process restarts (no false positive). On the 2.11.0 head without the guard, F3 and F7 do not recover (300 s and 421 s observed, no ERROR); with it, 22/22 in both GTID modes (F3 21-23 s, F7 7-9 s, I0 0 restarts).

---

## 6. Failure Modes & Recovery
The guard exists to make the connection's failure modes recover by themselves; its own failure modes are few and each is loud.

- **FM-01.09-1 Source closed the connection** (mysqld crash/kill, reboot, failover, dump thread killed).
  - **Detection**: ERROR "the MySQL source closed the binlog connection (end of stream)..." at once, `clickhouse_sink_binlog_connection_lost` +1, then "Engine stopped with an error" and "Restarting the engine - retry n of N".
  - **Blast radius**: replication pauses until the source accepts connections again; nothing is lost or skipped.
  - **Recovery**: automatic. If the source stays down past the retry budget the process exits with code 3 and the supervisor restarts it; nothing to do on the connector side once the source is back.
  - **RTO**: measured 7-9 s after mysqld came back (chaos harness F7, both GTID modes); plus the re-apply of the in-flight transaction.
  - **Test**: `BinlogConnectionGuardTest.aPeerCloseOnARealSocketThrows()`, chaos F7.
- **FM-01.09-2 Connection died silently** (partition, dropped flow, vanished host).
  - **Detection**: ERROR "no byte from the MySQL source for N ms (binlog.read.timeout.ms)..." `binlog.read.timeout.ms` after the last byte (120 s default), metric +1, engine restart.
  - **Blast radius**: replication pauses for up to the timeout; nothing lost.
  - **Recovery**: automatic once the network is back; each attempt that cannot connect draws on the retry budget, then exit code 3 and supervisor restart.
  - **RTO**: at most `binlog.read.timeout.ms` after the last byte, then the restart; measured 21-23 s after the partition healed (F3, the timeout having elapsed during the partition).
  - **Test**: `BinlogConnectionGuardTest.aSilentPeerFailsTheClientWithinTheTimeout()`, chaos F3.
- **FM-01.09-3 The shadow class is not the one loaded** (a classpath that puts Debezium's jar first).
  - **Detection**: WARN at every start "The binlog connection guard will NOT be applied ... marker=null".
  - **Blast radius**: the connection is unguarded: FM-01.09-1/2 revert to silent stalls.
  - **Recovery**: run the shaded lightweight jar (the supported artifact); restart.
  - **RTO**: one restart after the fix.
  - **Test**: `BinlogConnectionGuardTest.theShadowCopyIsTheLoadedClass()`.
- **FM-01.09-4 Timeout configured too low for the source's heartbeat**.
  - **Detection**: WARN at start naming the raised value; with a raised value, none.
  - **Blast radius**: none (the value is raised to two heartbeat periods).
  - **Recovery**: set `binlog.read.timeout.ms` above `0.8 x connect.keep.alive.interval.ms`.
  - **RTO**: not applicable.
  - **Test**: `BinlogConnectionGuardTest.aTimeoutNotLongerThanTheHeartbeatIsRaised()`.
- **FM-01.09-5 Timeout disabled** (`binlog.read.timeout.ms=0`).
  - **Detection**: WARN at every start that the read timeout is disabled.
  - **Blast radius**: FM-01.09-2 is a silent stall again; FM-01.09-1 stays guarded.
  - **Recovery**: remove the setting; restart.
  - **RTO**: unbounded while disabled -- an operator choice, stated at WARN.
  - **Test**: `BinlogConnectionGuardTest.operatorValueWinsAndZeroDisablesTheTimeout()`.

Summary: 5 failure modes, 0 DEFECT, 0 GAP.

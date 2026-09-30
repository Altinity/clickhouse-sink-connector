package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.common.Metrics;
import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.network.SocketFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Properties;

/**
 * Makes every way a binlog connection can die a loud communication failure, so the engine restarts
 * from the durable offset instead of standing still (spec 01.09).
 *
 * <p>With {@code connect.keep.alive=false} -- the connector's default (spec 01.07), chosen because the
 * binlog client's own reconnect can resume inside a statement and lose its rest -- nothing in Debezium
 * 3.1.3 or the binlog client 0.40.2 notices a connection that died in two ways, and replication then
 * stops for as long as the process lives: the engine reports running, nothing is logged at ERROR, and
 * only an external lag alert or a human notices. Both were measured end to end (spec 01.08 section 6):</p>
 * <ol>
 *   <li><b>The source closed the connection</b> (mysqld killed or crashed, host rebooted, a failover, a
 *   {@code KILL} of the dump thread). The client's read loop ({@code BinaryLogClient.listenForEventPackets},
 *   {@code while (inputStream.peek() != -1)}) treats end-of-stream at a packet boundary as a normal end:
 *   it closes the channel and reports {@code onDisconnect} only -- never {@code onCommunicationFailure},
 *   the one callback Debezium turns into an engine failure. Debezium logs "Stopped reading binlog after N
 *   events" at INFO and the engine idles with no reader. Measured (F7, mysqld killed -9 under load): no
 *   progress and no further log line for 15 minutes, until the process was restarted.</li>
 *   <li><b>The connection died silently</b> (a network partition, a dropped firewall or NAT flow, a
 *   vanished host). The source's dump thread gives up after {@code net_write_timeout} and closes its
 *   side, but the close is lost; the client, which only reads, never provokes the RST that would tell
 *   it, and its socket has no {@code SO_TIMEOUT}. The reader blocks in {@code read()} forever. Measured
 *   (F3, 300 s partition): zero progress and no log line for the whole observation window.</li>
 * </ol>
 *
 * <p><b>The fix</b>, both halves in the socket the client reads from, given to it through its
 * {@code setSocketFactory} (the only hook it has):</p>
 * <ul>
 *   <li>End-of-stream from the source is an {@link EOFException}, not {@code -1}. In blocking mode (the
 *   only mode Debezium uses) the source never ends a binlog stream on purpose, so a peer close is always
 *   a lost connection; thrown from the read, it reaches the client's catch and becomes
 *   {@code onCommunicationFailure}. A close initiated on OUR side (an engine stop) is unaffected: the
 *   client marks itself disconnected before it closes the channel, so the failure callback is skipped
 *   exactly as before.</li>
 *   <li>{@code SO_TIMEOUT} = {@code binlog.read.timeout.ms}, default twice the keep-alive interval: the
 *   source sends a heartbeat every {@code 0.8 x connect.keep.alive.interval.ms} when it has nothing else
 *   to send (the client always requests it; Debezium 3.1.3
 *   {@code BinlogStreamingChangeEventSource.createBinaryLogClient}), so a live connection is never silent
 *   that long. This is MySQL's own replica mechanism ({@code replica_net_timeout}). It fires only while
 *   the reader is blocked inside {@code read()} with no byte arriving: a reader blocked on a full queue
 *   behind a slow sink, or busy decoding a large payload, is not reading and is never timed out.</li>
 * </ul>
 * <p>Either way the client reports a communication failure, Debezium stops the engine with it, and the
 * completion callback restarts from the durable offset (spec 10.04) -- the bounded, loss-free path a
 * connection reset already took (F2: 18 s). {@code SO_KEEPALIVE} is set as well.</p>
 *
 * <p><b>How it reaches the client.</b> Debezium never hands the {@link BinaryLogClient} to the
 * embedding application and never sets its socket factory. The connector's copy of
 * {@code io.debezium.connector.mysql.MySqlStreamingChangeEventSourceMetrics} -- the one class the MySQL
 * connector constructs with the task's client before streaming starts -- calls
 * {@link #install(BinaryLogClient)}; the shade plugin keeps the project's class and drops Debezium's
 * (the arrangement the streaming payload decoder already uses, spec 01.08 section 3.2.1).
 * {@link #shadowActive(Properties)} proves at start that the copy is the class the runtime loads. MariaDB's
 * connector constructs its own metrics class and is not covered.</p>
 */
public final class BinlogConnectionGuard {

    private static final Logger log = LogManager.getLogger(BinlogConnectionGuard.class);

    /** Connector property: the binlog read timeout in milliseconds; {@code 0} disables the timeout. */
    public static final String PROPERTY = "binlog.read.timeout.ms";

    /** Debezium's keep-alive interval key; the source heartbeat period is 0.8 of it. */
    static final String KEEP_ALIVE_INTERVAL = "connect.keep.alive.interval.ms";

    /** Debezium 3.1.3's default for {@link #KEEP_ALIVE_INTERVAL}. */
    static final long DEFAULT_KEEP_ALIVE_INTERVAL_MS = 60_000L;

    /** Debezium's factor from the keep-alive interval to the requested heartbeat period. */
    static final double HEARTBEAT_FACTOR = 0.8;

    /** The marker the connector's copy of the metrics class carries (see {@link #shadowActive(Properties)}). */
    public static final String MARKER = "binlog-connection-guard-v1";

    /** The fully qualified name Debezium constructs; the connector ships its own class under it. */
    static final String SHADOWED_CLASS = "io.debezium.connector.mysql.MySqlStreamingChangeEventSourceMetrics";

    /** The message of the exception a source-side close becomes. */
    static final String PEER_CLOSED = "the MySQL source closed the binlog connection (end of stream): treated as a "
            + "communication failure so the engine restarts from the last committed offset (spec 01.09)";

    /** The timeout resolved at setup and applied by {@link #install}; {@code 0} = none. */
    private static volatile long timeoutMs = 0L;

    private BinlogConnectionGuard() {
    }

    /**
     * Resolves the read timeout from the connector properties and records it for {@link #install}.
     *
     * <p>Unset: twice the keep-alive interval (120 s at Debezium's default), i.e. 2.5 source heartbeat
     * periods. Set: the operator's value, {@code 0} disabling the timeout (loudly; the end-of-stream half
     * of the guard stays on). A value not longer than one heartbeat period would time out an idle, healthy
     * source and is raised to two periods, with a WARN.</p>
     *
     * @param props the connector properties.
     * @return the timeout that {@link #install} will apply, in milliseconds ({@code 0} = none).
     */
    public static long configure(Properties props) {
        if (!BinlogKeepAlivePreflight.isBinlogConnector(props)) {
            timeoutMs = 0L;
            return 0L;
        }
        long keepAlive = parseLong(props.getProperty(KEEP_ALIVE_INTERVAL), DEFAULT_KEEP_ALIVE_INTERVAL_MS);
        long heartbeat = (long) (keepAlive * HEARTBEAT_FACTOR);
        String configured = props.getProperty(PROPERTY);
        long value;
        if (configured == null || configured.trim().isEmpty()) {
            value = 2 * keepAlive;
        } else {
            value = parseLong(configured, -1L);
            if (value < 0) {
                throw new IllegalArgumentException(String.format(
                        "%s=%s is not a number of milliseconds (use 0 to disable the binlog read timeout)",
                        PROPERTY, configured));
            }
        }
        if (value == 0) {
            log.warn("{}=0: the binlog read timeout is DISABLED. A binlog connection that dies without a FIN "
                    + "or RST reaching the connector (a network partition, a dropped firewall or NAT flow, a "
                    + "vanished source host) then stalls replication silently until the process is restarted "
                    + "(spec 01.09).", PROPERTY);
        } else if (value <= heartbeat) {
            long raised = 2 * heartbeat;
            log.warn("{}={} ms is not longer than the source heartbeat period ({} ms = {} x {}={} ms): an idle, "
                    + "healthy source would be timed out. Using {} ms (spec 01.09).",
                    PROPERTY, value, heartbeat, HEARTBEAT_FACTOR, KEEP_ALIVE_INTERVAL, keepAlive, raised);
            value = raised;
        }
        timeoutMs = value;
        log.info("binlog connection guard: a source-side close is a communication failure{} -- either way the "
                        + "engine restarts from the last committed offset instead of standing still (spec 01.09)",
                value > 0 ? String.format("; no byte for %d ms (%s; the source heartbeats every %d ms when idle) is "
                        + "a dead connection", value, PROPERTY, heartbeat) : "");
        return value;
    }

    /** @return the read timeout {@link #install} applies, in milliseconds ({@code 0} = none). */
    public static long timeoutMs() {
        return timeoutMs;
    }

    /**
     * Gives the binlog client the guarded socket factory. Called by the connector's copy of Debezium's
     * MySQL streaming metrics class, with the task's client, before the client connects.
     *
     * @param client the task's binlog client.
     */
    public static void install(BinaryLogClient client) {
        if (client == null) {
            log.warn("binlog connection guard NOT installed: the task context has no binlog client yet (a Debezium "
                    + "change in construction order?). A binlog connection closed by the source, or one that dies "
                    + "silently, would stall replication until the process is restarted (spec 01.09).");
            return;
        }
        long ms = timeoutMs;
        client.setSocketFactory(new GuardedSocketFactory(ms));
        log.info("binlog connection guard installed (read timeout {} ms, end of stream is a failure) (spec 01.09)",
                ms);
    }

    /**
     * Whether the metrics class the runtime loads under Debezium's name is the connector's copy (it
     * carries {@code BINLOG_CONNECTION_GUARD} = {@link #MARKER}); Debezium's own class has no such field.
     *
     * @return the marker value, or {@code null} when Debezium's class (or none) is loaded.
     */
    public static String shadowMarker() {
        try {
            Class<?> cls = Class.forName(SHADOWED_CLASS, false, BinlogConnectionGuard.class.getClassLoader());
            Object value = cls.getField("BINLOG_CONNECTION_GUARD").get(null);
            return value instanceof String ? (String) value : null;
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /**
     * Logs whether the guard will actually reach the binlog client. Called once at setup, after
     * {@link #configure}: a classpath on which Debezium's own metrics class shadows the connector's
     * would leave the binlog connection unguarded, and the operator is told so.
     *
     * @param props the connector properties (only binlog connectors are checked).
     * @return true when the connector's class is the one loaded.
     */
    public static boolean shadowActive(Properties props) {
        if (!BinlogKeepAlivePreflight.isBinlogConnector(props)) {
            return false;
        }
        String marker = shadowMarker();
        boolean active = MARKER.equals(marker);
        if (!active) {
            log.warn("The binlog connection guard will NOT be applied: the class loaded as {} is not the "
                    + "connector's copy (marker={}); Debezium's own class shadows it on this classpath. A binlog "
                    + "connection closed by the source, or one that dies silently, will stall replication until "
                    + "the process is restarted (spec 01.09).", SHADOWED_CLASS, marker);
        }
        return active;
    }

    private static long parseLong(String value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Guarded sockets: {@code SO_TIMEOUT}, {@code SO_KEEPALIVE}, end of stream as an exception. */
    static final class GuardedSocketFactory implements SocketFactory {
        private final int timeoutMs;

        GuardedSocketFactory(long timeoutMs) {
            this.timeoutMs = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, timeoutMs));
        }

        int timeoutMs() {
            return timeoutMs;
        }

        @Override
        public Socket createSocket() throws SocketException {
            Socket socket = new GuardedSocket(timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.setKeepAlive(true);
            return socket;
        }
    }

    /** A plain socket whose input stream turns the peer's end of stream into an {@link EOFException}. */
    static final class GuardedSocket extends Socket {
        private final int timeoutMs;

        GuardedSocket(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return new PeerCloseIsAFailure(super.getInputStream(), timeoutMs);
        }
    }

    /**
     * An input stream on which the peer's end of stream is an {@link EOFException} instead of {@code -1}.
     * A zero-length read still returns 0, as the contract requires.
     */
    static final class PeerCloseIsAFailure extends FilterInputStream {
        private final int timeoutMs;

        PeerCloseIsAFailure(InputStream in) {
            this(in, 0);
        }

        /** @param timeoutMs the SO_TIMEOUT of the socket this stream reads, for the ERROR line. */
        PeerCloseIsAFailure(InputStream in, int timeoutMs) {
            super(in);
            this.timeoutMs = timeoutMs;
        }

        @Override
        public int read() throws IOException {
            int b;
            try {
                b = super.read();
            } catch (SocketTimeoutException e) {
                throw timedOut(e);
            }
            if (b < 0) {
                throw peerClosed();
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            int n;
            try {
                n = super.read(b, off, len);
            } catch (SocketTimeoutException e) {
                throw timedOut(e);
            }
            if (n < 0) {
                throw peerClosed();
            }
            return n;
        }

        private static EOFException peerClosed() {
            Metrics.incrementBinlogConnectionLost();
            log.error(PEER_CLOSED);
            return new EOFException(PEER_CLOSED);
        }

        private SocketTimeoutException timedOut(SocketTimeoutException e) {
            Metrics.incrementBinlogConnectionLost();
            log.error("no byte from the MySQL source for {} ms ({}): the binlog connection is dead (a partition, a "
                    + "dropped firewall or NAT flow, a vanished host); treated as a communication failure so the "
                    + "engine restarts from the last committed offset (spec 01.09)", this.timeoutMs, PROPERTY);
            return e;
        }
    }
}

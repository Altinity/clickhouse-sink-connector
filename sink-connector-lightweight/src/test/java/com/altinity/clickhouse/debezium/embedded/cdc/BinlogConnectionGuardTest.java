package com.altinity.clickhouse.debezium.embedded.cdc;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The binlog connection guard (spec 01.09): every way a binlog connection dies is a communication
 * failure, never a silent stall.
 *
 * <p><b>The gap.</b> With {@code connect.keep.alive=false} (the connector's default, spec 01.07), the
 * binlog client 0.40.2 treats end of stream at a packet boundary as a normal end -- it reports only
 * {@code onDisconnect}, which Debezium logs at INFO and never turns into an engine failure -- and its
 * socket has no read timeout, so a connection whose FIN was lost behind a partition blocks the reader
 * forever. Measured end to end in spec 01.08 section 6: mysqld killed -9 (F7) left the connector idle for
 * 15 minutes with no ERROR; a 300 s partition (F3) left it idle for the whole window.</p>
 */
public class BinlogConnectionGuardTest {

    private final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable c : closeables) {
            c.close();
        }
        BinlogConnectionGuard.configure(props());
    }

    private static Properties props(String... kv) {
        Properties p = new Properties();
        p.setProperty("connector.class", "io.debezium.connector.mysql.MySqlConnector");
        for (int i = 0; i < kv.length; i += 2) {
            p.setProperty(kv[i], kv[i + 1]);
        }
        return p;
    }

    @Test
    void defaultIsTwiceTheKeepAliveInterval() {
        assertEquals(120_000L, BinlogConnectionGuard.configure(props()),
                "unset: 2 x Debezium's 60 s keep-alive interval (2.5 source heartbeat periods)");
        assertEquals(60_000L, BinlogConnectionGuard.configure(props("connect.keep.alive.interval.ms", "30000")));
        assertEquals(60_000L, BinlogConnectionGuard.timeoutMs());
    }

    @Test
    void operatorValueWinsAndZeroDisablesTheTimeout() {
        assertEquals(300_000L, BinlogConnectionGuard.configure(props("binlog.read.timeout.ms", "300000")));
        assertEquals(0L, BinlogConnectionGuard.configure(props("binlog.read.timeout.ms", "0")));
        assertEquals(0L, BinlogConnectionGuard.timeoutMs());
    }

    @Test
    void aTimeoutNotLongerThanTheHeartbeatIsRaised() {
        // keep-alive 60 s -> the source is asked for a heartbeat every 48 s; 10 s would time out an idle,
        // healthy source, so it is raised to two heartbeat periods.
        assertEquals(96_000L, BinlogConnectionGuard.configure(props("binlog.read.timeout.ms", "10000")));
        assertEquals(96_000L, BinlogConnectionGuard.configure(props("binlog.read.timeout.ms", "48000")));
        assertEquals(48_001L, BinlogConnectionGuard.configure(props("binlog.read.timeout.ms", "48001")));
    }

    @Test
    void garbageIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> BinlogConnectionGuard.configure(props("binlog.read.timeout.ms", "two minutes")));
    }

    @Test
    void nonBinlogConnectorIsUntouched() {
        Properties p = new Properties();
        p.setProperty("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
        assertEquals(0L, BinlogConnectionGuard.configure(p));
    }

    @Test
    void factorySocketsCarryTheTimeoutAndKeepAlive() throws Exception {
        BinlogConnectionGuard.GuardedSocketFactory factory = new BinlogConnectionGuard.GuardedSocketFactory(1234);
        try (Socket s = factory.createSocket()) {
            assertEquals(1234, s.getSoTimeout());
            assertTrue(s.getKeepAlive());
            assertTrue(s instanceof BinlogConnectionGuard.GuardedSocket);
        }
    }

    /** End of stream is an EOFException on both read forms; data before it and zero-length reads are intact. */
    @Test
    void endOfStreamIsAFailureNotMinusOne() throws Exception {
        InputStream in = new BinlogConnectionGuard.PeerCloseIsAFailure(new ByteArrayInputStream(new byte[] {7, 8, 9}));
        assertEquals(7, in.read());
        byte[] buf = new byte[8];
        assertEquals(0, in.read(buf, 0, 0));
        assertEquals(2, in.read(buf, 0, 8));
        assertEquals(8, buf[0]);
        assertEquals(9, buf[1]);
        EOFException single = assertThrows(EOFException.class, in::read);
        assertTrue(single.getMessage().contains("closed the binlog connection"));
        assertThrows(EOFException.class, () -> in.read(buf, 0, 8));
    }

    /**
     * A real socket closed by the peer: the guarded socket's stream throws where a plain one returns -1.
     * This is what turns the binlog client's clean end-of-loop into onCommunicationFailure.
     */
    @Test
    void aPeerCloseOnARealSocketThrows() throws Exception {
        ServerSocket server = new ServerSocket(0);
        closeables.add(server);
        Thread closer = new Thread(() -> {
            try (Socket s = server.accept(); OutputStream out = s.getOutputStream()) {
                out.write(42);
                out.flush();
            } catch (IOException ignored) {
                // the test fails on its own assertions if the peer could not be served
            }
        }, "closing-peer");
        closer.setDaemon(true);
        closer.start();
        Socket client = new BinlogConnectionGuard.GuardedSocketFactory(5_000).createSocket();
        closeables.add(client);
        client.connect(server.getLocalSocketAddress(), 5_000);
        InputStream in = client.getInputStream();
        assertEquals(42, in.read());
        assertThrows(EOFException.class, in::read);
    }

    /**
     * A peer that accepts the connection and then never sends a byte -- what a binlog connection looks
     * like after its source end vanished behind a partition. With the guard installed, the client's
     * blocked read on the guarded socket fails with {@link SocketTimeoutException} within the timeout.
     * The client reads the greeting and every later binlog packet from that one socket, so the same
     * timeout covers the streaming phase; the streaming-phase stall itself (handshake done, dump started,
     * then silence) is reproduced end to end by the fault-proxy partition of spec 01.08 section 6 (F3).
     * Mutation-checked: a factory that does not call setSoTimeout makes this test fail (the client then
     * fails only on its own 3 s handshake timer, with no SocketTimeoutException in the cause chain, and in
     * the streaming phase it would block forever).
     */
    @Test
    void aSilentPeerFailsTheClientWithinTheTimeout() throws Exception {
        ServerSocket silent = new ServerSocket(0);
        closeables.add(silent);
        List<Socket> accepted = new ArrayList<>();
        Thread acceptor = new Thread(() -> {
            try {
                while (!silent.isClosed()) {
                    Socket s = silent.accept();
                    synchronized (accepted) {
                        accepted.add(s);
                    }
                }
            } catch (IOException ignored) {
                // the server socket was closed by the test
            }
        }, "silent-peer");
        acceptor.setDaemon(true);
        acceptor.start();

        // keep-alive 200 ms -> heartbeat period 160 ms, so a 600 ms timeout is accepted as configured
        assertEquals(600L, BinlogConnectionGuard.configure(props("connect.keep.alive.interval.ms", "200",
                "binlog.read.timeout.ms", "600")));
        BinaryLogClient client = new BinaryLogClient("127.0.0.1", silent.getLocalPort(), "u", "p");
        client.setKeepAlive(false);
        BinlogConnectionGuard.install(client);

        long t0 = System.nanoTime();
        IOException failure = assertTimeoutPreemptively(Duration.ofSeconds(20),
                () -> assertThrows(IOException.class, client::connect));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(hasCause(failure, SocketTimeoutException.class),
                "the failure is the read timeout, not something else: " + failure);
        assertTrue(elapsedMs < 10_000, "failed after " + elapsedMs + " ms, expected about the 600 ms timeout");
        synchronized (accepted) {
            for (Socket s : accepted) {
                s.close();
            }
        }
    }

    /** The guard is installed whatever the timeout: with 0 the end-of-stream half still applies. */
    @Test
    void installAlwaysSetsTheGuardedFactory() throws Exception {
        java.lang.reflect.Field f = BinaryLogClient.class.getDeclaredField("socketFactory");
        f.setAccessible(true);
        BinlogConnectionGuard.configure(props("binlog.read.timeout.ms", "0"));
        BinaryLogClient client = new BinaryLogClient("127.0.0.1", 1, "u", "p");
        assertNull(f.get(client));
        BinlogConnectionGuard.install(client);
        assertTrue(f.get(client) instanceof BinlogConnectionGuard.GuardedSocketFactory);
        assertEquals(0, ((BinlogConnectionGuard.GuardedSocketFactory) f.get(client)).timeoutMs());
        BinlogConnectionGuard.configure(props());
        BinlogConnectionGuard.install(client);
        assertEquals(120_000, ((BinlogConnectionGuard.GuardedSocketFactory) f.get(client)).timeoutMs());
    }

    /** The class Debezium constructs under its own name is the connector's copy, which installs the guard. */
    @Test
    void theShadowCopyIsTheLoadedClass() {
        assertEquals(BinlogConnectionGuard.MARKER, BinlogConnectionGuard.shadowMarker());
        assertTrue(BinlogConnectionGuard.shadowActive(props()));
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }
}

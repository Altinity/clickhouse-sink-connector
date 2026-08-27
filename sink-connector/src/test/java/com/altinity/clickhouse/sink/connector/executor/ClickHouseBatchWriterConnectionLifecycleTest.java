package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import org.junit.Assert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Issue #1252: ClickHouseBatchWriter opened JDBC connections and never closed
 * them.
 *
 * <p>It caches one connection per database in {@code databaseToConnectionMap}
 * (field declared at ClickHouseBatchWriter.java:55, populated in
 * {@code getClickHouseConnection}) and holds a further connection to the
 * system database in {@code systemConnection} (opened by the constructor).
 * Before this fix the class had no {@code close()}, no {@code shutdown()} and
 * did not implement {@code AutoCloseable}, so nothing on the shutdown path
 * could release any of them: every connector stop leaked the whole set, and
 * the lightweight connector restarts its event loop in-process (the REST
 * force-start calls stop() then builds a NEW DebeziumChangeEventCapture), so
 * the leak accumulates without the JVM ever exiting.</p>
 *
 * <p>The reconnect path leaked in addition to that. {@code systemConnection()}
 * replaces the cached handle when {@link BaseDbWriter#isUnusable} says it can
 * no longer be used, and simply overwrote the field. When the handle is
 * unusable because {@code isClosed()} THREW -- a driver-level fault, not a
 * closed connection -- the old handle is still open and is now unreachable.
 * The replacement closes it first.</p>
 *
 * <p>The writer needs a reachable ClickHouse to acquire real connections, so
 * these tests construct it against a port nothing listens on (the same device
 * {@code ClickHouseBatchWriterMissingTableTest} uses) and install recording
 * connections into the very fields the production code reads. The assertions
 * are about the OUTCOME the issue reports: after the lifecycle call, every
 * connection the writer was holding is closed.</p>
 */
public class ClickHouseBatchWriterConnectionLifecycleTest {

    /** A port nothing listens on: the writer constructs, but connects to nothing. */
    private static final int UNREACHABLE_PORT = 1;

    /**
     * A Connection that records close() and answers isClosed() accordingly.
     * Mockito is not on the test classpath, so this uses a JDK proxy in the
     * same style as {@code SystemConnectionRefreshTest}.
     */
    private static final class RecordingConnection {

        private final AtomicInteger closeCalls = new AtomicInteger();
        private volatile boolean closed;
        /** When true, isClosed() throws instead of answering -- a driver fault. */
        private final boolean isClosedThrows;
        private final Connection proxy;

        RecordingConnection(boolean isClosedThrows) {
            this.isClosedThrows = isClosedThrows;
            InvocationHandler h = (p, method, args) -> {
                switch (method.getName()) {
                    case "close":
                        this.closeCalls.incrementAndGet();
                        this.closed = true;
                        return null;
                    case "isClosed":
                        if (this.isClosedThrows) {
                            throw new SQLException("driver cannot report connection state");
                        }
                        return this.closed;
                    case "toString":
                        return "RecordingConnection(closed=" + this.closed + ")";
                    case "hashCode":
                        return System.identityHashCode(p);
                    case "equals":
                        return p == args[0];
                    default:
                        return null;
                }
            };
            this.proxy = (Connection) Proxy.newProxyInstance(
                    RecordingConnection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, h);
        }

        Connection get() {
            return this.proxy;
        }
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        props.put("clickhouse.server.url", "127.0.0.1");
        props.put("clickhouse.server.port", String.valueOf(UNREACHABLE_PORT));
        props.put("clickhouse.server.user", "default");
        props.put("clickhouse.server.password", "");
        props.put("clickhouse.server.database", "testdb");
        props.put("connection.pool.disable", "true");
        props.put("errors.max.retries", "1");
        return new ClickHouseSinkConnectorConfig(props);
    }

    private static ClickHouseBatchWriter writer() {
        return new ClickHouseBatchWriter(config(), new HashMap<>());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Connection> cache(ClickHouseBatchWriter writer)
            throws Exception {
        Field field = ClickHouseBatchWriter.class
                .getDeclaredField("databaseToConnectionMap");
        field.setAccessible(true);
        return (Map<String, Connection>) field.get(writer);
    }

    private static void setSystemConnection(ClickHouseBatchWriter writer,
                                            Connection connection) throws Exception {
        Field field = ClickHouseBatchWriter.class.getDeclaredField("systemConnection");
        field.setAccessible(true);
        field.set(writer, connection);
    }

    private static Connection systemConnectionField(ClickHouseBatchWriter writer)
            throws Exception {
        Field field = ClickHouseBatchWriter.class.getDeclaredField("systemConnection");
        field.setAccessible(true);
        return (Connection) field.get(writer);
    }

    // ------------------------------------------------------------------
    // The defect.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("#1252: close() closes every cached per-database connection")
    public void closeReleasesEveryCachedConnection() throws Exception {
        ClickHouseBatchWriter writer = writer();
        RecordingConnection ordersDb = new RecordingConnection(false);
        RecordingConnection customersDb = new RecordingConnection(false);
        cache(writer).put("testdb", ordersDb.get());
        cache(writer).put("otherdb", customersDb.get());

        writer.close();

        Assert.assertTrue("the cached connection for testdb must be closed on shutdown; "
                        + "before this fix the writer had no lifecycle method at all and "
                        + "every connection it had opened leaked (issue #1252)",
                ordersDb.closed);
        Assert.assertTrue("the cached connection for otherdb must be closed on shutdown",
                customersDb.closed);
        Assert.assertTrue("a closed connection must not be handed out again, so the cache "
                        + "must be emptied", cache(writer).isEmpty());
    }

    @Test
    @DisplayName("#1252: close() closes the system-database connection too")
    public void closeReleasesTheSystemConnection() throws Exception {
        ClickHouseBatchWriter writer = writer();
        RecordingConnection systemDb = new RecordingConnection(false);
        setSystemConnection(writer, systemDb.get());

        writer.close();

        Assert.assertTrue("the system connection is opened in the constructor and held for "
                        + "the life of the writer; it must be closed on shutdown",
                systemDb.closed);
        Assert.assertNull("the closed system handle must not be retained",
                systemConnectionField(writer));
    }

    /**
     * The second leak in the issue: the reconnect path discarded the old
     * handle instead of closing it.
     */
    @Test
    @DisplayName("#1252: replacing an unusable system connection closes the stale handle")
    public void reconnectClosesTheStaleSystemConnection() throws Exception {
        ClickHouseBatchWriter writer = writer();
        // isClosed() throws, so isUnusable() is true while the handle is in
        // fact still open -- the case where overwriting the field orphans a
        // live connection.
        RecordingConnection stale = new RecordingConnection(true);
        setSystemConnection(writer, stale.get());

        writer.systemConnection();

        Assert.assertEquals("the stale system connection must be closed before it is "
                        + "replaced; overwriting the field leaks it",
                1, stale.closeCalls.get());
    }

    // ------------------------------------------------------------------
    // Controls: behaviour that must not change.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("control: the writer is AutoCloseable so callers can use try-with-resources")
    public void writerIsAutoCloseable() {
        Assert.assertTrue("ClickHouseBatchWriter must be AutoCloseable",
                AutoCloseable.class.isAssignableFrom(ClickHouseBatchWriter.class));
    }

    @Test
    @DisplayName("control: close() is idempotent -- a second stop must not fail or reconnect")
    public void closeIsIdempotent() throws Exception {
        ClickHouseBatchWriter writer = writer();
        RecordingConnection cached = new RecordingConnection(false);
        RecordingConnection systemDb = new RecordingConnection(false);
        cache(writer).put("testdb", cached.get());
        setSystemConnection(writer, systemDb.get());

        writer.close();
        writer.close();

        Assert.assertEquals("each connection must be closed exactly once",
                1, cached.closeCalls.get());
        Assert.assertEquals("each connection must be closed exactly once",
                1, systemDb.closeCalls.get());
        Assert.assertNull("close() must not reopen the system connection",
                systemConnectionField(writer));
    }

    /**
     * A connection whose close() fails must not stop the others being closed:
     * a shutdown that abandons the rest of the set is the same leak again.
     */
    @Test
    @DisplayName("control: one connection failing to close does not abandon the others")
    public void aFailingCloseDoesNotStopTheRest() throws Exception {
        ClickHouseBatchWriter writer = writer();
        AtomicInteger throwingCloseCalls = new AtomicInteger();
        InvocationHandler h = (p, method, args) -> {
            if ("close".equals(method.getName())) {
                throwingCloseCalls.incrementAndGet();
                throw new SQLException("close failed");
            }
            if ("isClosed".equals(method.getName())) {
                return false;
            }
            if ("toString".equals(method.getName())) {
                return "ThrowingConnection";
            }
            if ("hashCode".equals(method.getName())) {
                return System.identityHashCode(p);
            }
            if ("equals".equals(method.getName())) {
                return p == args[0];
            }
            return null;
        };
        Connection throwing = (Connection) Proxy.newProxyInstance(
                ClickHouseBatchWriterConnectionLifecycleTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, h);
        RecordingConnection healthy = new RecordingConnection(false);
        RecordingConnection systemDb = new RecordingConnection(false);
        cache(writer).put("adb", throwing);
        cache(writer).put("bdb", healthy.get());
        setSystemConnection(writer, systemDb.get());

        writer.close();

        Assert.assertEquals("the failing connection must have been attempted",
                1, throwingCloseCalls.get());
        Assert.assertTrue("a failure closing one connection must not skip the others",
                healthy.closed);
        Assert.assertTrue("a failure closing a cached connection must not skip the system one",
                systemDb.closed);
        Assert.assertTrue("the cache must still be emptied", cache(writer).isEmpty());
    }

    /**
     * The writer must not acquire connections it was not asked for: an
     * unreachable server yields no cached connections and nothing to close.
     */
    @Test
    @DisplayName("control: closing a writer that never connected is a silent no-op")
    public void closingAWriterWithNoConnectionsIsANoOp() throws Exception {
        ClickHouseBatchWriter writer = writer();

        Assert.assertTrue("no connection can have been cached against an unreachable server",
                cache(writer).isEmpty());

        writer.close();
    }

    /**
     * Sanity check on the reflective handles this test relies on, so a rename
     * of either field fails loudly here instead of silently testing nothing.
     */
    @Test
    @DisplayName("control: the fields under test are the ones the writer actually uses")
    public void theFieldsUnderTestExist() throws Exception {
        Field cacheField = ClickHouseBatchWriter.class
                .getDeclaredField("databaseToConnectionMap");
        Assert.assertTrue("databaseToConnectionMap must remain a Map",
                Map.class.isAssignableFrom(cacheField.getType()));
        Field systemField = ClickHouseBatchWriter.class.getDeclaredField("systemConnection");
        Assert.assertEquals("systemConnection must remain a java.sql.Connection",
                Connection.class, systemField.getType());
    }
}

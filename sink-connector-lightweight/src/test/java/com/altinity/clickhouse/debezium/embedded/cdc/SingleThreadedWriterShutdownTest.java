package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchWriter;
import org.junit.Assert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Issue #1252, owner half: a close() nobody calls is not a fix.
 *
 * <p>{@code ClickHouseBatchWriter} is constructed in exactly one place --
 * {@code setupProcessingThread} when {@code single.threaded=true} -- and the
 * handle is kept in {@code DebeziumChangeEventCapture.singleThreadedWriter}.
 * Nothing else can reach the JDBC connections it holds, so {@code stop()} is
 * the only place its lifecycle can be driven from.</p>
 *
 * <p>This test drives the real {@code stop()} with a writer whose cached and
 * system connections are recording proxies, and asserts they end up closed.
 * The writer is pointed at a port nothing listens on so no ClickHouse is
 * needed; {@code stop()} runs with a null engine and null executors, which is
 * the state after a setup that never started them, and each block is already
 * null-guarded.</p>
 */
public class SingleThreadedWriterShutdownTest {

    /** A port nothing listens on: the writer constructs, but connects to nothing. */
    private static final int UNREACHABLE_PORT = 1;

    private static final AtomicInteger CLOSES = new AtomicInteger();

    private static Connection recording() {
        InvocationHandler h = (p, m, a) -> {
            switch (m.getName()) {
                case "close":
                    CLOSES.incrementAndGet();
                    return null;
                case "isClosed":
                    return false;
                case "toString":
                    return "RecordingConnection";
                case "hashCode":
                    return System.identityHashCode(p);
                case "equals":
                    return p == a[0];
                default:
                    return null;
            }
        };
        return (Connection) Proxy.newProxyInstance(
                SingleThreadedWriterShutdownTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, h);
    }

    private static ClickHouseBatchWriter writer() {
        Map<String, String> props = new HashMap<>();
        props.put("clickhouse.server.url", "127.0.0.1");
        props.put("clickhouse.server.port", String.valueOf(UNREACHABLE_PORT));
        props.put("clickhouse.server.user", "default");
        props.put("clickhouse.server.password", "");
        props.put("clickhouse.server.database", "testdb");
        props.put("connection.pool.disable", "true");
        props.put("errors.max.retries", "1");
        props.put("enable.metrics", "false");
        return new ClickHouseBatchWriter(
                new ClickHouseSinkConnectorConfig(props), new HashMap<>());
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

    @Test
    @DisplayName("#1252: stop() releases the single-threaded writer's JDBC connections")
    public void stopClosesTheSingleThreadedWritersConnections() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        ClickHouseBatchWriter writer = writer();
        cache(writer).put("testdb", recording());
        cache(writer).put("otherdb", recording());
        setSystemConnection(writer, recording());
        capture.singleThreadedWriter = writer;
        CLOSES.set(0);

        capture.stop();

        Assert.assertEquals("stop() must close both cached per-database connections and the "
                        + "system connection; the writer is the only holder of these handles, "
                        + "so a stop that skips it leaks all three (issue #1252)",
                3, CLOSES.get());
        Assert.assertTrue("the connection cache must be emptied on shutdown",
                cache(writer).isEmpty());
        Assert.assertNull("the released writer must not be retained across a restart -- the "
                        + "REST force-start builds a new DebeziumChangeEventCapture after "
                        + "calling stop()",
                capture.singleThreadedWriter);
    }

    /**
     * The multi-threaded path builds no writer at all, and a stop before any
     * setup has one null too. Neither may fail.
     */
    @Test
    @DisplayName("control: stop() without a single-threaded writer is unaffected")
    public void stopWithoutASingleThreadedWriterStillWorks() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        CLOSES.set(0);

        capture.stop();

        Assert.assertEquals("nothing to close when no writer was built", 0, CLOSES.get());
        Assert.assertNull(capture.singleThreadedWriter);
    }

    /**
     * Two stops in a row happen on the REST force-start path; the second must
     * not fail or re-close anything.
     */
    @Test
    @DisplayName("control: a second stop() is a no-op")
    public void secondStopIsANoOp() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        ClickHouseBatchWriter writer = writer();
        cache(writer).put("testdb", recording());
        capture.singleThreadedWriter = writer;
        CLOSES.set(0);

        capture.stop();
        capture.stop();

        Assert.assertEquals("the cached connection must be closed exactly once",
                1, CLOSES.get());
    }
}

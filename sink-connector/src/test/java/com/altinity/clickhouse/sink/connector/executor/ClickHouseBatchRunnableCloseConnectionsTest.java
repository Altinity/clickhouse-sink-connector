package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.DbWriter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A discarded worker closes the connections it holds (spec 01.01 §3.3 step 4a).
 *
 * <p><b>The defect.</b> Every engine restart in the process shuts the worker
 * pool down and schedules a new one. The old workers kept their per-database
 * connections and their system connection in fields nobody closed, so each
 * restart leaked {@code thread.pool.size} x databases connections for the
 * life of the process.</p>
 *
 * <p>The worker's single connection seam, {@code openConnection}, is
 * overridden with in-memory proxies that record whether they were closed, so
 * no ClickHouse is needed.</p>
 */
public class ClickHouseBatchRunnableCloseConnectionsTest {

    private static final AtomicLong UNIQUE = new AtomicLong(System.nanoTime());

    /** A connection proxy that remembers whether close() was called and may refuse to close. */
    private static final class Tracked {
        final Connection connection;
        final AtomicBoolean closed = new AtomicBoolean(false);
        final String database;

        Tracked(String database, boolean refuseClose) {
            this.database = database;
            InvocationHandler handler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "isClosed":
                        return closed.get();
                    case "close":
                        if (refuseClose) {
                            throw new SQLException("simulated close failure");
                        }
                        closed.set(true);
                        return null;
                    case "prepareStatement":
                        return statement(PreparedStatement.class);
                    case "createStatement":
                        return statement(Statement.class);
                    case "toString":
                        return "tracked-" + database;
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        return defaultValue(method.getReturnType());
                }
            };
            this.connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, handler);
        }
    }

    private static Object statement(Class<?> type) {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "toString":
                    return "recording-statement";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        };
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }

    /**
     * A worker whose every connection is a tracked proxy. The superclass
     * constructor opens the system connection before this class's field
     * initializers run, so the recorder and the refusing name are static and
     * set before construction.
     */
    private static final class TrackingWorker extends ClickHouseBatchRunnable {
        static final List<Tracked> OPENED = Collections.synchronizedList(new ArrayList<>());
        static volatile String refuseCloseFor = "";

        TrackingWorker(ClickHouseSinkConnectorConfig config) {
            super(new LinkedBlockingQueue<List<ClickHouseStruct>>(), config, new HashMap<>());
        }

        @Override
        Connection openConnection(String jdbcUrl, String databaseName) {
            Tracked t = new Tracked(databaseName, databaseName.equals(refuseCloseFor));
            OPENED.add(t);
            return t.connection;
        }
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        ClickHouseSinkConnectorConfig.setDefaultValues(props);
        return new ClickHouseSinkConnectorConfig(props);
    }

    private static String uniqueDb(String prefix) {
        return prefix + "_" + UNIQUE.incrementAndGet();
    }

    @Test
    @DisplayName("closeConnections() closes the per-database and system connections, forgets them, skips one that refuses, and is idempotent")
    public void closeConnectionsClosesAndForgetsEverything() {
        String stubborn = uniqueDb("stubborn");
        TrackingWorker.OPENED.clear();
        TrackingWorker.refuseCloseFor = stubborn;
        TrackingWorker worker = new TrackingWorker(config());
        String dbA = uniqueDb("db_a");
        String dbB = uniqueDb("db_b");
        assertNotNull(worker.getClickHouseConnection(dbA));
        assertNotNull(worker.getClickHouseConnection(dbB));
        assertNotNull(worker.getClickHouseConnection(stubborn));
        assertEquals(3, worker.openDatabaseConnections(), "three per-database connections held");

        worker.closeConnections();

        assertEquals(0, worker.openDatabaseConnections(), "the map is emptied even when one close fails");
        int closed = 0;
        int refused = 0;
        for (Tracked t : TrackingWorker.OPENED) {
            if (t.database.equals(stubborn) && !t.database.equals("system")) {
                refused += t.closed.get() ? 0 : 1;
            } else if (t.database.equals(dbA) || t.database.equals(dbB) || t.database.equals("system")) {
                assertTrue(t.closed.get(), "closed: " + t.database);
                closed++;
            }
        }
        assertTrue(closed >= 3, "the two data connections and the system connection were closed: " + closed);
        assertEquals(1, refused, "the refusing connection was skipped, not rethrown");

        // Idempotent: nothing left to close, nothing thrown.
        worker.closeConnections();
        assertEquals(0, worker.openDatabaseConnections());
    }

    /**
     * A table writer is built on the worker's per-database connection, but
     * {@code BaseDbWriter.getConnection()} swaps in a fresh pool checkout
     * when that handle is closed or evicted ({@code this.conn = HikariDbSource
     * .initiateNewConnectionIfClosed(...)}). That replacement is known to the
     * writer alone. Before this test, {@code closeConnections()} closed only
     * the per-database map: the stale original (a no-op) was closed and the
     * replacement stayed checked out of the pool for the life of the process.
     * The swap is reproduced by assigning the writer's connection field the
     * way {@code getConnection()} does, so no pool is needed.
     */
    @Test
    @DisplayName("closeConnections() also closes the connection a writer re-acquired after its original became unusable")
    public void closeConnectionsClosesAWriterReacquiredConnection() throws Exception {
        TrackingWorker.OPENED.clear();
        TrackingWorker.refuseCloseFor = "";
        TrackingWorker worker = new TrackingWorker(config());
        String dbA = uniqueDb("db_a");
        Connection original = worker.getClickHouseConnection(dbA);
        assertNotNull(original);

        ClickHouseStruct record = new ClickHouseStruct();
        record.setTopic("srv." + dbA + ".t");
        DbWriter writer = worker.getDbWriterForTable(record.getTopic(), "t", dbA, record, original);
        assertNotNull(writer);
        assertTrue(writer.heldConnection() == original, "the writer starts on the per-database connection");

        // The original goes stale and the writer re-acquires -- the exact
        // assignment BaseDbWriter.getConnection() performs.
        Tracked reacquired = new Tracked(dbA + "-reacquired", false);
        java.lang.reflect.Field conn = BaseDbWriter.class.getDeclaredField("conn");
        conn.setAccessible(true);
        conn.set(writer, reacquired.connection);
        assertTrue(writer.heldConnection() == reacquired.connection);

        worker.closeConnections();

        assertTrue(reacquired.closed.get(), "the re-acquired connection is closed, not leaked");
        boolean originalClosed = false;
        for (Tracked t : TrackingWorker.OPENED) {
            if (t.connection == original) {
                originalClosed = t.closed.get();
            }
        }
        assertTrue(originalClosed, "the per-database connection is still closed");
        assertEquals(0, worker.openDatabaseConnections());

        // Idempotent: the writer is forgotten, nothing thrown.
        worker.closeConnections();
        assertTrue(reacquired.closed.get());
    }
}

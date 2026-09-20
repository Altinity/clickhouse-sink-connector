package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ClickHouseBatchRunnable.getClickHouseConnection} must ensure the
 * destination database exists ONCE per process per database, and must say so
 * loudly when it cannot reach the server.
 *
 * <p><b>The defect.</b> On every cache miss the worker issued TWO unconditional
 * {@code CREATE DATABASE IF NOT EXISTS} statements — the second a plain
 * duplicate of the first — and, because a null connection is never cached, it
 * re-issued both on every batch for as long as {@code createConnection}
 * returned null, without ever saying which database it could not reach. With
 * a pool of N workers that is 2N statements per database at start-up and a
 * steady stream of them on a live target whenever a connection cannot be
 * obtained (spec 03.05 §3.3).</p>
 *
 * <p>The tests subclass the runnable and override its single connection seam,
 * {@code openConnection}, with in-memory recorders, so no ClickHouse is
 * required and every statement the worker prepares is observable.</p>
 */
public class ClickHouseBatchRunnableDatabaseBootstrapTest {

    /** Every SQL statement prepared on any recorded connection, in order. */
    private static final List<String> STATEMENTS = Collections.synchronizedList(new ArrayList<>());

    private static final AtomicLong UNIQUE = new AtomicLong(System.nanoTime());

    /** Something a fake connection may do with a statement before it "executes". */
    private interface StatementHook {
        void onPrepare(String sql) throws SQLException;
    }

    /** Collects everything the runnable logs. */
    private static final class CapturingAppender extends AbstractAppender {

        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

        CapturingAppender() {
            super("capture-database-bootstrap", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    /** A worker whose every connection is an in-memory recorder. */
    private static final class RecordingWorker extends ClickHouseBatchRunnable {
        RecordingWorker(ClickHouseSinkConnectorConfig config) {
            super(new LinkedBlockingQueue<List<ClickHouseStruct>>(), config, new HashMap<>());
        }

        @Override
        Connection openConnection(String jdbcUrl, String databaseName) {
            return recordingConnection(sql -> { });
        }
    }

    /** A worker for which no connection can ever be obtained (server unreachable). */
    private static final class UnreachableWorker extends ClickHouseBatchRunnable {
        UnreachableWorker(ClickHouseSinkConnectorConfig config) {
            super(new LinkedBlockingQueue<List<ClickHouseStruct>>(), config, new HashMap<>());
        }

        @Override
        Connection openConnection(String jdbcUrl, String databaseName) {
            return null;
        }
    }

    /** A worker whose server rejects CREATE DATABASE while {@link #denyCreate} is set. */
    private static final class DeniedCreateWorker extends ClickHouseBatchRunnable {
        static volatile boolean denyCreate = false;

        DeniedCreateWorker(ClickHouseSinkConnectorConfig config) {
            super(new LinkedBlockingQueue<List<ClickHouseStruct>>(), config, new HashMap<>());
        }

        @Override
        Connection openConnection(String jdbcUrl, String databaseName) {
            return recordingConnection(sql -> {
                if (denyCreate && sql.toUpperCase().contains("CREATE DATABASE")) {
                    // 497 ACCESS_DENIED: deterministic, classified non-retryable.
                    throw new SQLException("Code: 497. DB::Exception: Not enough privileges");
                }
            });
        }
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

    private static PreparedStatement recordingStatement() {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "execute":
                    return false;
                case "close":
                    return null;
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
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, handler);
    }

    private static Connection recordingConnection(StatementHook hook) {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "isClosed":
                    return false;
                case "close":
                    return null;
                case "prepareStatement": {
                    String sql = (String) args[0];
                    STATEMENTS.add(sql);
                    hook.onPrepare(sql);
                    return recordingStatement();
                }
                case "toString":
                    return "recording-connection";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, handler);
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        ClickHouseSinkConnectorConfig.setDefaultValues(props);
        return new ClickHouseSinkConnectorConfig(props);
    }

    /** A database name no other test (or earlier run in this JVM) has ensured. */
    private static String uniqueDatabase(String tag) {
        return "bootstrap_" + tag + "_" + Long.toUnsignedString(UNIQUE.incrementAndGet());
    }

    /**
     * {@code getClickHouseConnection} is private; its callers are the batch
     * paths that need a full record stream. Reflective seam, same as the other
     * tests in this package.
     */
    private static Connection getClickHouseConnection(ClickHouseBatchRunnable worker, String database)
            throws Exception {
        Method m = ClickHouseBatchRunnable.class.getDeclaredMethod("getClickHouseConnection", String.class);
        m.setAccessible(true);
        try {
            return (Connection) m.invoke(worker, database);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw ite;
        }
    }

    private static List<String> createDatabaseStatements() {
        synchronized (STATEMENTS) {
            return STATEMENTS.stream()
                    .filter(s -> s.toUpperCase().contains("CREATE DATABASE"))
                    .collect(Collectors.toList());
        }
    }

    private static List<String> errorsNaming(List<LogEvent> events, String database) {
        return events.stream()
                .filter(e -> e.getLevel() == Level.ERROR
                        && e.getMessage().getFormattedMessage().contains(database))
                .map(e -> e.getMessage().getFormattedMessage())
                .collect(Collectors.toList());
    }

    /**
     * The regression: two workers, five lookups each, ONE statement.
     *
     * <p>Against the pre-fix code this fails with four: each worker's first
     * miss issued the statement twice.</p>
     */
    @Test
    @DisplayName("CREATE DATABASE IF NOT EXISTS is issued once per database per process, across workers and calls")
    public void createDatabaseIsIssuedOncePerDatabaseAcrossWorkersAndCalls() throws Exception {
        STATEMENTS.clear();
        String database = uniqueDatabase("once");
        RecordingWorker workerA = new RecordingWorker(config());
        RecordingWorker workerB = new RecordingWorker(config());

        for (int i = 0; i < 5; i++) {
            assertNotNull(getClickHouseConnection(workerA, database),
                    "a recording connection must be handed back to worker A");
            assertNotNull(getClickHouseConnection(workerB, database),
                    "a recording connection must be handed back to worker B");
        }

        List<String> creates = createDatabaseStatements();
        assertEquals(1, creates.size(),
                "the database must be ensured exactly once per process, not once (or twice) per "
                        + "worker per miss; statements issued: " + creates);
        assertTrue(creates.get(0).contains("`" + database + "`"),
                "the single statement must target the requested database: " + creates.get(0));
    }

    /**
     * When no connection can be obtained the worker must say WHICH database it
     * could not reach, at ERROR, instead of silently retrying forever.
     *
     * <p>Against the pre-fix code this fails: nothing was logged by the
     * runnable that named the database.</p>
     */
    @Test
    @DisplayName("An unreachable server produces an ERROR naming the database, no statement, and a null connection")
    public void unreachableServerLogsErrorNamingTheDatabase() throws Exception {
        STATEMENTS.clear();
        String database = uniqueDatabase("unreachable");
        UnreachableWorker worker = new UnreachableWorker(config());

        Logger coreLogger = (Logger) LogManager.getLogger(ClickHouseBatchRunnable.class);
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        coreLogger.addAppender(appender);
        Connection conn;
        try {
            conn = getClickHouseConnection(worker, database);
        } finally {
            coreLogger.removeAppender(appender);
            appender.stop();
        }

        assertNull(conn, "with no server there is no connection to hand back");
        assertTrue(createDatabaseStatements().isEmpty(),
                "no CREATE DATABASE can be attempted without a connection");
        List<String> errors = errorsNaming(appender.events, database);
        assertTrue(!errors.isEmpty(),
                "an unreachable server must be reported at ERROR naming the database `" + database
                        + "`; got only: " + appender.events.stream()
                        .map(e -> e.getLevel() + ": " + e.getMessage().getFormattedMessage())
                        .collect(Collectors.toList()));
    }

    /**
     * A rejected CREATE DATABASE is reported and NOT recorded as ensured, so the
     * next worker to miss on that database tries again; once it succeeds, no
     * further worker issues it.
     */
    @Test
    @DisplayName("A failed CREATE DATABASE is logged at ERROR and retried on the next miss; a successful one is not repeated")
    public void failedCreateDatabaseIsNotMarkedEnsured() throws Exception {
        STATEMENTS.clear();
        String database = uniqueDatabase("denied");

        Logger coreLogger = (Logger) LogManager.getLogger(ClickHouseBatchRunnable.class);
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        coreLogger.addAppender(appender);
        try {
            DeniedCreateWorker.denyCreate = true;
            getClickHouseConnection(new DeniedCreateWorker(config()), database);
            assertEquals(1, createDatabaseStatements().size(),
                    "the first miss attempts the statement once");
            assertTrue(!errorsNaming(appender.events, database).isEmpty(),
                    "a rejected CREATE DATABASE must be reported at ERROR naming the database");

            DeniedCreateWorker.denyCreate = false;
            getClickHouseConnection(new DeniedCreateWorker(config()), database);
            assertEquals(2, createDatabaseStatements().size(),
                    "a failed attempt must not be recorded as ensured; the next miss must retry it");

            getClickHouseConnection(new DeniedCreateWorker(config()), database);
            assertEquals(2, createDatabaseStatements().size(),
                    "once the statement succeeded no further worker may issue it again");
        } finally {
            DeniedCreateWorker.denyCreate = false;
            coreLogger.removeAppender(appender);
            appender.stop();
        }
    }
}

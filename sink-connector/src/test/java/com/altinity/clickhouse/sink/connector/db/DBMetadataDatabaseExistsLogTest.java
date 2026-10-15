package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 08.03 section 3.3: {@code DBMetadata.checkIfDatabaseExists} reports a
 * retry only when one happened.
 *
 * <p><b>The defect.</b> The probe logged "Retrying checkIfDatabaseExists,
 * attempt 1" at INFO before its FIRST attempt, so a healthy connector -- whose
 * probes never fail -- wrote one "Retrying" line per batch: 218 lines in 20
 * minutes on one deployment, with "attempt 2" never appearing once.</p>
 *
 * <p>The tests drive the real method against a JDBC proxy whose first
 * {@code executeQuery} either answers or throws, with the class logger opened
 * to INFO and captured.</p>
 */
public class DBMetadataDatabaseExistsLogTest {

    private static final String DB = "orders_db";

    /** Collects everything DBMetadata logs during one call. */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

        CapturingAppender() {
            super("capture-database-exists-log", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }

        List<LogEvent> retryLines() {
            List<LogEvent> out = new ArrayList<>();
            for (LogEvent e : events) {
                if (e.getMessage().getFormattedMessage().contains("Retrying checkIfDatabaseExists")) {
                    out.add(e);
                }
            }
            return out;
        }
    }

    /** A default value of the right shape for any JDBC method the code under test does not care about. */
    private static Object defaultFor(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class || type == long.class || type == short.class || type == byte.class) {
            return 0;
        }
        return null;
    }

    /**
     * A connection whose statement answers the SHOW DATABASES probe with {@code DB}
     * on every attempt after the first {@code failures} attempts, which throw.
     */
    private static Connection connection(int failures) {
        AtomicInteger attempts = new AtomicInteger();
        InvocationHandler resultSet = (proxy, method, args) -> {
            switch (method.getName()) {
                case "next":
                    return true;
                case "getString":
                    return DB;
                default:
                    return defaultFor(method.getReturnType());
            }
        };
        InvocationHandler statement = (proxy, method, args) -> {
            if ("executeQuery".equals(method.getName())) {
                if (attempts.incrementAndGet() <= failures) {
                    throw new SQLException("simulated transient failure");
                }
                return Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                        new Class<?>[]{ResultSet.class}, resultSet);
            }
            return defaultFor(method.getReturnType());
        };
        InvocationHandler connection = (proxy, method, args) -> {
            if ("createStatement".equals(method.getName())) {
                return Proxy.newProxyInstance(Statement.class.getClassLoader(),
                        new Class<?>[]{Statement.class}, statement);
            }
            return defaultFor(method.getReturnType());
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, connection);
    }

    /** Pool disabled, so a failed attempt retries on the same (proxy) connection. */
    private static DBMetadata metadata() {
        Map<String, String> props = new HashMap<>();
        props.put("connection.pool.disable", "true");
        return new DBMetadata(new ClickHouseSinkConnectorConfig(props));
    }

    private static CapturingAppender capture() {
        Configurator.setLevel(DBMetadata.class.getName(), Level.INFO);
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        ((Logger) LogManager.getLogger(DBMetadata.class)).addAppender(appender);
        return appender;
    }

    private static void release(CapturingAppender appender) {
        ((Logger) LogManager.getLogger(DBMetadata.class)).removeAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("A probe answered on the first attempt logs no Retrying line")
    public void firstAttemptThatSucceedsLogsNoRetry() throws SQLException {
        CapturingAppender appender = capture();
        try {
            assertTrue(metadata().checkIfDatabaseExists(connection(0), DB), "the database is reported present");
            List<LogEvent> retries = appender.retryLines();
            assertEquals(0, retries.size(), "no retry happened, so no Retrying line: " + retries);
        } finally {
            release(appender);
        }
    }

    @Test
    @DisplayName("A probe that fails once logs exactly one Retrying line, for attempt 2")
    public void retryAfterFailureIsLoggedOnce() throws SQLException {
        CapturingAppender appender = capture();
        try {
            assertTrue(metadata().checkIfDatabaseExists(connection(1), DB), "the second attempt answers");
            List<LogEvent> retries = appender.retryLines();
            assertEquals(1, retries.size(), "one retry happened, so one Retrying line: " + retries);
            LogEvent line = retries.get(0);
            assertEquals(Level.INFO, line.getLevel());
            String text = line.getMessage().getFormattedMessage();
            assertTrue(text.contains(DB), text);
            assertTrue(text.contains("attempt 2 of"), text);
        } finally {
            release(appender);
        }
    }
}

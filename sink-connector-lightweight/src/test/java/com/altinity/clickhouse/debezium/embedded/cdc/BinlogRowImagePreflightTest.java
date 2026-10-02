package com.altinity.clickhouse.debezium.embedded.cdc;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The connector refuses to start against a MySQL source whose
 * {@code binlog_row_image} is not {@code FULL} (spec 01.01 §3.2, spec 10.04 §3.6).
 *
 * <p><b>The gap.</b> Nothing checked the row image. With {@code MINIMAL} the
 * binlog carries only the changed columns and the key in each row image, so
 * every UPDATE the connector turns into a full-row insert binds NULL (or a
 * ClickHouse default) for every column the statement did not touch -- a
 * value-level divergence on every update, with row counts intact. With
 * {@code NOBLOB} the same happens to every BLOB/TEXT column. Nothing
 * downstream can recover what the source never logged, so the only correct
 * behaviour is to refuse at start.</p>
 *
 * <p>The source is a JDBC {@link Connection} stub built with a dynamic proxy:
 * no MySQL is needed, and the stub answers only the one query the preflight
 * is allowed to issue.</p>
 */
public class BinlogRowImagePreflightTest {

    /** Collects everything the class under test logs during one call. */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

        CapturingAppender() {
            super("capture-row-image", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    /**
     * A {@link Connection} whose statements answer every query with the given
     * single value (or throw when {@code failure} is set).
     */
    private static Connection sourceAnswering(String value, SQLException failure) {
        InvocationHandler resultSet = (proxy, method, args) -> {
            switch (method.getName()) {
                case "next":
                    return value != null;
                case "getString":
                    return value;
                case "close":
                    return null;
                default:
                    return defaultValue(method);
            }
        };
        ResultSet rs = (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                new Class<?>[] {ResultSet.class}, resultSet);
        InvocationHandler statement = (proxy, method, args) -> {
            switch (method.getName()) {
                case "executeQuery":
                    if (failure != null) {
                        throw failure;
                    }
                    return rs;
                case "close":
                    return null;
                default:
                    return defaultValue(method);
            }
        };
        Statement st = (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(),
                new Class<?>[] {Statement.class}, statement);
        InvocationHandler connection = (proxy, method, args) -> {
            switch (method.getName()) {
                case "createStatement":
                    return st;
                case "isReadOnly":
                    return true;
                case "setReadOnly":
                case "close":
                    return null;
                default:
                    return defaultValue(method);
            }
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, connection);
    }

    private static Object defaultValue(Method method) {
        Class<?> r = method.getReturnType();
        if (r == boolean.class) {
            return false;
        }
        if (r == int.class) {
            return 0;
        }
        if (r == long.class) {
            return 0L;
        }
        return null;
    }

    private static Properties mysqlProps() {
        Properties props = new Properties();
        props.setProperty("connector.class", "io.debezium.connector.mysql.MySqlConnector");
        props.setProperty("database.hostname", "mysql-host");
        props.setProperty("database.user", "u");
        props.setProperty("database.password", "p");
        return props;
    }

    private static List<LogEvent> capture(Runnable body) {
        Logger coreLogger = (Logger) LogManager.getLogger(BinlogRowImagePreflight.class);
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        coreLogger.addAppender(appender);
        try {
            body.run();
        } finally {
            coreLogger.removeAppender(appender);
            appender.stop();
        }
        return new ArrayList<>(appender.events);
    }

    @Test
    @DisplayName("binlog_row_image=MINIMAL is refused at start, naming the variable, the value and the fix")
    public void minimalIsRefused() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> BinlogRowImagePreflight.check(mysqlProps(), sourceAnswering("MINIMAL", null)),
                "with MINIMAL every UPDATE replicates untouched columns as NULL; the connector must "
                        + "refuse rather than diverge on every update");
        assertTrue(ex.getMessage().contains("binlog_row_image"), ex.getMessage());
        assertTrue(ex.getMessage().contains("MINIMAL"), ex.getMessage());
        assertTrue(ex.getMessage().contains("FULL"), ex.getMessage());
        assertTrue(ex.getMessage().toLowerCase().contains("set global binlog_row_image"),
                "the refusal must say how to fix the source: " + ex.getMessage());
    }

    @Test
    @DisplayName("binlog_row_image=NOBLOB is refused too (BLOB/TEXT columns would be lost on every update)")
    public void noblobIsRefused() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> BinlogRowImagePreflight.check(mysqlProps(), sourceAnswering("NOBLOB", null)));
        assertTrue(ex.getMessage().contains("NOBLOB"), ex.getMessage());
    }

    @Test
    @DisplayName("binlog_row_image=FULL passes, whatever the case the server reports it in")
    public void fullPasses() {
        assertDoesNotThrow(() -> BinlogRowImagePreflight.check(mysqlProps(), sourceAnswering("FULL", null)));
        assertDoesNotThrow(() -> BinlogRowImagePreflight.check(mysqlProps(), sourceAnswering("full", null)));
    }

    @Test
    @DisplayName("A source that cannot be asked does not block startup, but says so at WARN")
    public void unreadableIsWarnedNotRefused() {
        List<LogEvent> events = capture(() -> assertDoesNotThrow(
                () -> BinlogRowImagePreflight.check(mysqlProps(),
                        sourceAnswering(null, new SQLException("access denied")))));
        assertTrue(events.stream().anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getMessage().getFormattedMessage().contains("binlog_row_image")),
                "a check that could not run must be visible: " + events);
        List<LogEvent> empty = capture(() -> assertDoesNotThrow(
                () -> BinlogRowImagePreflight.check(mysqlProps(), sourceAnswering(null, null))));
        assertTrue(empty.stream().anyMatch(e -> e.getLevel() == Level.WARN));
    }

    @Test
    @DisplayName("The skip property lets a MINIMAL source through, loudly")
    public void skipIsLoud() {
        Properties props = mysqlProps();
        props.setProperty(BinlogRowImagePreflight.SKIP_PROPERTY, "true");
        List<LogEvent> events = capture(() -> assertDoesNotThrow(
                () -> BinlogRowImagePreflight.check(props, sourceAnswering("MINIMAL", null))));
        assertTrue(events.stream().anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getMessage().getFormattedMessage().contains(BinlogRowImagePreflight.SKIP_PROPERTY)),
                "silencing a correctness check must not itself be quiet: " + events);
    }

    @Test
    @DisplayName("Non-MySQL sources and unreachable hosts pass through without blocking startup")
    public void nonMysqlAndUnreachablePassThrough() {
        Properties postgres = new Properties();
        postgres.setProperty("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
        postgres.setProperty("database.hostname", "nonexistent.invalid");
        assertDoesNotThrow(() -> BinlogRowImagePreflight.check(postgres));

        Properties unreachable = mysqlProps();
        unreachable.setProperty("database.hostname", "nonexistent.invalid");
        unreachable.setProperty("database.port", "3306");
        assertDoesNotThrow(() -> BinlogRowImagePreflight.check(unreachable));
    }

    @Test
    @DisplayName("The preflight reads the variable with a SELECT that passes the read-only allowlist")
    public void queryIsReadOnly() {
        assertDoesNotThrow(() -> KeylessTablePreflight.assertReadOnlySql(BinlogRowImagePreflight.QUERY));
        assertTrue(BinlogRowImagePreflight.QUERY.toLowerCase().contains("binlog_row_image"));
    }
}

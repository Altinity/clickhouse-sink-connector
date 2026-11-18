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
 * The connector refuses to start against a MySQL source whose row format would replicate wrongly, and against
 * a source it cannot ask (spec 01.01 section 3.2, spec 01.10 section 3.1, spec 10.04 section 3.6).
 *
 * <p><b>The gap.</b> Nothing checked the row image. With {@code MINIMAL} the binlog carries only the changed
 * columns and the key in each row image, so every UPDATE the connector turns into a full-row insert binds NULL
 * (or a ClickHouse default) for every column the statement did not touch -- a value-level divergence on every
 * update, with row counts intact. With {@code NOBLOB} the same happens to every BLOB/TEXT column. With
 * {@code binlog_row_value_options=PARTIAL_JSON} an UPDATE of a JSON column is logged as a partial update that
 * Debezium drops. A source that cannot be asked was let through with a WARN. Nothing downstream can recover
 * what the source never logged, so the only correct behaviour is to refuse at start.</p>
 *
 * <p>The source is a JDBC {@link Connection} stub built with dynamic proxies: no MySQL is needed. The stub
 * answers the two row-format queries separately, by the text of the SQL it is given.</p>
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
     * A {@link Connection} that answers {@code binlog_row_image} with {@code rowImage} and
     * {@code binlog_row_value_options} with {@code valueOptions}. An answer is a String (the value), {@code null}
     * (no row), or an {@link SQLException} (thrown by the query).
     */
    static Connection source(Object rowImage, Object valueOptions) {
        InvocationHandler connection = (proxy, method, args) -> {
            switch (method.getName()) {
                case "createStatement":
                    return statementAnswering(rowImage, valueOptions);
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

    /** A source answering both queries with the same value, or throwing the same failure for both. */
    private static Connection sourceAnswering(String value, SQLException failure) {
        Object answer = failure != null ? failure : value;
        return source(answer, answer);
    }

    private static Statement statementAnswering(Object rowImage, Object valueOptions) {
        InvocationHandler statement = (proxy, method, args) -> {
            switch (method.getName()) {
                case "executeQuery": {
                    String sql = (String) args[0];
                    Object answer = sql.contains("binlog_row_value_options") ? valueOptions : rowImage;
                    if (answer instanceof SQLException) {
                        throw (SQLException) answer;
                    }
                    return resultSetAnswering((String) answer);
                }
                case "close":
                    return null;
                default:
                    return defaultValue(method);
            }
        };
        return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(),
                new Class<?>[] {Statement.class}, statement);
    }

    private static ResultSet resultSetAnswering(String value) {
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
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                new Class<?>[] {ResultSet.class}, resultSet);
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
    @DisplayName("A source whose binlog_row_image cannot be read is refused, not waved through with a WARN")
    public void unreadableRowImageRefuses() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> BinlogRowImagePreflight.check(mysqlProps(),
                        sourceAnswering(null, new SQLException("access denied"))),
                "a source that cannot show it logs full row images must not start");
        assertTrue(ex.getMessage().contains("binlog_row_image"), ex.getMessage());
        assertTrue(ex.getMessage().contains("cannot be read"), ex.getMessage());
    }

    @Test
    @DisplayName("A query that returns no value is an unreadable source, refused the same way")
    public void missingValueRefuses() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> BinlogRowImagePreflight.check(mysqlProps(), sourceAnswering(null, null)));
        assertTrue(ex.getMessage().contains("cannot be read"), ex.getMessage());
    }

    @Test
    @DisplayName("binlog.row.image.check.skip=true lets a MINIMAL source through, with a WARN naming the property")
    public void skipIsLoud() {
        Properties props = mysqlProps();
        props.setProperty("binlog.row.image.check.skip", "true");
        List<LogEvent> events = capture(() -> assertDoesNotThrow(
                () -> BinlogRowImagePreflight.check(props, sourceAnswering("MINIMAL", null))));
        assertTrue(events.stream().anyMatch(e -> e.getLevel() == Level.WARN
                && e.getMessage().getFormattedMessage().contains("binlog.row.image.check.skip")),
                "a skipped check must be logged at WARN every start: " + events);
    }

    @Test
    @DisplayName("The skip property also lets an unreadable source through, but only with a WARN naming it")
    public void skipAllowsAnUnreadableSourceLoudly() {
        Properties props = mysqlProps();
        props.setProperty("binlog.row.image.check.skip", "true");
        List<LogEvent> events = capture(() -> assertDoesNotThrow(
                () -> BinlogRowImagePreflight.check(props, sourceAnswering(null, new SQLException("access denied")))));
        assertTrue(events.stream().anyMatch(e -> e.getLevel() == Level.WARN
                && e.getMessage().getFormattedMessage().contains("binlog.row.image.check.skip")),
                "a skipped check on an unreadable source must still be logged at WARN: " + events);
    }

    @Test
    @DisplayName("binlog_row_value_options containing PARTIAL_JSON is refused, naming the value and the fix")
    public void partialJsonRefuses() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> BinlogRowImagePreflight.check(mysqlProps(), source("FULL", "PARTIAL_JSON")),
                "PARTIAL_JSON logs an UPDATE of a JSON column as a partial update Debezium drops");
        assertTrue(ex.getMessage().contains("binlog_row_value_options"), ex.getMessage());
        assertTrue(ex.getMessage().contains("PARTIAL_JSON"), ex.getMessage());
        assertTrue(ex.getMessage().contains("SET GLOBAL binlog_row_value_options"),
                "the refusal must say how to fix the source: " + ex.getMessage());
    }

    @Test
    @DisplayName("A server with no binlog_row_value_options (MySQL 5.7, error 1193) passes: it cannot log partial updates")
    public void unknownValueOptionsVariablePasses() {
        SQLException unknown = new SQLException("Unknown system variable 'binlog_row_value_options'", "HY000", 1193);
        assertDoesNotThrow(() -> BinlogRowImagePreflight.check(mysqlProps(), source("FULL", unknown)));
    }

    @Test
    @DisplayName("An empty binlog_row_value_options passes")
    public void emptyValueOptionsPasses() {
        assertDoesNotThrow(() -> BinlogRowImagePreflight.check(mysqlProps(), source("FULL", "")));
    }

    @Test
    @DisplayName("binlog_row_value_options that cannot be read (any error but 1193) refuses like binlog_row_image")
    public void unreadableValueOptionsRefuses() {
        SQLException denied = new SQLException("access denied", "42000", 1045);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> BinlogRowImagePreflight.check(mysqlProps(), source("FULL", denied)));
        assertTrue(ex.getMessage().contains("binlog_row_value_options"), ex.getMessage());
        assertTrue(ex.getMessage().contains("cannot be read"), ex.getMessage());
    }

    @Test
    @DisplayName("A MySQL host that cannot be reached refuses: the connector cannot show the source's row format")
    public void unreachableHostRefuses() {
        Properties unreachable = mysqlProps();
        unreachable.setProperty("database.hostname", "127.0.0.1");
        unreachable.setProperty("database.port", "1");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> BinlogRowImagePreflight.check(unreachable));
        assertTrue(ex.getMessage().contains("could not be reached"), ex.getMessage());
    }

    @Test
    @DisplayName("A non-MySQL connector passes through untouched")
    public void nonMysqlPassesThrough() {
        Properties postgres = new Properties();
        postgres.setProperty("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
        postgres.setProperty("database.hostname", "127.0.0.1");
        postgres.setProperty("database.port", "1");
        assertDoesNotThrow(() -> BinlogRowImagePreflight.check(postgres));
    }

    @Test
    @DisplayName("The preflight reads the variable with a SELECT that passes the read-only allowlist")
    public void queryIsReadOnly() {
        assertDoesNotThrow(() -> KeylessTablePreflight.assertReadOnlySql(BinlogRowImagePreflight.QUERY));
        assertTrue(BinlogRowImagePreflight.QUERY.toLowerCase().contains("binlog_row_image"));
    }

    @Test
    @DisplayName("The value-options read is a SELECT too, so it passes the same read-only allowlist")
    public void valueOptionsQueryIsReadOnly() {
        assertDoesNotThrow(() -> KeylessTablePreflight.assertReadOnlySql("SELECT @@GLOBAL.binlog_row_value_options"));
    }
}

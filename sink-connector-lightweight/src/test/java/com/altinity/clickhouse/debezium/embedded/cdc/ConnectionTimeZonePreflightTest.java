package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.cdc.ConnectionTimeZonePreflight.Outcome;
import com.altinity.clickhouse.debezium.embedded.cdc.ConnectionTimeZonePreflight.State;
import com.mysql.cj.util.TimeUtil;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The connector refuses to start against a MySQL source whose session time
 * zone the JDBC driver cannot name, when {@code database.connectionTimeZone}
 * is not set (spec 07.03 §3.1.5).
 *
 * <p><b>The gap.</b> With the property empty, Connector/J derives the session
 * zone from {@code @@time_zone}, or {@code @@system_time_zone} when that is
 * {@code SYSTEM}. On a source host in a DST zone the latter is an abbreviation
 * such as {@code CDT}, which names more than one zone, and the driver refuses
 * every connection. The embedded engine then failed at start and was
 * restarted {@code errors.max.retries} times, each with a full stack trace,
 * before {@code Replication is STOPPED}.</p>
 *
 * <p>The source is a JDBC {@link Connection} stub built with a dynamic proxy:
 * no MySQL is needed, and the stub answers only the one query the preflight
 * is allowed to issue. The zone resolution runs the REAL driver resolver,
 * {@link TimeUtil#getCanonicalTimeZone}.</p>
 */
public class ConnectionTimeZonePreflightTest {

    /** Collects everything the class under test logs during one call. */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

        CapturingAppender() {
            super("capture-connection-time-zone", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    /**
     * A {@link Connection} whose statements answer every query with one row of
     * the two given values (or throw when {@code failure} is set).
     */
    private static Connection sourceAnswering(String timeZone, String systemTimeZone, SQLException failure) {
        ResultSet rs = resultSet(timeZone, systemTimeZone);
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
        return connection((Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(),
                new Class<?>[] {Statement.class}, statement));
    }

    /** A {@link Connection} that fails the test if any statement is executed. */
    private static Connection sourceRefusingAnyQuery() {
        InvocationHandler statement = (proxy, method, args) -> {
            if (method.getName().startsWith("execute")) {
                throw new AssertionError("the preflight must not issue a query here, issued: "
                        + (args == null ? "" : args[0]));
            }
            return defaultValue(method);
        };
        return connection((Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(),
                new Class<?>[] {Statement.class}, statement));
    }

    private static ResultSet resultSet(String col1, String col2) {
        InvocationHandler resultSet = (proxy, method, args) -> {
            switch (method.getName()) {
                case "next":
                    return col1 != null || col2 != null;
                case "getString":
                    switch ((Integer) args[0]) {
                        case 1:
                            return col1;
                        case 2:
                            return col2;
                        default:
                            throw new AssertionError("unexpected column " + args[0]);
                    }
                case "close":
                    return null;
                default:
                    return defaultValue(method);
            }
        };
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                new Class<?>[] {ResultSet.class}, resultSet);
    }

    private static Connection connection(Statement st) {
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
        Logger coreLogger = (Logger) LogManager.getLogger(ConnectionTimeZonePreflight.class);
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

    private static boolean logged(List<LogEvent> events, Level level, String... fragments) {
        return events.stream().anyMatch(e -> {
            if (e.getLevel() != level) {
                return false;
            }
            String text = e.getMessage().getFormattedMessage();
            for (String fragment : fragments) {
                if (!text.contains(fragment)) {
                    return false;
                }
            }
            return true;
        });
    }

    private static Outcome checked(Properties props, Connection conn, AtomicReference<List<LogEvent>> events) {
        AtomicReference<Outcome> outcome = new AtomicReference<>();
        events.set(capture(() -> outcome.set(ConnectionTimeZonePreflight.check(props, conn))));
        return outcome.get();
    }

    @Test
    @DisplayName("time_zone=SYSTEM with system_time_zone=CDT and no database.connectionTimeZone is refused, naming the fix")
    public void ambiguousAbbreviationRefuses() {
        AtomicReference<IllegalStateException> thrown = new AtomicReference<>();
        List<LogEvent> events = capture(() -> thrown.set(assertThrows(IllegalStateException.class,
                () -> ConnectionTimeZonePreflight.check(mysqlProps(), sourceAnswering("SYSTEM", "CDT", null)),
                "the driver cannot map CDT to one zone; every connection would fail and the engine would be "
                        + "retried errors.max.retries times -- the connector must refuse at start instead")));
        String message = thrown.get().getMessage();
        assertTrue(message.contains(ConnectionTimeZonePreflight.PROPERTY),
                "the refusal must name the property to set: " + message);
        assertTrue(message.contains("database.connectionTimeZone"), message);
        assertTrue(message.contains("CDT"), "the refusal must name the value the source reported: " + message);
        assertTrue(message.contains("SYSTEM"), "the refusal must show time_zone=SYSTEM: " + message);
        assertTrue(message.contains("errors.max.retries"),
                "the refusal must say what would have happened without it: " + message);
        assertTrue(message.contains(ConnectionTimeZonePreflight.EXAMPLE_ZONE),
                "the refusal must give an IANA zone example: " + message);
        assertTrue(message.indexOf("set " + ConnectionTimeZonePreflight.PROPERTY) < message.indexOf("Alternatively"),
                "setting the property is the primary fix; changing the source comes second: " + message);
        assertTrue(logged(events, Level.ERROR, "REFUSING TO START", "CDT"),
                "the refusal must be a banner at ERROR: " + events);
    }

    @Test
    @DisplayName("database.connectionTimeZone=SERVER means 'derive from the source': it is probed like an unset value")
    public void serverKeywordIsProbed() {
        Properties props = mysqlProps();
        props.setProperty(ConnectionTimeZonePreflight.PROPERTY, "server");
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> ConnectionTimeZonePreflight.check(props, sourceAnswering("SYSTEM", "CDT", null)),
                "SERVER hands the decision to the source zone, so an ambiguous source zone must still be refused");
        assertTrue(thrown.getMessage().contains("CDT"), thrown.getMessage());
        assertEquals(State.RESOLVED,
                ConnectionTimeZonePreflight.check(props, sourceAnswering("SYSTEM", "UTC", null)).state,
                "SERVER with a resolvable source zone passes like an unset value");
    }

    @Test
    @DisplayName("An IANA id in time_zone resolves to itself and passes at INFO")
    public void namedZoneResolves() {
        AtomicReference<List<LogEvent>> events = new AtomicReference<>();
        Outcome outcome = assertDoesNotThrow(() -> checked(mysqlProps(),
                sourceAnswering("America/Chicago", "CDT", null), events));
        assertEquals(State.RESOLVED, outcome.state, outcome.toString());
        assertEquals("America/Chicago", outcome.effective, outcome.toString());
        assertEquals("America/Chicago", outcome.canonical, outcome.toString());
        assertEquals("America/Chicago", outcome.timeZone);
        assertEquals("CDT", outcome.systemTimeZone);
        assertTrue(logged(events.get(), Level.INFO, ConnectionTimeZonePreflight.PROPERTY, "America/Chicago", "CDT"),
                "the pass must name the property, the source values and the resolved zone: " + events.get());
        assertFalse(logged(events.get(), Level.WARN), "no WARN on a resolvable zone: " + events.get());
        assertFalse(logged(events.get(), Level.ERROR), "no ERROR on a resolvable zone: " + events.get());
    }

    @Test
    @DisplayName("time_zone=SYSTEM with system_time_zone=UTC resolves (UTC is unambiguous)")
    public void systemUtcResolves() {
        AtomicReference<List<LogEvent>> events = new AtomicReference<>();
        Outcome outcome = assertDoesNotThrow(() -> checked(mysqlProps(), sourceAnswering("SYSTEM", "UTC", null), events));
        assertEquals(State.RESOLVED, outcome.state, outcome.toString());
        assertEquals("UTC", outcome.effective, "under SYSTEM the effective zone is system_time_zone: " + outcome);
        assertNotNull(outcome.canonical, outcome.toString());
        // The SYSTEM branch is case-insensitive, as the driver's is.
        assertEquals(State.RESOLVED, assertDoesNotThrow(() -> ConnectionTimeZonePreflight.check(mysqlProps(),
                sourceAnswering("system", "UTC", null))).state);
    }

    @Test
    @DisplayName("An offset such as +00:00 in time_zone resolves")
    public void offsetResolves() {
        AtomicReference<List<LogEvent>> events = new AtomicReference<>();
        Outcome outcome = assertDoesNotThrow(() -> checked(mysqlProps(), sourceAnswering("+00:00", "CDT", null), events));
        assertEquals(State.RESOLVED, outcome.state, outcome.toString());
        assertEquals("+00:00", outcome.effective, outcome.toString());
        assertNotNull(outcome.canonical, outcome.toString());
        assertFalse(logged(events.get(), Level.ERROR), events.get().toString());
    }

    @Test
    @DisplayName("With database.connectionTimeZone set nothing is queried; the driver uses the configured value")
    public void configuredPropertySkipsTheProbe() {
        Properties props = mysqlProps();
        props.setProperty(ConnectionTimeZonePreflight.PROPERTY, "America/Chicago");
        AtomicReference<List<LogEvent>> events = new AtomicReference<>();
        Outcome outcome = assertDoesNotThrow(() -> checked(props, sourceRefusingAnyQuery(), events));
        assertEquals(State.CONFIGURED, outcome.state, outcome.toString());
        assertEquals("America/Chicago", outcome.configured, outcome.toString());
        assertTrue(logged(events.get(), Level.INFO, ConnectionTimeZonePreflight.PROPERTY, "America/Chicago"),
                "the configured value must be named once at INFO: " + events.get());
        assertFalse(logged(events.get(), Level.WARN), events.get().toString());

        // A blank value is "not set" -- the driver ignores it -- so the probe runs and CDT is still refused.
        Properties blank = mysqlProps();
        blank.setProperty(ConnectionTimeZonePreflight.PROPERTY, "  ");
        assertThrows(IllegalStateException.class,
                () -> ConnectionTimeZonePreflight.check(blank, sourceAnswering("SYSTEM", "CDT", null)));

        // The no-connection entry point returns before opening a connection: an unresolvable host is never contacted.
        Properties unreachable = mysqlProps();
        unreachable.setProperty("database.hostname", "nonexistent.invalid");
        unreachable.setProperty(ConnectionTimeZonePreflight.PROPERTY, "UTC");
        assertEquals(State.CONFIGURED, assertDoesNotThrow(() -> ConnectionTimeZonePreflight.check(unreachable)).state);
    }

    @Test
    @DisplayName("A source that cannot be asked does not block startup, but says so at WARN")
    public void probeFailureWarnsAndContinues() {
        AtomicReference<List<LogEvent>> events = new AtomicReference<>();
        Outcome outcome = assertDoesNotThrow(() -> checked(mysqlProps(),
                sourceAnswering(null, null, new SQLException("access denied")), events));
        assertEquals(State.UNKNOWN, outcome.state, outcome.toString());
        assertTrue(logged(events.get(), Level.WARN, ConnectionTimeZonePreflight.PROPERTY, "access denied"),
                "a check that could not run must be visible, with the cause: " + events.get());
        assertFalse(logged(events.get(), Level.ERROR), events.get().toString());

        // No row at all is UNKNOWN too.
        assertEquals(State.UNKNOWN, assertDoesNotThrow(() -> ConnectionTimeZonePreflight.check(mysqlProps(),
                sourceAnswering(null, null, null))).state);

        // No host/user in the configuration: nothing to connect to, WARN and continue.
        Properties noHost = new Properties();
        noHost.setProperty("connector.class", "io.debezium.connector.mysql.MySqlConnector");
        AtomicReference<Outcome> viaProps = new AtomicReference<>();
        List<LogEvent> noHostEvents = capture(() -> viaProps.set(
                assertDoesNotThrow(() -> ConnectionTimeZonePreflight.check(noHost))));
        assertEquals(State.UNKNOWN, viaProps.get().state);
        assertTrue(logged(noHostEvents, Level.WARN, ConnectionTimeZonePreflight.PROPERTY), noHostEvents.toString());
    }

    @Test
    @DisplayName("A non-MySQL connector is never touched: no query, no log")
    public void nonMySqlConnectorIsUntouched() {
        Properties postgres = new Properties();
        postgres.setProperty("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
        postgres.setProperty("database.hostname", "nonexistent.invalid");
        postgres.setProperty("database.user", "u");
        AtomicReference<List<LogEvent>> events = new AtomicReference<>();
        Outcome outcome = assertDoesNotThrow(() -> checked(postgres, sourceRefusingAnyQuery(), events));
        assertEquals(State.SKIPPED, outcome.state, outcome.toString());
        assertTrue(events.get().isEmpty(), "nothing to say about a source without a MySQL session zone: " + events.get());
        assertEquals(State.SKIPPED, assertDoesNotThrow(() -> ConnectionTimeZonePreflight.check(postgres)).state);
    }

    @Test
    @DisplayName("The driver's own resolver rejects CDT and accepts America/Chicago -- the contract the preflight relies on")
    public void driverAgreesWithThePreflight() {
        RuntimeException cdt = assertThrows(RuntimeException.class,
                () -> TimeUtil.getCanonicalTimeZone("CDT", null),
                "Connector/J cannot map the abbreviation CDT to one zone; if this ever passes, the "
                        + "preflight's refusal is no longer grounded in the driver");
        assertTrue(cdt.getMessage().contains("CDT"), cdt.getMessage());
        assertEquals("America/Chicago", TimeUtil.getCanonicalTimeZone("America/Chicago", null));
        assertNotNull(TimeUtil.getCanonicalTimeZone("UTC", null));
        assertNotNull(TimeUtil.getCanonicalTimeZone("+00:00", null));
    }

    @Test
    @DisplayName("The preflight reads the variables with a SELECT that passes the read-only allowlist")
    public void queryIsReadOnly() {
        assertDoesNotThrow(() -> KeylessTablePreflight.assertReadOnlySql(ConnectionTimeZonePreflight.QUERY));
        assertTrue(ConnectionTimeZonePreflight.QUERY.contains("@@GLOBAL.time_zone"));
        assertTrue(ConnectionTimeZonePreflight.QUERY.contains("@@GLOBAL.system_time_zone"));
    }
}

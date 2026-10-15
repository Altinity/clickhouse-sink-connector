package com.altinity.clickhouse.debezium.embedded.cdc;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The binlog client's keep-alive auto-reconnect is off unless the operator
 * asked for it (spec 01.07).
 *
 * <p><b>The gap.</b> Debezium defaults {@code connect.keep.alive} to
 * {@code true}: a dropped binlog connection is reconnected by the client
 * itself, from its last-read byte offset. When the connection was lost while
 * the sink was blocked, that offset is inside a transaction; the resumed
 * stream's ROTATE clears the TABLE_MAP cache and the remaining ROWS events of
 * the interrupted statement are skipped without an error. Every restart path
 * this connector owns resumes from the durable offset -- a transaction
 * boundary -- so the loss-free behaviour is to let a lost connection stop the
 * engine and restart it (spec 10.04), which is what {@code false} does.</p>
 */
public class BinlogKeepAlivePreflightTest {

    /** Collects everything the class under test logs during one call. */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

        CapturingAppender() {
            super("capture-keep-alive", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    private static List<LogEvent> capture(Runnable body) {
        Logger coreLogger = (Logger) LogManager.getLogger(BinlogKeepAlivePreflight.class);
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

    private static Properties propsFor(String connectorClass) {
        Properties props = new Properties();
        props.setProperty("connector.class", connectorClass);
        props.setProperty("database.hostname", "mysql-host");
        return props;
    }

    private static Properties mysqlProps() {
        return propsFor("io.debezium.connector.mysql.MySqlConnector");
    }

    private static boolean logged(List<LogEvent> events, Level level, String needle) {
        return events.stream().anyMatch(e -> e.getLevel() == level
                && e.getMessage().getFormattedMessage().contains(needle));
    }

    @Test
    @DisplayName("An absent connect.keep.alive is set to false for a MySQL source, and said so at INFO")
    public void absentIsDefaultedToFalse() {
        Properties props = mysqlProps();
        List<LogEvent> events = capture(() ->
                assertTrue(BinlogKeepAlivePreflight.apply(props),
                        "the preflight must report that it set the default"));
        assertEquals("false", props.getProperty(BinlogKeepAlivePreflight.PROPERTY),
                "with the keep-alive thread on, a connection lost mid-transaction resumes past the "
                        + "TABLE_MAP and the rest of the statement is dropped silently");
        assertTrue(logged(events, Level.INFO, BinlogKeepAlivePreflight.PROPERTY),
                "the default must be visible in the start-up log: " + events);
        assertFalse(logged(events, Level.WARN, BinlogKeepAlivePreflight.PROPERTY),
                "the default is not an operator override; nothing to warn about: " + events);
    }

    @Test
    @DisplayName("An explicit connect.keep.alive=true is kept, and warned about on every start")
    public void explicitTrueIsKeptAndWarned() {
        Properties props = mysqlProps();
        props.setProperty(BinlogKeepAlivePreflight.PROPERTY, "true");
        List<LogEvent> events = capture(() ->
                assertFalse(BinlogKeepAlivePreflight.apply(props),
                        "an operator's explicit choice is never overwritten"));
        assertEquals("true", props.getProperty(BinlogKeepAlivePreflight.PROPERTY));
        assertTrue(logged(events, Level.WARN, BinlogKeepAlivePreflight.PROPERTY),
                "re-enabling a reconnect path that drops rows must not be quiet: " + events);
        assertTrue(logged(events, Level.WARN, "TABLE_MAP"),
                "the warning must say what is at risk: " + events);
    }

    @Test
    @DisplayName("Case and whitespace do not hide an explicit true")
    public void explicitTrueIsRecognisedWhateverTheSpelling() {
        Properties props = mysqlProps();
        props.setProperty(BinlogKeepAlivePreflight.PROPERTY, " TRUE ");
        List<LogEvent> events = capture(() -> assertFalse(BinlogKeepAlivePreflight.apply(props)));
        assertEquals(" TRUE ", props.getProperty(BinlogKeepAlivePreflight.PROPERTY),
                "the operator's value is kept verbatim");
        assertTrue(logged(events, Level.WARN, BinlogKeepAlivePreflight.PROPERTY), events.toString());
    }

    @Test
    @DisplayName("An explicit connect.keep.alive=false is kept without a warning")
    public void explicitFalseIsKeptQuietly() {
        Properties props = mysqlProps();
        props.setProperty(BinlogKeepAlivePreflight.PROPERTY, "false");
        List<LogEvent> events = capture(() -> assertFalse(BinlogKeepAlivePreflight.apply(props)));
        assertEquals("false", props.getProperty(BinlogKeepAlivePreflight.PROPERTY));
        assertFalse(logged(events, Level.WARN, BinlogKeepAlivePreflight.PROPERTY), events.toString());
    }

    @Test
    @DisplayName("MariaDB has a binlog client too and gets the same default")
    public void mariaDbIsDefaultedToo() {
        Properties props = propsFor("io.debezium.connector.mariadb.MariaDbConnector");
        assertTrue(BinlogKeepAlivePreflight.apply(props));
        assertEquals("false", props.getProperty(BinlogKeepAlivePreflight.PROPERTY));
    }

    @Test
    @DisplayName("Connectors without a binlog client are left untouched")
    public void nonBinlogConnectorsAreUntouched() {
        Properties postgres = propsFor("io.debezium.connector.postgresql.PostgresConnector");
        assertFalse(BinlogKeepAlivePreflight.apply(postgres));
        assertNull(postgres.getProperty(BinlogKeepAlivePreflight.PROPERTY),
                "a PostgreSQL source has no binlog client; the key must not be invented for it");

        Properties unknown = new Properties();
        assertFalse(BinlogKeepAlivePreflight.apply(unknown));
        assertNull(unknown.getProperty(BinlogKeepAlivePreflight.PROPERTY));
    }

    @Test
    @DisplayName("The property the preflight sets is Debezium's own key, so the engine actually honours it")
    public void propertyIsDebeziumsKey() {
        assertEquals("connect.keep.alive", BinlogKeepAlivePreflight.PROPERTY,
                "a differently spelled key would be ignored by Debezium and the default would be a no-op");
        assertEquals("false", BinlogKeepAlivePreflight.SAFE_VALUE);
    }
}

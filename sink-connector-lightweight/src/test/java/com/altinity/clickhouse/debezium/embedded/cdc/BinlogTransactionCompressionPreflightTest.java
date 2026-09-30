package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.cdc.BinlogTransactionCompressionPreflight.Outcome;
import com.altinity.clickhouse.debezium.embedded.cdc.BinlogTransactionCompressionPreflight.SourceState;
import com.altinity.clickhouse.sink.connector.common.Metrics;
import com.github.shyiko.mysql.binlog.event.TransactionPayloadEventData;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The connector verifies at start that it can decode a MySQL source's
 * zstd-compressed {@code Transaction_payload} events, and reports the source's
 * {@code binlog_transaction_compression} (spec 01.08).
 *
 * <p><b>The gap.</b> With {@code binlog_transaction_compression=ON} (MySQL
 * 8.0.20+) every transaction is one zstd-compressed event. The embedded binlog
 * client decodes it through zstd-jni's native library; on a platform where
 * that library does not load, the connector fails on the first committed
 * transaction and nothing at start said why. Nothing detected, verified or
 * reported the setting.</p>
 *
 * <p>The source is a JDBC {@link Connection} stub built with a dynamic proxy:
 * no MySQL is needed, and the stub answers only the queries the preflight is
 * allowed to issue. The decoder self-test runs the REAL zstd-jni and the REAL
 * binlog-client deserializer.</p>
 */
public class BinlogTransactionCompressionPreflightTest {

    /** Collects everything the class under test logs during one call. */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

        CapturingAppender() {
            super("capture-transaction-compression", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    /**
     * A {@link Connection} whose statements answer the preflight's three-column
     * query with the given values, or throw {@code failure} for it (a server
     * older than 8.0.20 rejects the unknown variable); the version-only
     * fallback query is always answered with {@code version}.
     */
    private static Connection sourceAnswering(String compression, Integer level, String version,
                                              SQLException failure) {
        ResultSet threeColumns = resultSet(compression, level, version);
        ResultSet versionOnly = resultSet(version, null, null);
        InvocationHandler statement = (proxy, method, args) -> {
            switch (method.getName()) {
                case "executeQuery":
                    String sql = (String) args[0];
                    if (BinlogTransactionCompressionPreflight.QUERY.equals(sql)) {
                        if (failure != null) {
                            throw failure;
                        }
                        return threeColumns;
                    }
                    if (BinlogTransactionCompressionPreflight.VERSION_QUERY.equals(sql)) {
                        return versionOnly;
                    }
                    throw new AssertionError("unexpected query issued by the preflight: " + sql);
                case "close":
                    return null;
                default:
                    return defaultValue(method);
            }
        };
        return connection((Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(),
                new Class<?>[] {Statement.class}, statement));
    }

    /** A {@link Connection} that fails the test if any statement is created or executed. */
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

    private static ResultSet resultSet(String col1, Integer col2, String col3) {
        InvocationHandler resultSet = (proxy, method, args) -> {
            switch (method.getName()) {
                case "next":
                    return col1 != null || col3 != null;
                case "getString":
                    switch ((Integer) args[0]) {
                        case 1:
                            return col1;
                        case 2:
                            return col2 == null ? null : col2.toString();
                        case 3:
                            return col3;
                        default:
                            throw new AssertionError("unexpected column " + args[0]);
                    }
                case "getInt":
                    if ((Integer) args[0] == 2) {
                        return col2 == null ? 0 : col2;
                    }
                    throw new AssertionError("unexpected getInt column " + args[0]);
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

    private static Properties mysqlProps(String mode) {
        Properties props = mysqlProps();
        props.setProperty(BinlogTransactionCompressionPreflight.PROPERTY, mode);
        return props;
    }

    private static List<LogEvent> capture(Runnable body) {
        Logger coreLogger = (Logger) LogManager.getLogger(BinlogTransactionCompressionPreflight.class);
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
        events.set(capture(() -> outcome.set(BinlogTransactionCompressionPreflight.check(props, conn))));
        return outcome.get();
    }

    // ------------------------------------------------------------------
    // Decoder self-test: real zstd-jni, real deserializer
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The decoder self-test decodes a synthetic zstd Transaction_payload through the real deserializer")
    public void decoderSelfTestDecodesASyntheticPayload() {
        List<LogEvent> events = capture(() -> assertTrue(BinlogTransactionCompressionPreflight.decoderSelfTest(),
                "zstd-jni and TransactionPayloadEventDataDeserializer are on the classpath; the self-test "
                        + "must pass on this platform"));
        assertTrue(logged(events, Level.INFO, "decoder self-test passed",
                        Long.toString(BinlogTransactionCompressionPreflight.SELF_TEST_XID)),
                "success must be visible at INFO with the decoded xid: " + events);
        assertFalse(logged(events, Level.ERROR), "no ERROR on a passing self-test: " + events);
    }

    @Test
    @DisplayName("A payload whose zstd frame is corrupted fails the self-test instead of throwing")
    public void decoderSelfTestFailsOnACorruptPayload() {
        long xid = BinlogTransactionCompressionPreflight.SELF_TEST_XID;
        byte[] body = BinlogTransactionCompressionPreflight.syntheticPayload(xid);
        int magic = indexOfZstdMagic(body);
        assertTrue(magic > 0, "the payload must carry a zstd frame after the TLV header");
        // Clobber the frame's magic number: zstd rejects the frame outright.
        body[magic] ^= 0x55;
        body[magic + 1] ^= 0x55;
        List<LogEvent> events = capture(() -> assertFalse(
                BinlogTransactionCompressionPreflight.decoderSelfTest(body, xid),
                "a corrupted frame must be reported as a failed self-test, not propagate"));
        assertTrue(logged(events, Level.ERROR, "self-test FAILED"),
                "the cause must be logged at ERROR: " + events);
    }

    @Test
    @DisplayName("A payload that decodes to the wrong xid fails the self-test")
    public void decoderSelfTestFailsOnAMismatchedXid() {
        byte[] body = BinlogTransactionCompressionPreflight.syntheticPayload(42L);
        assertFalse(BinlogTransactionCompressionPreflight.decoderSelfTest(body, 43L));
        assertTrue(BinlogTransactionCompressionPreflight.decoderSelfTest(body, 42L));
    }

    @Test
    @DisplayName("The synthetic payload round-trips: header fields, zstd frame and the inner XID event")
    public void decoderSelfTestRoundTripsThroughZstd() throws Exception {
        long xid = 0x00CAFEBABEL;
        byte[] inner = BinlogTransactionCompressionPreflight.xidEvent(xid);
        assertEquals(27, inner.length, "19-byte v4 header + 8-byte xid");
        assertEquals(16, inner[4], "type byte is XID");
        assertEquals(27, inner[9], "event_size, little-endian");

        byte[] body = BinlogTransactionCompressionPreflight.syntheticPayload(xid);
        // TLV header: (1,1,payloadSize) (2,1,ZSTD=0) (3,1,27) then the end mark 0.
        assertEquals(1, body[0]);
        assertEquals(1, body[1]);
        int payloadSize = body[2] & 0xFF;
        assertEquals(2, body[3]);
        assertEquals(1, body[4]);
        assertEquals(0, body[5], "compression type ZSTD");
        assertEquals(3, body[6]);
        assertEquals(1, body[7]);
        assertEquals(27, body[8], "uncompressed size");
        assertEquals(0, body[9], "header end mark");
        assertEquals(10 + payloadSize, body.length);

        TransactionPayloadEventData decoded = BinlogTransactionCompressionPreflight.decodePayload(body);
        assertEquals(payloadSize, decoded.getPayloadSize());
        assertEquals(27, decoded.getUncompressedSize());
        // The inner events are streamed (spec 01.08 section 3.2): count them by iterating.
        assertEquals(1, decoded.getUncompressedEvents().stream().count());

        // The packed-integer writer: one byte below 251, 0xFC + u16 LE above.
        ByteArrayOutputStream small = new ByteArrayOutputStream();
        BinlogTransactionCompressionPreflight.writePackedInteger(small, 250);
        assertArrayEquals(new byte[] {(byte) 250}, small.toByteArray());
        ByteArrayOutputStream large = new ByteArrayOutputStream();
        BinlogTransactionCompressionPreflight.writePackedInteger(large, 0x1234);
        assertArrayEquals(new byte[] {(byte) 0xFC, 0x34, 0x12}, large.toByteArray());
    }

    private static int indexOfZstdMagic(byte[] body) {
        for (int i = 0; i + 3 < body.length; i++) {
            if ((body[i] & 0xFF) == 0x28 && (body[i + 1] & 0xFF) == 0xB5
                    && (body[i + 2] & 0xFF) == 0x2F && (body[i + 3] & 0xFF) == 0xFD) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Decision table
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Source ON with a working decoder is reported at INFO with the level and version, and passes")
    public void sourceOnIsReportedAndDecoderVerified() {
        AtomicReference<List<LogEvent>> events = new AtomicReference<>();
        Outcome outcome = assertDoesNotThrow(() -> checked(mysqlProps(), sourceAnswering("1", 3, "8.0.41", null), events));
        assertEquals(SourceState.ON, outcome.sourceState);
        assertEquals(Integer.valueOf(3), outcome.zstdLevel);
        assertEquals("8.0.41", outcome.serverVersion);
        assertTrue(outcome.decoderOk);
        assertEquals("auto", outcome.mode);
        assertTrue(logged(events.get(), Level.INFO, "binlog_transaction_compression=ON", "level 3", "8.0.41"),
                "ON must be visible at INFO with the zstd level: " + events.get());
        assertFalse(logged(events.get(), Level.WARN), "nothing to warn about: " + events.get());

        // The server may also report the boolean as text.
        assertEquals(SourceState.ON, BinlogTransactionCompressionPreflight.check(mysqlProps(),
                sourceAnswering("ON", 3, "8.0.41", null)).sourceState);
        assertEquals(SourceState.ON, BinlogTransactionCompressionPreflight.check(mysqlProps("REQUIRE"),
                sourceAnswering("true", 1, "8.4.0", null)).sourceState);
    }

    @Test
    @DisplayName("Source ON older than 8.0.34 warns in auto (a >1 GiB compressed payload is unreadable by every reader) and refuses in require")
    public void sourceOnBefore8034WarnsInAutoAndRefusesInRequire() {
        AtomicReference<List<LogEvent>> events = new AtomicReference<>();
        Outcome outcome = assertDoesNotThrow(() -> checked(mysqlProps(), sourceAnswering("1", 3, "8.0.32-24", null), events));
        assertEquals(SourceState.ON, outcome.sourceState);
        assertTrue(outcome.decoderOk);
        assertTrue(logged(events.get(), Level.WARN, "8.0.32-24", "8.0.34", "Bug #33588473", "1 GiB"),
                "the pre-8.0.34 hazard must be a WARN banner in auto: " + events.get());
        assertFalse(logged(events.get(), Level.ERROR), events.get().toString());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> BinlogTransactionCompressionPreflight.check(mysqlProps("require"),
                        sourceAnswering("ON", 3, "8.0.33", null)));
        assertTrue(ex.getMessage().contains("8.0.33"), ex.getMessage());
        assertTrue(ex.getMessage().contains("8.0.34"), ex.getMessage());
        assertTrue(ex.getMessage().contains("SET PERSIST binlog_transaction_compression = OFF"), ex.getMessage());

        // 8.0.34 and later (and the 8.1+/8.4/9.x series) fall back to an uncompressed transaction: no warning.
        for (String v : new String[] {"8.0.34", "8.0.41-32", "8.0.46", "8.1.0", "8.4.3", "9.1.0"}) {
            AtomicReference<List<LogEvent>> ev = new AtomicReference<>();
            assertDoesNotThrow(() -> checked(mysqlProps("require"), sourceAnswering("1", 3, v, null), ev), v);
            assertFalse(logged(ev.get(), Level.WARN), v + ": " + ev.get());
        }
    }

    @Test
    @DisplayName("Source OFF older than 8.0.34 in auto warns not to enable compression on that server")
    public void sourceOffBefore8034WarnsNotToEnable() {
        AtomicReference<List<LogEvent>> events = new AtomicReference<>();
        Outcome outcome = assertDoesNotThrow(() -> checked(mysqlProps(), sourceAnswering("0", 3, "8.0.20", null), events));
        assertEquals(SourceState.OFF, outcome.sourceState);
        assertTrue(logged(events.get(), Level.WARN, "binlog_transaction_compression=OFF", "8.0.20", "Do not turn"),
                events.get().toString());
    }

    @Test
    @DisplayName("Only 8.0.20-8.0.33 is classified as writing oversized payloads; an unparseable version is never a refusal")
    public void oversizedPayloadVersionClassification() {
        assertTrue(BinlogTransactionCompressionPreflight.writesOversizedPayloads("8.0.20"));
        assertTrue(BinlogTransactionCompressionPreflight.writesOversizedPayloads("8.0.33-25"));
        assertTrue(BinlogTransactionCompressionPreflight.writesOversizedPayloads(" 8.0.32-24 "));
        assertFalse(BinlogTransactionCompressionPreflight.writesOversizedPayloads("8.0.34"));
        assertFalse(BinlogTransactionCompressionPreflight.writesOversizedPayloads("8.0.41-32"));
        assertFalse(BinlogTransactionCompressionPreflight.writesOversizedPayloads("8.1.0"));
        assertFalse(BinlogTransactionCompressionPreflight.writesOversizedPayloads("8.4.3"));
        assertFalse(BinlogTransactionCompressionPreflight.writesOversizedPayloads("9.1.0"));
        assertFalse(BinlogTransactionCompressionPreflight.writesOversizedPayloads(null));
        assertFalse(BinlogTransactionCompressionPreflight.writesOversizedPayloads("garbage"));
        assertFalse(BinlogTransactionCompressionPreflight.writesOversizedPayloads("8.0"));
    }

    @Test
    @DisplayName("Source OFF in auto is an INFO line and passes")
    public void sourceOffAutoIsQuiet() {
        AtomicReference<List<LogEvent>> events = new AtomicReference<>();
        Outcome outcome = assertDoesNotThrow(() -> checked(mysqlProps(), sourceAnswering("0", 3, "8.0.41", null), events));
        assertEquals(SourceState.OFF, outcome.sourceState);
        assertTrue(outcome.decoderOk);
        assertTrue(logged(events.get(), Level.INFO, "binlog_transaction_compression=OFF"), events.get().toString());
        assertFalse(logged(events.get(), Level.WARN), events.get().toString());
        assertFalse(logged(events.get(), Level.ERROR), events.get().toString());
        assertEquals(SourceState.OFF, BinlogTransactionCompressionPreflight.check(mysqlProps(),
                sourceAnswering("OFF", 3, "8.0.41", null)).sourceState);
    }

    @Test
    @DisplayName("Source OFF in require mode refuses, quoting the SET PERSIST that turns it on")
    public void sourceOffRequireRefuses() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> BinlogTransactionCompressionPreflight.check(mysqlProps("require"),
                        sourceAnswering("0", 3, "8.0.41", null)));
        assertTrue(ex.getMessage().contains("SET PERSIST binlog_transaction_compression = ON"), ex.getMessage());
        assertTrue(ex.getMessage().contains("binlog_transaction_compression = ON"), ex.getMessage());
        assertTrue(ex.getMessage().contains(BinlogTransactionCompressionPreflight.PROPERTY), ex.getMessage());
        assertTrue(ex.getMessage().contains("8.0.41"), ex.getMessage());
    }

    @Test
    @DisplayName("A source that cannot be asked (pre-8.0.20, permission denied) warns and continues in auto")
    public void sourceUnknownAutoWarnsAndContinues() {
        AtomicReference<List<LogEvent>> events = new AtomicReference<>();
        Outcome outcome = assertDoesNotThrow(() -> checked(mysqlProps(),
                sourceAnswering(null, null, "5.7.44", new SQLException("Unknown system variable "
                        + "'binlog_transaction_compression'", "HY000", 1193)), events));
        assertEquals(SourceState.UNKNOWN, outcome.sourceState);
        assertNull(outcome.zstdLevel);
        assertEquals("5.7.44", outcome.serverVersion, "the version is still named when it can be read");
        assertTrue(outcome.decoderOk);
        assertTrue(logged(events.get(), Level.WARN, "binlog_transaction_compression could not be read", "5.7.44"),
                "an unreadable source must be visible at WARN naming the version: " + events.get());
        assertFalse(logged(events.get(), Level.ERROR), events.get().toString());

        // No row at all is unknown too.
        assertEquals(SourceState.UNKNOWN, BinlogTransactionCompressionPreflight.check(mysqlProps(),
                sourceAnswering(null, null, null, null)).sourceState);
    }

    @Test
    @DisplayName("A source that cannot be asked refuses in require mode")
    public void sourceUnknownRequireRefuses() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> BinlogTransactionCompressionPreflight.check(mysqlProps("require"),
                        sourceAnswering(null, null, null, new SQLException("access denied"))));
        assertTrue(ex.getMessage().contains("could not be read"), ex.getMessage());
        assertTrue(ex.getMessage().contains("SET PERSIST binlog_transaction_compression = ON"), ex.getMessage());
    }

    @Test
    @DisplayName("skip issues no query, runs no self-test, and says so once at INFO")
    public void skipIssuesNoQueryAndNoSelfTest() {
        AtomicReference<List<LogEvent>> events = new AtomicReference<>();
        Outcome outcome = assertDoesNotThrow(() -> checked(mysqlProps(" Skip "), sourceRefusingAnyQuery(), events));
        assertEquals(SourceState.SKIPPED, outcome.sourceState);
        assertFalse(outcome.decoderOk, "the self-test did not run");
        assertNull(outcome.zstdLevel);
        assertNull(outcome.serverVersion);
        assertEquals("skip", outcome.mode);
        assertTrue(logged(events.get(), Level.INFO, BinlogTransactionCompressionPreflight.PROPERTY + "=skip"),
                events.get().toString());
        assertFalse(logged(events.get(), Level.INFO, "decoder self-test passed"),
                "no self-test in skip mode: " + events.get());
        assertEquals(1, events.get().size(), "one line: " + events.get());
    }

    @Test
    @DisplayName("An unknown mode refuses to start, naming the allowed values")
    public void unknownModeRefuses() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> BinlogTransactionCompressionPreflight.check(mysqlProps("yes"), sourceRefusingAnyQuery()));
        assertTrue(ex.getMessage().contains(BinlogTransactionCompressionPreflight.PROPERTY), ex.getMessage());
        assertTrue(ex.getMessage().contains("yes"), ex.getMessage());
        assertTrue(ex.getMessage().contains("auto"), ex.getMessage());
        assertTrue(ex.getMessage().contains("require"), ex.getMessage());
        assertTrue(ex.getMessage().contains("skip"), ex.getMessage());
    }

    @Test
    @DisplayName("Non-MySQL connectors are untouched: no query, no self-test, no log line")
    public void nonMySqlConnectorIsUntouched() {
        Properties postgres = new Properties();
        postgres.setProperty("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
        postgres.setProperty("database.hostname", "nonexistent.invalid");
        AtomicReference<List<LogEvent>> events = new AtomicReference<>();
        Outcome outcome = assertDoesNotThrow(() -> checked(postgres, sourceRefusingAnyQuery(), events));
        assertEquals(SourceState.SKIPPED, outcome.sourceState);
        assertFalse(outcome.decoderOk);
        assertTrue(events.get().isEmpty(), "untouched means silent: " + events.get());
        // The connecting entry point too, without reaching for the network.
        assertEquals(SourceState.SKIPPED, assertDoesNotThrow(
                () -> BinlogTransactionCompressionPreflight.check(postgres)).sourceState);

        Properties mariadb = new Properties();
        mariadb.setProperty("connector.class", "io.debezium.connector.mariadb.MariaDbConnector");
        assertEquals(SourceState.SKIPPED, BinlogTransactionCompressionPreflight.check(mariadb,
                sourceRefusingAnyQuery()).sourceState);
    }

    @Test
    @DisplayName("An unreachable MySQL host is source UNKNOWN: WARN in auto, refusal in require")
    public void unreachableHostIsUnknown() {
        Properties unreachable = mysqlProps();
        unreachable.setProperty("database.hostname", "nonexistent.invalid");
        unreachable.setProperty("database.port", "3306");
        AtomicReference<List<LogEvent>> events = new AtomicReference<>();
        AtomicReference<Outcome> outcome = new AtomicReference<>();
        events.set(capture(() -> outcome.set(assertDoesNotThrow(
                () -> BinlogTransactionCompressionPreflight.check(unreachable)))));
        assertEquals(SourceState.UNKNOWN, outcome.get().sourceState);
        assertTrue(outcome.get().decoderOk);
        assertTrue(logged(events.get(), Level.WARN, "could not connect"), events.get().toString());

        unreachable.setProperty(BinlogTransactionCompressionPreflight.PROPERTY, "require");
        assertThrows(IllegalStateException.class, () -> BinlogTransactionCompressionPreflight.check(unreachable));
    }

    @Test
    @DisplayName("Updating the compression gauges before metrics are initialized is a no-op, not an NPE")
    public void metricsUpdateIsANoOpWhenMetricsAreOff() {
        assertDoesNotThrow(() -> Metrics.updateBinlogTransactionCompression(1, 3, 1));
        assertDoesNotThrow(() -> Metrics.updateBinlogTransactionCompression(-1, -1, 0));
    }

    @Test
    @DisplayName("The preflight reads the variables with SELECTs that pass the read-only allowlist")
    public void queriesAreReadOnly() {
        assertDoesNotThrow(() -> KeylessTablePreflight.assertReadOnlySql(BinlogTransactionCompressionPreflight.QUERY));
        assertDoesNotThrow(() -> KeylessTablePreflight.assertReadOnlySql(
                BinlogTransactionCompressionPreflight.VERSION_QUERY));
        assertTrue(BinlogTransactionCompressionPreflight.QUERY.contains("binlog_transaction_compression_level_zstd"));
    }
}

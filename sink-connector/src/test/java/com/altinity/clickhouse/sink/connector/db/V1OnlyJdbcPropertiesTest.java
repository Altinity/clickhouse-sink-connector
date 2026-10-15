package com.altinity.clickhouse.sink.connector.db;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Tests for the V1-only JDBC connection-property filter.
 * <p>
 * The clickhouse-jdbc V2 client validates the whole property set up front and
 * throws {@code ClientMisconfigurationException: Unknown and unmapped config
 * properties: [...]} for anything it does not recognise. Two properties in the
 * shipped docker configuration's {@code clickhouse.jdbc.params} —
 * {@code keepalive.timeout} and {@code max_buffer_size} — fall in that
 * category, so with the V2 driver EVERY connection attempt failed with
 * "Failed to create connection". Reproduced live against clickhouse-jdbc
 * 0.9.8 with the exact property set the connector builds:
 * <pre>
 *   FAILED java.sql.SQLException: Failed to create connection
 *     caused by ClientMisconfigurationException:
 *       Unknown and unmapped config properties: [keepalive.timeout, max_buffer_size]
 * </pre>
 * The same probe confirmed the other properties the connector sets
 * (socket_timeout, connection_timeout, client_name, custom_settings,
 * http_connection_provider, jdbc_ignore_unsupported_values) are all accepted.
 */
public class V1OnlyJdbcPropertiesTest {

    private static Properties dockerConfigProperties() {
        // Exactly the clickhouse.jdbc.params value shipped in
        // sink-connector-lightweight/docker/config.yml, plus the properties
        // BaseDbWriter.createConnection always sets.
        Properties p = BaseDbWriter.splitJdbcProperties(
                "keepalive.timeout=3,max_buffer_size=1000000,"
                        + "socket_timeout=30000,connection_timeout=30000");
        p.setProperty("client_name", "test");
        p.setProperty("custom_settings", "allow_experimental_object_type=1");
        p.setProperty("http_connection_provider", "HTTP_URL_CONNECTION");
        return p;
    }

    @Test
    public void testV1OnlyPropertiesAreRemovedForV2() {
        Properties p = dockerConfigProperties();
        int removed = BaseDbWriter.dropV1OnlyProperties(p);

        Assert.assertEquals(2, removed);
        Assert.assertNull(p.getProperty("keepalive.timeout"));
        Assert.assertNull(p.getProperty("max_buffer_size"));
    }

    @Test
    public void testV2CompatiblePropertiesArePreserved() {
        Properties p = dockerConfigProperties();
        BaseDbWriter.dropV1OnlyProperties(p);

        // Every property the V2 driver accepts must survive untouched.
        Assert.assertEquals("30000", p.getProperty("socket_timeout"));
        Assert.assertEquals("30000", p.getProperty("connection_timeout"));
        Assert.assertEquals("test", p.getProperty("client_name"));
        Assert.assertEquals("allow_experimental_object_type=1",
                p.getProperty("custom_settings"));
        Assert.assertEquals("HTTP_URL_CONNECTION",
                p.getProperty("http_connection_provider"));
    }

    @Test
    public void testFilterIsIdempotentAndSafeWhenNothingToRemove() {
        Properties p = new Properties();
        p.setProperty("socket_timeout", "30000");

        Assert.assertEquals(0, BaseDbWriter.dropV1OnlyProperties(p));
        Assert.assertEquals(0, BaseDbWriter.dropV1OnlyProperties(p));
        Assert.assertEquals(1, p.size());
        Assert.assertEquals("30000", p.getProperty("socket_timeout"));
    }

    @Test
    public void testFilterCoversExactlyTheKnownV1OnlyKeys() {
        // Guards against silently widening the drop list: anything added here
        // must be justified by a live probe against the V2 driver.
        Assert.assertArrayEquals(
                new String[]{"keepalive.timeout", "max_buffer_size"},
                BaseDbWriter.V1_ONLY_PROPERTIES);
    }


    /** Collects everything BaseDbWriter logs during one call. */
    private static final class CapturingAppender extends AbstractAppender {

        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

        CapturingAppender() {
            super("capture-v1-only-warn-once", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    /**
     * Clears the once-per-JVM record so the test is deterministic no matter
     * which earlier test in this JVM already went through createConnection().
     * Tolerates the field's absence so the test also compiles and runs (red)
     * against code without the dedupe.
     */
    private static void resetOnceOnlyWarnings() throws Exception {
        try {
            Field f = BaseDbWriter.class.getDeclaredField("WARNED_V1_ONLY_PROPERTIES");
            f.setAccessible(true);
            ((Set<?>) f.get(null)).clear();
        } catch (NoSuchFieldException absent) {
            // pre-fix code: every call warns, nothing to reset
        }
    }

    private static long warnsMentioning(List<LogEvent> events, String key) {
        return events.stream()
                .filter(e -> e.getLevel() == Level.WARN
                        && e.getMessage().getFormattedMessage().contains("'" + key + "'"))
                .count();
    }

    /**
     * dropV1OnlyProperties runs on EVERY createConnection() call -- with a
     * worker pool, several times a minute for the life of the process. The
     * properties must be dropped every time, but the WARN telling the operator
     * about it belongs once per property key per JVM; afterwards it is DEBUG.
     *
     * <p>Against the pre-fix code this fails with two WARNs per key.</p>
     */
    @Test
    public void warnsOncePerPropertyKeyPerJvm() throws Exception {
        resetOnceOnlyWarnings();
        Logger coreLogger = (Logger) LogManager.getLogger(BaseDbWriter.class);
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        coreLogger.addAppender(appender);
        try {
            // Two connection attempts, each with the full docker property set.
            Assert.assertEquals(2, BaseDbWriter.dropV1OnlyProperties(dockerConfigProperties()));
            Assert.assertEquals(2, BaseDbWriter.dropV1OnlyProperties(dockerConfigProperties()));
        } finally {
            coreLogger.removeAppender(appender);
            appender.stop();
        }

        Assert.assertEquals("keepalive.timeout must be reported at WARN exactly once per JVM, "
                        + "not on every connection", 1, warnsMentioning(appender.events, "keepalive.timeout"));
        Assert.assertEquals("max_buffer_size must be reported at WARN exactly once per JVM, "
                        + "not on every connection", 1, warnsMentioning(appender.events, "max_buffer_size"));
    }
}

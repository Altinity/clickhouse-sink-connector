package com.altinity.clickhouse.sink.connector.db;

import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.util.Properties;

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
}

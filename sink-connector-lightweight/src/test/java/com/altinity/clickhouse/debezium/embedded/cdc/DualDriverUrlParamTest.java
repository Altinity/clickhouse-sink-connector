package com.altinity.clickhouse.debezium.embedded.cdc;

import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.util.Properties;

/**
 * Unit tests for the Debezium-storage JDBC URL tagging that backs the
 * clickhouse.jdbc.v1 dual-driver toggle in the lightweight connector.
 * The offset-storage and schema-history URLs must be tagged with the
 * V1 marker when the legacy driver is requested, and with the V2-only
 * jdbc_ignore_unsupported_values flag otherwise — never both.
 */
public class DualDriverUrlParamTest {

    @Test
    public void testV2FlagAppended() {
        Properties props = new Properties();
        props.setProperty("offset.storage.jdbc.url", "jdbc:clickhouse://host:8123/altinity_sink_connector");
        DebeziumChangeEventCapture.ensureIgnoreUnsupportedValuesParam(props, "offset.storage.jdbc.url");
        Assert.assertEquals(
                "jdbc:clickhouse://host:8123/altinity_sink_connector?jdbc_ignore_unsupported_values=true",
                props.getProperty("offset.storage.jdbc.url"));
    }

    @Test
    public void testV1MarkerAppended() {
        Properties props = new Properties();
        props.setProperty("offset.storage.jdbc.url", "jdbc:clickhouse://host:8123/altinity_sink_connector");
        DebeziumChangeEventCapture.ensureUrlParam(props, "offset.storage.jdbc.url",
                DebeziumChangeEventCapture.V1_DRIVER_MARKER + "=true");
        Assert.assertEquals(
                "jdbc:clickhouse://host:8123/altinity_sink_connector?clickhouse.jdbc.v1=true",
                props.getProperty("offset.storage.jdbc.url"));
    }

    @Test
    public void testV1MarkerWithExistingQueryString() {
        Properties props = new Properties();
        props.setProperty("schema.history.internal.jdbc.url",
                "jdbc:clickhouse://host:8123/db?socket_timeout=10000");
        DebeziumChangeEventCapture.ensureUrlParam(props, "schema.history.internal.jdbc.url",
                DebeziumChangeEventCapture.V1_DRIVER_MARKER + "=true");
        Assert.assertEquals(
                "jdbc:clickhouse://host:8123/db?socket_timeout=10000&clickhouse.jdbc.v1=true",
                props.getProperty("schema.history.internal.jdbc.url"));
    }

    @Test
    public void testIdempotent() {
        Properties props = new Properties();
        props.setProperty("offset.storage.jdbc.url",
                "jdbc:clickhouse://host:8123/db?clickhouse.jdbc.v1=true");
        DebeziumChangeEventCapture.ensureUrlParam(props, "offset.storage.jdbc.url",
                DebeziumChangeEventCapture.V1_DRIVER_MARKER + "=true");
        Assert.assertEquals("jdbc:clickhouse://host:8123/db?clickhouse.jdbc.v1=true",
                props.getProperty("offset.storage.jdbc.url"));
    }

    @Test
    public void testNonClickHouseUrlUntouched() {
        Properties props = new Properties();
        props.setProperty("offset.storage.jdbc.url", "jdbc:mysql://host:3306/db");
        DebeziumChangeEventCapture.ensureUrlParam(props, "offset.storage.jdbc.url",
                DebeziumChangeEventCapture.V1_DRIVER_MARKER + "=true");
        Assert.assertEquals("jdbc:mysql://host:3306/db",
                props.getProperty("offset.storage.jdbc.url"));
    }

    @Test
    public void testMissingKeyIsNoOp() {
        Properties props = new Properties();
        DebeziumChangeEventCapture.ensureUrlParam(props, "offset.storage.jdbc.url",
                DebeziumChangeEventCapture.V1_DRIVER_MARKER + "=true");
        Assert.assertNull(props.getProperty("offset.storage.jdbc.url"));
    }
}

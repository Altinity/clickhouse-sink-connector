package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Unit tests for the dual JDBC driver toggle (clickhouse.jdbc.v1).
 *
 * clickhouse-jdbc 0.7+ bundles BOTH driver implementations: the new V2
 * driver (default) and the legacy V1 driver (0.6.x code). The
 * clickhouse.jdbc.v1 connector parameter selects between them at runtime
 * — no rebuild needed. These tests verify the URL-rewrite plumbing that
 * performs the selection.
 */
public class DualJdbcDriverToggleTest {

    @Test
    public void testConfigDefaultsToV2Driver() {
        Map<String, String> props = new HashMap<>();
        props.put("connector.class", "io.debezium.connector.mysql.MySqlConnector");
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(props);
        Assert.assertFalse("clickhouse.jdbc.v1 must default to false (V2 driver)",
                config.getBoolean(
                        ClickHouseSinkConnectorConfigVariables.JDBC_V1_DRIVER.toString()));
    }

    @Test
    public void testConfigAcceptsV1Toggle() {
        Map<String, String> props = new HashMap<>();
        props.put("connector.class", "io.debezium.connector.mysql.MySqlConnector");
        props.put(ClickHouseSinkConnectorConfigVariables.JDBC_V1_DRIVER.toString(), "true");
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(props);
        Assert.assertTrue("clickhouse.jdbc.v1=true must be honored",
                config.getBoolean(
                        ClickHouseSinkConnectorConfigVariables.JDBC_V1_DRIVER.toString()));
    }

    @Test
    public void testV2UrlGetsIgnoreUnsupportedFlag() {
        String url = SinkConnectorDataSource.applyDriverSelection(
                "jdbc:clickhouse://host:8123/db", false);
        Assert.assertEquals(
                "jdbc:clickhouse://host:8123/db?jdbc_ignore_unsupported_values=true", url);
    }

    @Test
    public void testV1UrlGetsV1Marker() {
        String url = SinkConnectorDataSource.applyDriverSelection(
                "jdbc:clickhouse://host:8123/db", true);
        Assert.assertEquals(
                "jdbc:clickhouse://host:8123/db?clickhouse.jdbc.v1=true", url);
        Assert.assertFalse("V2-only flag must not be added on the V1 path",
                url.contains("jdbc_ignore_unsupported_values"));
    }

    @Test
    public void testV1UrlMarkerAppendedWithAmpersandWhenQueryStringExists() {
        String url = SinkConnectorDataSource.applyDriverSelection(
                "jdbc:clickhouse://host:8123/db?socket_timeout=10000", true);
        Assert.assertEquals(
                "jdbc:clickhouse://host:8123/db?socket_timeout=10000&clickhouse.jdbc.v1=true",
                url);
    }

    @Test
    public void testDriverSelectionIsIdempotent() {
        // Re-applying the rewrite must not duplicate parameters.
        String v1Once = SinkConnectorDataSource.applyDriverSelection(
                "jdbc:clickhouse://host:8123/db", true);
        String v1Twice = SinkConnectorDataSource.applyDriverSelection(v1Once, true);
        Assert.assertEquals(v1Once, v1Twice);

        String v2Once = SinkConnectorDataSource.applyDriverSelection(
                "jdbc:clickhouse://host:8123/db", false);
        String v2Twice = SinkConnectorDataSource.applyDriverSelection(v2Once, false);
        Assert.assertEquals(v2Once, v2Twice);
    }

    @Test
    public void testNullUrlIsPassedThrough() {
        Assert.assertNull(SinkConnectorDataSource.applyDriverSelection(null, true));
        Assert.assertNull(SinkConnectorDataSource.applyDriverSelection(null, false));
    }
}

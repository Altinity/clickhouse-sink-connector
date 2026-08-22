package com.altinity.clickhouse.sink.connector.db;

import com.clickhouse.jdbc.ClickHouseDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * The SinkConnectorDataSource class extends the ClickHouseDataSource class
 * to provide custom behavior for obtaining connections to ClickHouse.
 * <p>
 * This class is used in the ClickHouse sink connector to manage the creation
 * and configuration of database connections. It can be extended or modified
 * to use custom HTTP clients or handle other specific connection logic if required.
 * </p>
 * <p>
 * clickhouse-jdbc 0.7+ ships BOTH driver implementations in one jar: the new
 * JDBC V2 driver (default) and the legacy V1 driver (the 0.6.x code, bundled
 * as {@code DriverV1}/{@code DataSourceV1}). The parent
 * {@code ClickHouseDataSource} routes between them based on
 * {@code ClickHouseDriver.isV2(url)}: when the JDBC URL contains
 * {@code clickhouse.jdbc.v1=true} (or the {@code clickhouse.jdbc.v1} system
 * property is set), the V1 implementation is used. This class exposes that
 * choice via the {@code useV1Driver} constructor flag so the connector can
 * toggle drivers with a configuration parameter, without a rebuild.
 * </p>
 */
public class SinkConnectorDataSource extends ClickHouseDataSource {

    /**
     * The JDBC driver property that tells the ClickHouse V2 driver to silently
     * ignore unsupported JDBC calls (e.g. setAutoCommit(false), commit,
     * rollback) instead of throwing SQLFeatureNotSupportedException.
     */
    private static final String IGNORE_UNSUPPORTED_KEY = "jdbc_ignore_unsupported_values";

    /**
     * URL marker understood by {@code com.clickhouse.jdbc.ClickHouseDriver}
     * (0.7+): when present with value {@code true}, the legacy V1 driver
     * implementation is selected instead of the V2 default.
     */
    public static final String V1_DRIVER_MARKER = "clickhouse.jdbc.v1";

    /**
     * Constructs a new SinkConnectorDataSource with the specified URL and
     * properties, using the default (V2) driver implementation.
     * <p>
     * Automatically enables {@code jdbc_ignore_unsupported_values=true} so that
     * callers can use standard JDBC patterns (e.g. setAutoCommit) without
     * hitting SQLFeatureNotSupportedException on the ClickHouse driver.
     * </p>
     *
     * @param url the URL of the ClickHouse server to connect to.
     * @param properties the properties to configure the connection.
     * @throws SQLException if an error occurs while creating the data source.
     */
    public SinkConnectorDataSource(String url, Properties properties) throws SQLException {
        this(url, properties, false);
    }

    /**
     * Constructs a new SinkConnectorDataSource with an explicit driver choice.
     *
     * @param url the URL of the ClickHouse server to connect to.
     * @param properties the properties to configure the connection.
     * @param useV1Driver when true, route to the legacy V1 driver
     *                    implementation (0.6.x behavior) bundled in the
     *                    clickhouse-jdbc jar; when false, use the V2 driver.
     * @throws SQLException if an error occurs while creating the data source.
     */
    public SinkConnectorDataSource(String url, Properties properties, boolean useV1Driver)
            throws SQLException {
        super(applyDriverSelection(url, useV1Driver),
                useV1Driver ? nonNull(properties) : withIgnoreUnsupported(properties));
        this.jdbcUrl = applyDriverSelection(url, useV1Driver);
    }

    /**
     * The effective JDBC URL this data source connects to, including host and
     * port. The parent {@code ClickHouseDataSource} exposes no accessor for it,
     * and connection pools must be keyed by server — not just by database name
     * — so it is retained here.
     */
    private final String jdbcUrl;

    /**
     * Returns the effective JDBC URL (including host and port) this data source
     * connects to.
     *
     * @return the JDBC URL.
     */
    public String getJdbcUrl() {
        return jdbcUrl;
    }

    /**
     * Applies the driver-selection URL rewrite: appends the V1 marker when the
     * legacy driver is requested, or the ignore-unsupported-values flag for V2.
     * The V2-only {@code jdbc_ignore_unsupported_values} flag is intentionally
     * NOT added on the V1 path — the V1 driver ignores those calls natively and
     * warns on unknown URL parameters.
     */
    static String applyDriverSelection(String url, boolean useV1Driver) {
        if (useV1Driver) {
            return appendUrlParam(url, V1_DRIVER_MARKER + "=true");
        }
        return appendUrlParam(url, IGNORE_UNSUPPORTED_KEY + "=true");
    }

    private static String appendUrlParam(String url, String param) {
        String key = param.substring(0, param.indexOf('='));
        if (url != null && !url.contains(key)) {
            String separator = url.contains("?") ? "&" : "?";
            return url + separator + param;
        }
        return url;
    }

    private static Properties nonNull(Properties properties) {
        return properties == null ? new Properties() : properties;
    }

    private static Properties withIgnoreUnsupported(Properties properties) {
        properties = nonNull(properties);
        properties.putIfAbsent(IGNORE_UNSUPPORTED_KEY, "true");
        return properties;
    }

    /**
     * Returns a connection to the ClickHouse server.
     * <p>
     * This method overrides the getConnection method to allow custom connection logic.
     * In this case, it simply calls the parent class's getConnection method, but
     * it could be extended to use a custom HTTP client or implement additional logic
     * before returning the connection.
     * </p>
     *
     * @return a Connection object.
     * @throws SQLException if an error occurs while obtaining the connection.
     */
    @Override
    public Connection getConnection() throws SQLException {
        // Custom behavior can be added here if needed, for example, using a custom HTTP client.
        // System.out.println("Using custom HTTP client for ClickHouse!");
        return super.getConnection();
    }
}

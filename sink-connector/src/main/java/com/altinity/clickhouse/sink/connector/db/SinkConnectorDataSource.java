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
 */
public class SinkConnectorDataSource extends ClickHouseDataSource {

    /**
     * The JDBC driver property that tells the ClickHouse driver to silently
     * ignore unsupported JDBC calls (e.g. setAutoCommit(false), commit,
     * rollback) instead of throwing SQLFeatureNotSupportedException.
     */
    private static final String IGNORE_UNSUPPORTED_KEY = "jdbc_ignore_unsupported_values";

    /**
     * Constructs a new SinkConnectorDataSource with the specified URL and properties.
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
        super(ensureIgnoreUnsupported(url), withIgnoreUnsupported(properties));
    }

    private static String ensureIgnoreUnsupported(String url) {
        if (url != null && !url.contains(IGNORE_UNSUPPORTED_KEY)) {
            String separator = url.contains("?") ? "&" : "?";
            return url + separator + IGNORE_UNSUPPORTED_KEY + "=true";
        }
        return url;
    }

    private static Properties withIgnoreUnsupported(Properties properties) {
        if (properties == null) {
            properties = new Properties();
        }
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

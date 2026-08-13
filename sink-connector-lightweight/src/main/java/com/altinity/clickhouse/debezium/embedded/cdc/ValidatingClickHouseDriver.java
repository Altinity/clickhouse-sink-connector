package com.altinity.clickhouse.debezium.embedded.cdc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * A JDBC driver that delegates to the ClickHouse driver but refuses to hand
 * back a connection to a server that is not actually reachable.
 *
 * <p>Why this exists: Debezium's {@code RetriableConnection#executeWithRetry}
 * (debezium-storage-jdbc 3.1.3.Final) is a {@code while (true)} loop whose
 * {@code retry.max.attempts} / {@code wait.retry.delay.ms} bounds live
 * <em>only</em> on the reconnect branch, and that branch is guarded by
 * {@code isOpen()}:
 *
 * <pre>
 * while (true) {
 *     if (!isOpen()) {              // reconnect branch: bounded + sleeps
 *         ... if (attempt &gt;= maxRetryCount) throw e;
 *         attempt++; sleep(waitRetryDelay); continue;
 *     }
 *     try { return func.accept(conn); }
 *     catch (SQLException e) {      // statement branch: NO bound, NO sleep
 *         LOGGER.warn("Attempt {} to call '{}' failed.", attempt, name, e);
 *         close();
 *     }
 * }
 * </pre>
 *
 * <p>The legacy ClickHouse JDBC V1 driver throws "Connection refused" from
 * {@code getConnection()} when the server is down, so {@code isOpen()} is
 * false and the bounded reconnect branch runs. The V2 driver in
 * clickhouse-jdbc 0.9.8 instead returns a lazily-connected object that reports
 * {@code isClosed() == false}. {@code isOpen()} is then true on every
 * iteration, the reconnect branch is never entered, and the loop spins on the
 * statement branch: log a full stack trace, {@code close()}, repeat — with no
 * delay and without ever incrementing {@code attempt}.
 *
 * <p>Measured against Debezium's own class with a dead port and the V2 driver:
 * 10,468 failed calls in 25s and still running (a bounded 5-attempt loop with
 * 3s delays cannot exceed ~15s); the same probe on V1 terminated in 12.0s.
 * In CI this produced a 3.6 GB log and a six-hour hang, where the identical
 * job on the 2.10.0 base finishes in ~2h with a 6 MB log. The log's signature
 * is {@code Attempt 1 to call 'history storage exists' failed} repeated
 * 212,369 times — always attempt <em>1</em>, never 2.
 *
 * <p>This driver restores the V1 fail-fast contract that Debezium depends on,
 * by validating the connection before returning it. It is deliberately scoped:
 * it is registered under its own {@link #URL_PREFIX} scheme and applied only
 * to Debezium's two internal storage URLs
 * ({@code offset.storage.jdbc.url} and
 * {@code schema.history.internal.jdbc.url}). The sink's own data path is
 * untouched, and {@code com.clickhouse.jdbc.ClickHouseDriver#acceptsURL}
 * returns false for this scheme, so there is no ambiguity about which driver
 * {@link DriverManager} selects.
 */
public class ValidatingClickHouseDriver implements Driver {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ValidatingClickHouseDriver.class);

    /**
     * Scheme handled by this driver. Rewriting a {@code jdbc:clickhouse://}
     * URL to this prefix routes it here; the delegate sees the original.
     * Verified against clickhouse-jdbc 0.9.8: {@code ClickHouseDriver} accepts
     * {@code jdbc:clickhouse:} and {@code jdbc:ch:} but rejects this one, so
     * {@link DriverManager} cannot bypass the validation.
     */
    static final String URL_PREFIX = "jdbc:clickhouse-checked:";

    /**
     * The scheme this driver delegates to after stripping {@link #URL_PREFIX}.
     */
    static final String DELEGATE_PREFIX = "jdbc:clickhouse:";

    /**
     * Statement used to prove the server is actually serving. It must be
     * trivial: it runs once per connection attempt on the Debezium storage
     * path only.
     */
    private static final String VALIDATION_QUERY = "SELECT 1";

    private static volatile boolean registered;

    /**
     * Registers this driver with {@link DriverManager} exactly once.
     *
     * @throws SQLException if registration fails.
     */
    static synchronized void register() throws SQLException {
        if (!registered) {
            DriverManager.registerDriver(new ValidatingClickHouseDriver());
            registered = true;
        }
    }

    /**
     * Rewrites a ClickHouse JDBC URL so that it is handled by this driver.
     * Any other URL (including one already rewritten) is returned unchanged.
     *
     * @param url the JDBC URL, may be null.
     * @return the URL routed through this driver, or the input unchanged.
     */
    static String toCheckedUrl(String url) {
        if (url == null || url.startsWith(URL_PREFIX) || !url.startsWith(DELEGATE_PREFIX)) {
            return url;
        }
        return URL_PREFIX + url.substring(DELEGATE_PREFIX.length());
    }

    /**
     * Inverse of {@link #toCheckedUrl(String)}.
     *
     * @param url the JDBC URL, may be null.
     * @return the URL the ClickHouse driver expects.
     */
    static String toDelegateUrl(String url) {
        if (url == null || !url.startsWith(URL_PREFIX)) {
            return url;
        }
        return DELEGATE_PREFIX + url.substring(URL_PREFIX.length());
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(URL_PREFIX);
    }

    /**
     * Opens a connection through the ClickHouse driver and returns it only if
     * the server answers {@value #VALIDATION_QUERY}. When it does not, the
     * half-open handle is closed and an exception is thrown, so that Debezium
     * sees a closed connection and takes its bounded reconnect path.
     *
     * @param url  the JDBC URL.
     * @param info the connection properties.
     * @return a validated, usable connection, or null when the URL is not ours.
     * @throws SQLException when the server is not reachable.
     */
    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        Connection connection = DriverManager.getConnection(toDelegateUrl(url), info);
        try (Statement statement = connection.createStatement()) {
            statement.execute(VALIDATION_QUERY);
        } catch (SQLException e) {
            closeQuietly(connection);
            log.warn("ClickHouse at {} did not answer the connection validation query; "
                    + "reporting the connection as unavailable so the caller can retry",
                    toDelegateUrl(url));
            throw e;
        } catch (RuntimeException e) {
            closeQuietly(connection);
            throw new SQLException("Connection validation failed for " + toDelegateUrl(url), e);
        }
        return connection;
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            log.debug("Ignoring failure while closing an unusable connection", e);
        }
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        Driver delegate = DriverManager.getDriver(toDelegateUrl(url));
        return delegate.getPropertyInfo(toDelegateUrl(url), info);
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger(ValidatingClickHouseDriver.class.getName());
    }
}

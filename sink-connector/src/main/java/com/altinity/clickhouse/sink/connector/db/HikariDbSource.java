package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.common.Metrics;
import com.clickhouse.jdbc.ClickHouseDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

// Singleton class(one per database)
/**
 * Singleton class for managing HikariCP connection pools.
 * <p>
 * This class maintains a map of HikariDataSource instances, one for each
 * database. It provides methods to create, retrieve, and close connection
 * pools, and to obtain JDBC connections.
 * </p>
 */
public class HikariDbSource {

    /**
     * Map of database name to HikariDataSource instance.
     */
    private static Map<String, HikariDataSource> instance = new HashMap<>();

    /**
     * Map of database name to Connection.
     */
    private static Map<String, Connection> connectionPool = new HashMap<>();

    // private static HikariDbSource instance;

    /**
     * Flag to disable connection pooling.
     */
    private static boolean disabled = false;

    /**
     * Logger instance for logging events in HikariDbSource.
     */
    private static final Logger log =
            LogManager.getLogger(HikariDbSource.class);
    // private HikariDataSource dataSource;

    /**
     * The name of the database.
     */
    private String databaseName;

    /**
     * Private constructor.
     *
     * @param dataSource   ClickHouseDataSource instance.
     * @param databaseName name of the database.
     */
    private HikariDbSource(ClickHouseDataSource dataSource,
                           String databaseName) {
        // this.createConnectionPool(dataSource, databaseName);
    }

    /**
     * Initiates a new connection if the connection pool is open.
     *
     * @param databaseName name of the database.
     * @return a JDBC Connection.
     * @throws SQLException if a database error occurs.
     */
    public static Connection initiateNewConnectionIfClosed(
            String databaseName) throws SQLException {

        if (disabled) {
            return null;
        }
        HikariDataSource dbSource = instance.get(databaseName);
        if (dbSource == null) {
            // No dedicated pool for this database; fall back to the system pool
            // which is always seeded first by BaseDbWriter.createConnection.
            dbSource = instance.get(BaseDbWriter.SYSTEM_DB);
            if (dbSource == null) {
                // Neither a dedicated pool nor the system fallback exists. This
                // happens when the initial connection to ClickHouse failed, so no
                // pool was ever created. Surface it as a SQLException (message
                // includes the database name) so callers' retry logic runs instead
                // of hitting a NullPointerException.
                throw new SQLException(
                        "No connection pool exists for database: " + databaseName);
            }
            log.debug("No pool for database '{}', using '{}' fallback pool.",
                    databaseName, BaseDbWriter.SYSTEM_DB);
        }
        if (dbSource.isClosed()) {
            // A pool that has been shut down can never hand out a usable
            // connection again; every getConnection() on it throws. Drop it so
            // the caller's retry can rebuild one instead of failing forever.
            instance.values().remove(dbSource);
            throw new SQLException(
                    "Connection pool is closed for database: " + databaseName);
        }
        HikariDbSource.printConnectionInfo();
        return dbSource.getConnection();
    }

    /**
     * Returns the HikariDataSource instance for the given database.
     * <p>
     * If an instance does not exist, a new connection pool is created.
     * </p>
     *
     * @param dataSource   SinkConnectorDataSource instance.
     * @param databaseName name of the database.
     * @param config       connector configuration.
     * @return the HikariDataSource instance.
     */
    public static HikariDataSource getInstance(
            SinkConnectorDataSource dataSource, String databaseName,
            ClickHouseSinkConnectorConfig config, String userName,
            String password) {

        disabled = config.getBoolean(
                ClickHouseSinkConnectorConfigVariables
                        .CONNECTION_POOL_DISABLE.toString());
        HikariDataSource cached = instance.get(databaseName);
        if (cached != null && servesSameServer(cached, dataSource)) {
            return cached;
        }
        if (cached != null) {
            // The cached pool points at a DIFFERENT ClickHouse server than the
            // caller asked for. Handing it back silently routes queries to the
            // wrong (or a no-longer-running) server; the symptom is
            // "Connect to http://<other-host> failed: Connection refused" from
            // a caller that supplied a perfectly good URL. Replace it.
            log.warn("Replacing pooled connection for database '{}': it was created for a "
                    + "different server ({} != {})", databaseName,
                    poolTargetUrl(cached), dataSource.getJdbcUrl());
            try {
                cached.close();
            } catch (Exception e) {
                log.warn("Error closing the superseded connection pool for database '{}'",
                        databaseName, e);
            }
        }
        HikariDataSource hikariDataSource = createConnectionPool(
                dataSource, databaseName, config, userName, password);
        instance.put(databaseName, hikariDataSource);
        return hikariDataSource;
    }

    /**
     * Returns the JDBC URL the given pool's underlying data source targets, or
     * null when it cannot be determined.
     *
     * @param pool the pool to inspect.
     * @return the target JDBC URL, or null.
     */
    static String poolTargetUrl(HikariDataSource pool) {
        if (pool == null) {
            return null;
        }
        javax.sql.DataSource underlying = pool.getDataSource();
        if (underlying instanceof SinkConnectorDataSource) {
            return ((SinkConnectorDataSource) underlying).getJdbcUrl();
        }
        return null;
    }

    /**
     * Returns true when the cached pool already targets the same server as the
     * requested data source.
     * <p>
     * Pools are keyed by database name alone, but the same database name can be
     * requested against different servers within one JVM (tests using
     * throwaway containers, a reconfigured endpoint, a failover). Reusing a
     * pool across servers sends queries to the wrong host, so the target URL is
     * compared before a cached pool is reused. When either URL is unknown the
     * pool is considered a match, preserving the previous behavior rather than
     * churning pools on an unexpected data-source type.
     *
     * @param cached    the pool already cached for this database name.
     * @param requested the data source the caller wants a pool for.
     * @return true when the cached pool can be reused.
     */
    static boolean servesSameServer(HikariDataSource cached,
                                    SinkConnectorDataSource requested) {
        String cachedUrl = poolTargetUrl(cached);
        String requestedUrl = requested == null ? null : requested.getJdbcUrl();
        if (cachedUrl == null || requestedUrl == null) {
            return true;
        }
        return cachedUrl.equals(requestedUrl);
    }

    /**
     * Returns the HikariDataSource instance for the given database.
     *
     * @param databaseName name of the database.
     * @return the HikariDataSource instance.
     */
    public static HikariDataSource getInstance(String databaseName) {
        return instance.get(databaseName);
    }

    /**
     * Creates a connection pool for the given data source.
     * <p>
     * It sets the pool name, JDBC URL, driver, connection timeout, max pool
     * size, max lifetime, and attaches the data source. Optionally, it sets
     * a metric registry.
     * </p>
     *
     * @param chDataSource ClickHouse data source.
     * @param databaseName name of the database.
     * @param config       connector configuration.
     * @return a new HikariDataSource instance.
     */
    private static HikariDataSource createConnectionPool(
            SinkConnectorDataSource chDataSource, String databaseName,
            ClickHouseSinkConnectorConfig config, String userName,
            String password) {

        // pass the clickhouse config to create the datasource
        int maxPoolSize = config.getInt(
                ClickHouseSinkConnectorConfigVariables
                        .CONNECTION_POOL_MAX_SIZE.toString());
        long poolConnectionTimeout = config.getLong(
                ClickHouseSinkConnectorConfigVariables
                        .CONNECTION_POOL_TIMEOUT.toString());
        int minIdle = config.getInt(
                ClickHouseSinkConnectorConfigVariables
                        .CONNECTION_POOL_MIN_IDLE.toString());
        long maxLifetime = config.getLong(
                ClickHouseSinkConnectorConfigVariables
                        .CONNECTION_POOL_MAX_LIFETIME.toString());

        HikariConfig poolConfig = new HikariConfig();
        poolConfig.setPoolName("clickhouse" + "-" + databaseName);
        // The pool is seeded with a SinkConnectorDataSource that already carries the
        // real jdbc:clickhouse://host:port/db URL and properties. Do NOT also call
        // setJdbcUrl/setDriverClassName: when a DataSource is provided, HikariCP uses
        // it directly. The previous template "jdbc:ch:{hostname}:{port}/..." was a
        // leftover that the clickhouse-jdbc 0.9.x v2 driver does not expand, causing
        // connections to fall back to localhost:8123.
        poolConfig.setConnectionTimeout(poolConnectionTimeout);
        poolConfig.setMaximumPoolSize(maxPoolSize);
        // poolConfig.setMinimumIdle(minIdle);
        // poolConfig.setIdleTimeout(2_000L);
        poolConfig.setMaxLifetime(maxLifetime);
        poolConfig.setDataSource(chDataSource);
        if (userName != null && !userName.isEmpty()) {
            poolConfig.setUsername(userName);
        }
        if (password != null) {
            poolConfig.setPassword(password);
        }

        HikariDataSource dataSource = new HikariDataSource(poolConfig);

        PrometheusMeterRegistry meterRegistry = Metrics.meterRegistry();

        if (meterRegistry != null) {
            dataSource.setMetricRegistry(meterRegistry);
        }
        return dataSource;
    }

    /**
     * Closes all HikariDataSource instances and clears the pool.
     */
    public static void close() {

        if (instance != null) {
            for (HikariDataSource hikariDataSource : instance.values()) {
                try {
                    hikariDataSource.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            instance.clear();
        }
    }

    /**
     * Closes the connection pool for the specified database.
     *
     * @param databaseName name of the database.
     */
    public static void closeDatabaseConnection(String databaseName) {
        if (instance.containsKey(databaseName)) {
            try {
                instance.get(databaseName).close();
            } catch (Exception e) {
                e.printStackTrace();
                log.error("Error closing database connection pool", e);
            }
        }
    }

    /**
     * Prints connection pool information for all databases.
     * <p>
     * It logs the pool name, maximum pool size, and the number of active
     * connections.
     * </p>
     */
    public static void printConnectionInfo() {
        for (HikariDataSource hikariDataSource : instance.values()) {
            log.debug("Connection Pool Info: " +
                    hikariDataSource.getPoolName() +
                    " Max Size: " +
                    hikariDataSource.getMaximumPoolSize() +
                    " Active Connections: " +
                    hikariDataSource.getHikariPoolMXBean()
                            .getActiveConnections());
        }
    }
}

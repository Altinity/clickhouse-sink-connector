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
            // No pool exists for the database.
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
            ClickHouseSinkConnectorConfig config) {

        disabled = config.getBoolean(
                ClickHouseSinkConnectorConfigVariables
                        .CONNECTION_POOL_DISABLE.toString());
        if (instance.containsKey(databaseName)) {
            return instance.get(databaseName);
        } else {
            HikariDataSource hikariDataSource = createConnectionPool(
                    dataSource, databaseName, config);
            instance.put(databaseName, hikariDataSource);
        }
        return instance.get(databaseName);
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
            ClickHouseSinkConnectorConfig config) {

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
        String jdbcUrl = String.format(
                "jdbc:ch:{hostname}:{port}/%s?insert_quorum=auto&server_time_zone" +
                        "&http_connection_provider=HTTP_URL_CONNECTION&server_version=" +
                        "22.13.1.24495", databaseName);
        poolConfig.setJdbcUrl(jdbcUrl);
        poolConfig.setDriverClassName(
                "com.clickhouse.jdbc.ClickHouseDriver"); // Ensure driver is set
        // poolConfig.setUsername(dataSource.getConnection().getCurrentUser());
        // Optional, if already in JDBC URL
        // poolConfig.setPassword(dataSource.getConnection().());
        // Optional, if already in JDBC URL
        poolConfig.setConnectionTimeout(poolConnectionTimeout);
        poolConfig.setMaximumPoolSize(maxPoolSize);
        // poolConfig.setMinimumIdle(minIdle);
        // poolConfig.setIdleTimeout(2_000L);
        poolConfig.setMaxLifetime(maxLifetime);
        poolConfig.setDataSource(chDataSource);

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

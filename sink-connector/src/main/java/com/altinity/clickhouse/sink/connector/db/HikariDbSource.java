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
     * Map of pool key to HikariDataSource instance.
     * <p>
     * The key is {@code host:port|database} — see {@link #poolKey}. Keying by
     * database name ALONE is not enough: more than one server can be addressed
     * under the same database name at the same time, and a name-keyed cache
     * holds only one of them, so each caller destroys the other's pool.
     * Including the server in the key lets concurrent targets coexist.
     */
    private static Map<String, HikariDataSource> instance = new HashMap<>();

    /**
     * Map of database name to the pool key most recently requested for it.
     * <p>
     * {@link #getInstance} is the only place a caller states which server it
     * wants; {@link #initiateNewConnectionIfClosed} receives nothing but a
     * database name. This pointer lets that method resolve the pool the most
     * recent caller actually asked for.
     */
    private static Map<String, String> currentKey = new HashMap<>();

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
        String key = currentKey.get(databaseName);
        HikariDataSource dbSource = key == null ? null : instance.get(key);
        if (dbSource == null) {
            // No pool for this database; fall back to the system pool, which is
            // always seeded first by BaseDbWriter.createConnection.
            key = currentKey.get(BaseDbWriter.SYSTEM_DB);
            dbSource = key == null ? null : instance.get(key);
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
            dropPool(key);
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
        // The pool is identified by server AND database, so a request for a
        // different server does not disturb the pool already serving this
        // database name on another server. Record which key this database name
        // most recently resolved to, for the retry path that only gets a name.
        String key = poolKey(
                dataSource == null ? null : dataSource.getJdbcUrl(), databaseName);
        currentKey.put(databaseName, key);

        HikariDataSource cached = instance.get(key);
        if (cached != null && !cached.isClosed()) {
            return cached;
        }
        HikariDataSource hikariDataSource = createConnectionPool(
                dataSource, databaseName, config, userName, password);
        instance.put(key, hikariDataSource);
        return hikariDataSource;
    }

    /**
     * Builds the cache key identifying a pool: the target server endpoint and
     * the database name.
     * <p>
     * Keying by database name alone cannot represent the state that actually
     * occurs — two servers addressed under the same database name at the same
     * time. With one slot per name the two requests evict each other in turn,
     * and each eviction closes a pool another live caller is still using, so
     * both sides see
     * "Connect to http://&lt;the other server&gt; failed: Connection refused"
     * while both servers are up. Giving each (server, database) pair its own
     * slot lets them coexist, and makes eviction-on-mismatch unnecessary.
     * <p>
     * When the endpoint cannot be determined the database name is used alone,
     * preserving the previous behaviour for URL shapes this cannot parse.
     *
     * @param jdbcUrl      the target JDBC URL; may be null.
     * @param databaseName the database name.
     * @return the cache key.
     */
    static String poolKey(String jdbcUrl, String databaseName) {
        String endpoint = serverEndpoint(jdbcUrl);
        return endpoint == null ? databaseName : endpoint + "|" + databaseName;
    }

    /**
     * Removes a pool from the cache, along with any database name still
     * pointing at it.
     *
     * @param key the cache key to forget.
     */
    private static void dropPool(String key) {
        instance.remove(key);
        currentKey.values().removeIf(k -> k.equals(key));
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
     * Retained for callers that need to compare a pool against a data source
     * directly. {@link #getInstance} no longer needs it: the server endpoint is
     * part of the cache key (see {@link #poolKey}), so a lookup can only ever
     * return a pool for the requested server.
     * <p>
     * Only the server endpoint (host:port) participates in the comparison — NOT
     * the database path or the query string. Callers legitimately create a pool
     * under one pool key while pointing the URL at another database: e.g.
     * {@code createConnection(".../employees", ..., databaseName="system")} in
     * ITCommon.getDBWriter and ClickHouseBatchRunnable. Treating that as a
     * server change would make a pool registered as 'system' resolve
     * unqualified names against 'employees' — the
     * "Code: 81 ... Database employees does not exist" failures in
     * DatabaseOverrideInitialIT and ~40 sibling ITs. When either URL is
     * unknown the pool is considered a match.
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
        String cachedServer = serverEndpoint(cachedUrl);
        String requestedServer = serverEndpoint(requestedUrl);
        if (cachedServer == null || requestedServer == null) {
            return true;
        }
        return cachedServer.equals(requestedServer);
    }

    /**
     * Extracts the {@code host:port} endpoint from a ClickHouse JDBC URL,
     * discarding the database path and any query parameters.
     * <p>
     * Handles the shapes the connector emits, e.g.
     * {@code jdbc:clickhouse://host:8123/db?k=v},
     * {@code jdbc:ch://host:8123/db} and
     * {@code jdbc:clickhouse-checked://host:8123/db}. Returns null when the
     * endpoint cannot be determined, which callers treat as "assume a match"
     * so an unparseable URL never churns a working pool.
     *
     * @param url the JDBC URL to inspect.
     * @return the {@code host:port} portion, or null.
     */
    static String serverEndpoint(String url) {
        if (url == null) {
            return null;
        }
        int schemeEnd = url.indexOf("//");
        if (schemeEnd < 0) {
            return null;
        }
        String rest = url.substring(schemeEnd + 2);
        // The authority ends at the first '/', '?' or '#'.
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        String authority = rest.substring(0, end);
        return authority.isEmpty() ? null : authority;
    }

    /**
     * Returns the pool currently serving the given database, or null when
     * there is none.
     *
     * @param databaseName name of the database.
     * @return the HikariDataSource instance, or null.
     */
    public static HikariDataSource getInstance(String databaseName) {
        String key = currentKey.get(databaseName);
        return key == null ? null : instance.get(key);
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
        // These point at pools that no longer exist.
        currentKey.clear();
    }

    /**
     * Closes the connection pool for the specified database.
     *
     * @param databaseName name of the database.
     */
    public static void closeDatabaseConnection(String databaseName) {
        HikariDataSource pool = getInstance(databaseName);
        if (pool != null) {
            try {
                pool.close();
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

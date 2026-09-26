package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseCreateDatabase;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Properties;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * BaseDbWriter is a base class for writing data to a ClickHouse
 * database. It provides methods for connection handling, creating
 * destination databases, and utility functions such as splitting
 * JDBC properties.
 */
public class BaseDbWriter {

    /**
     * Base delay in milliseconds used for retry attempts. The actual delay
     * for a retry is computed as <code>maxRetries * RETRY_DELAY_MS</code>.
     */
    private static final int RETRY_DELAY_MS = 5000;

    /**
     * The name of the database client.
     */
    public static final String DATABASE_CLIENT_NAME = "Sink_Connector";

    /**
     * The system database name.
     */
    public static final String SYSTEM_DB = "system";

    /**
     * The active database connection.
     */
    protected Connection conn;

    /**
     * The hostname of the database server.
     */
    private String hostName;

    /**
     * The port number of the database server.
     */
    private Integer port;

    /**
     * The name of the database.
     */
    protected String database;

    /**
     * The username for the database connection.
     */
    private String userName;

    /**
     * The password for the database connection.
     */
    private String password;

    /**
     * The server time zone derived from the database.
     */
    private ZoneId serverTimeZone;

    /**
     * The ClickHouse sink connector configuration.
     */
    private ClickHouseSinkConnectorConfig config;

    /**
     * Logger instance for BaseDbWriter.
     */
    private static final Logger log =
            LogManager.getLogger(BaseDbWriter.class);

    /**
     * Constructs a BaseDbWriter instance with the provided parameters.
     *
     * @param hostName the host name of the database server
     * @param port the port number of the database server
     * @param database the name of the database
     * @param userName the username for the database connection
     * @param password the password for the database connection
     * @param config the ClickHouse sink connector configuration
     * @param conn an existing Connection object; if null, a new one may be
     *             created later
     */
    public BaseDbWriter(String hostName, Integer port, String database,
                        String userName, String password,
                        ClickHouseSinkConnectorConfig config, Connection conn) {
        this.hostName = hostName;
        this.port = port;
        this.database = database;
        this.userName = userName;
        this.password = password;
        this.config = config;
        this.conn = conn;
        // Initialize the server time zone from the database metadata.
        this.serverTimeZone = new DBMetadata(config).getServerTimeZone(this.conn);
    }

    /**
     * Creates the destination database if it does not already exist.
     *
     * @param databaseName the name of the destination database to create
     * @param useOnCluster whether to execute the operation on the cluster
     * @throws RuntimeException if the database creation fails after the
     *         maximum number of retries
     */
    protected void createDestinationDatabase(String databaseName, Boolean useOnCluster, ClickHouseSinkConnectorConfig config) {
        DBMetadata metadata = new DBMetadata(config);
        try {
            if (!metadata.checkIfDatabaseExists(this.conn, databaseName)) {
                new ClickHouseCreateDatabase()
                        .createNewDatabase(this.conn, databaseName, useOnCluster, this.config);
            }
        } catch (Exception e) {
            int maxRetries = 0;
            // Get max retries from configuration, default to 10
            int MAX_RETRIES = 10;
            try {
                if (config.getInt(ClickHouseSinkConnectorConfigVariables.ERRORS_MAX_RETRIES.toString()) != null) {
                    MAX_RETRIES = config.getInt(ClickHouseSinkConnectorConfigVariables.ERRORS_MAX_RETRIES.toString());
                }
            } catch (Exception ex) {
                log.warn("Error retrieving errors.max.retries configuration, using default: " + MAX_RETRIES);
            }
            
            log.error("Error creating Database: " + databaseName);

            // Retry creating the database until max retries is reached.
            boolean createDatabaseFailed = false;
            while (maxRetries++ < MAX_RETRIES) {
                try {
                    Thread.sleep(maxRetries * RETRY_DELAY_MS);
                    if (!metadata.checkIfDatabaseExists(this.conn,
                            databaseName)) {
                        new ClickHouseCreateDatabase()
                                .createNewDatabase(this.conn, databaseName, useOnCluster, this.config);
                        createDatabaseFailed = true;
                        break;
                    }
                } catch (Exception ex) {
                    log.error("Retry Number: " + maxRetries + " of "
                            + MAX_RETRIES + "  Error creating Database: "
                            + databaseName);
                }
            }
            // If database creation still fails, throw a runtime exception.
            if (!createDatabaseFailed) {
                throw new RuntimeException("Error creating Database: "
                        + databaseName, e);
            }
        }
    }

    /**
     * ClickHouse setting that, at its server default of {@code 1}, silently
     * replaces a NULL inserted into a non-Nullable column with the column's
     * DEFAULT expression. The connector binds source NULLs explicitly (Spec
     * 07.07 section 3.2), which protects nothing if the server then swaps in
     * the DEFAULT and reports success; measured with clickhouse local 24.8.14
     * on {@code v Int32 DEFAULT 7}: the row stored {@code 7}. With the setting
     * at {@code 0} the same insert is rejected (Code 53 TYPE_MISMATCH), so the
     * batch fails loudly and the ClickHouse column can be corrected.
     */
    static final String NULL_AS_DEFAULT_SETTING = "input_format_null_as_default";

    /**
     * The ClickHouse session settings every connection is opened with when
     * {@code clickhouse.jdbc.settings} is not configured (Spec 07.07 section 3.2.1).
     */
    static final String DEFAULT_CUSTOM_SETTINGS = NULL_AS_DEFAULT_SETTING + "=0,"
            + "allow_experimental_object_type=1,insert_allow_materialized_columns=1";

    /**
     * Resolves the {@code custom_settings} JDBC property from the user's
     * {@code clickhouse.jdbc.settings} value.
     *
     * <p>A user list is used verbatim, with {@code input_format_null_as_default=0}
     * appended when the list does not mention that key: configuring other
     * session settings must not silently re-enable DEFAULT substitution. A
     * user who sets the key explicitly, to either value, is honoured -- the
     * choice is then visible in the configuration rather than silent.</p>
     *
     * @param userJdbcSettings the configured {@code clickhouse.jdbc.settings},
     *                         may be null or empty
     * @return the comma-separated settings list to open connections with
     */
    static String customSettings(String userJdbcSettings) {
        if (userJdbcSettings == null || userJdbcSettings.isEmpty()) {
            return DEFAULT_CUSTOM_SETTINGS;
        }
        boolean mentionsNullAsDefault = Arrays.stream(userJdbcSettings.split(","))
                .anyMatch(s -> s.trim().startsWith(NULL_AS_DEFAULT_SETTING));
        if (mentionsNullAsDefault) {
            return userJdbcSettings;
        }
        log.info("{} does not set {}; appending {}=0 so a source NULL bound for a "
                        + "non-Nullable column is rejected rather than replaced by the column DEFAULT",
                ClickHouseSinkConnectorConfigVariables.JDBC_SETTINGS, NULL_AS_DEFAULT_SETTING,
                NULL_AS_DEFAULT_SETTING);
        return userJdbcSettings + "," + NULL_AS_DEFAULT_SETTING + "=0";
    }

    /**
     * Splits a JDBC properties string into a Properties object.
     * The input string should be in the format:
     * "key1=value1,key2=value2,..."
     *
     * @param jdbcProperties the JDBC properties string
     * @return a Properties object populated with the key-value pairs from
     *         the input string
     */
    public static Properties splitJdbcProperties(String jdbcProperties) {
        String[] splitProperties = jdbcProperties.split(",");
        Properties properties = new Properties();
        Arrays.stream(splitProperties).forEach(property -> {
            String[] keyValue = property.split("=");
            properties.setProperty(keyValue[0], keyValue[1]);
        });
        return properties;
    }

    /**
     * Connection properties understood by the legacy V1 ClickHouse JDBC driver
     * that the V2 driver rejects outright.
     * <p>
     * The V2 client validates the whole property set up front and throws
     * {@code ClientMisconfigurationException: Unknown and unmapped config
     * properties: [...]} for anything it does not recognise, which
     * {@code createConnection()} surfaces as "Failed to create connection".
     * Because these values arrive from the user-facing
     * {@code clickhouse.jdbc.params} setting, an existing deployment that
     * upgrades to a build using the V2 driver would fail to connect at all
     * until it edited its configuration.
     * <p>
     * Determined empirically against clickhouse-jdbc 0.9.8 by offering each
     * property from the shipped docker configuration to the V2 driver
     * individually: {@code socket_timeout}, {@code connection_timeout},
     * {@code client_name}, {@code custom_settings},
     * {@code http_connection_provider} and
     * {@code jdbc_ignore_unsupported_values} are all accepted; only the two
     * below are rejected.
     */
    static final String[] V1_ONLY_PROPERTIES = {
            "keepalive.timeout",
            "max_buffer_size",
    };

    /**
     * Property keys whose removal has already been reported at WARN in this
     * JVM. {@link #dropV1OnlyProperties} runs on EVERY {@code createConnection()}
     * call -- with a worker pool, several times a minute for the life of the
     * process -- and one WARN per call is noise that hides real warnings. The
     * property is still removed every time; only the first report is a WARN.
     */
    private static final java.util.Set<String> WARNED_V1_ONLY_PROPERTIES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Removes V1-only connection properties when the V2 driver is in use, so
     * that a configuration written for the legacy driver keeps working instead
     * of failing the connection outright. Each removal is logged: at WARN the
     * first time a key is seen in this process, at DEBUG afterwards.
     * <p>
     * Nothing is dropped on the V1 path — the legacy driver still receives and
     * honours these properties.
     *
     * @param properties the connection properties, modified in place.
     * @return the number of properties removed.
     */
    static int dropV1OnlyProperties(Properties properties) {
        int removed = 0;
        for (String key : V1_ONLY_PROPERTIES) {
            if (properties.remove(key) != null) {
                removed++;
                if (WARNED_V1_ONLY_PROPERTIES.add(key)) {
                    log.warn("Ignoring JDBC property '{}': it is only supported by the legacy "
                            + "ClickHouse JDBC V1 driver and the V2 driver rejects the "
                            + "connection outright when it is present. Set "
                            + "clickhouse.jdbc.v1=true to keep using the legacy driver.", key);
                } else {
                    log.debug("Ignoring JDBC property '{}' (V1-only; already reported once "
                            + "for this process).", key);
                }
            }
        }
        return removed;
    }

    /**
     * Retrieves the current database connection, replacing it when it is null
     * or has been closed.
     * <p>
     * Checking only for null was not enough: a pooled connection that has been
     * closed (returned to / evicted by the pool, or reaped after an idle
     * period) is a non-null object whose every subsequent use throws
     * {@code SQLException: Connection is closed}. Callers hold the writer for
     * the lifetime of the task and re-fetch through this method precisely so
     * that a stale handle gets replaced, so the closed case has to be handled
     * here too.
     *
     * @return a Connection object representing the current database
     *         connection
     */
    public Connection getConnection() {
        HikariDbSource.printConnectionInfo();
        if (isUnusable(this.conn)) {
            try {
                // Pass this writer's own server, not just the database name: a
                // name is not unique across concurrently addressed servers, so
                // resolving by name alone can return another writer's pool.
                this.conn = HikariDbSource.initiateNewConnectionIfClosed(
                        this.database, this.jdbcUrl());
            } catch (Exception e) {
                log.error("Error retrieving new connection in getConnection");
            }
        }
        return this.conn;
    }

    /**
     * The connection this writer holds RIGHT NOW, without re-acquiring one:
     * {@code null} or a closed handle is returned as-is.
     * <p>
     * For the owner's cleanup only. {@link #getConnection()} replaces an
     * unusable handle with a fresh pool checkout, which is the wrong thing to
     * do while shutting a worker down: it would open a connection in order to
     * close it, and against a pool that is already gone it would log an error
     * for nothing. The handle returned here may be one {@link #getConnection()}
     * re-acquired after the original became unusable, which the worker's
     * per-database map never saw -- that is exactly the one the owner has to
     * close (spec 01.01 §3.3 step 4a).
     *
     * @return the current connection, possibly null or closed.
     */
    public Connection heldConnection() {
        return this.conn;
    }

    /**
     * Returns the JDBC URL identifying the server this writer targets, or null
     * when the host and port are unknown.
     *
     * @return the JDBC URL, or null.
     */
    String jdbcUrl() {
        if (this.hostName == null || this.port == null) {
            return null;
        }
        return getConnectionString(this.hostName, this.port, this.database);
    }

    /**
     * Returns true when the supplied connection cannot be used for a new
     * statement, i.e. it is null or already closed. A driver that throws while
     * answering {@link Connection#isClosed()} is treated as unusable as well,
     * since nothing can be done with such a handle either.
     *
     * @param connection the connection to inspect; may be null.
     * @return true when a replacement connection should be obtained.
     */
    public static boolean isUnusable(Connection connection) {
        if (connection == null) {
            return true;
        }
        try {
            return connection.isClosed();
        } catch (SQLException e) {
            log.warn("Could not determine connection state, treating it as closed", e);
            return true;
        }
    }

    /**
     * Constructs a JDBC connection string using the provided host, port, and
     * database.
     *
     * @param hostName the host or IP address of the database server
     * @param port the port number of the database server
     * @param database the name of the database
     * @return a JDBC connection string in the format
     *         "jdbc:clickhouse://host:port/database"
     */
    public static String getConnectionString(String hostName, Integer port,
                                             String database) {
        return String.format("jdbc:clickhouse://%s:%s/%s", hostName, port,
                database);
    }

    /**
     * Creates a new connection to ClickHouse using the given JDBC URL,
     * client name, username, password, and database name. Additional JDBC
     * parameters are merged from the configuration.
     *
     * @param url the JDBC URL (e.g., jdbc:clickhouse://host/database)
     * @param clientName the name of the client
     * @param userName the username for the database connection
     * @param password the password for the database connection
     * @param databaseName the name of the database
     * @param config the ClickHouse sink connector configuration
     * @return a Connection object to ClickHouse, or null if an error occurs
     */
    public static Connection createConnection(String url, String clientName,
                                              String userName, String password,
                                              String databaseName,
                                              ClickHouseSinkConnectorConfig config) {
        String jdbcParams = "";
        String jdbcSettings = "";
        
        Connection conn = null;

        if(config != null) {
            jdbcParams = config.getString(ClickHouseSinkConnectorConfigVariables.JDBC_PARAMETERS.toString());
            jdbcSettings = config.getString(ClickHouseSinkConnectorConfigVariables.JDBC_SETTINGS.toString());
        }
        try {
            Properties properties = new Properties();
            properties.setProperty("client_name", clientName);
            properties.setProperty("custom_settings", customSettings(jdbcSettings));
            boolean connectionPoolDisable = config.getBoolean(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE.toString());
            // Set the http connection provider to HTTP_URL_CONNECTION if connection pool is enabled.
            if(!connectionPoolDisable) {
                properties.setProperty("http_connection_provider", "HTTP_URL_CONNECTION");
            }
            if (jdbcParams != null && !jdbcParams.isEmpty()) {
                log.info("**** JDBC PARAMS from configuration:" + jdbcParams);
                Properties userProps = splitJdbcProperties(jdbcParams);
                properties.putAll(userProps);
            }

            boolean useV1Driver = config.getBoolean(
                    ClickHouseSinkConnectorConfigVariables.JDBC_V1_DRIVER.toString());
            if (useV1Driver) {
                log.info("clickhouse.jdbc.v1=true — using legacy ClickHouse JDBC V1 driver");
            } else {
                dropV1OnlyProperties(properties);
            }
            SinkConnectorDataSource dataSource =
                    new SinkConnectorDataSource(url, properties, useV1Driver);

            if (connectionPoolDisable) {
                log.info("Connection pool is disabled, creating a new connection");
                conn = dataSource.getConnection(userName, password);
            } else {
                HikariDataSource hikariDbSource = HikariDbSource.getInstance(
                        dataSource, databaseName, config, userName, password);
                conn = hikariDbSource.getConnection();
            }
        } catch (Exception e) {
            log.error("Error creating ClickHouse connection" + e);
        }
        return conn;
    }
}

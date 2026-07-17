package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseCreateDatabase;

import java.net.URLEncoder;
import java.sql.Connection;
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
     * Retrieves the current database connection. If the connection is null,
     * a new connection is initiated.
     *
     * @return a Connection object representing the current database
     *         connection
     */
    public Connection getConnection() {
        HikariDbSource.printConnectionInfo();
        if (this.conn == null) {
            try {
                this.conn = HikariDbSource
                        .initiateNewConnectionIfClosed(this.database);
            } catch (Exception e) {
                log.error("Error retrieving new connection in getConnection");
            }
        }
        return this.conn;
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
            if(jdbcSettings != null && !jdbcSettings.isEmpty()) {
                properties.setProperty("custom_settings", jdbcSettings);
            } else {
                properties.setProperty("custom_settings", "allow_experimental_object_type=1,insert_allow_materialized_columns=1");
            }
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
            String encodedUserName = null;
            String encodedPassword = null;

            // if username is empty don't encode it
            if(userName != null && !userName.isEmpty()) {
                encodedUserName = URLEncoder.encode(userName, "UTF-8");
            }
            if(password != null && !password.isEmpty()) {
                encodedPassword = URLEncoder.encode(password, "UTF-8");
            }
            if(encodedUserName != null && encodedPassword != null) {
                url = url + "?user=" + encodedUserName + "&password=" + encodedPassword;
            }
            
            SinkConnectorDataSource dataSource =
                    new SinkConnectorDataSource(url, properties);

            if (connectionPoolDisable) {
                log.info("Connection pool is disabled, creating a new connection");
                conn = dataSource.getConnection();
            } else {
                HikariDataSource hikariDbSource = HikariDbSource.getInstance(
                        dataSource, databaseName, config);
                conn = hikariDbSource.getConnection();
            }
        } catch (Exception e) {
            log.error("Error creating ClickHouse connection" + e);
        }
        return conn;
    }
}

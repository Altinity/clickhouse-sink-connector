package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseCreateDatabase;

import java.sql.Connection;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Properties;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class BaseDbWriter {

    public static final String DATABASE_CLIENT_NAME = "Sink_Connector";
    public static final String SYSTEM_DB = "system";
    protected Connection conn;

    private String hostName;
    private Integer port;
    protected String database;
    private String userName;
    private String password;

    private ZoneId serverTimeZone;

    private ClickHouseSinkConnectorConfig config;

    private static final Logger log = LogManager.getLogger(BaseDbWriter.class);

    public BaseDbWriter(
            String hostName,
            Integer port,
            String database,
            String userName,
            String password,
            ClickHouseSinkConnectorConfig config,
            Connection conn
    ) {

        this.hostName = hostName;
        this.port = port;
        this.database = database;
        this.userName = userName;
        this.password = password;

        this.config = config;
        this.conn = conn;
        //this.createConnection(connectionUrl, "Agent_1", userName, password);
        this.serverTimeZone = new DBMetadata().getServerTimeZone(this.conn);
    }

    // Create offset/schema history storage database.
    protected void createDestinationDatabase(String databaseName) {

        DBMetadata metadata = new DBMetadata();
        try {
            if (false == metadata.checkIfDatabaseExists(this.conn, databaseName)) {
                new ClickHouseCreateDatabase().createNewDatabase(this.conn, databaseName);
            }
        } catch(Exception e) {

            int maxRetries = 0;
            final int MAX_RETRIES = 5;
            log.error("Error creating Database: " + databaseName);

            // Keep retrying to createNewDatabase until Max number of retries is reached.
            boolean createDatabaseFailed = false;
            while(maxRetries++ > MAX_RETRIES) {
                try {
                    Thread.sleep(maxRetries * 5000);
                    if (false == metadata.checkIfDatabaseExists(this.conn, databaseName)) {
                        new ClickHouseCreateDatabase().createNewDatabase(this.conn, databaseName);
                        createDatabaseFailed = true;
                        break;
                    }
                } catch (Exception ex) {
                    log.error("Retry Number: " + maxRetries + "of" + MAX_RETRIES + "  Error creating Database: " + databaseName);
                }
            }
            // if maxRetries exceeded, throw runtime exception.
            if(createDatabaseFailed == false) {
                throw new RuntimeException("Error creating Database: " + databaseName, e);
            }
        }
    }

    /**
     * Function to split JDBC properties string into Properties object.
     * @param jdbcProperties
     * @return
     */
    public static Properties splitJdbcProperties(String jdbcProperties) {
        // Split JDBC properties(delimited by equal sign) string delimited by comma.
        String[] splitProperties = jdbcProperties.split(",");

        // Iterate through splitProperties and convert to Properties.
        Properties properties = new Properties();
        Arrays.stream(splitProperties).forEach(property -> {
            String[] keyValue = property.split("=");
            properties.setProperty(keyValue[0], keyValue[1]);
        });

        return properties;
    }

    public Connection getConnection() {
        HikariDbSource.printConnectionInfo();
        if(this.conn == null) {
            try {
                this.conn = HikariDbSource.initiateNewConnectionIfClosed(this.database);
            } catch (Exception e) {
                log.error("Error retrieving new connection in getConnection");
            }
        }
        return this.conn;
    }
    public static String getConnectionString(String hostName, Integer port, String database) {
        return String.format("jdbc:clickhouse://%s:%s/%s", hostName, port, database);
    }

    /**
     * Function to create Connection using the JDBC Driver
     *
     * @param url        url with the JDBC format jdbc:ch://localhost/test
     * @param clientName Client Name
     * @param userName   UserName
     * @param password   Password
     */
    public static Connection createConnection(String url, String clientName, String userName,
                                              String password, String databaseName
            , ClickHouseSinkConnectorConfig config) {

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
            if(!jdbcSettings.isEmpty()) {
                properties.setProperty("custom_settings", jdbcSettings);
            } else {
                properties.setProperty("custom_settings", "allow_experimental_object_type=1,insert_allow_materialized_columns=1");
            }
            boolean connectionPoolDisable = config.getBoolean(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE.toString());
            // Set the http connection provider to HTTP_URL_CONNECTION if connection pool is enabled.
            if(!connectionPoolDisable) {
                properties.setProperty("http_connection_provider", "HTTP_URL_CONNECTION");
            }
            if(!jdbcParams.isEmpty()) {
                log.info("**** JDBC PARAMS from configuration:" + jdbcParams);
                Properties userProps = splitJdbcProperties(jdbcParams);
                properties.putAll(userProps);
            }
            // Add username/password to the url.
            url = url + "?user=" + userName + "&password=" + password;

            SinkConnectorDataSource dataSource = new SinkConnectorDataSource(url, properties);
            // Get connection from the pool.
            if(connectionPoolDisable) {
                log.info("Connection pool is disabled, creating a new connection");
                conn = dataSource.getConnection();
            } else {
                HikariDataSource hikariDbSource = HikariDbSource.getInstance(dataSource, databaseName, config);
                // Create a new ClickHouseConnection object with the connection from the pool.
                // Convert Connection to ClickHouseConnection.
                conn = hikariDbSource.getConnection();
            }
        } catch (Exception e) {
            log.error("Error creating ClickHouse connection" + e);
        }

        return conn;
    }




}


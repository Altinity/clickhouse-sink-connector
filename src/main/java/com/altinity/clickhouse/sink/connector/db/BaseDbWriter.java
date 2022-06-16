package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.clickhouse.jdbc.ClickHouseConnection;
import com.clickhouse.jdbc.ClickHouseDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

public class BaseDbWriter {

    protected ClickHouseConnection conn;

    private static final Logger log = LoggerFactory.getLogger(BaseDbWriter.class);

    public BaseDbWriter(
            String hostName,
            Integer port,
            String database,
            String tableName,
            String userName,
            String password,
            ClickHouseSinkConnectorConfig config
    ) {

        String connectionUrl = getConnectionString(hostName, port, database);
        this.createConnection(connectionUrl, "Agent_1", userName, password);
    }

    public ClickHouseConnection getConnection() {
        return this.conn;
    }
    public String getConnectionString(String hostName, Integer port, String database) {
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
    protected void createConnection(String url, String clientName, String userName, String password) {
        try {
            Properties properties = new Properties();
            properties.setProperty("client_name", clientName);
            ClickHouseDataSource dataSource = new ClickHouseDataSource(url, properties);

            this.conn = dataSource.getConnection(userName, password);
        } catch (Exception e) {
            log.warn("Error creating SQL connection" + e);
        }
    }

}


package com.altinity.clickhouse.sink.connector.db;

import com.clickhouse.jdbc.ClickHouseConnection;
import com.clickhouse.jdbc.ClickHouseDataSource;

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
     * Constructs a new SinkConnectorDataSource with the specified URL and properties.
     * <p>
     * This constructor is used to create a data source that can be used to
     * obtain connections to a ClickHouse database. The URL and properties are passed
     * to the parent ClickHouseDataSource class to configure the connection.
     * </p>
     *
     * @param url the URL of the ClickHouse server to connect to.
     * @param properties the properties to configure the connection.
     * @throws SQLException if an error occurs while creating the data source.
     */
    public SinkConnectorDataSource(String url, Properties properties) throws SQLException {
        super(url, properties);
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
     * @return a ClickHouseConnection object.
     * @throws SQLException if an error occurs while obtaining the connection.
     */
    @Override
    public ClickHouseConnection getConnection() throws SQLException {
        // Custom behavior can be added here if needed, for example, using a custom HTTP client.
        // System.out.println("Using custom HTTP client for ClickHouse!");
        return super.getConnection();
    }
}

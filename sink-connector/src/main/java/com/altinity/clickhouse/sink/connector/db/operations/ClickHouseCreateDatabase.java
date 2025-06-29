package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * ClickHouseCreateDatabase provides functionality for creating a new
 * ClickHouse database if it does not already exist.
 *
 * <p>It extends {@code ClickHouseTableOperationsBase}, leveraging common
 * behaviors for ClickHouse table operations.
 */
public class ClickHouseCreateDatabase extends ClickHouseTableOperationsBase {

    /**
     * Creates a new ClickHouse database if it does not already exist.
     *
     * @param conn   the active {@link Connection} to the ClickHouse server
     * @param dbName the name of the database to create
     * @throws SQLException if an SQL error occurs during database creation
     */
    public void createNewDatabase(Connection conn, String dbName, ClickHouseSinkConnectorConfig config)
            throws SQLException {
        String query = String.format("CREATE DATABASE IF NOT EXISTS %s", dbName);
        DBMetadata metadata = new DBMetadata(config);
        metadata.executeSystemQuery(conn, query);
    }
}

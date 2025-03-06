package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.clickhouse.jdbc.ClickHouseConnection;

import java.sql.Connection;
import java.sql.SQLException;

public class ClickHouseCreateDatabase extends ClickHouseTableOperationsBase {
    public void createNewDatabase(Connection conn, String dbName) throws SQLException {
        String query = String.format("CREATE DATABASE IF NOT EXISTS %s", dbName);
        DBMetadata metadata = new DBMetadata();
        metadata.executeSystemQuery(conn, query);
    }
}

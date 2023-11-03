package com.altinity.clickhouse.sink.connector.db.operations;

import com.clickhouse.jdbc.ClickHouseConnection;

import java.sql.SQLException;

public class ClickHouseCreateDatabase extends ClickHouseTableOperationsBase {
    public void createNewDatabase(ClickHouseConnection conn, String dbName) throws SQLException {
        String query = String.format("USE system; CREATE DATABASE IF NOT EXISTS %s; USE %s", dbName, dbName);
        this.runQuery(query, conn);
    }
}

package com.altinity.clickhouse.sink.connector.db.operations;

import com.clickhouse.jdbc.ClickHouseConnection;

import java.sql.SQLException;

public class ClickHouseCreateDatabase extends ClickHouseTableOperationsBase {
    public void createNewDatabase(ClickHouseConnection conn, String dbName) throws SQLException {
        this.runQuery(String.format("CREATE DATABASE %s", dbName), conn);
    }
}

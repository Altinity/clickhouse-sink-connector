package com.altinity.clickhouse.sink.connector.db;


import com.clickhouse.jdbc.ClickHouseConnection;
import com.clickhouse.jdbc.ClickHouseDataSource;

import java.sql.SQLException;
import java.util.Properties;

public class SinkConnectorDataSource extends ClickHouseDataSource {

    public SinkConnectorDataSource(String url, Properties properties) throws SQLException {
        super(url, properties);
    }

    @Override
    public ClickHouseConnection getConnection() throws SQLException {
        //System.out.println("Using custom HTTP client for ClickHouse!");
        return super.getConnection();
    }



}

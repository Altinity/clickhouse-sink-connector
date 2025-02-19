package com.altinity.clickhouse.sink.connector.db;


import com.clickhouse.jdbc.ClickHouseConnection;
import com.clickhouse.jdbc.ClickHouseDataSource;
import okhttp3.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

public class SinkConnectorDataSource extends ClickHouseDataSource {
    private final OkHttpClient httpClient;

    public SinkConnectorDataSource(String url, Properties properties, OkHttpClient client) throws SQLException {
        super(url, properties);
        this.httpClient = client;
    }

    @Override
    public ClickHouseConnection getConnection() throws SQLException {
        //System.out.println("Using custom HTTP client for ClickHouse!");
        return super.getConnection();
    }



}

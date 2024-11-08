package com.altinity.clickhouse.sink.connector.db;

import com.clickhouse.jdbc.ClickHouseDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

// Singleton class.
public class HikariDbSource {
    private static HikariDbSource instance;

    private HikariDataSource dataSource;

    // private constructor
    private HikariDbSource(ClickHouseDataSource dataSource) {
        this.createConnectionPool(dataSource);
    }

    public Connection getConnection() throws SQLException {
        return this.dataSource.getConnection();
    }

    public static HikariDbSource getInstance(ClickHouseDataSource dataSource) {
        if (instance == null) {
            instance = new HikariDbSource(dataSource);
        }
        return instance;
    }
    public void createConnectionPool(ClickHouseDataSource dataSource) {
        // pass the clickhouse config to create the datasource

     
        HikariConfig poolConfig = new HikariConfig();
        poolConfig.setConnectionTimeout(50000L);
        poolConfig.setMaximumPoolSize(20);
        poolConfig.setMaxLifetime(300_000L);
        poolConfig.setDataSource(dataSource);

        this.dataSource = new HikariDataSource(poolConfig);
    }   
}

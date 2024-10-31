package com.altinity.clickhouse.sink.connector.db;

import com.clickhouse.jdbc.ClickHouseDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

// Singleton class to manage the Hikari connection pool
public class HikariDbSource {
    
    private HikariDataSource dataSource;


    public HikariDbSource(HikariDataSource dataSource, String jdbcUrl, String username, String password) {

        this.dataSource = dataSource;

        HikariConfig poolConfig = new HikariConfig();
        poolConfig.setConnectionTimeout(5000L);
        poolConfig.setMaximumPoolSize(20);
        poolConfig.setMaxLifetime(300_000L);
        poolConfig.setDataSource(new ClickHouseDataSource(dataSource.getJdbcUrl(), dataSource.getUsername(), dataSource.getPassword()));

        HikariDataSource ds = new HikariDataSource(poolConfig);
    }

    public Connection getConnection() throws SQLException {
        return this.dataSource.getConnection();
    }
}

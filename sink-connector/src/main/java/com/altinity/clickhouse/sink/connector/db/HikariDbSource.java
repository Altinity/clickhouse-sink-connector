package com.altinity.clickhouse.sink.connector.db;

import com.clickhouse.jdbc.ClickHouseDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

// Singleton class(one per database)
public class HikariDbSource {
    private static HikariDbSource instance;

    private HikariDataSource dataSource;
    private String databaseName;

    // private constructor
    private HikariDbSource(ClickHouseDataSource dataSource, String databaseName) {
        this.createConnectionPool(dataSource, databaseName);
    }

    public Connection getConnection() throws SQLException {
        return this.dataSource.getConnection();
    }

    public static HikariDbSource getInstance(ClickHouseDataSource dataSource, String databaseName) {
        if (instance == null) {
            instance = new HikariDbSource(dataSource, databaseName);
        }
        return instance;
    }
    public void createConnectionPool(ClickHouseDataSource dataSource, String databaseName)  {
        // pass the clickhouse config to create the datasource

     
        HikariConfig poolConfig = new HikariConfig();
        String jdbcUrl = String.format("jdbc:ch:{hostname}:{port}/%s?insert_quorum=auto&server_time_zone&server_version=22.13.1.24495", databaseName);
        poolConfig.setJdbcUrl(jdbcUrl);
        poolConfig.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver"); // Ensure driver is set
       // poolConfig.setUsername(dataSource.getConnection().getCurrentUser()); // Optional, if already in JDBC URL
        // poolConfig.setPassword(dataSource.getConnection().()); // Optional, if already in JDBC URL
        poolConfig.setConnectionTimeout(50000L);
        poolConfig.setMaximumPoolSize(500);
        poolConfig.setMinimumIdle(10);
        poolConfig.setMaxLifetime(300_000L);
        poolConfig.setDataSource(dataSource);

        this.dataSource = new HikariDataSource(poolConfig);
    }   
}

package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.common.Metrics;
import com.clickhouse.jdbc.ClickHouseDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.prometheus.PrometheusMeterRegistry;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

// Singleton class(one per database)
public class HikariDbSource {
    private static Map<String, HikariDbSource> instance = new HashMap<>();
    //private static HikariDbSource instance;

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

        if(instance.containsKey(databaseName)) {
            return instance.get(databaseName);
        } else {
            HikariDbSource hikariDbSource = new HikariDbSource(dataSource, databaseName);
            instance.put(databaseName, hikariDbSource);
        }
        return instance.get(databaseName);
    }
    public void createConnectionPool(ClickHouseDataSource dataSource, String databaseName)  {
        // pass the clickhouse config to create the datasource

     
        HikariConfig poolConfig = new HikariConfig();
        poolConfig.setPoolName("clickhouse" + "-" + databaseName);
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

        PrometheusMeterRegistry meterRegistry = Metrics.meterRegistry();

        if(meterRegistry != null) {
            this.dataSource.setMetricRegistry(meterRegistry);
        }
    }   
}

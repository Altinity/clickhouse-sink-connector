package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.clickhouse.jdbc.ClickHouseConnection;
import com.clickhouse.jdbc.ClickHouseDataSource;
import com.altinity.clickhouse.sink.connector.db.DbWriter;
import org.junit.Assert;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.junit.jupiter.Container;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ClickHouseContainer;
import org.apache.commons.lang3.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.ArrayList;
import java.util.HashMap;

@Testcontainers

public class ClickHouseCreateDatabaseTest {

    static DbWriter dbWriter;
    static DbWriter maintenanceDbWriter;
    static String dbName;

    @Container
    private ClickHouseContainer clickHouseContainer = new ClickHouseContainer("clickhouse/clickhouse-server:latest")
            .withInitScript("./init_clickhouse.sql");;
    @BeforeAll
    static void initialize() {

        String hostName = "localhost";
        Integer port = 8123;
        String userName = "root";
        String password = "root";
        String systemDb = "system";
        dbName = "test_create_db";

        ClickHouseSinkConnectorConfig config= new ClickHouseSinkConnectorConfig(new HashMap<>());
        dbWriter = new DbWriter(hostName, port, dbName, null, userName, password, config, null);
        maintenanceDbWriter = new DbWriter(hostName, port, systemDb, null, userName, password, config, null);
    }

    @BeforeEach                                         
    void dropTestDatabase() throws SQLException {
        Statement drop = maintenanceDbWriter.getConnection().createStatement();
        drop.executeQuery(String.format("DROP DATABASE IF EXISTS %s", dbName));
    }

    @Test
    public void testCreateNewDatabase() throws SQLException {
        ClickHouseCreateDatabase act = new ClickHouseCreateDatabase();
        ClickHouseConnection conn = dbWriter.getConnection();
        try {
            act.createNewDatabase(conn, dbName);
        } catch(SQLException se) {
            //System.out.println(se.getMessage());
            Assert.assertTrue(false);
        }
        Statement stmt = dbWriter.getConnection().createStatement();
        String query = String.format("SELECT name FROM system.databases WHERE name = '%s'", dbName);
        ResultSet rs = stmt.executeQuery(query);
        Assert.assertTrue(rs.next());
    }
}

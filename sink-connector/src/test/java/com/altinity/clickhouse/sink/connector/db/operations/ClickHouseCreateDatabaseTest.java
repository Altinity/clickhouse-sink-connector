package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.clickhouse.jdbc.ClickHouseConnection;
import com.altinity.clickhouse.sink.connector.db.DbWriter;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseCreateDatabase;

import org.junit.Assert;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.junit.jupiter.Container;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;

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
    private static ClickHouseContainer clickHouseContainer = new ClickHouseContainer("clickhouse/clickhouse-server:latest");
    @BeforeAll
    static void initialize() {

        String hostName = clickHouseContainer.getHost();
        Integer port = clickHouseContainer.getMappedPort(8123);
        String userName = "default";
        String password = "";
        String systemDb = "system";
        dbName = "test_create_db";

        ClickHouseSinkConnectorConfig config= new ClickHouseSinkConnectorConfig(new HashMap<>());
        String jdbcUrl = BaseDbWriter.getConnectionString(hostName, port, systemDb);
        ClickHouseConnection conn = DbWriter.createConnection(jdbcUrl, "client_1", userName, password, config);
        dbWriter = new DbWriter(hostName, port, dbName, null, userName, password, config, null, conn);
        maintenanceDbWriter = new DbWriter(hostName, port, systemDb, null, userName, password, config, null, conn);
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

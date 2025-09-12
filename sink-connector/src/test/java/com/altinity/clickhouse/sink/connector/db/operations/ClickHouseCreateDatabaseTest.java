package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import com.clickhouse.jdbc.ClickHouseConnection;
import com.altinity.clickhouse.sink.connector.db.DbWriter;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseCreateDatabase;

import org.junit.After;
import org.junit.Assert;
import org.junit.jupiter.api.AfterAll;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.junit.jupiter.Container;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.ArrayList;
import java.util.HashMap;

@Testcontainers

public class ClickHouseCreateDatabaseTest {

    static DbWriter dbWriter;
    static String dbName;

    @Container
    private static ClickHouseContainer clickHouseContainer = new ClickHouseContainer("clickhouse/clickhouse-server:24.8.8")
            .waitingFor(new HttpWaitStrategy().forPort(8123));
    @BeforeAll
    static void initialize() {

        String hostName = clickHouseContainer.getHost();
        Integer port = clickHouseContainer.getMappedPort(8123);
        String userName = "default";
        String password = "";
        String systemDb = "system";
        dbName = "test_create_db";

        HashMap<String, String> options = new HashMap<>();
        options.put(ClickHouseSinkConnectorConfigVariables.ERRORS_MAX_RETRIES.toString(), "5");
        options.put("connector.class", "io.debezium.connector.mysql.MySqlConnector");
        ClickHouseSinkConnectorConfig config= new ClickHouseSinkConnectorConfig(options );

        String jdbcUrl = BaseDbWriter.getConnectionString(hostName, port, systemDb);
        Connection conn = BaseDbWriter.createConnection(jdbcUrl, BaseDbWriter.DATABASE_CLIENT_NAME, userName, password,
                DbWriter.SYSTEM_DB, config);

        dbWriter = new DbWriter(hostName, port, "employees", "employees", userName, password, config, null, conn);
    }

    @AfterAll
    static void dropTestDatabase() throws SQLException {
//        Statement drop = maintenanceDbWriter.getConnection().createStatement();
//        drop.executeQuery(String.format("DROP DATABASE IF EXISTS %s", dbName));
    }

    @AfterAll
    public static void cleanup() {
        clickHouseContainer.stop();
        HikariDbSource.close();
    }

    @Test
    public void testCreateNewDatabase() throws SQLException, InterruptedException {
        Thread.sleep(10000);
        ClickHouseCreateDatabase act = new ClickHouseCreateDatabase();
        Connection conn = dbWriter.getConnection();

        try {
            act.createNewDatabase(conn, dbName, false, new ClickHouseSinkConnectorConfig(new HashMap<>()));
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

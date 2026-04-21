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
    static void initialize() throws Exception {
        clickHouseContainer.start();

        String hostName = clickHouseContainer.getHost();
        Integer port = clickHouseContainer.getMappedPort(8123);
        String userName = clickHouseContainer.getUsername();
        String password = clickHouseContainer.getPassword() != null ? clickHouseContainer.getPassword() : "";
        String systemDb = "system";
        dbName = "test_create_db";

        HashMap<String, String> options = new HashMap<>();
        options.put(ClickHouseSinkConnectorConfigVariables.ERRORS_MAX_RETRIES.toString(), "5");
        options.put("connector.class", "io.debezium.connector.mysql.MySqlConnector");
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(options);

        String jdbcUrl = BaseDbWriter.getConnectionString(hostName, port, systemDb);
        Connection conn = BaseDbWriter.createConnection(jdbcUrl, BaseDbWriter.DATABASE_CLIENT_NAME, userName, password,
                DbWriter.SYSTEM_DB, config);

        Assert.assertNotNull("Connection must not be null; ensure container is running and credentials are correct", conn);
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
        Assert.assertNotNull("DbWriter must be initialized", dbWriter);
        Connection conn = dbWriter.getConnection();
        Assert.assertNotNull("DbWriter connection must not be null", conn);

        ClickHouseCreateDatabase act = new ClickHouseCreateDatabase();
        try {
            act.createNewDatabase(conn, dbName, false, new ClickHouseSinkConnectorConfig(new HashMap<>()));
        } catch (SQLException se) {
            Assert.fail("createNewDatabase failed: " + se.getMessage());
        }
        Statement stmt = dbWriter.getConnection().createStatement();
        String query = String.format("SELECT name FROM system.databases WHERE name = '%s'", dbName);
        ResultSet rs = stmt.executeQuery(query);
        Assert.assertTrue(rs.next());
    }

    @Test
    public void testCreateDatabaseSyntaxQuotesDatabaseName() {
        ClickHouseCreateDatabase act = new ClickHouseCreateDatabase();

        Assert.assertEquals("CREATE DATABASE IF NOT EXISTS `default`",
                act.createDatabaseSyntax("default", false));
        Assert.assertEquals("CREATE DATABASE IF NOT EXISTS `order-db` ON CLUSTER `{cluster}`",
                act.createDatabaseSyntax("order-db", true));
    }
}

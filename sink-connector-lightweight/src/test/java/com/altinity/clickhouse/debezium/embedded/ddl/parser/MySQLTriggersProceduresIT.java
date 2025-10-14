package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.config.SinkConnectorLightWeightConfig;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.clickhouse.jdbc.ClickHouseConnection;

import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.*;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration Test class to validate MySQL triggers, stored procedures, and functions
 * with ClickHouse replication using Debezium.
 */
@Disabled
@DisplayName("Integration Test to validate MySQL triggers, procedures and functions with ClickHouse replication")
public class MySQLTriggersProceduresIT {

    private static AtomicReference<DebeziumChangeEventCapture> engine;
    private static ExecutorService executorService;
    private static Connection mysqlConn;
    private static BaseDbWriter writer;

    @Container
    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:latest")
            .asCompatibleSubstituteFor("clickhouse"))
            .withInitScript("init_clickhouse_it.sql")
            .withUsername("ch_user")
            .withPassword("password")
            .withExposedPorts(8123);

    @Container
    public static MySQLContainer mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:8.0.36")
            .asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("test_db")
            .withUsername("root")
            .withPassword("adminpass")
            .withInitScript("triggers_procedures_setup.sql")
            .withExtraHost("mysql-server", "0.0.0.0")
            .waitingFor(new HttpWaitStrategy().forPort(3306));

    @BeforeAll
    public static void startContainers() {
        try {
            BasicConfigurator.configure();
            mySqlContainer.start();
            clickHouseContainer.start();
            Thread.sleep(10000);

            setupDebeziumEngine();
            mysqlConn = connectToMySQL();
            String jdbcUrl = BaseDbWriter.getConnectionString(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(), "test_db");
            Connection connection = BaseDbWriter.createConnection(jdbcUrl, "client_1", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(),
                    "test_db", new ClickHouseSinkConnectorConfig(new HashMap<>()));
            writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                    "test_db", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null, connection);
            Thread.sleep(20000);
        } catch (Exception e) {
            throw new RuntimeException("Failed to start containers", e);
        }
    }

    private static void setupDebeziumEngine() throws Exception {
        engine = new AtomicReference<>();
        Properties props = ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer);
        executorService = Executors.newSingleThreadExecutor();
        executorService.execute(() -> {
            try {
                DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
                engine.set(capture);
                capture.setup(props, new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException("Failed to start Debezium engine", e);
            }
        });
        Thread.sleep(20000);
    }

    protected static Connection connectToMySQL() {
        return ITCommon.connectToMySQL(mySqlContainer);
    }

    @AfterAll
    public static void stopEngine() throws Exception {
        if (engine != null && engine.get() != null) {
            engine.get().stop();
        }
        executorService.shutdownNow();
    }

    @Test
    @DisplayName("Test customer insert trigger")
    public void testCustomerInsertTrigger() throws Exception {
        // Insert a new customer
        mysqlConn.prepareStatement(
            "INSERT INTO customers (name, email) VALUES ('Test User', 'test.user@example.com')"
        ).execute();
        Thread.sleep(10000);

        // Verify customer was inserted
        try (ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT * FROM test_db.`customers` FINAL WHERE email = 'test.user@example.com'").executeQuery()) {
            Assert.assertTrue("Customer should be found", rs.next());
            Assert.assertEquals("Test User", rs.getString("name"));
        }

        // Verify audit log was created
        try (ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT * FROM test_db.`audit_logs` FINAL WHERE action_type = 'INSERT' ORDER BY created_at DESC LIMIT 1").executeQuery()) {
            Assert.assertTrue("Audit log should be found", rs.next());
            Assert.assertTrue("Audit log should contain customer details", 
                rs.getString("action_details").contains("Customer added"));
        }
    }

    @Test
    @DisplayName("Test customer update trigger")
    public void testCustomerUpdateTrigger() throws Exception {
        // Update customer name (should succeed)
        mysqlConn.prepareStatement(
            "UPDATE customers SET name = 'Updated Test User' WHERE email = 'test.user@example.com'"
        ).execute();
        Thread.sleep(10000);

        // Verify name was updated
        try (ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT * FROM test_db.`customers` FINAL WHERE email = 'test.user@example.com'").executeQuery()) {
            Assert.assertTrue("Customer should be found", rs.next());
            Assert.assertEquals("Updated Test User", rs.getString("name"));
        }

        // Try to update email (should fail)
        try {
            mysqlConn.prepareStatement(
                "UPDATE customers SET email = 'updated.email@example.com' WHERE email = 'test.user@example.com'"
            ).execute();
            Assert.fail("Email update should have failed");
        } catch (Exception e) {
            Assert.assertTrue("Error should mention email updates not allowed", 
                e.getMessage().contains("Email updates are not allowed"));
        }
    }

    @Test
    @DisplayName("Test stored procedures")
    public void testStoredProcedures() throws Exception {
        // Test insert_order procedure
        mysqlConn.prepareStatement(
            "CALL insert_order(1, 150.00)"
        ).execute();
        Thread.sleep(10000);

        // Verify order was inserted
        try (ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT * FROM test_db.`orders` FINAL WHERE order_amount = 150.00").executeQuery()) {
            Assert.assertTrue("Order should be found", rs.next());
            Assert.assertEquals(1, rs.getInt("customer_id"));
        }

        // Test customer_report procedure
        try (ResultSet rs = mysqlConn.prepareStatement("CALL customer_report()").executeQuery()) {
            Assert.assertTrue("Report should return results", rs.next());
            Assert.assertTrue("Report should include customer details", 
                rs.getString("name") != null);
        }
    }

    @Test
    @DisplayName("Test functions")
    public void testFunctions() throws Exception {
        // Test get_total_order_amount function
        try (ResultSet rs = mysqlConn.prepareStatement(
                "SELECT get_total_order_amount(1) AS total_amount").executeQuery()) {
            Assert.assertTrue("Should return total amount", rs.next());
            Assert.assertTrue("Total amount should be greater than 0", 
                rs.getDouble("total_amount") > 0);
        }

        // Test get_customer_info function
        try (ResultSet rs = mysqlConn.prepareStatement(
                "SELECT get_customer_info(1) AS customer_info").executeQuery()) {
            Assert.assertTrue("Should return customer info", rs.next());
            String jsonInfo = rs.getString("customer_info");
            Assert.assertTrue("JSON should contain customer details", 
                jsonInfo.contains("customer_id") && jsonInfo.contains("name"));
        }

        // Test count_orders function
        try (ResultSet rs = mysqlConn.prepareStatement(
                "SELECT count_orders(1) AS order_count").executeQuery()) {
            Assert.assertTrue("Should return order count", rs.next());
            Assert.assertTrue("Order count should be greater than 0", 
                rs.getInt("order_count") > 0);
        }
    }

    @Test
    @DisplayName("Test delete customer procedure")
    public void testDeleteCustomerProcedure() throws Exception {
        // Get initial counts
        int initialCustomers = getCount(mysqlConn, "SELECT COUNT(*) FROM customers");
        int initialOrders = getCount(mysqlConn, "SELECT COUNT(*) FROM orders");
        int initialAuditLogs = getCount(mysqlConn, "SELECT COUNT(*) FROM audit_logs");

        // Delete customer
        mysqlConn.prepareStatement("CALL delete_customer(1)").execute();
        Thread.sleep(10000);

        // Verify customer was deleted
        try (ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT * FROM test_db.`customers` FINAL WHERE customer_id = 1").executeQuery()) {
            Assert.assertFalse("Customer should be deleted", rs.next());
        }

        // Verify orders were deleted
        try (ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT * FROM test_db.`orders` FINAL WHERE customer_id = 1").executeQuery()) {
            Assert.assertFalse("Orders should be deleted", rs.next());
        }

        // Verify audit logs were created
        int finalAuditLogs = getCount(writer.getConnection(), 
            "SELECT COUNT(*) FROM test_db.`audit_logs` FINAL");
        Assert.assertTrue("Audit logs should increase", finalAuditLogs > initialAuditLogs);
    }

    private int getCount(Connection conn, String query) throws Exception {
        try (ResultSet rs = conn.createStatement().executeQuery(query)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
} 
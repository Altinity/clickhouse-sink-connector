package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.CacheInvalidationManager;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;

/**
 * Integration Test that validates cache invalidation after ALTER TABLE DROP COLUMN.
 * 
 * This test verifies that when a DDL statement (ALTER TABLE DROP COLUMN) is executed,
 * the cached DbWriter is invalidated and subsequent inserts use the updated schema.
 */
@DisplayName("Integration Test that validates cache invalidation after ALTER TABLE DROP COLUMN")
@Testcontainers
public class AlterTableDropColumnCacheIT extends DDLBaseIT {

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse(MYSQL_DOCKER_IMAGE)
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                .withInitScript("alter_ddl_drop_column_cache.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        clickHouseContainer.start();
        
        // Clear any pending invalidations from previous tests
        CacheInvalidationManager.getInstance().clearAll();
        
        Thread.sleep(15000);
    }

    @Test
    @DisplayName("Test that DROP COLUMN triggers cache invalidation and subsequent inserts succeed")
    public void testDropColumnCacheInvalidation() throws Exception {

        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                Properties properties = getDebeziumProperties();
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(properties, new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Wait for initial data replication (row with id=1, including age column)
        Thread.sleep(30000);

        // Verify initial row is replicated to ClickHouse with all columns including 'age'
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        Connection chConn = writer.getConnection();
        
        // Check that the initial row exists with the age column
        try (Statement stmt = chConn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT id, name, email, age FROM employees.cache_test WHERE id = 1");
            Assert.assertTrue("Initial row should be replicated", rs.next());
            Assert.assertEquals("John Doe", rs.getString("name"));
            Assert.assertEquals("john@example.com", rs.getString("email"));
            Assert.assertEquals(30, rs.getInt("age"));
            rs.close();
        }

        // Execute ALTER TABLE DROP COLUMN age on MySQL
        Connection mysqlConn = connectToMySQL();
        mysqlConn.prepareStatement("ALTER TABLE cache_test DROP COLUMN age").execute();

        // Wait for DDL to be processed
        Thread.sleep(10000);

        // Insert new row without the dropped column
        mysqlConn.prepareStatement("INSERT INTO cache_test (id, name, email) VALUES (2, 'Jane Smith', 'jane@example.com')").execute();

        // Wait for insert to be replicated
        Thread.sleep(15000);

        // Verify the new row is correctly inserted in ClickHouse
        try (Statement stmt = chConn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT id, name, email FROM employees.cache_test WHERE id = 2");
            Assert.assertTrue("New row should be replicated after DROP COLUMN", rs.next());
            Assert.assertEquals("Jane Smith", rs.getString("name"));
            Assert.assertEquals("jane@example.com", rs.getString("email"));
            rs.close();
        }

        // Verify the age column no longer exists in ClickHouse
        try (Statement stmt = chConn.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                "SELECT count(*) as cnt FROM system.columns " +
                "WHERE database = 'employees' AND table = 'cache_test' AND name = 'age'"
            );
            rs.next();
            Assert.assertEquals("age column should be dropped", 0, rs.getInt("cnt"));
            rs.close();
        }

        // Verify total row count
        try (Statement stmt = chConn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT count(*) as cnt FROM employees.cache_test");
            rs.next();
            Assert.assertTrue("Should have at least 2 rows", rs.getInt("cnt") >= 2);
            rs.close();
        }

        // Cleanup
        if (engine.get() != null) {
            engine.get().stop();
        }
        executorService.shutdown();
        HikariDbSource.close();
    }
}

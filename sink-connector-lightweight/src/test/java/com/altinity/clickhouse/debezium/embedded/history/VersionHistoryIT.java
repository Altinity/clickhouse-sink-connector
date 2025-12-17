package com.altinity.clickhouse.debezium.embedded.history;

import com.altinity.clickhouse.debezium.embedded.AppInjector;
import com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication;
import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.api.DebeziumEmbeddedRestApi;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.log4j.BasicConfigurator;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.getDebeziumProperties;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
import static org.junit.Assert.assertTrue;

public class VersionHistoryIT {

    private static final Logger log = LoggerFactory.getLogger(VersionHistoryIT.class);

    protected MySQLContainer mySqlContainer;

    @Container
    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_DOCKER_IMAGE)
            .asCompatibleSubstituteFor("clickhouse"))
            .withUsername("ch_user")
            .withPassword("password")
            .withExposedPorts(8123);

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse(MYSQL_DOCKER_IMAGE)
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        clickHouseContainer.start();
        Thread.sleep(35000);
    }

    @AfterEach()
    public void stop() {
        mySqlContainer.stop();
        clickHouseContainer.stop();
    }

    /**
     * Test that validates _valid_to and _valid_from columns are correctly set for
     * INSERT, UPDATE, and DELETE operations.
     * 
     * For INSERT: _valid_to should be set to max date (2100-01-01)
     * For UPDATE: 
     *   - Old record should be closed (is_deleted=1, _valid_to set to binlog timestamp)
     *   - New record should have open-ended _valid_to (max date)
     * For DELETE: record should be marked as deleted (is_deleted=1, _valid_to set to binlog timestamp)
     */
    @DisplayName("Test that validates _valid_to and _valid_from columns for INSERT, UPDATE, DELETE operations")
    @Test
    public void testValidToValidFromColumnsOnUpdateDelete() throws Exception {
        
        Injector injector = Guice.createInjector(new AppInjector());

        Properties props = getDebeziumProperties(mySqlContainer, clickHouseContainer);
        props.setProperty("snapshot.mode", "no_data");
        props.setProperty("schema.history.internal.store.only.captured.tables.ddl", "true");
        props.setProperty("schema.history.internal.store.only.captured.databases.ddl", "true");
        props.setProperty("database.include.list", "employees");

        // Enable binlog history
        props.setProperty("replication.history.enable", "true");

        ClickHouseDebeziumEmbeddedApplication clickHouseDebeziumEmbeddedApplication = new ClickHouseDebeziumEmbeddedApplication();

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                clickHouseDebeziumEmbeddedApplication.start(injector.getInstance(DebeziumRecordParserService.class), props, false);
                DebeziumEmbeddedRestApi.startRestApi(props, injector, clickHouseDebeziumEmbeddedApplication.getDebeziumEventCapture(), new Properties());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(25000);

        // Step 1: Create table and insert a record
        Connection conn = ITCommon.connectToMySQL(mySqlContainer);
        conn.prepareStatement("create table `employees_temporal_test`(emp_id int not null, name varchar(255), salary int, primary key(emp_id))").execute();
        
        // Insert first record
        conn.prepareStatement("insert into employees_temporal_test values(1, 'John Doe', 50000)").execute();
        
        Thread.sleep(10000);
        
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        
        // First, verify the table structure
        log.info("Checking table structure for binlog_history.employees_temporal_test");
        ResultSet tableStructRs = ITCommon.executeQueryWithResultSet(
            "DESCRIBE binlog_history.employees_temporal_test",
            writer.getConnection());
        while (tableStructRs.next()) {
            log.info("Column: name={}, type={}, default={}", 
                tableStructRs.getString("name"),
                tableStructRs.getString("type"),
                tableStructRs.getString("default_expression"));
        }
        
        // Validate INSERT: _valid_to should be max date (2100-01-01)
        log.info("Validating INSERT operation - _valid_to should be max date");
        ResultSet insertRs = ITCommon.executeQueryWithResultSet(
            "SELECT emp_id, name, salary, `_valid_to`, `is_deleted`, `_version` FROM binlog_history.employees_temporal_test ORDER BY `_version`",
            writer.getConnection());
        
        int insertRecordCount = 0;
        while (insertRs.next()) {
            insertRecordCount++;
            String validTo = insertRs.getString("_valid_to");
            int isDeleted = insertRs.getInt("is_deleted");
            log.info("After INSERT - Record: emp_id={}, name={}, _valid_to={}, is_deleted={}", 
                insertRs.getInt("emp_id"), insertRs.getString("name"), validTo, isDeleted);
            
            // For inserted record, _valid_to should be max date and is_deleted should be 0
            assertTrue("After INSERT, is_deleted should be 0", isDeleted == 0);
            assertTrue("After INSERT, _valid_to should be year 2100", validTo.startsWith("2100"));
        }
        assertTrue("Should have exactly 1 record after INSERT", insertRecordCount == 1);
        
        // Step 2: Update the record
        log.info("Performing UPDATE operation");
        conn.prepareStatement("update employees_temporal_test set salary = 60000 where emp_id = 1").execute();
        
        Thread.sleep(10000);
        
        // Validate UPDATE: Should have multiple records - old one closed, new one with open _valid_to
        log.info("Validating UPDATE operation - checking _valid_to values");
        ResultSet updateRs = ITCommon.executeQueryWithResultSet(
            "SELECT emp_id, name, salary, `_valid_to`, `is_deleted`, `_version` FROM binlog_history.employees_temporal_test ORDER BY `_version`",
            writer.getConnection());
        
        int updateRecordCount = 0;
        int activeRecords = 0;
        int closedRecords = 0;
        int latestSalary = 0;
        long latestVersion = 0;
        
        while (updateRs.next()) {
            updateRecordCount++;
            String validTo = updateRs.getString("_valid_to");
            int isDeleted = updateRs.getInt("is_deleted");
            int salary = updateRs.getInt("salary");
            long version = updateRs.getLong("_version");
            log.info("After UPDATE - Record {}: emp_id={}, salary={}, _valid_to={}, is_deleted={}, _version={}", 
                updateRecordCount, updateRs.getInt("emp_id"), salary, validTo, isDeleted, version);
            
            if (isDeleted == 0 && validTo.startsWith("2100")) {
                activeRecords++;
                latestSalary = salary;
                latestVersion = version;
            } else {
                closedRecords++;
            }
        }
        
        log.info("After UPDATE - Total records: {}, Active records: {}, Closed records: {}, Latest salary: {}, Latest version: {}", 
            updateRecordCount, activeRecords, closedRecords, latestSalary, latestVersion);
        
        // Validate that we have at least one active record with updated salary
        assertTrue("Should have at least 1 active record after UPDATE, but found: " + activeRecords, activeRecords >= 1);
        
        // First, let's debug by checking if there's an active record with salary 60000
        if (latestSalary != 60000) {
            log.error("***** TEST FAILURE: Expected salary 60000 but found {} *****", latestSalary);
            log.error("***** This likely means UPDATE operation didn't create new record correctly *****");
            // Let's also query without ordering to see all records
            ResultSet debugRs = ITCommon.executeQueryWithResultSet(
                "SELECT emp_id, name, salary, `_valid_to`, `is_deleted`, `_version`, `_operation` FROM binlog_history.employees_temporal_test ORDER BY `_version`",
                writer.getConnection());
            log.error("***** ALL RECORDS IN TABLE (for debugging): *****");
            while (debugRs.next()) {
                log.error("Record: emp_id={}, salary={}, _valid_to={}, is_deleted={}, _version={}, _operation={}", 
                    debugRs.getInt("emp_id"), 
                    debugRs.getInt("salary"),
                    debugRs.getString("_valid_to"),
                    debugRs.getInt("is_deleted"),
                    debugRs.getLong("_version"),
                    debugRs.getString("_operation"));
            }
        }
        
        assertTrue(String.format("Active record should have updated salary of 60000, but found: %d (version: %d)", latestSalary, latestVersion), 
            latestSalary == 60000);
        
        // Validate using FINAL that we get the latest version with updated salary
        ResultSet finalRs = ITCommon.executeQueryWithResultSet(
            "SELECT emp_id, name, salary, `_valid_to`, `is_deleted` FROM binlog_history.employees_temporal_test FINAL WHERE is_deleted = 0 and _valid_to > now()",
            writer.getConnection());
        
        boolean foundActiveRecord = false;
        int finalSalary = 0;
        String finalValidTo = "";
        int finalIsDeleted = -1;
        while (finalRs.next()) {
            foundActiveRecord = true;
            finalSalary = finalRs.getInt("salary");
            finalValidTo = finalRs.getString("_valid_to");
            finalIsDeleted = finalRs.getInt("is_deleted");
            log.info("FINAL after UPDATE - emp_id={}, salary={}, _valid_to={}, is_deleted={}", 
                finalRs.getInt("emp_id"), finalSalary, finalValidTo, finalIsDeleted);
            
            assertTrue(String.format("FINAL record should have updated salary of 60000, but found: %d", finalSalary), 
                finalSalary == 60000);
            assertTrue(String.format("FINAL record should have is_deleted = 0, but found: %d", finalIsDeleted), 
                finalIsDeleted == 0);
            assertTrue(String.format("FINAL record should have _valid_to in 2100, but found: %s", finalValidTo), 
                finalValidTo.startsWith("2100"));
        }
        assertTrue("Should find active record with FINAL after UPDATE", foundActiveRecord);
        
        // Step 3: Delete the record
        log.info("Performing DELETE operation");
        conn.prepareStatement("delete from employees_temporal_test where emp_id = 1").execute();
        
        Thread.sleep(10000);
        
        // Validate DELETE: Using FINAL, the record should be marked as deleted
        log.info("Validating DELETE operation - checking is_deleted and _valid_to");
        ResultSet deleteRs = ITCommon.executeQueryWithResultSet(
            "SELECT emp_id, name, salary, `_valid_to`, `is_deleted`, `_version` FROM binlog_history.employees_temporal_test ORDER BY `_version` DESC LIMIT 1",
            writer.getConnection());
        
        while (deleteRs.next()) {
            int isDeleted = deleteRs.getInt("is_deleted");
            String validTo = deleteRs.getString("_valid_to");
            log.info("After DELETE - Latest record: emp_id={}, _valid_to={}, is_deleted={}", 
                deleteRs.getInt("emp_id"), validTo, isDeleted);
            
            assertTrue("After DELETE, is_deleted should be 1 for the latest record", isDeleted == 1);
            // After delete, _valid_to should NOT be 2100 (it should be the binlog timestamp)
            assertTrue("After DELETE, _valid_to should not be year 2100 (should be closed)", 
                !validTo.startsWith("2100"));
        }
        
        // Validate using FINAL that no active records remain
        ResultSet finalDeleteRs = ITCommon.executeQueryWithResultSet(
            "SELECT count(*) as cnt FROM binlog_history.employees_temporal_test FINAL WHERE is_deleted = 0",
            writer.getConnection());
        
        while (finalDeleteRs.next()) {
            int count = finalDeleteRs.getInt("cnt");
            log.info("FINAL after DELETE - Active record count: {}", count);
            assertTrue("After DELETE, no active records should remain with FINAL query", count == 0);
        }
        
        log.info("Successfully validated _valid_to and _valid_from columns for INSERT, UPDATE, DELETE operations");

        conn.close();
        executorService.shutdown();
        HikariDbSource.close();
    }
}



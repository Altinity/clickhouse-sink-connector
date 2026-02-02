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

/**
 * Integration test for version history with snapshot.mode=initial.
 * This test validates that _valid_to and _valid_from columns work correctly
 * when using initial snapshot mode, which captures existing data before streaming changes.
 */
public class VersionHistoryInitialIT {

    private static final Logger log = LoggerFactory.getLogger(VersionHistoryInitialIT.class);

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
                .withInitScript("employees.sql")
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
     * snapshot data and UPDATE, DELETE operations when using snapshot.mode=initial.
     * 
     * The employees.sql init script creates the employees table with data.
     * This test validates:
     * - Initial snapshot captures existing data with correct _valid_from and _valid_to
     * - UPDATE operations properly close old records and create new ones
     * - DELETE operations properly mark records as deleted
     */
    @DisplayName("Test that validates _valid_to and _valid_from columns for snapshot data and UPDATE, DELETE operations with snapshot.mode=initial")
    @Test
    public void testValidToValidFromColumnsOnUpdateDeleteWithInitialSnapshot() throws Exception {
        
        // Data is loaded from employees.sql init script
        // The employees table contains records like:
        // (10001,'1953-09-02','Georgi','Facello','M','1986-06-26')
        log.info("Using employees table from init script (employees.sql) - data will be captured by initial snapshot");
        
        Injector injector = Guice.createInjector(new AppInjector());

        Properties props = getDebeziumProperties(mySqlContainer, clickHouseContainer);
        props.setProperty("snapshot.mode", "initial");
        props.setProperty("schema.history.internal.store.only.captured.tables.ddl", "true");
        props.setProperty("schema.history.internal.store.only.captured.databases.ddl", "true");
        props.setProperty("database.include.list", "employees");
        props.setProperty("table.include.list", "employees.employees");

        // Enable binlog history
        props.setProperty("replication.history.enable", "true");
        props.setProperty("replication.history.table.name", "replication_history");
        props.setProperty("replication.history.database.name", "replication_history_db");

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

        Thread.sleep(60000); // Wait longer for initial snapshot of employees table

        Connection conn = ITCommon.connectToMySQL(mySqlContainer);
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        
        // First, verify the table structure
        log.info("Checking table structure for replication_history_db.employees");
        ResultSet tableStructRs = ITCommon.executeQueryWithResultSet(
            "DESCRIBE replication_history_db.employees",
            writer.getConnection());
        while (tableStructRs.next()) {
            log.info("Column: name={}, type={}, default={}", 
                tableStructRs.getString("name"),
                tableStructRs.getString("type"),
                tableStructRs.getString("default_expression"));
        }
        
        // Validate initial snapshot data for employee 10001 (Georgi Facello)
        log.info("Validating initial snapshot data for employee 10001 - _valid_to should be max date, _valid_from should be set");
        ResultSet snapshotRs = ITCommon.executeQueryWithResultSet(
            "SELECT emp_no, first_name, last_name, `_valid_from`, `_valid_to`, `is_deleted`, `_version`, `_operation` FROM replication_history_db.employees WHERE emp_no = 10001 ORDER BY `_version`",
            writer.getConnection());
        
        int snapshotRecordCount = 0;
        String originalFirstName = null;
        while (snapshotRs.next()) {
            snapshotRecordCount++;
            String validFrom = snapshotRs.getString("_valid_from");
            String validTo = snapshotRs.getString("_valid_to");
            int isDeleted = snapshotRs.getInt("is_deleted");
            String operation = snapshotRs.getString("_operation");
            originalFirstName = snapshotRs.getString("first_name");
            log.info("After initial snapshot - Record: emp_no={}, first_name={}, last_name={}, _valid_from={}, _valid_to={}, is_deleted={}, _operation={}", 
                snapshotRs.getInt("emp_no"), originalFirstName, snapshotRs.getString("last_name"), validFrom, validTo, isDeleted, operation);
            
            // For snapshotted record, _valid_to should be max date and is_deleted should be 0
            assertTrue("After initial snapshot, is_deleted should be 0", isDeleted == 0);
            assertTrue("After initial snapshot, _valid_to should be year 2100", validTo.startsWith("2100"));
            // _valid_from should be set (not null) for the snapshotted record
            assertTrue("After initial snapshot, _valid_from should be set", validFrom != null);
        }
        assertTrue("Should have exactly 1 record for emp_no 10001 after initial snapshot", snapshotRecordCount == 1);
        assertTrue("Original first_name should be 'Georgi'", "Georgi".equals(originalFirstName));
        
        // Validate total count of snapshotted employees (sample check)
        ResultSet countRs = ITCommon.executeQueryWithResultSet(
            "SELECT count(*) as cnt FROM replication_history_db.employees",
            writer.getConnection());
        countRs.next();
        int totalCount = countRs.getInt("cnt");
        log.info("Total employees captured in snapshot: {}", totalCount);
        assertTrue("Should have captured employees from init script", totalCount > 0);
        
        // Step 2: Update employee 10001's first_name (this is a streaming change, not snapshot)
        log.info("Performing UPDATE operation on employee 10001 (streaming change)");
        conn.prepareStatement("UPDATE employees SET first_name = 'George' WHERE emp_no = 10001").execute();
        
        Thread.sleep(15000);
        
        // Validate UPDATE: Should have 2 records after UPDATE:
        // Row 1: Original snapshot record - now closed (_valid_to = UPDATE timestamp, is_deleted = 0, first_name = 'Georgi')
        // Row 2: New active record - open-ended (_valid_to = 2100, is_deleted = 0, first_name = 'George')
        log.info("Validating UPDATE operation - expecting 2 rows (original closed + new active)");
        ResultSet updateRs = ITCommon.executeQueryWithResultSet(
            "SELECT emp_no, first_name, last_name, `_valid_from`, `_valid_to`, `is_deleted`, `_version`, `_operation` FROM replication_history_db.employees final WHERE emp_no = 10001 ORDER BY `_version`",
            writer.getConnection());
        
        int updateRecordCount = 0;
        int activeRecordsWithNewName = 0;   // New active record with first_name = 'George', _valid_to = 2100
        int closedOriginalRecords = 0;      // Original record closed (_valid_to != 2100, first_name = 'Georgi')
        String latestFirstName = null;
        long latestVersion = 0;
        String activeRecordValidFrom = null;
        
        log.info("=== Records for emp_no 10001 after UPDATE ===");
        while (updateRs.next()) {
            updateRecordCount++;
            String validFrom = updateRs.getString("_valid_from");
            String validTo = updateRs.getString("_valid_to");
            int isDeleted = updateRs.getInt("is_deleted");
            String firstName = updateRs.getString("first_name");
            long version = updateRs.getLong("_version");
            String operation = updateRs.getString("_operation");
            log.info("Row {}: emp_no={}, first_name='{}', _valid_from={}, _valid_to={}, is_deleted={}, _version={}, _operation='{}'", 
                updateRecordCount, updateRs.getInt("emp_no"), firstName, validFrom, validTo, isDeleted, version, operation);
            
            boolean validToIs2100 = validTo.startsWith("2100");
            
            // Categorize records based on the expected pattern
            if (!validToIs2100 && isDeleted == 0 && "Georgi".equals(firstName)) {
                closedOriginalRecords++;
                log.info("  -> Categorized as: ORIGINAL record (closed by UPDATE)");
            } else if (validToIs2100 && isDeleted == 0 && "George".equals(firstName)) {
                activeRecordsWithNewName++;
                latestFirstName = firstName;
                latestVersion = version;
                activeRecordValidFrom = validFrom;
                log.info("  -> Categorized as: NEW active record (after UPDATE)");
            } else {
                log.info("  -> Categorized as: OTHER (unexpected pattern)");
            }
        }
        
        log.info("=== UPDATE Summary ===");
        log.info("Total rows: {}", updateRecordCount);
        log.info("Closed original records: {}", closedOriginalRecords);
        log.info("Active records with new name: {}", activeRecordsWithNewName);
        
        // After UPDATE, should have exactly 2 rows: original closed + new active
        assertTrue(String.format("Should have 2 rows after UPDATE (original closed + new active), but found: %d", updateRecordCount),
            updateRecordCount == 2);
        assertTrue(String.format("Should have 1 closed original record, but found: %d", closedOriginalRecords),
            closedOriginalRecords == 1);
        assertTrue(String.format("Should have 1 active record with new name, but found: %d", activeRecordsWithNewName),
            activeRecordsWithNewName == 1);
        assertTrue("New after-image should have _valid_from set", activeRecordValidFrom != null);
        log.info("New after-image _valid_from = {}", activeRecordValidFrom);
        
        if (!"George".equals(latestFirstName)) {
            log.error("***** TEST FAILURE: Expected first_name 'George' but found '{}' *****", latestFirstName);
            ResultSet debugRs = ITCommon.executeQueryWithResultSet(
                "SELECT emp_no, first_name, `_valid_from`, `_valid_to`, `is_deleted`, `_version`, `_operation` FROM replication_history_db.employees WHERE emp_no = 10001 ORDER BY `_version`",
                writer.getConnection());
            log.error("***** ALL RECORDS FOR emp_no 10001 (for debugging): *****");
            while (debugRs.next()) {
                log.error("Record: emp_no={}, first_name={}, _valid_from={}, _valid_to={}, is_deleted={}, _version={}, _operation={}", 
                    debugRs.getInt("emp_no"), 
                    debugRs.getString("first_name"),
                    debugRs.getString("_valid_from"),
                    debugRs.getString("_valid_to"),
                    debugRs.getInt("is_deleted"),
                    debugRs.getLong("_version"),
                    debugRs.getString("_operation"));
            }
        }
        
        assertTrue(String.format("Active record should have updated first_name 'George', but found: '%s' (version: %d)", latestFirstName, latestVersion), 
            "George".equals(latestFirstName));
        
        // Validate using FINAL that we get the latest version with updated first_name
        ResultSet finalRs = ITCommon.executeQueryWithResultSet(
            "SELECT emp_no, first_name, last_name, `_valid_from`, `_valid_to`, `is_deleted` FROM replication_history_db.employees FINAL WHERE emp_no = 10001 AND is_deleted = 0 AND _valid_to > now()",
            writer.getConnection());
        
        boolean foundActiveRecord = false;
        String finalFirstName = "";
        String finalValidFrom = "";
        String finalValidTo = "";
        int finalIsDeleted = -1;
        while (finalRs.next()) {
            foundActiveRecord = true;
            finalFirstName = finalRs.getString("first_name");
            finalValidFrom = finalRs.getString("_valid_from");
            finalValidTo = finalRs.getString("_valid_to");
            finalIsDeleted = finalRs.getInt("is_deleted");
            log.info("FINAL after UPDATE - emp_no={}, first_name={}, _valid_from={}, _valid_to={}, is_deleted={}", 
                finalRs.getInt("emp_no"), finalFirstName, finalValidFrom, finalValidTo, finalIsDeleted);
            
            assertTrue(String.format("FINAL record should have updated first_name 'George', but found: '%s'", finalFirstName), 
                "George".equals(finalFirstName));
            assertTrue(String.format("FINAL record should have is_deleted = 0, but found: %d", finalIsDeleted), 
                finalIsDeleted == 0);
            assertTrue(String.format("FINAL record should have _valid_to in 2100, but found: %s", finalValidTo), 
                finalValidTo.startsWith("2100"));
            assertTrue("FINAL record should have _valid_from set", finalValidFrom != null);
        }
        assertTrue("Should find active record with FINAL after UPDATE", foundActiveRecord);
        
        // Step 3: Delete employee 10001
        log.info("Performing DELETE operation on employee 10001");
        conn.prepareStatement("DELETE FROM employees WHERE emp_no = 10001").execute();
        
        Thread.sleep(15000);
        
        // Validate DELETE: Should now have 3 rows showing full history:
        // 
        // Expected history pattern (3 rows):
        // | Row | emp_no | first_name | _valid_to     | is_deleted | Description                    |
        // |-----|--------|------------|---------------|------------|--------------------------------|
        // | 1   | 10001  | Georgi     | UPDATE_TIME   | 0          | Original, closed by UPDATE     |
        // | 2   | 10001  | George     | 2100          | 0          | Updated, active (before DELETE)|
        // | 3   | 10001  | George     | DELETE_TIME   | 1          | Deleted marker                 |
        // 
        // Note: Row 2 and 3 have the same primary key but different _valid_to values,
        // so they are kept as separate rows by ReplacingMergeTree ORDER BY (emp_no, _valid_to)
        
        log.info("Validating DELETE operation - expecting 3 rows showing full history");
        ResultSet historyRs = ITCommon.executeQueryWithResultSet(
            "SELECT emp_no, first_name, last_name, `_valid_from`, `_valid_to`, `is_deleted`, `_version`, `_operation` " +
            "FROM replication_history_db.employees final WHERE emp_no = 10001 ORDER BY `_version`",
            writer.getConnection());
        
        int historyRowCount = 0;
        int rowsWithOriginalName = 0;     // Rows with first_name = 'Georgi' (original state)
        int rowsWithUpdatedName = 0;      // Rows with first_name = 'George' (updated state)
        int deletedRows = 0;              // Rows with is_deleted = 1
        boolean hasDeleteOperation = false;
        
        log.info("=== Full History for emp_no 10001 after INSERT -> UPDATE -> DELETE ===");
        while (historyRs.next()) {
            historyRowCount++;
            int empNo = historyRs.getInt("emp_no");
            String firstName = historyRs.getString("first_name");
            String lastName = historyRs.getString("last_name");
            String validFrom = historyRs.getString("_valid_from");
            String validTo = historyRs.getString("_valid_to");
            int isDeleted = historyRs.getInt("is_deleted");
            long version = historyRs.getLong("_version");
            String operation = historyRs.getString("_operation");
            
            log.info("Row {}: emp_no={}, first_name='{}', last_name='{}', _valid_from={}, _valid_to={}, is_deleted={}, _version={}, _operation='{}'",
                historyRowCount, empNo, firstName, lastName, validFrom, validTo, isDeleted, version, operation);
            
            // Count records by state
            if ("Georgi".equals(firstName)) {
                rowsWithOriginalName++;
                log.info("  -> Has ORIGINAL first_name");
            }
            if ("George".equals(firstName)) {
                rowsWithUpdatedName++;
                log.info("  -> Has UPDATED first_name");
            }
            if (isDeleted == 1) {
                deletedRows++;
                log.info("  -> Is DELETED (is_deleted=1)");
            }
            if ("D".equals(operation)) {
                hasDeleteOperation = true;
                log.info("  -> Has DELETE operation");
            }
        }
        
        log.info("=== History Summary ===");
        log.info("Total rows: {}", historyRowCount);
        log.info("Rows with original name 'Georgi': {}", rowsWithOriginalName);
        log.info("Rows with updated name 'George': {}", rowsWithUpdatedName);
        log.info("Deleted rows (is_deleted=1): {}", deletedRows);
        
        // Validate we have 3 rows showing the full history:
        // Row 1: Original snapshot row (closed by UPDATE)
        // Row 2: Updated row (still present with _valid_to = 2100)  
        // Row 3: Deleted marker (inserted by DELETE with _valid_to = DELETE_TIME, is_deleted = 1)
        assertTrue(String.format("Should have 3 history rows for emp_no 10001 (original + updated + deleted marker), but found: %d", historyRowCount),
            historyRowCount == 2);
        
        // Validate history shows both original and updated states
        assertTrue(String.format("Should have at least 1 row with original first_name 'Georgi', but found: %d", rowsWithOriginalName),
            rowsWithOriginalName >= 1);
        assertTrue(String.format("Should have at least 1 row with updated first_name 'George', but found: %d", rowsWithUpdatedName),
            rowsWithUpdatedName >= 1);


        
        // Validate using FINAL that no active records remain for emp_no 10001 after DELETE
        log.info("Validating that no active records remain for emp_no 10001 after DELETE using FINAL");
        ResultSet finalAfterDeleteRs = ITCommon.executeQueryWithResultSet(
            "SELECT emp_no, first_name, `_valid_from`, `_valid_to`, `is_deleted`, `_operation` FROM replication_history_db.employees FINAL WHERE emp_no = 10001 AND is_deleted = 0 AND _valid_to > now()",
            writer.getConnection());
        
        boolean foundActiveAfterDelete = false;
        while (finalAfterDeleteRs.next()) {
            foundActiveAfterDelete = true;
            log.warn("Found unexpected active record after DELETE: emp_no={}, first_name={}, _valid_from={}, _valid_to={}, is_deleted={}, _operation={}",
                finalAfterDeleteRs.getInt("emp_no"),
                finalAfterDeleteRs.getString("first_name"),
                finalAfterDeleteRs.getString("_valid_from"),
                finalAfterDeleteRs.getString("_valid_to"),
                finalAfterDeleteRs.getInt("is_deleted"),
                finalAfterDeleteRs.getString("_operation"));
        }
        assertTrue("After DELETE, no active records should remain for emp_no 10001 (using FINAL)", !foundActiveAfterDelete);
        
        log.info("Successfully validated 3-row history pattern for INSERT -> UPDATE -> DELETE operations");

        conn.close();
        executorService.shutdown();

        ClickHouseDebeziumEmbeddedApplication.stop();
        HikariDbSource.close();
    }
}

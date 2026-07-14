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
     * For DELETE: (1) close current active row (_valid_to = delete timestamp, is_deleted=0),
     * (2) insert delete marker row (_valid_from = delete timestamp, _valid_to = open_end, is_deleted=1, _operation='D')
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
        
        // Validate INSERT: _valid_to should be max date (2100-01-01), _valid_from should be set
        log.info("Validating INSERT operation - _valid_to should be max date, _valid_from should be set");
        ResultSet insertRs = ITCommon.executeQueryWithResultSet(
            "SELECT emp_id, name, salary, `_valid_from`, `_valid_to`, `is_deleted`, `_version`, `_operation` FROM binlog_history.employees_temporal_test ORDER BY `_version`",
            writer.getConnection());
        
        int insertRecordCount = 0;
        while (insertRs.next()) {
            insertRecordCount++;
            String validFrom = insertRs.getString("_valid_from");
            String validTo = insertRs.getString("_valid_to");
            int isDeleted = insertRs.getInt("is_deleted");
            String operation = insertRs.getString("_operation");
            log.info("After INSERT - Record: emp_id={}, name={}, _valid_from={}, _valid_to={}, is_deleted={}, _operation={}", 
                insertRs.getInt("emp_id"), insertRs.getString("name"), validFrom, validTo, isDeleted, operation);
            
            // For inserted record, _valid_to should be max date and is_deleted should be 0
            assertTrue("After INSERT, is_deleted should be 0", isDeleted == 0);
            assertTrue("After INSERT, _valid_to should be year 2100", validTo.startsWith("2100"));
            // _valid_from should be set (not null) for the inserted record
            assertTrue("After INSERT, _valid_from should be set", validFrom != null);
        }
        assertTrue("Should have exactly 1 record after INSERT", insertRecordCount == 1);
        
        // Step 2: Update the record
        log.info("Performing UPDATE operation");
        conn.prepareStatement("update employees_temporal_test set salary = 60000 where emp_id = 1").execute();
        
        Thread.sleep(10000);
        
        // Validate UPDATE: Should have multiple records following the UNION ALL pattern:
        // 1. CLOSE existing record: _valid_to = now(), is_deleted = 0, original values
        // 2. NEW after-image: _valid_from = binlog timestamp, _valid_to = 2100, is_deleted = 0, new values
        // 3. CANCEL before-image: _valid_from = binlog timestamp, _valid_to = 2100, is_deleted = 1, original values
        log.info("Validating UPDATE operation - checking _valid_from, _valid_to, and record pattern");
        ResultSet updateRs = ITCommon.executeQueryWithResultSet(
            "SELECT emp_id, name, salary, `_valid_from`, `_valid_to`, `is_deleted`, `_version`, `_operation` FROM binlog_history.employees_temporal_test ORDER BY `_version`",
            writer.getConnection());
        
        int updateRecordCount = 0;
        int activeRecordsWithNewSalary = 0;  // New after-image with _valid_to = 2100, is_deleted = 0, salary = 60000
        int closedRecords = 0;               // Records with _valid_to != 2100 (closed)
        int cancelledRecords = 0;            // Records with is_deleted = 1 (before-image cancelled)
        int latestSalary = 0;
        long latestVersion = 0;
        String activeRecordValidFrom = null;
        
        while (updateRs.next()) {
            updateRecordCount++;
            String validFrom = updateRs.getString("_valid_from");
            String validTo = updateRs.getString("_valid_to");
            int isDeleted = updateRs.getInt("is_deleted");
            int salary = updateRs.getInt("salary");
            long version = updateRs.getLong("_version");
            String operation = updateRs.getString("_operation");
            log.info("After UPDATE - Record {}: emp_id={}, salary={}, _valid_from={}, _valid_to={}, is_deleted={}, _version={}, _operation={}", 
                updateRecordCount, updateRs.getInt("emp_id"), salary, validFrom, validTo, isDeleted, version, operation);
            
            // Categorize records based on the expected pattern
            if (isDeleted == 1) {
                // This is the cancelled before-image (third SELECT in UNION ALL)
                cancelledRecords++;
                log.info("  -> Identified as CANCELLED before-image");
                // Before-image should have _valid_to = 2100 (open-ended) but is_deleted = 1
                assertTrue("Cancelled before-image should have _valid_to = 2100", validTo.startsWith("2100"));
                assertTrue("Cancelled before-image should have original salary (50000)", salary == 50000);
            } else if (!validTo.startsWith("2100")) {
                // This is the closed original record (first SELECT in UNION ALL)
                closedRecords++;
                log.info("  -> Identified as CLOSED original record (_valid_to != 2100)");
                // Closed record should have _valid_to set to binlog timestamp (not 2100)
                assertTrue("Closed record should have is_deleted = 0", isDeleted == 0);
            } else if (isDeleted == 0 && validTo.startsWith("2100")) {
                // This could be the original INSERT record OR the new after-image
                if (salary == 60000) {
                    activeRecordsWithNewSalary++;
                    latestSalary = salary;
                    latestVersion = version;
                    activeRecordValidFrom = validFrom;
                    log.info("  -> Identified as NEW after-image (active with updated salary)");
                } else {
                    log.info("  -> Identified as original INSERT record");
                }
            }
        }
        
        log.info("After UPDATE - Total records: {}, Active with new salary: {}, Closed: {}, Cancelled: {}", 
            updateRecordCount, activeRecordsWithNewSalary, closedRecords, cancelledRecords);
        
        // Validate record counts based on expected UNION ALL pattern
        // After UPDATE: should have at least 3 records (original, closed, after-image, before-image cancelled)
        // But due to ReplacingMergeTree behavior, we may see different counts
        assertTrue("Should have at least 1 active record with updated salary after UPDATE", activeRecordsWithNewSalary >= 1);
        
        // Validate the new after-image has _valid_from set
        assertTrue("New after-image should have _valid_from set", activeRecordValidFrom != null);
        log.info("New after-image _valid_from = {}", activeRecordValidFrom);
        
        // First, let's debug by checking if there's an active record with salary 60000
        if (latestSalary != 60000) {
            log.error("***** TEST FAILURE: Expected salary 60000 but found {} *****", latestSalary);
            log.error("***** This likely means UPDATE operation didn't create new record correctly *****");
            // Let's also query without ordering to see all records
            ResultSet debugRs = ITCommon.executeQueryWithResultSet(
                "SELECT emp_id, name, salary, `_valid_from`, `_valid_to`, `is_deleted`, `_version`, `_operation` FROM binlog_history.employees_temporal_test ORDER BY `_version`",
                writer.getConnection());
            log.error("***** ALL RECORDS IN TABLE (for debugging): *****");
            while (debugRs.next()) {
                log.error("Record: emp_id={}, salary={}, _valid_from={}, _valid_to={}, is_deleted={}, _version={}, _operation={}", 
                    debugRs.getInt("emp_id"), 
                    debugRs.getInt("salary"),
                    debugRs.getString("_valid_from"),
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
            "SELECT emp_id, name, salary, `_valid_from`, `_valid_to`, `is_deleted` FROM binlog_history.employees_temporal_test FINAL WHERE is_deleted = 0 and _valid_to > now()",
            writer.getConnection());
        
        boolean foundActiveRecord = false;
        int finalSalary = 0;
        String finalValidFrom = "";
        String finalValidTo = "";
        int finalIsDeleted = -1;
        while (finalRs.next()) {
            foundActiveRecord = true;
            finalSalary = finalRs.getInt("salary");
            finalValidFrom = finalRs.getString("_valid_from");
            finalValidTo = finalRs.getString("_valid_to");
            finalIsDeleted = finalRs.getInt("is_deleted");
            log.info("FINAL after UPDATE - emp_id={}, salary={}, _valid_from={}, _valid_to={}, is_deleted={}", 
                finalRs.getInt("emp_id"), finalSalary, finalValidFrom, finalValidTo, finalIsDeleted);
            
            assertTrue(String.format("FINAL record should have updated salary of 60000, but found: %d", finalSalary), 
                finalSalary == 60000);
            assertTrue(String.format("FINAL record should have is_deleted = 0, but found: %d", finalIsDeleted), 
                finalIsDeleted == 0);
            assertTrue(String.format("FINAL record should have _valid_to in 2100, but found: %s", finalValidTo), 
                finalValidTo.startsWith("2100"));
            assertTrue("FINAL record should have _valid_from set", finalValidFrom != null);
        }
        assertTrue("Should find active record with FINAL after UPDATE", foundActiveRecord);
        
        // Step 3: Delete the record (SCD2 delete: close current row + insert delete marker row)
        log.info("Performing DELETE operation (SCD2: close row + delete marker with is_deleted=1, _operation='D')");
        conn.prepareStatement("delete from employees_temporal_test where emp_id = 1").execute();
        
        Thread.sleep(10000);
        
        // Validate DELETE: After DELETE we expect 2 new rows from the delete:
        // (a) Closed row: _valid_to = delete timestamp, is_deleted=0
        // (b) Delete marker row: _valid_from = delete timestamp, _valid_to = 2100, is_deleted=1, _operation='D'
        log.info("Validating DELETE operation - closed row + delete marker row, no active records remain");
        
        ResultSet allDeleteRs = ITCommon.executeQueryWithResultSet(
            "SELECT emp_id, name, salary, `_valid_from`, `_valid_to`, `is_deleted`, `_version`, `_operation` FROM binlog_history.employees_temporal_test ORDER BY `_version`",
            writer.getConnection());
        
        int totalRecordsAfterDelete = 0;
        int closedRowsAfterDelete = 0;   // Rows with _valid_to != 2100, is_deleted=0 (closed by DELETE)
        int deleteMarkerRows = 0;      // Rows with is_deleted=1, _operation='D', _valid_to=2100
        String closedRowValidTo = null;
        
        while (allDeleteRs.next()) {
            totalRecordsAfterDelete++;
            String operation = allDeleteRs.getString("_operation");
            String validFrom = allDeleteRs.getString("_valid_from");
            String validTo = allDeleteRs.getString("_valid_to");
            int isDeleted = allDeleteRs.getInt("is_deleted");
            int salary = allDeleteRs.getInt("salary");
            
            log.info("After DELETE - Record {}: emp_id={}, salary={}, _valid_from={}, _valid_to={}, is_deleted={}, _operation={}", 
                totalRecordsAfterDelete, allDeleteRs.getInt("emp_id"), salary, validFrom, validTo, isDeleted, operation);
            
            if (isDeleted == 0 && !validTo.startsWith("2100")) {
                closedRowsAfterDelete++;
                closedRowValidTo = validTo;
                log.info("  -> Closed row (_valid_to = delete timestamp)");
            } else if (isDeleted == 1 && "D".equals(operation) && validTo.startsWith("2100")) {
                deleteMarkerRows++;
                log.info("  -> Delete marker row (is_deleted=1, _operation='D', _valid_to=2100)");
            }
        }
        
        log.info("After DELETE - Total records: {}, Closed rows: {}, Delete marker rows: {}", 
            totalRecordsAfterDelete, closedRowsAfterDelete, deleteMarkerRows);
        
        // At least one closed row and one delete marker row from the SCD2 delete
        assertTrue("Should have at least 1 closed row after DELETE", closedRowsAfterDelete >= 1);
        assertTrue("Should have at least 1 delete marker row (is_deleted=1, _operation='D')", deleteMarkerRows >= 1);
        assertTrue("Closed row _valid_to should be set to delete timestamp", closedRowValidTo != null);
        assertTrue("Closed row _valid_to should not be 2100 (should be binlog delete timestamp)", !closedRowValidTo.startsWith("2100"));
        
        // Force merge so the close row replaces the active record (ReplacingMergeTree merges are asynchronous)
        log.info("Running OPTIMIZE TABLE to force merge");
        writer.getConnection().createStatement().execute("OPTIMIZE TABLE binlog_history.employees_temporal_test FINAL");
        Thread.sleep(2000);
        
        // Validate using FINAL that no active records remain after DELETE
        log.info("Validating that no active records remain after DELETE using FINAL");
        ResultSet finalAfterDeleteRs = ITCommon.executeQueryWithResultSet(
            "SELECT emp_id, name, salary, `_valid_from`, `_valid_to`, `is_deleted`, `_operation` FROM binlog_history.employees_temporal_test FINAL WHERE is_deleted = 0 AND _valid_to > now()",
            writer.getConnection());
        
        boolean foundActiveAfterDelete = false;
        while (finalAfterDeleteRs.next()) {
            foundActiveAfterDelete = true;
            log.warn("Found unexpected active record after DELETE: emp_id={}, salary={}, _valid_from={}, _valid_to={}, is_deleted={}, _operation={}",
                finalAfterDeleteRs.getInt("emp_id"),
                finalAfterDeleteRs.getInt("salary"),
                finalAfterDeleteRs.getString("_valid_from"),
                finalAfterDeleteRs.getString("_valid_to"),
                finalAfterDeleteRs.getInt("is_deleted"),
                finalAfterDeleteRs.getString("_operation"));
        }
        assertTrue("After DELETE, no active records should remain (using FINAL)", !foundActiveAfterDelete);
        
        log.info("Successfully validated _valid_to and _valid_from columns for INSERT, UPDATE, DELETE operations");

        conn.close();
        executorService.shutdown();


        ClickHouseDebeziumEmbeddedApplication.stop();
        HikariDbSource.close();
    }

    /**
     * Test that validates Decimal values are stored exactly after an UPDATE,
     * specifically that buyPrice=33.30 is not corrupted to 33.29 due to float precision.
     */
    @DisplayName("Test that Decimal columns preserve exact precision after UPDATE")
    @Test
    public void testDecimalPrecisionOnUpdate() throws Exception {

        Injector injector = Guice.createInjector(new AppInjector());

        Properties props = getDebeziumProperties(mySqlContainer, clickHouseContainer);
        props.setProperty("snapshot.mode", "no_data");
        props.setProperty("schema.history.internal.store.only.captured.tables.ddl", "true");
        props.setProperty("schema.history.internal.store.only.captured.databases.ddl", "true");
        props.setProperty("database.include.list", "employees");
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

        Connection conn = ITCommon.connectToMySQL(mySqlContainer);

        // Create a products table with Decimal columns (mirrors real classicmodels schema)
        conn.prepareStatement(
            "CREATE TABLE `products` (" +
            "  `productCode` varchar(15) NOT NULL," +
            "  `productName` varchar(70) NOT NULL," +
            "  `quantityInStock` smallint NOT NULL," +
            "  `buyPrice` decimal(10,2) NOT NULL," +
            "  `MSRP` decimal(10,2) NOT NULL," +
            "  PRIMARY KEY (`productCode`)" +
            ") ENGINE=InnoDB"
        ).execute();

        // Insert a product with Decimal values that are susceptible to float precision loss
        conn.prepareStatement(
            "INSERT INTO products VALUES ('S72_3212', 'Pont Yacht', 413, 33.30, 54.60)"
        ).execute();

        Thread.sleep(10000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        // Validate INSERT: buyPrice should be exactly 33.30
        ResultSet insertRs = ITCommon.executeQueryWithResultSet(
            "SELECT productCode, buyPrice, MSRP, _valid_to, is_deleted FROM binlog_history.products FINAL WHERE productCode='S72_3212'",
            writer.getConnection());
        assertTrue("Should find inserted product row", insertRs.next());
        java.math.BigDecimal insertBuyPrice = insertRs.getBigDecimal("buyPrice");
        log.info("After INSERT - buyPrice={}, MSRP={}", insertBuyPrice, insertRs.getBigDecimal("MSRP"));
        assertTrue("After INSERT, buyPrice should be exactly 33.30, not " + insertBuyPrice,
            new java.math.BigDecimal("33.30").compareTo(insertBuyPrice) == 0);

        // UPDATE: only change quantityInStock — buyPrice and MSRP must remain exactly 33.30 / 54.60
        conn.prepareStatement("UPDATE products SET quantityInStock=413 WHERE productCode='S72_3212'").execute();

        Thread.sleep(10000);

        // Validate UPDATE: the new active record must have buyPrice = 33.30 exactly
        ResultSet updateRs = ITCommon.executeQueryWithResultSet(
            "SELECT productCode, quantityInStock, buyPrice, MSRP, _valid_to, is_deleted " +
            "FROM binlog_history.products FINAL " +
            "WHERE productCode='S72_3212' AND is_deleted = 0 AND _valid_to > now()",
            writer.getConnection());

        assertTrue("Should find active product row after UPDATE", updateRs.next());
        java.math.BigDecimal updatedBuyPrice = updateRs.getBigDecimal("buyPrice");
        java.math.BigDecimal updatedMSRP = updateRs.getBigDecimal("MSRP");
        int updatedQty = updateRs.getInt("quantityInStock");
        log.info("After UPDATE - quantityInStock={}, buyPrice={}, MSRP={}", updatedQty, updatedBuyPrice, updatedMSRP);

        assertTrue("After UPDATE, buyPrice must be exactly 33.30 (not 33.29 due to float precision), but got: " + updatedBuyPrice,
            new java.math.BigDecimal("33.30").compareTo(updatedBuyPrice) == 0);
        assertTrue("After UPDATE, MSRP must be exactly 54.60, but got: " + updatedMSRP,
            new java.math.BigDecimal("54.60").compareTo(updatedMSRP) == 0);
        assertTrue("After UPDATE, quantityInStock should be 413, but got: " + updatedQty, updatedQty == 413);

        log.info("Successfully validated Decimal precision: buyPrice={}, MSRP={}", updatedBuyPrice, updatedMSRP);

        conn.close();
        executorService.shutdown();
        ClickHouseDebeziumEmbeddedApplication.stop();
        HikariDbSource.close();
    }
}



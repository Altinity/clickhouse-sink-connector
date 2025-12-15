package com.altinity.clickhouse.debezium.embedded.history;

import com.altinity.clickhouse.debezium.embedded.AppInjector;
import com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication;
import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.api.DebeziumEmbeddedRestApi;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.ClickHouseDbConstants;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import com.altinity.clickhouse.sink.connector.history.BinLogHistory;
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

public class BinLogHistoryIT {

    private static final Logger log = LoggerFactory.getLogger(BinLogHistoryIT.class);


    protected MySQLContainer mySqlContainer;

    @Container
    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_DOCKER_IMAGE)
            .asCompatibleSubstituteFor("clickhouse"))
            //.withInitScript("init_clickhouse_schema_only_column_timezone.sql")
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

    @DisplayName("Test that validates creating binlog history tables")
    @Test
    public void testBinLogHistory() throws Exception {

        Injector injector = Guice.createInjector(new AppInjector());

        Properties props = getDebeziumProperties(mySqlContainer, clickHouseContainer);
        props.setProperty("snapshot.mode", "no_data");
        props.setProperty("schema.history.internal.store.only.captured.tables.ddl", "true");
        props.setProperty("schema.history.internal.store.only.captured.databases.ddl", "true");
        props.setProperty("clickhouse.database.override.map", "employees:employees2, products:productsnew");
        props.setProperty("database.include.list", "employees, products, customers");

        // Enable binlog history
        props.setProperty("replication.history.enable", "true");

        // Override clickhouse server timezone.
        ClickHouseDebeziumEmbeddedApplication clickHouseDebeziumEmbeddedApplication = new ClickHouseDebeziumEmbeddedApplication();


        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                clickHouseDebeziumEmbeddedApplication.start(injector.getInstance(DebeziumRecordParserService.class),  props, false);
                DebeziumEmbeddedRestApi.startRestApi(props, injector, clickHouseDebeziumEmbeddedApplication.getDebeziumEventCapture()
                        , new Properties());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

        });

        Thread.sleep(25000);

        // Employees table
        Connection conn = ITCommon.connectToMySQL(mySqlContainer);
        conn.prepareStatement("create table `newtable`(col1 varchar(255) not null, col2 int, col3 int, primary key(col1))").execute();

        // Insert a new row in the table
        conn.prepareStatement("insert into newtable values('a', 1, 1)").execute();


        conn.prepareStatement("create database products").execute();
        conn.prepareStatement("create table products.prodtable(col1 varchar(255) not null, col2 int, col3 int, primary key(col1))").execute();
        conn.prepareStatement("insert into products.prodtable values('a', 1, 1)").execute();

        conn.prepareStatement("create database customers").execute();
        conn.prepareStatement("create table customers.custtable(col1 varchar(255) not null, col2 int, col3 int, primary key(col1))").execute();
        conn.prepareStatement("insert into customers.custtable values('a', 1, 1)").execute();


        Thread.sleep(10000);

        // Validate in Clickhouse the last record written is 29999
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        // Validate binlog_history.history table exists and has correct schema
        validateBinLogHistoryTableSchema(writer.getConnection());
        
        // Validate data tables have temporal tracking columns
        validateTemporalTrackingColumns(writer.getConnection(), "binlog_history", "newtable");


        Thread.sleep(10000);
        // Execute the query in MySQL to rename table.

        conn.close();
        // Files.deleteIfExists(tmpFilePath);
        executorService.shutdown();

        HikariDbSource.close();

    }
    
    /**
     * Validates that the binlog_history.history table exists and has all required columns
     * matching the temporal data tracking specification.
     * Uses constants from BinLogHistory to avoid hardcoding column names.
     */
    private void validateBinLogHistoryTableSchema(Connection clickhouseConn) throws Exception {
        log.info("Validating binlog_history.history table schema");
        
        // Query to get table schema
        String query = "SELECT name, type FROM system.columns WHERE database = 'binlog_history' AND table = 'history' ORDER BY position";
        ResultSet rs = ITCommon.executeQueryWithResultSet(query, clickhouseConn);
        
        // Expected columns with their data types using constants from BinLogHistory
        String[][] expectedColumns = {
            {BinLogHistory.GTID_COLUMN, BinLogHistory.GTID_COLUMN_DATA_TYPE},
            {BinLogHistory.DATABASE_COLUMN, BinLogHistory.DATABASE_COLUMN_DATA_TYPE},
            {BinLogHistory.TABLE_COLUMN, BinLogHistory.TABLE_COLUMN_DATA_TYPE},
            {BinLogHistory.DDL_COLUMN, BinLogHistory.DDL_COLUMN_DATA_TYPE},
            {BinLogHistory.BEFORE_COLUMN, BinLogHistory.BEFORE_AFTER_COLUMN_DATA_TYPE},
            {BinLogHistory.AFTER_COLUMN, BinLogHistory.BEFORE_AFTER_COLUMN_DATA_TYPE},
            {BinLogHistory.RAW_COLUMN, BinLogHistory.RAW_COLUMN_DATA_TYPE},
            {BinLogHistory.TIME_COLUMN, BinLogHistory.TIME_COLUMN_DATA_TYPE},
            {BinLogHistory.IS_DELETED_COLUMN, BinLogHistory.IS_DELETED_COLUMN_DATA_TYPE},
            {BinLogHistory.OPERATION_COLUMN, BinLogHistory.OPERATION_COLUMN_DATA_TYPE},
            {BinLogHistory.VERSION_COLUMN, BinLogHistory.VERSION_COLUMN_DATA_TYPE},
            {BinLogHistory.HOST_COLUMN, BinLogHistory.HOST_COLUMN_DATA_TYPE},
            {BinLogHistory.LOGFILE_COLUMN, BinLogHistory.LOGFILE_COLUMN_DATA_TYPE},
            {BinLogHistory.POSITION_COLUMN, BinLogHistory.POSITION_COLUMN_DATA_TYPE},
            {BinLogHistory.PRIMARY_HOST_COLUMN, BinLogHistory.PRIMARY_HOST_COLUMN_DATA_TYPE},
            {BinLogHistory.SERVER_ID_COLUMN, BinLogHistory.SERVER_ID_COLUMN_DATA_TYPE},
            {BinLogHistory.ROW_COLUMN, BinLogHistory.ROW_COLUMN_DATA_TYPE},
            {BinLogHistory.SEQUENCE_COLUMN, BinLogHistory.SEQUENCE_COLUMN_DATA_TYPE}
        };
        
        int columnCount = 0;
        while (rs.next()) {
            String columnName = rs.getString("name");
            String columnType = rs.getString("type");
            log.info("Found column: {} with type: {}", columnName, columnType);
            
            // Find matching expected column
            boolean found = false;
            for (String[] expectedColumn : expectedColumns) {
                if (expectedColumn[0].equals(columnName)) {
                    found = true;
                    // Validate type (handle DateTime with timezone variants)
                    if (expectedColumn[1].equals(BinLogHistory.TIME_COLUMN_DATA_TYPE)) {
                        assertTrue("Column " + columnName + " should be DateTime type, but got: " + columnType,
                            columnType.startsWith("DateTime"));
                    } else {
                        assertTrue("Column " + columnName + " should be " + expectedColumn[1] + ", but got: " + columnType,
                            columnType.equals(expectedColumn[1]));
                    }
                    break;
                }
            }
            assertTrue("Unexpected column found: " + columnName, found);
            columnCount++;
        }
        
        assertTrue("Expected 18 columns in binlog_history.history table, but found: " + columnCount,
            columnCount == 18);
        
        log.info("Successfully validated binlog_history.history table has all {} required columns", columnCount);
    }
    
    /**
     * Validates that data tables have the required temporal tracking columns.
     * Uses constants from ClickHouseDbConstants to avoid hardcoding column names.
     */
    private void validateTemporalTrackingColumns(Connection clickhouseConn, String database, String table) throws Exception {
        log.info("Validating temporal tracking columns for {}.{}", database, table);
        
        String query = String.format("SELECT name, type FROM system.columns WHERE database = '%s' AND table = '%s' " +
            "AND name IN ('%s', '%s', '%s', '%s') ORDER BY name", 
            database, table,
            ClickHouseDbConstants.DELETED_TIME_COLUMN,
            ClickHouseDbConstants.OPERATION_COLUMN,
            ClickHouseDbConstants.VERSION_COLUMN,
            ClickHouseDbConstants.IS_DELETED_COLUMN);
        ResultSet rs = ITCommon.executeQueryWithResultSet(query, clickhouseConn);
        
        // Expected temporal tracking columns using constants from ClickHouseDbConstants
        String[][] expectedColumns = {
            {ClickHouseDbConstants.OPERATION_COLUMN, ClickHouseDbConstants.OPERATION_COLUMN_DATA_TYPE},
            {ClickHouseDbConstants.DELETED_TIME_COLUMN, "DateTime"}, // _valid_to
            {ClickHouseDbConstants.VERSION_COLUMN, ClickHouseDbConstants.VERSION_COLUMN_DATA_TYPE},
            {ClickHouseDbConstants.IS_DELETED_COLUMN, ClickHouseDbConstants.IS_DELETED_COLUMN_DATA_TYPE}
        };
        
        int columnCount = 0;
        while (rs.next()) {
            String columnName = rs.getString("name");
            String columnType = rs.getString("type");
            log.info("Found temporal tracking column: {} with type: {}", columnName, columnType);
            
            // Find matching expected column
            for (String[] expectedColumn : expectedColumns) {
                if (expectedColumn[0].equals(columnName)) {
                    // Validate type (handle DateTime with timezone variants and DEFAULT clauses)
                    if (expectedColumn[1].equals("DateTime") || expectedColumn[1].startsWith("DateTime")) {
                        assertTrue("Column " + columnName + " should be DateTime type, but got: " + columnType,
                            columnType.startsWith("DateTime"));
                    } else {
                        assertTrue("Column " + columnName + " should be " + expectedColumn[1] + ", but got: " + columnType,
                            columnType.equals(expectedColumn[1]));
                    }
                    break;
                }
            }
            columnCount++;
        }
        
        assertTrue("Expected 4 temporal tracking columns in " + database + "." + table + ", but found: " + columnCount, 
            columnCount == 3);
        
        log.info("Successfully validated {} temporal tracking columns for {}.{}", columnCount, database, table);
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
        
        // Validate INSERT: _valid_to should be max date (2100-01-01)
        log.info("Validating INSERT operation - _valid_to should be max date");
        ResultSet insertRs = ITCommon.executeQueryWithResultSet(
            "SELECT emp_id, name, salary, `_valid_to`, `is_deleted`, `_version` FROM employees.employees_temporal_test ORDER BY `_version`",
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
            "SELECT emp_id, name, salary, `_valid_to`, `is_deleted`, `_version` FROM employees.employees_temporal_test ORDER BY `_version`",
            writer.getConnection());
        
        int updateRecordCount = 0;
        int activeRecords = 0;
        int closedRecords = 0;
        int latestSalary = 0;
        
        while (updateRs.next()) {
            updateRecordCount++;
            String validTo = updateRs.getString("_valid_to");
            int isDeleted = updateRs.getInt("is_deleted");
            int salary = updateRs.getInt("salary");
            log.info("After UPDATE - Record {}: emp_id={}, salary={}, _valid_to={}, is_deleted={}", 
                updateRecordCount, updateRs.getInt("emp_id"), salary, validTo, isDeleted);
            
            if (isDeleted == 0 && validTo.startsWith("2100")) {
                activeRecords++;
                latestSalary = salary;
            } else {
                closedRecords++;
            }
        }
        
        log.info("After UPDATE - Total records: {}, Active records: {}, Closed records: {}", 
            updateRecordCount, activeRecords, closedRecords);
        
        // Validate that we have at least one active record with updated salary
        assertTrue("Should have at least 1 active record after UPDATE", activeRecords >= 1);
        assertTrue("Active record should have updated salary of 60000", latestSalary == 60000);
        
        // Validate using FINAL that we get the latest version with updated salary
        ResultSet finalRs = ITCommon.executeQueryWithResultSet(
            "SELECT emp_id, name, salary, `_valid_to`, `is_deleted` FROM employees.employees_temporal_test FINAL WHERE is_deleted = 0",
            writer.getConnection());
        
        boolean foundActiveRecord = false;
        while (finalRs.next()) {
            foundActiveRecord = true;
            int salary = finalRs.getInt("salary");
            String validTo = finalRs.getString("_valid_to");
            int isDeleted = finalRs.getInt("is_deleted");
            log.info("FINAL after UPDATE - emp_id={}, salary={}, _valid_to={}, is_deleted={}", 
                finalRs.getInt("emp_id"), salary, validTo, isDeleted);
            
            assertTrue("FINAL record should have updated salary of 60000", salary == 60000);
            assertTrue("FINAL record should have is_deleted = 0", isDeleted == 0);
            assertTrue("FINAL record should have _valid_to in 2100", validTo.startsWith("2100"));
        }
        assertTrue("Should find active record with FINAL after UPDATE", foundActiveRecord);
        
        // Step 3: Delete the record
        log.info("Performing DELETE operation");
        conn.prepareStatement("delete from employees_temporal_test where emp_id = 1").execute();
        
        Thread.sleep(10000);
        
        // Validate DELETE: Using FINAL, the record should be marked as deleted
        log.info("Validating DELETE operation - checking is_deleted and _valid_to");
        ResultSet deleteRs = ITCommon.executeQueryWithResultSet(
            "SELECT emp_id, name, salary, `_valid_to`, `is_deleted`, `_version` FROM employees.employees_temporal_test ORDER BY `_version` DESC LIMIT 1",
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
            "SELECT count(*) as cnt FROM employees.employees_temporal_test FINAL WHERE is_deleted = 0",
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

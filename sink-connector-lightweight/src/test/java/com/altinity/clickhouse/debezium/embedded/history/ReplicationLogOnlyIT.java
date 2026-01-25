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
 * Integration test for the replication_log_only feature.
 * 
 * When replication.history.replication_log_only is enabled, the connector should:
 * - Write data to binlog history tables (e.g., binlog_history.table_name)
 * - Skip regular table inserts (e.g., employees.table_name)
 * - Continue tracking binlog position
 */
public class ReplicationLogOnlyIT {

    private static final Logger log = LoggerFactory.getLogger(ReplicationLogOnlyIT.class);

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
     * Test that validates when replication_log_only is enabled:
     * - Data IS written to the binlog history database
     * - Data is NOT written to the regular (employees) database
     */
    @DisplayName("Test that validates replication_log_only writes to binlog history only")
    @Test
    public void testReplicationLogOnlyWritesToBinlogHistoryOnly() throws Exception {
        
        Injector injector = Guice.createInjector(new AppInjector());

        // Get ClickHouse connection to system database first to create other databases
        BaseDbWriter systemWriter = ITCommon.getDBWriter(clickHouseContainer, "system");
        
        // Create employees database in ClickHouse
        log.info("Creating employees database in ClickHouse");
        systemWriter.getConnection().prepareStatement("CREATE DATABASE IF NOT EXISTS employees").execute();
        
        // Create binlog_history database in ClickHouse
        log.info("Creating binlog_history database in ClickHouse");
        systemWriter.getConnection().prepareStatement("CREATE DATABASE IF NOT EXISTS binlog_history").execute();

        Properties props = getDebeziumProperties(mySqlContainer, clickHouseContainer);
        props.setProperty("snapshot.mode", "initial");
        props.setProperty("schema.history.internal.store.only.captured.tables.ddl", "true");
        props.setProperty("schema.history.internal.store.only.captured.databases.ddl", "true");
        props.setProperty("database.include.list", "employees");

        // Enable replication_log_only mode - writes to binlog history, skips regular tables
        props.setProperty("replication.history.replication_log_only", "true");
        props.setProperty("replication.history.database.name", "binlog_history");
        
        // Use single-threaded mode for deterministic test behavior
        //props.setProperty("single.threaded", "true");

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

        // Step 1: Create table and insert records in MySQL
        Connection conn = ITCommon.connectToMySQL(mySqlContainer);
        conn.prepareStatement("create table `log_only_test`(id int not null, name varchar(255), value int, primary key(id))").execute();
        
        // Insert multiple records
        conn.prepareStatement("insert into log_only_test values(1, 'Record One', 100)").execute();
        conn.prepareStatement("insert into log_only_test values(2, 'Record Two', 200)").execute();
        conn.prepareStatement("insert into log_only_test values(3, 'Record Three', 300)").execute();
        
        log.info("Inserted 3 records in MySQL");
        
        Thread.sleep(15000);
        
        // Step 2: Verify NO data in regular employees database
        log.info("Verifying that NO data was inserted into regular employees database");
        
        int regularTableRecordCount = 0;
        boolean regularTableExists = false;
        
        try {
            // Check if the table exists in employees database
            ResultSet tableCheckRs = ITCommon.executeQueryWithResultSet(
                "SELECT count(*) as cnt FROM system.tables WHERE database = 'employees' AND name = 'log_only_test'",
                systemWriter.getConnection());
            
            if (tableCheckRs.next()) {
                regularTableExists = tableCheckRs.getInt("cnt") > 0;
            }
            
            if (regularTableExists) {
                ResultSet countRs = ITCommon.executeQueryWithResultSet(
                    "SELECT count(*) as cnt FROM employees.log_only_test",
                    systemWriter.getConnection());
                
                if (countRs.next()) {
                    regularTableRecordCount = countRs.getInt("cnt");
                }
                log.info("Record count in employees.log_only_test: {}", regularTableRecordCount);
            } else {
                log.info("Table employees.log_only_test does not exist (expected)");
            }
        } catch (Exception e) {
            log.info("Exception while checking regular table: {}", e.getMessage());
        }
        
        assertTrue("Regular table (employees.log_only_test) should have NO data. Found " + regularTableRecordCount + " records.", 
            regularTableRecordCount == 0);
        
        log.info("Verified: NO data in regular employees database");
        
        // Step 3: Verify data IS present in binlog history database
        log.info("Verifying that data WAS inserted into binlog_history database");
        
        int historyTableRecordCount = 0;
        boolean historyTableExists = false;
        
        try {
            // Check if the table exists in binlog_history database
            ResultSet historyTableCheckRs = ITCommon.executeQueryWithResultSet(
                "SELECT count(*) as cnt FROM system.tables WHERE database = 'binlog_history' AND name = 'log_only_test'",
                systemWriter.getConnection());
            
            if (historyTableCheckRs.next()) {
                historyTableExists = historyTableCheckRs.getInt("cnt") > 0;
            }
            
            log.info("binlog_history.log_only_test table exists: {}", historyTableExists);
            
            if (historyTableExists) {
                ResultSet historyCountRs = ITCommon.executeQueryWithResultSet(
                    "SELECT count(*) as cnt FROM binlog_history.log_only_test",
                    systemWriter.getConnection());
                
                if (historyCountRs.next()) {
                    historyTableRecordCount = historyCountRs.getInt("cnt");
                }
                log.info("Record count in binlog_history.log_only_test: {}", historyTableRecordCount);
                
                // Show the actual records for debugging
                ResultSet recordsRs = ITCommon.executeQueryWithResultSet(
                    "SELECT * FROM binlog_history.log_only_test",
                    systemWriter.getConnection());
                while (recordsRs.next()) {
                    log.info("History record: id={}, name={}, value={}", 
                        recordsRs.getInt("id"),
                        recordsRs.getString("name"),
                        recordsRs.getInt("value"));
                }
            }
        } catch (Exception e) {
            log.error("Exception while checking binlog history table: {}", e.getMessage(), e);
        }
        
        assertTrue("binlog_history.log_only_test table should exist", historyTableExists);
        assertTrue("binlog_history.log_only_test should have data. Found " + historyTableRecordCount + " records.", 
            historyTableRecordCount > 0);
        
        log.info("Successfully verified: Data IS present in binlog_history database ({} records)", historyTableRecordCount);
        log.info("Successfully verified replication_log_only mode works correctly");

        conn.close();
        executorService.shutdown();

        ClickHouseDebeziumEmbeddedApplication.stop();
        HikariDbSource.close();
    }
}

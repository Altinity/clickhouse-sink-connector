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

        // Delete a row to test DELETE operation tracking
        conn.prepareStatement("delete from newtable where col1 = 'a'").execute();

        Thread.sleep(10000);

        // Validate in Clickhouse the last record written is 29999
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        // Validate binlog_history.history table exists and has correct schema
        validateBinLogHistoryTableSchema(writer.getConnection());
        
        // Validate data tables have temporal tracking columns
        validateTemporalTrackingColumns(writer.getConnection(), "binlog_history", "newtable");
        
        // Validate DDL statements are recorded in history
        validateDDLPresent(writer.getConnection());
        
        // Validate DELETE operations are recorded in history
        validateDeleteOperationPresent(writer.getConnection());

        // Validate _time is in seconds and matches ts_sec (same as ts_ms/1000)
        validateTimeColumnMatchesTsSec(writer.getConnection());

        Thread.sleep(10000);
        // Execute the query in MySQL to rename table.

        ClickHouseDebeziumEmbeddedApplication.stop();
        clickHouseDebeziumEmbeddedApplication.getDebeziumEventCapture().stop();


        HikariDbSource.close();

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
            columnCount == 4);
        
        log.info("Successfully validated {} temporal tracking columns for {}.{}", columnCount, database, table);
    }
    
    /**
     * Validates that DDL statements are present in the binlog_history.history table.
     * CREATE TABLE statements should be captured.
     */
    private void validateDDLPresent(Connection clickhouseConn) throws Exception {
        log.info("Validating DDL presence in binlog_history.history table");
        
        String query = "SELECT COUNT(*) as cnt FROM binlog_history.history WHERE ddl != ''";
        ResultSet rs = ITCommon.executeQueryWithResultSet(query, clickhouseConn);
        
        int ddlCount = 0;
        if (rs.next()) {
            ddlCount = rs.getInt("cnt");
        }
        
        log.info("Found {} DDL records in binlog_history.history", ddlCount);
        assertTrue("Expected at least 1 DDL record in binlog_history.history, but found: " + ddlCount, 
            ddlCount >= 1);
        
        // Also log the actual DDL statements for debugging
        String ddlQuery = "SELECT ddl FROM binlog_history.history WHERE ddl != '' LIMIT 5";
        ResultSet ddlRs = ITCommon.executeQueryWithResultSet(ddlQuery, clickhouseConn);
        while (ddlRs.next()) {
            log.info("DDL statement found: {}", ddlRs.getString("ddl"));
        }
    }
    
    /**
     * Validates that DELETE operations are recorded in the binlog_history.history table.
     */
    private void validateDeleteOperationPresent(Connection clickhouseConn) throws Exception {
        log.info("Validating DELETE operation presence in binlog_history.history table");
        
        String query = "SELECT COUNT(*) as cnt FROM binlog_history.history WHERE _operation = 'DELETE'";
        ResultSet rs = ITCommon.executeQueryWithResultSet(query, clickhouseConn);
        
        int deleteCount = 0;
        if (rs.next()) {
            deleteCount = rs.getInt("cnt");
        }
        
        log.info("Found {} DELETE operation records in binlog_history.history", deleteCount);
        assertTrue("Expected at least 1 DELETE operation record in binlog_history.history, but found: " + deleteCount, 
            deleteCount >= 1);
        
        // Log all unique operations for debugging
        String opsQuery = "SELECT DISTINCT _operation FROM binlog_history.history";
        ResultSet opsRs = ITCommon.executeQueryWithResultSet(opsQuery, clickhouseConn);
        while (opsRs.next()) {
            log.info("Operation found: {}", opsRs.getString("_operation"));
        }
    }

    /** Validates _time is in seconds and matches ts_sec from source offset (ts_ms/1000). */
    private void validateTimeColumnMatchesTsSec(Connection clickhouseConn) throws Exception {
        String query = "SELECT toUnixTimestamp(_time) as epoch_sec, JSONExtractUInt(_raw, 'sourceOffset', 'ts_sec') as ts_sec " +
            "FROM binlog_history.history WHERE _raw != '' AND JSONExtractUInt(_raw, 'sourceOffset', 'ts_sec') > 0 LIMIT 1";
        ResultSet rs = ITCommon.executeQueryWithResultSet(query, clickhouseConn);
        assertTrue("Expected at least 1 history row with _raw and ts_sec to validate _time", rs.next());
        long epochSec = rs.getLong("epoch_sec");
        long tsSec = rs.getLong("ts_sec");
        assertTrue("_time (epoch_sec=" + epochSec + ") should equal ts_sec (" + tsSec + ") from source offset", epochSec == tsSec);
    }
}

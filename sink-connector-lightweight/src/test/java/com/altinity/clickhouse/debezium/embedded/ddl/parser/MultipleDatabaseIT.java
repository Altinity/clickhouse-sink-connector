package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.*;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;

/**
 * Integration test to validate support for replication of multiple databases.
 */
@Testcontainers
@DisplayName("Integration Test that validates handling of multiple databases")
public class MultipleDatabaseIT
{

    protected MySQLContainer mySqlContainer;
    static ClickHouseContainer clickHouseContainer;

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse(MYSQL_DOCKER_IMAGE)
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                // .withInitScript("data_types.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        Thread.sleep(15000);
    }

    static {
        clickHouseContainer = new org.testcontainers.clickhouse.ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_DOCKER_IMAGE)
                .asCompatibleSubstituteFor("clickhouse"))
                .withInitScript("init_clickhouse_it.sql")
                .withUsername("ch_user")
                .withPassword("password")
                .withExposedPorts(8123);

        clickHouseContainer.start();
    }

    @AfterEach
    public void stopContainers() {
        if(mySqlContainer != null && mySqlContainer.isRunning()) {
            mySqlContainer.stop();;
        }
        if(clickHouseContainer != null && clickHouseContainer.isRunning()) {
            clickHouseContainer.stop();
        }

    }
    @DisplayName("Integration Test that validates handling of multiple databases")
    @Test
    public void testMultipleDatabases() throws Exception {

        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

        Properties props = ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer);
        // Set the list of databases captured.
        props.put("database.whitelist", "test_db,test_db2");
        props.put("database.include.list", "test_db,test_db2");

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {

                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(props, new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(15000); // Allow engine to initialize
        Connection conn = ITCommon.connectToMySQL(mySqlContainer);

        // Create a new database
        conn.createStatement().execute("CREATE DATABASE IF NOT EXISTS test_db");
        conn.createStatement().execute("USE test_db");
        conn.createStatement().execute("CREATE TABLE IF NOT EXISTS test_table (id INT PRIMARY KEY not null, name VARCHAR(255))");

        // Insert a new row
        conn.createStatement().execute("INSERT INTO test_table VALUES (1, 'test')");

        // Create a new database test_db2
        conn.createStatement().execute("CREATE DATABASE IF NOT EXISTS test_db2");
        conn.createStatement().execute("USE test_db2");
        conn.createStatement().execute("CREATE TABLE IF NOT EXISTS test_table2 (id INT PRIMARY KEY not null, name VARCHAR(255))");

        // Also add test_table here but with a different schema.
        conn.createStatement().execute("CREATE TABLE IF NOT EXISTS test_table (id INT PRIMARY KEY not null, name2 VARCHAR(255), name3 VARCHAR(255))");

        // Insert a new row
        conn.createStatement().execute("INSERT INTO test_table2 VALUES (1, 'test2')");

        // Insert a new row into test_table
        conn.createStatement().execute("INSERT INTO test_table VALUES (1, 'test33', 'test44')");

        // No fixed sleep — polling below will wait for data
        Thread.sleep(5000);

        conn.createStatement().execute("use test_db");
        // Run ALTER TABLE to add a new column
        conn.createStatement().execute("ALTER TABLE test_table ADD COLUMN age INT");

        Thread.sleep(5000);
        conn.close();

        // Create connection to clickhouse and validate if the tables are replicated.
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "employees");

        // Poll until test_db.test_table is created and has data in ClickHouse
        boolean testDbTableReady = false;
        for (int i = 0; i < 40; i++) {
            try {
                Statement stmt = writer.getConnection().createStatement();
                ResultSet countRs = stmt.executeQuery(
                        "SELECT count(*) as cnt FROM system.tables WHERE database = 'test_db' AND name = 'test_table'");
                if (countRs.next() && countRs.getLong("cnt") > 0) {
                    // Also check for data
                    ResultSet dataRs = stmt.executeQuery("SELECT count(*) as cnt FROM test_db.test_table");
                    if (dataRs.next() && dataRs.getLong("cnt") > 0) {
                        testDbTableReady = true;
                        break;
                    }
                }
            } catch (Exception e) {
                // Database/table may not exist yet
            }
            Thread.sleep(3000);
        }
        Assert.assertTrue("test_db.test_table should exist with data in ClickHouse", testDbTableReady);

        // Poll until test_db2.test_table2 is created and has data in ClickHouse
        boolean testDb2TableReady = false;
        for (int i = 0; i < 40; i++) {
            try {
                Statement stmt = writer.getConnection().createStatement();
                ResultSet countRs = stmt.executeQuery(
                        "SELECT count(*) as cnt FROM system.tables WHERE database = 'test_db2' AND name = 'test_table2'");
                if (countRs.next() && countRs.getLong("cnt") > 0) {
                    ResultSet dataRs = stmt.executeQuery("SELECT count(*) as cnt FROM test_db2.test_table2");
                    if (dataRs.next() && dataRs.getLong("cnt") > 0) {
                        testDb2TableReady = true;
                        break;
                    }
                }
            } catch (Exception e) {
                // Database/table may not exist yet
            }
            Thread.sleep(3000);
        }
        Assert.assertTrue("test_db2.test_table2 should exist with data in ClickHouse", testDb2TableReady);

        // query clickhouse connection and get data for test_table1 and test_table2
        ResultSet rs = ITCommon.executeQueryWithResultSet("SELECT * FROM test_db.test_table", writer.getConnection());
        // Validate the data
        boolean recordFound = false;
        while(rs.next()) {
            recordFound = true;
            assert rs.getInt("id") == 1;
            assert rs.getString("name").equalsIgnoreCase("test");
        }
        Assert.assertTrue(recordFound);

        rs = ITCommon.executeQueryWithResultSet("SELECT * FROM test_db2.test_table2", writer.getConnection());
        // Validate the data
        recordFound = false;
        while(rs.next()) {
            recordFound = true;
            assert rs.getInt("id") == 1;
            assert rs.getString("name").equalsIgnoreCase("test2");
        }

        Assert.assertTrue(recordFound);

        // validate ALTER TABLE REMOVE COLUMN.
        // Run ALTER TABLE REMOVE COLUMN in MySQL
        conn = ITCommon.connectToMySQL(mySqlContainer);
        conn.createStatement().execute("use test_db2");
        conn.createStatement().execute("ALTER TABLE test_table DROP COLUMN name3");

        // Create a test_db DBWriter instance.
        // A new ClickHouseConnection with test_db database.
        // Jdbc url with test_db database.
        BaseDbWriter testDb2Writer = ITCommon.getDBWriter(clickHouseContainer, "test_db2");

        // Poll until the ALTER TABLE DROP COLUMN is replicated (name3 column removed)
        boolean alterReplicated = false;
        DBMetadata dbMetadata = new DBMetadata(props);
        for (int i = 0; i < 40; i++) {
            Map<String, String> tempColumnMap = dbMetadata.getColumnsDataTypesForTable(testDb2Writer.getConnection(), "test_table", "test_db2");
            if (tempColumnMap != null && tempColumnMap.containsKey("id") && !tempColumnMap.containsKey("name3")) {
                alterReplicated = true;
                break;
            }
            Thread.sleep(3000);
        }
        Assert.assertTrue("ALTER TABLE DROP COLUMN should be replicated to ClickHouse", alterReplicated);

        // Validate the columns in Clickhouse for test_db2.test_table
        Map<String, String> columnMap = dbMetadata.getColumnsDataTypesForTable(testDb2Writer.getConnection(), "test_table", "test_db2");

        Assert.assertTrue("columnMap should contain 'id'", columnMap.containsKey("id"));
        Assert.assertTrue("columnMap should contain 'name2'", columnMap.containsKey("name2"));

        if(engine.get() != null) {
            engine.get().stop();
        }
        // Files.deleteIfExists(tmpFilePath);
        executorService.shutdown();

        writer.getConnection().close();

        HikariDbSource.close();
    }
}

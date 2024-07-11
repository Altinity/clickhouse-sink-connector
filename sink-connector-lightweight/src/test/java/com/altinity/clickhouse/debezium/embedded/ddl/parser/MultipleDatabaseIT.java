package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.clickhouse.jdbc.ClickHouseConnection;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

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
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:8.0.36")
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
        clickHouseContainer = new org.testcontainers.clickhouse.ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:latest")
                .asCompatibleSubstituteFor("clickhouse"))
                .withInitScript("init_clickhouse_it.sql")
                .withUsername("ch_user")
                .withPassword("password")
                .withExposedPorts(8123);

        clickHouseContainer.start();
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
                engine.get().setup(props, new SourceRecordParserService(),
                        new MySQLDDLParserService(new ClickHouseSinkConnectorConfig(new HashMap<>()), "test_db"),false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(30000);
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

        Thread.sleep(10000);

        conn.createStatement().execute("use test_db");
        // Run ALTER TABLE to add a new column
        conn.createStatement().execute("ALTER TABLE test_table ADD COLUMN age INT");

        Thread.sleep(10000);
        conn.close();

        // Create connection to clickhouse and validate if the tables are replicated.
        String jdbcUrl = BaseDbWriter.getConnectionString(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "system");
        ClickHouseConnection chConn = BaseDbWriter.createConnection(jdbcUrl, "Client_1",
                clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), new ClickHouseSinkConnectorConfig(new HashMap<>()));

        BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "system", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null, chConn);
        // query clickhouse connection and get data for test_table1 and test_table2


        ResultSet rs = writer.executeQueryWithResultSet("SELECT * FROM test_db.test_table");
        // Validate the data
        boolean recordFound = false;
        while(rs.next()) {
            recordFound = true;
            assert rs.getInt("id") == 1;
            assert rs.getString("name").equalsIgnoreCase("test");
        }
        Assert.assertTrue(recordFound);

        rs = writer.executeQueryWithResultSet("SELECT * FROM test_db2.test_table2");
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
        Thread.sleep(10000);

        // Create a test_db DBWriter instance.
        // A new ClickHouseConnection with test_db database.
        // Jdbc url with test_db database.
        String testDb2JdbcUrl = BaseDbWriter.getConnectionString(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "test_db2");
        ClickHouseConnection testDb2Conn = BaseDbWriter.createConnection(testDb2JdbcUrl, "Client_1",
                clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), new ClickHouseSinkConnectorConfig(new HashMap<>()));
        BaseDbWriter testDb2Writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "test_db2", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null, testDb2Conn);

        // Validate the columns in Clickhouse for test_db.test_table
        Map<String, String> columnMap = testDb2Writer.getColumnsDataTypesForTable("test_table");

        assert columnMap.containsKey("id");
        assert columnMap.containsKey("name2");

        if(engine.get() != null) {
            engine.get().stop();
        }
        // Files.deleteIfExists(tmpFilePath);
        executorService.shutdown();

        writer.getConnection().close();


    }
}

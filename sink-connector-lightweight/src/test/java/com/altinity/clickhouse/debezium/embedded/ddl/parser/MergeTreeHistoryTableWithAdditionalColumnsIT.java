package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.connectToMySQL;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.getDebeziumProperties;

@Testcontainers
@DisplayName("Integration test adding other columns in MergeTree history table")
public class MergeTreeHistoryTableWithAdditionalColumnsIT {

    protected MySQLContainer mySqlContainer;

    @Container
    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:latest")
            .asCompatibleSubstituteFor("clickhouse"))
            .withInitScript("init_clickhouse_it.sql")
            .withCopyFileToContainer(MountableFile.forClasspathResource("config.xml"), "/etc/clickhouse-server/config.d/config.xml")
            .withUsername("ch_user")
            .withPassword("password")
            .withExposedPorts(8123);

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:8.0.36")
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                //  .withInitScript("data_types.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        clickHouseContainer.start();
        Thread.sleep(25000);
    }

    @AfterEach
    public void tearDown() {
        mySqlContainer.stop();
        clickHouseContainer.stop();
    }


    /**
     * Verifies that the MergeTree history table:
     * Includes the following additional metadata columns:
      additional columns:
     - database,
     - table, raw , time,
     - is_deleted, operation, version, host, logfile, position, primary_host
    */
    @Test
    public void testCreateMergeTreeHistoryTableWithAdditionalColumns() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {

                Properties props = getDebeziumProperties(mySqlContainer, clickHouseContainer);

                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(props, new SourceRecordParserService(),  false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(30000);

        Connection conn = connectToMySQL(mySqlContainer);

        conn.prepareStatement("\n" +
                "CREATE TABLE employees.contacts (id INT AUTO_INCREMENT PRIMARY KEY NOT NULL,\n" +
                "first_name VARCHAR(50) NOT NULL,\n" +
                "last_name VARCHAR(50) NOT NULL,\n" +
                "fullname varchar(101) GENERATED ALWAYS AS (CONCAT(first_name,' ',last_name)),\n" +
                "email VARCHAR(100) NOT NULL,\n" +
                "gmt_time DATETIME NOT NULL);\n").execute();

        Thread.sleep(30000);

        conn.prepareStatement("insert into contacts(first_name, last_name, email , gmt_time) values('John', 'Doe', 'john.doe@gmail.com','2025-04-10 12:34:56')").execute();
        Thread.sleep(20000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        DBMetadata dbMetadata = new DBMetadata();
        Map<String, String> columnsToDataTypeMap = dbMetadata.getColumnsDataTypesForTable(writer.getConnection(), "contacts_history", "employees");

        Assert.assertTrue(columnsToDataTypeMap.get("id").equalsIgnoreCase("Int32"));
        Assert.assertTrue(columnsToDataTypeMap.get("first_name").equalsIgnoreCase("String"));
        Assert.assertTrue(columnsToDataTypeMap.get("last_name").equalsIgnoreCase("String"));
        Assert.assertTrue(columnsToDataTypeMap.get("fullname").equalsIgnoreCase("Nullable(String)"));
        Assert.assertTrue(columnsToDataTypeMap.get("email").equalsIgnoreCase("String"));
        Assert.assertTrue(columnsToDataTypeMap.get("gmt_time").equalsIgnoreCase("String"));
        Assert.assertTrue(columnsToDataTypeMap.get("database").equalsIgnoreCase("String"));
        Assert.assertTrue(columnsToDataTypeMap.get("table").equalsIgnoreCase("String"));
        Assert.assertTrue(columnsToDataTypeMap.get("_raw").equalsIgnoreCase("String"));
        Assert.assertTrue(columnsToDataTypeMap.get("_time").equalsIgnoreCase("UInt64"));
        Assert.assertTrue(columnsToDataTypeMap.get("is_deleted").equalsIgnoreCase("UInt8"));
        Assert.assertTrue(columnsToDataTypeMap.get("operation").equalsIgnoreCase("String"));
        Assert.assertTrue(columnsToDataTypeMap.get("_version").equalsIgnoreCase("UInt64"));
        Assert.assertTrue(columnsToDataTypeMap.get("host").equalsIgnoreCase("String"));
        Assert.assertTrue(columnsToDataTypeMap.get("logfile").equalsIgnoreCase("String"));
        Assert.assertTrue(columnsToDataTypeMap.get("position").equalsIgnoreCase("UInt64"));
        Assert.assertTrue(columnsToDataTypeMap.get("primary_host").equalsIgnoreCase("String"));

        ResultSet resultSet = ITCommon.executeQueryWithResultSet("select database,table,_raw,is_deleted,operation,host,logfile from employees.contacts_history", writer.getConnection());
        boolean insertCheck = false;
        while (resultSet.next()) {
            insertCheck = true;
            Assert.assertTrue(resultSet.getString("database").equalsIgnoreCase("employees"));
            Assert.assertTrue(resultSet.getString("table").equalsIgnoreCase("contacts_history"));
            Assert.assertTrue(resultSet.getString("_raw").equalsIgnoreCase("{\"last_name\":\"Doe\",\"id\":1,\"fullname\":\"John Doe\",\"first_name\":\"John\",\"gmt_time\":1744288496000,\"email\":\"john.doe@gmail.com\"}"));
            Assert.assertTrue(resultSet.getString("is_deleted").equalsIgnoreCase("0"));
            Assert.assertTrue(resultSet.getString("operation").equalsIgnoreCase("C"));
            Assert.assertTrue(resultSet.getString("host").equalsIgnoreCase("1"));
            Assert.assertTrue(resultSet.getString("logfile").equalsIgnoreCase("binlog.000003"));
        }
        Thread.sleep(10000);

        Assert.assertTrue(insertCheck);
        writer.getConnection().close();

        Thread.sleep(10000);

        if(engine.get() != null) {
            engine.get().stop();
        }
        // Files.deleteIfExists(tmpFilePath);
        executorService.shutdown();

        HikariDbSource.close();
    }

    @Test
    public void testAlterMergeTreeHistoryTableWithAdditionalColumns() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {

                Properties props = getDebeziumProperties(mySqlContainer, clickHouseContainer);

                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(props, new SourceRecordParserService(),  false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(30000);

        // Connect to MySQL and create the `contacts` table
        Connection conn = connectToMySQL(mySqlContainer);
        conn.prepareStatement(
                "CREATE TABLE employees.contacts (\n" +
                        "  id INT AUTO_INCREMENT PRIMARY KEY NOT NULL,\n" +
                        "  first_name VARCHAR(50) NOT NULL,\n" +
                        "  last_name VARCHAR(50) NOT NULL,\n" +
                        "  fullname VARCHAR(101) GENERATED ALWAYS AS (CONCAT(first_name,' ',last_name)) STORED,\n" +
                        "  email VARCHAR(100) NOT NULL,\n" +
                        "  gmt_time DATETIME NOT NULL\n" +
                        ");"
        ).execute();
        // Wait for the table creation to propagate
        Thread.sleep(30000);

        // Alter the table to add two new DATETIME columns
        conn.prepareStatement(
                "ALTER TABLE employees.contacts\n" +
                        "  ADD COLUMN gmt_time3 DATETIME NOT NULL,\n" +
                        "  ADD COLUMN gmt_time4 DATETIME NOT NULL;"
        ).execute();
        // Allow the schema change to take effect
        Thread.sleep(20000);

        // Insert a row including values for the new columns
        conn.prepareStatement(
                "INSERT INTO employees.contacts (" +
                        " first_name, last_name, email, gmt_time, gmt_time3, gmt_time4" +
                        ") VALUES (" +
                        " 'John', 'Doe', 'john.doe@gmail.com'," +
                        " '2025-04-10 12:34:56'," +
                        " '2025-04-10 12:35:56'," +
                        " '2025-04-10 12:36:56'" +
                        ");"
        ).execute();
        // Give Debezium time to capture the insert event
        Thread.sleep(20000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        DBMetadata dbMetadata = new DBMetadata();
        Map<String, String> columnsToDataTypeMap = dbMetadata.getColumnsDataTypesForTable(writer.getConnection(), "contacts_history", "employees");

        Assert.assertTrue(columnsToDataTypeMap.get("id").equalsIgnoreCase("Int32"));
        Assert.assertTrue(columnsToDataTypeMap.get("first_name").equalsIgnoreCase("String"));
        Assert.assertTrue(columnsToDataTypeMap.get("last_name").equalsIgnoreCase("String"));
        Assert.assertTrue(columnsToDataTypeMap.get("fullname").equalsIgnoreCase("Nullable(String)"));
        Assert.assertTrue(columnsToDataTypeMap.get("email").equalsIgnoreCase("String"));
        Assert.assertTrue(columnsToDataTypeMap.get("gmt_time").equalsIgnoreCase("String"));
        Assert.assertTrue(columnsToDataTypeMap.get("database").equalsIgnoreCase("String"));
        Assert.assertTrue(columnsToDataTypeMap.get("table").equalsIgnoreCase("String"));
        Assert.assertTrue(columnsToDataTypeMap.get("_raw").equalsIgnoreCase("String"));
        Assert.assertTrue(columnsToDataTypeMap.get("_time").equalsIgnoreCase("UInt64"));
        Assert.assertTrue(columnsToDataTypeMap.get("is_deleted").equalsIgnoreCase("UInt8"));
        Assert.assertTrue(columnsToDataTypeMap.get("operation").equalsIgnoreCase("String"));
        Assert.assertTrue(columnsToDataTypeMap.get("_version").equalsIgnoreCase("UInt64"));
        Assert.assertTrue(columnsToDataTypeMap.get("host").equalsIgnoreCase("String"));
        Assert.assertTrue(columnsToDataTypeMap.get("logfile").equalsIgnoreCase("String"));
        Assert.assertTrue(columnsToDataTypeMap.get("position").equalsIgnoreCase("UInt64"));
        Assert.assertTrue(columnsToDataTypeMap.get("primary_host").equalsIgnoreCase("String"));
        Assert.assertTrue(columnsToDataTypeMap.get("gmt_time3").equalsIgnoreCase("Nullable(String)"));
        Assert.assertTrue(columnsToDataTypeMap.get("gmt_time4").equalsIgnoreCase("Nullable(String)"));

        ResultSet resultSet = ITCommon.executeQueryWithResultSet("select database,table,_raw,is_deleted,operation,host,logfile,gmt_time,gmt_time3,gmt_time4 from employees.contacts_history", writer.getConnection());
        boolean insertCheck = false;
        while (resultSet.next()) {
            insertCheck = true;
            Assert.assertTrue(resultSet.getString("database").equalsIgnoreCase("employees"));
            Assert.assertTrue(resultSet.getString("table").equalsIgnoreCase("contacts_history"));
            Assert.assertTrue(resultSet.getString("_raw").equalsIgnoreCase("{\"last_name\":\"Doe\",\"gmt_time3\":1744288556000,\"gmt_time4\":1744288616000,\"id\":1,\"fullname\":\"John Doe\",\"first_name\":\"John\",\"gmt_time\":1744288496000,\"email\":\"john.doe@gmail.com\"}"));
            Assert.assertTrue(resultSet.getString("is_deleted").equalsIgnoreCase("0"));
            Assert.assertTrue(resultSet.getString("operation").equalsIgnoreCase("C"));
            Assert.assertTrue(resultSet.getString("host").equalsIgnoreCase("1"));
            Assert.assertTrue(resultSet.getString("logfile").equalsIgnoreCase("binlog.000003"));
            Assert.assertTrue(resultSet.getString("gmt_time").equalsIgnoreCase("2025-04-10 07:34:56.000"));
            Assert.assertTrue(resultSet.getString("gmt_time3").equalsIgnoreCase("2025-04-10 07:35:56.000"));
            Assert.assertTrue(resultSet.getString("gmt_time4").equalsIgnoreCase("2025-04-10 07:36:56.000"));
        }
        Thread.sleep(10000);

        Assert.assertTrue(insertCheck);
        writer.getConnection().close();

        Thread.sleep(10000);

        if(engine.get() != null) {
            engine.get().stop();
        }
        // Files.deleteIfExists(tmpFilePath);
        executorService.shutdown();

        HikariDbSource.close();
    }
}

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;


@Testcontainers
@DisplayName("Integration Test that validates DDL(Create, ALTER, RENAME) on Clickhouse 22.3 and latest docker tags")
public class TableOperationsIT {
    protected MySQLContainer mySqlContainer;
    static ClickHouseContainer clickHouseContainer;

        @BeforeEach
        public void startContainers() throws InterruptedException {
            mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:8.0.36")
                    .asCompatibleSubstituteFor("mysql"))
                    .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                    .withInitScript("data_types.sql")
                    .withExtraHost("mysql-server", "0.0.0.0")
                    .waitingFor(new HttpWaitStrategy().forPort(3306));

            BasicConfigurator.configure();
            mySqlContainer.start();
           // clickHouseContainer.start();
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
        @ParameterizedTest
        @CsvSource({
            "clickhouse/clickhouse-server:latest",
            "clickhouse/clickhouse-server:22.3"
        })
        @DisplayName("Test that validates DDL(Create, ALTER, RENAME)")
        public void testTableOperations(String clickHouseServerVersion) throws Exception {

            AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

            ExecutorService executorService = Executors.newFixedThreadPool(1);
            executorService.execute(() -> {
                try {

                    engine.set(new DebeziumChangeEventCapture());
                    engine.get().setup(ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer), new SourceRecordParserService(),
                            new MySQLDDLParserService(new ClickHouseSinkConnectorConfig(new HashMap<>()), "employees"),false);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });


            Thread.sleep(30000);

            Connection conn = ITCommon.connectToMySQL(mySqlContainer);
            conn.prepareStatement("RENAME TABLE ship_class to ship_class_new, add_test to add_test_new").execute();
            conn.prepareStatement("RENAME TABLE ship_class_new to ship_class_new2").execute();
            conn.prepareStatement("ALTER TABLE ship_class_new2 rename ship_class_new3").execute();
            conn.prepareStatement("create table new_table(col1 varchar(255), col2 int, col3 int)").execute();
            conn.prepareStatement("CREATE TABLE members (\n" +
                            "    firstname VARCHAR(25) NOT NULL,\n" +
                            "    lastname VARCHAR(25) NOT NULL,\n" +
                            "    username VARCHAR(16) NOT NULL,\n" +
                            "    email VARCHAR(35),\n" +
                            "    joined DATE NOT NULL\n" +
                            ")\n" +
                            "PARTITION BY KEY(joined)\n" +
                            "PARTITIONS 6;").execute();
            conn.prepareStatement("create table copied_table like new_table").execute();
            conn.prepareStatement("CREATE TABLE rcx ( a INT not null, b INT, c CHAR(3) not null, d INT not null) PARTITION BY RANGE COLUMNS(a,d,c) ( PARTITION p0 VALUES LESS THAN (5,10,'ggg'));").execute();
            Thread.sleep(10000);


            conn.prepareStatement("\n" +
                    "CREATE TABLE contacts (id INT AUTO_INCREMENT PRIMARY KEY,\n" +
                    "first_name VARCHAR(50) NOT NULL,\n" +
                    "last_name VARCHAR(50) NOT NULL,\n" +
                    "fullname varchar(101) GENERATED ALWAYS AS (CONCAT(first_name,' ',last_name)),\n" +
                    "email VARCHAR(100) NOT NULL);\n").execute();

            String jdbcUrl = BaseDbWriter.getConnectionString(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                    "employees");
            ClickHouseConnection chConn = BaseDbWriter.createConnection(jdbcUrl, "Client_1",
                    clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), new ClickHouseSinkConnectorConfig(new HashMap<>()));

            BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                    "employees", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null, chConn);

            conn.prepareStatement("create table new_table_copy like new_table").execute();

            Map<String, String> shipClassColumns = writer.getColumnsDataTypesForTable("ship_class_new3");
            Map<String, String> addTestColumns = writer.getColumnsDataTypesForTable("add_test_new");
            Map<String, String> copied_table = writer.getColumnsDataTypesForTable("copied_table");

            Assert.assertTrue(shipClassColumns.size() == 9);
            Assert.assertTrue(addTestColumns.size() == 5);
            Assert.assertTrue(copied_table.size() == 5);


            // Validate table created with partitions.
            String membersResult = writer.executeQuery("show create table members");

            if(clickHouseServerVersion.contains("latest")) {
                Assert.assertTrue(membersResult.equalsIgnoreCase("CREATE TABLE employees.members\n" +
                        "(\n" +
                        "    `firstname` String,\n" +
                        "    `lastname` String,\n" +
                        "    `username` String,\n" +
                        "    `email` Nullable(String),\n" +
                        "    `joined` Date32,\n" +
                        "    `_version` UInt64,\n" +
                        "    `is_deleted` UInt8\n" +
                        ")\n" +
                        "ENGINE = ReplacingMergeTree(_version, is_deleted)\n" +
                        "PARTITION BY joined\n" +
                        "ORDER BY tuple()\n" +
                        "SETTINGS index_granularity = 8192"));
            } else {
                Assert.assertTrue(membersResult.equalsIgnoreCase("CREATE TABLE employees.members\n" +
                        "(\n" +
                        "    `firstname` String,\n" +
                        "    `lastname` String,\n" +
                        "    `username` String,\n" +
                        "    `email` Nullable(String),\n" +
                        "    `joined` Date32,\n" +
                        "    `_version` UInt64,\n" +
                        "    `is_deleted` UInt8\n" +
                        ")\n" +
                        "ENGINE = ReplacingMergeTree(_version, is_deleted)\n" +
                        "PARTITION BY joined\n" +
                        "ORDER BY tuple()\n" +
                        "SETTINGS index_granularity = 8192"));
            }

            String rcxResult = writer.executeQuery("show create table rcx");

            if(clickHouseServerVersion.contains("latest")) {
                Assert.assertTrue(rcxResult.equalsIgnoreCase("CREATE TABLE employees.rcx\n" +
                        "(\n" +
                        "    `a` Int32,\n" +
                        "    `b` Nullable(Int32),\n" +
                        "    `c` String,\n" +
                        "    `d` Int32,\n" +
                        "    `_version` UInt64,\n" +
                        "    `is_deleted` UInt8\n" +
                        ")\n" +
                        "ENGINE = ReplacingMergeTree(_version, is_deleted)\n" +
                        "PARTITION BY (a, d, c)\n" +
                        "ORDER BY tuple()\n" +
                        "SETTINGS index_granularity = 8192"));
            }


            if(engine.get() != null) {
                engine.get().stop();
            }
            // Files.deleteIfExists(tmpFilePath);
            executorService.shutdown();

        }

}

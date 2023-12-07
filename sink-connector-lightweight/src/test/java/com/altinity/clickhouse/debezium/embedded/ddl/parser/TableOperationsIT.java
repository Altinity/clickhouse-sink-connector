package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.common.PropertiesHelper;
import com.altinity.clickhouse.debezium.embedded.config.ConfigLoader;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
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
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;


@Testcontainers
@DisplayName("Integration Test that validates DDL(Create, ALTER, RENAME) on Clickhouse 22.3 and latest docker tags")
public class TableOperationsIT {
    protected MySQLContainer mySqlContainer;
    protected ClickHouseContainer clickHouseContainer;

//    @Container
//    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer("clickhouse/clickhouse-server:latest")
//            .withInitScript("init_clickhouse.sql")
//            .withExposedPorts(8123);

        @BeforeEach
        public void startContainers() throws InterruptedException {
            mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:latest")
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

        @ParameterizedTest
        @CsvSource({
            "clickhouse/clickhouse-server:latest",
            "clickhouse/clickhouse-server:22.3"
        })
        public void testTableOperations(String clickHouseServerVersion) throws Exception {
                //,String rcxExpectedResult) throws Exception {

            clickHouseContainer = new org.testcontainers.clickhouse.ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:latest")
                    .asCompatibleSubstituteFor("clickhouse"))
                    .withInitScript("init_clickhouse_it.sql")
                    .withUsername("ch_user")
                    .withPassword("password")
                    .withExposedPorts(8123);

            clickHouseContainer.start();

            AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

            ExecutorService executorService = Executors.newFixedThreadPool(1);
            executorService.execute(() -> {
                try {

                    engine.set(new DebeziumChangeEventCapture());
                    engine.get().setup(getDebeziumProperties(), new SourceRecordParserService(),
                            new MySQLDDLParserService(new ClickHouseSinkConnectorConfig(new HashMap<>())),false);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });


            Thread.sleep(10000);

            Connection conn = connectToMySQL();
            conn.prepareStatement("RENAME TABLE ship_class to ship_class_new, add_test to add_test_new").execute();
            conn.prepareStatement("RENAME TABLE ship_class_new to ship_class_new2").execute();
            conn.prepareStatement("ALTER TABLE ship_class_new2 rename ship_class_new3").execute();

            //conn.prepareStatement("ALTER TABLE ship_class_new2 rename ship_class_new3").execute();

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


            BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                    "employees", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null);

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
//            else {
//                Assert.assertTrue(rcxResult.equalsIgnoreCase("CREATE TABLE employees.rcx\n" +
//                        "(\n" +
//                        "    `a` Int32,\n" +
//                        "    `b` Nullable(Int32),\n" +
//                        "    `c` String,\n" +
//                        "    `d` Int32,\n" +
//                        "    `_sign` Int8,\n" +
//                        "    `_version` UInt64\n" +
//                        ")\n" +
//                        "ENGINE = ReplacingMergeTree(_version, is_deleted)\n" +
//                        "PARTITION BY (a, d, c)\n" +
//                        "ORDER BY tuple()\n" +
//                        "SETTINGS index_granularity = 8192"));
//            }

//            new com.altinity.clickhouse.sink.connector.db.DBMetadata().getTableEngine(writer.getConnection(), "employees", "rmt_test");

            if(engine.get() != null) {
                engine.get().stop();
            }
            // Files.deleteIfExists(tmpFilePath);
            executorService.shutdown();

        }

    Connection connectToMySQL() {
        Connection conn = null;
        try {

            String connectionUrl = String.format("jdbc:mysql://%s:%s/%s?user=%s&password=%s", mySqlContainer.getHost(), mySqlContainer.getFirstMappedPort(),
                    mySqlContainer.getDatabaseName(), mySqlContainer.getUsername(), mySqlContainer.getPassword());
            conn = DriverManager.getConnection(connectionUrl);


        } catch (SQLException ex) {
            // handle any errors

        }

        return conn;
    }

    protected Properties getDebeziumProperties() throws Exception {

        // Start the debezium embedded application.

        Properties defaultProps = new Properties();
        Properties defaultProperties = PropertiesHelper.getProperties("config.properties");

        defaultProps.putAll(defaultProperties);
        Properties fileProps = new ConfigLoader().load("config.yml");
        defaultProps.putAll(fileProps);

        defaultProps.setProperty("database.hostname", mySqlContainer.getHost());
        defaultProps.setProperty("database.port", String.valueOf(mySqlContainer.getFirstMappedPort()));
        defaultProps.setProperty("database.user", "root");
        defaultProps.setProperty("database.password", "adminpass");

        defaultProps.setProperty("clickhouse.server.url", clickHouseContainer.getHost());
        defaultProps.setProperty("clickhouse.server.port", String.valueOf(clickHouseContainer.getFirstMappedPort()));
        defaultProps.setProperty("clickhouse.server.user", clickHouseContainer.getUsername());
        defaultProps.setProperty("clickhouse.server.password", clickHouseContainer.getPassword());
        defaultProps.setProperty("clickhouse.server.database", "employees");

        defaultProps.setProperty("offset.storage.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));

        defaultProps.setProperty("schema.history.internal.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));

        defaultProps.setProperty("offset.storage.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));

        defaultProps.setProperty("schema.history.internal.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));


        return defaultProps;

    }

}

package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.config.ConfigLoader;
import com.altinity.clickhouse.debezium.embedded.config.EnvironmentConfigurationService;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;


@Testcontainers
public class ClickHouseDebeziumEmbeddedDDLTableOperationsIT {
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

            clickHouseContainer = new ClickHouseContainer(clickHouseServerVersion)
            .withInitScript("init_clickhouse.sql")
//                    .withEnv("CLICKHOUSE_USER", "root")
//                    .withEnv("CLICKHOUSE_PASSWORD", "root")
//                    .withEnv("CLICKHOUSE_DB", "test")
//                    .withClasspathResourceMapping("users.xml",
//                            "/etc/clickhouse-server/users.xml",
//                            BindMode.READ_ONLY)
            .withExposedPorts(8123);

            clickHouseContainer.start();

            AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

            ExecutorService executorService = Executors.newFixedThreadPool(1);
            executorService.execute(() -> {
                try {

                    Properties props = new ConfigLoader().load("config.yml");
                    //Properties props = getDebeziumProperties();
                    props.setProperty("database.hostname", mySqlContainer.getHost());
                    props.setProperty("database.port", String.valueOf(mySqlContainer.getFirstMappedPort()));
                    props.setProperty("database.include.list", "employees");
                    props.setProperty("clickhouse.server.database", "employees");
                    props.setProperty("offset.storage.jdbc.url", clickHouseContainer.getJdbcUrl());
                    props.setProperty("clickhouse.server.url", clickHouseContainer.getHost());
                    props.setProperty("clickhouse.server.port", String.valueOf(clickHouseContainer.getFirstMappedPort()));
                    props.setProperty("schema.history.internal.jdbc.url", clickHouseContainer.getJdbcUrl());
                    engine.set(new DebeziumChangeEventCapture());
                    engine.get().setup(props, new SourceRecordParserService(),
                            new MySQLDDLParserService());
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
                        "    `_sign` Int8,\n" +
                        "    `_version` UInt64\n" +
                        ")\n" +
                        "ENGINE = ReplacingMergeTree(_version)\n" +
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
            } else {
                Assert.assertTrue(rcxResult.equalsIgnoreCase("CREATE TABLE employees.rcx\n" +
                        "(\n" +
                        "    `a` Int32,\n" +
                        "    `b` Nullable(Int32),\n" +
                        "    `c` String,\n" +
                        "    `d` Int32,\n" +
                        "    `_sign` Int8,\n" +
                        "    `_version` UInt64\n" +
                        ")\n" +
                        "ENGINE = ReplacingMergeTree(_version)\n" +
                        "PARTITION BY (a, d, c)\n" +
                        "ORDER BY tuple()\n" +
                        "SETTINGS index_granularity = 8192"));
            }

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
        Properties defaultProps = (new EnvironmentConfigurationService()).parse();
        defaultProps.setProperty("database.hostname", mySqlContainer.getHost());
        defaultProps.setProperty("database.port", String.valueOf(mySqlContainer.getFirstMappedPort()));
        defaultProps.setProperty("database.user", "root");
        defaultProps.setProperty("database.password", "adminpass");

        defaultProps.setProperty("database.include.list", "employees");
        defaultProps.setProperty("snapshot.mode", "initial");


        defaultProps.setProperty("snapshot.mode", "initial");
        defaultProps.setProperty("connector.class", "io.debezium.connector.mysql.MySqlConnector");

        defaultProps.setProperty("include.schema.change", "true");
        defaultProps.setProperty("include.schema.comments", "true");

       // defaultProps.setProperty("offset.storage", "org.apache.kafka.connect.storage.FileOffsetBackingStore");
        defaultProps.setProperty("provide.transaction.metadata", "true");
        //String tempOffsetPath = "/tmp/2/offsets" + System.currentTimeMillis() + ".dat";
        Path tmpFilePath = Files.createTempFile("offsets", ".dat");
        Files.deleteIfExists(tmpFilePath);
        //defaultProps.setProperty("offset.storage.file.filename", tmpFilePath.toAbsolutePath().toString());
        defaultProps.setProperty("offset.flush.interval.ms", "60000");

        defaultProps.setProperty("auto.create.tables", "true");
        defaultProps.setProperty("clickhouse.server.url", clickHouseContainer.getHost());
        defaultProps.setProperty("clickhouse.server.port", String.valueOf(clickHouseContainer.getFirstMappedPort()));
        defaultProps.setProperty("clickhouse.server.user", "default");
        defaultProps.setProperty("clickhouse.server.password", "");
        defaultProps.setProperty("clickhouse.server.database", "employees");
        defaultProps.setProperty("replacingmergetree.delete.column", "_sign");
        defaultProps.setProperty("metrics.port", "8088");
        defaultProps.setProperty("thread.pool.size", "1");
        defaultProps.setProperty("database.allowPublicKeyRetrieval", "true");
        defaultProps.setProperty("metrics.enable", "false");

        return defaultProps;
    }

}

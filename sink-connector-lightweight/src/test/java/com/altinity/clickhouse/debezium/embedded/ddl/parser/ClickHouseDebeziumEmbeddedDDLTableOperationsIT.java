package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;


@Testcontainers
public class ClickHouseDebeziumEmbeddedDDLTableOperationsIT extends ClickHouseDebeziumEmbeddedDDLBaseIT  {

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
            clickHouseContainer.start();
            Thread.sleep(15000);
        }

        @Test
        public void testTableOperations() throws Exception {
            AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

            ExecutorService executorService = Executors.newFixedThreadPool(1);
            executorService.execute(() -> {
                try {

                    Properties props = getDebeziumProperties();
                    props.setProperty("database.include.list", "datatypes");
                    props.setProperty("clickhouse.server.database", "datatypes");

                    engine.set(new DebeziumChangeEventCapture());
                    engine.get().setup(getDebeziumProperties(), new SourceRecordParserService(),
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
            String chResult = writer.executeQuery("show create table members");

            Assert.assertTrue(chResult.equalsIgnoreCase("CREATE TABLE employees.members\n" +
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
            if(engine.get() != null) {
                engine.get().stop();
            }
            // Files.deleteIfExists(tmpFilePath);
            executorService.shutdown();

        }

}

package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import org.apache.log4j.BasicConfigurator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.config.SinkConnectorLightWeightConfig;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.clickhouse.jdbc.ClickHouseConnection;
import org.junit.Assert;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;


@Testcontainers
@DisplayName("Integration Test that validates DDL Ignore Regex")
public class DDLIgnoreRegExIT {
    protected MySQLContainer mySqlContainer;
    static ClickHouseContainer clickHouseContainer;

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:8.0.36")
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                //.withInitScript("data_types.sql")
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
                //.withInitScript("init_clickhouse_it.sql")
                .withUsername("ch_user")
                .withPassword("password")
                .withExposedPorts(8123);

        clickHouseContainer.start();
    }
    // Add 


    @Test
    public void testDDLIgnoreRegex() throws Exception {

        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

        ExecutorService executorService = Executors.newFixedThreadPool(1);

        DebeziumChangeEventCapture debeziumChangeEventCapture = new DebeziumChangeEventCapture();
        executorService.execute(() -> {
            try {

                java.util.Properties props = ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer);
                // Add the ignore DDL regex.
                props.put(SinkConnectorLightWeightConfig.IGNORE_DDL_REGEX, "(?i)(ANALYZE PARTITION).*||^CREATE\\s+DEFINER*.*\\n*.*\\n*.*\\n*.*\\n*.*");

                engine.set(debeziumChangeEventCapture);
                engine.get().setup(props, new SourceRecordParserService()
                        , false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // MySQL DDL
        String createTableWPartition = "CREATE TABLE sales (     id INT NOT NULL,     sale_date DATE NOT NULL,     amount DECIMAL(10, 2),     PRIMARY KEY (id, sale_date) ) PARTITION BY RANGE (YEAR(sale_date)) (     PARTITION p2020 VALUES LESS THAN (2021),     PARTITION p2021 VALUES LESS THAN (2022),     PARTITION p2022 VALUES LESS THAN (2023),     PARTITION pfuture VALUES LESS THAN MAXVALUE )";
        ITCommon.connectToMySQL(mySqlContainer).createStatement().executeUpdate(createTableWPartition);

        // Wait for the DDL to be captured by the engine.
        Thread.sleep(10000);


        String jdbcUrl = BaseDbWriter.getConnectionString(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(), "employees");
        ClickHouseConnection connection = BaseDbWriter.createConnection(jdbcUrl, "client_1", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), new ClickHouseSinkConnectorConfig(new HashMap<>()));
        BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null, connection);


       // Thread.sleep(5000);
        // Run MySQL DDL to run analyze partition.
        String analyzePartitionDDL = "alter table sales analyze partition p2022";
        ITCommon.connectToMySQL(mySqlContainer).createStatement().executeUpdate(analyzePartitionDDL);
        Thread.sleep(10000);

        // Validate that the last DDL that was ignored is the one that was ignored.
        Assert.assertEquals(debeziumChangeEventCapture.getLastIgnoredDDL(), analyzePartitionDDL);

        Thread.sleep(10000);

        String createTableAccountDDL = "CREATE TABLE account (\n" +
                "    id INT AUTO_INCREMENT PRIMARY KEY NOT NULL,\n" +
                "    account_number VARCHAR(20) NOT NULL,\n" +
                "    amount DECIMAL(10, 2) NOT NULL\n" +
                ")";
        ITCommon.connectToMySQL(mySqlContainer).createStatement().executeUpdate(createTableAccountDDL);

        Thread.sleep(10000);

        // Run the CREATE TRIGGER DDL
        String createTriggerDDL = "CREATE TRIGGER ins_transaction BEFORE INSERT ON account\n" +
                "       FOR EACH ROW\n" +
                "       SET\n" +
                "       @deposits = @deposits + IF(NEW.amount>0,NEW.amount,0),\n" +
                "       @withdrawals = @withdrawals + IF(NEW.amount<0,-NEW.amount,0);";

        ITCommon.connectToMySQL(mySqlContainer).createStatement().executeUpdate(createTriggerDDL);
        Thread.sleep(10000);

        // Validate that the last DDL that was ignored is the one that was ignored.
        Assert.assertTrue(debeziumChangeEventCapture.getLastIgnoredDDL().contains("CREATE DEFINER"));
        if(engine.get() != null) {
            engine.get().stop();
        }
        executorService.shutdown();
    }


}

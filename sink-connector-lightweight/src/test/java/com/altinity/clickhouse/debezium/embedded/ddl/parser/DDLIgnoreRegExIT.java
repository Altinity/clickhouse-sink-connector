package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import org.apache.log4j.BasicConfigurator;
import org.bouncycastle.util.Properties;
import org.junit.Test;
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
import com.altinity.clickhouse.sink.connector.db.DbWriter;
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
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
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
    // Add 


    @Test
    public void testDDLIgnoreRegex() throws Exception {

        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {

                java.util.Properties props = ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer);
                // Add the ignore DDL regex.
                props.put(SinkConnectorLightWeightConfig.IGNORE_DDL_REGEX, "(?i)(ANALYZE PARTITION).*");

                engine.set(new DebeziumChangeEventCapture());
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

        // Get columns for sales table.
        Map<String, String> salesColumns = writer.getColumnsDataTypesForTable("sales");
        Assert.assertTrue(salesColumns.get("id").equalsIgnoreCase("Int32"));
        Assert.assertTrue(salesColumns.get("sale_date").equalsIgnoreCase("Date32"));
        Assert.assertTrue(salesColumns.get("amount").equalsIgnoreCase("Decimal(10,2)"));

        // Run MySQL DDL to run analyze partition.
        String analyzePartitionDDL = "alter table sales analyze partition p2022";
        ITCommon.connectToMySQL(mySqlContainer).createStatement().executeUpdate(analyzePartitionDDL);
        Thread.sleep(10000);


        if(engine.get() != null) {
            engine.get().stop();
        }
        executorService.shutdown();
    }


}

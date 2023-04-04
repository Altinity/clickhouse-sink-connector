package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import org.apache.log4j.BasicConfigurator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Testcontainers
public class ClickHouseDebeziumEmbeddedDDLDataTypeTest extends ClickHouseDebeziumEmbeddedDDLBaseIT {
    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:latest")
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                .withInitScript("data_types_test.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        Thread.sleep(15000);
    }

    @Test
    public void testDataTypes() throws Exception {

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                new DebeziumChangeEventCapture().setup(getDebeziumProperties(), new SourceRecordParserService(),
                        new MySQLDDLParserService());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(10000);


        BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null);

        Map<String, String> decimalTable = writer.getColumnsDataTypesForTable("numeric_types_DECIMAL_65_30");
        Map<String, String> dateTimeTable = writer.getColumnsDataTypesForTable("temporal_types_DATETIME6");
        Map<String, String> timestampTable = writer.getColumnsDataTypesForTable("temporal_types_TIMESTAMP6");

//        // Validate all decimal records.
//        Assert.assertTrue(decimalTable.get("Type").equalsIgnoreCase("String"));
//        Assert.assertTrue(decimalTable.get("Minimum Value").equalsIgnoreCase("Decimal(10, 0)"));
//
//        // Validate dateTime64 records.
//        Assert.assertTrue(dateTimeTable.get("Type").equalsIgnoreCase("String"));
//        Assert.assertTrue(dateTimeTable.get("Minimum Value").equalsIgnoreCase("DateTime64(3)"));
//        Assert.assertTrue(dateTimeTable.get("Mid Value").equalsIgnoreCase("DateTime64(3)"));
//        Assert.assertTrue(dateTimeTable.get("Maximum Value").equalsIgnoreCase("DateTime64(3)"));
//        Assert.assertTrue(dateTimeTable.get("Null Value").equalsIgnoreCase("DateTime64(3)"));
//
//        // Validate timestamp records
//        Assert.assertTrue(timestampTable.get("Type").equalsIgnoreCase("String"));
//        Assert.assertTrue(timestampTable.get("Minimum Value").equalsIgnoreCase("DateTime64(3)"));
//        Assert.assertTrue(timestampTable.get("Mid Value").equalsIgnoreCase("DateTime64(3)"));
//        Assert.assertTrue(timestampTable.get("Maximum Value").equalsIgnoreCase("DateTime64(3)"));
//        Assert.assertTrue(timestampTable.get("Null Value").equalsIgnoreCase("DateTime64(3)"));

        // Files.deleteIfExists(tmpFilePath);
        executorService.shutdown();

    }
}

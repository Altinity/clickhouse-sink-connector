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

import java.sql.ResultSet;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@Testcontainers
public class CreateTableDataTypesIT extends DDLBaseIT {

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
    public void testCreateTable() throws Exception {
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

        Thread.sleep(30000);


        BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null);

        Map<String, String> decimalTable = writer.getColumnsDataTypesForTable("numeric_types_DECIMAL_65_30");
        Map<String, String> dateTimeTable = writer.getColumnsDataTypesForTable("temporal_types_DATETIME6");
        Map<String, String> timestampTable = writer.getColumnsDataTypesForTable("temporal_types_TIMESTAMP6");

        // Validate all decimal records.
        Assert.assertTrue(decimalTable.get("Type").equalsIgnoreCase("String"));
        Assert.assertTrue(decimalTable.get("Minimum_Value").equalsIgnoreCase("Decimal(65, 30)"));
        Assert.assertTrue(decimalTable.get("Zero_Value").equalsIgnoreCase("Decimal(65, 30)"));
        Assert.assertTrue(decimalTable.get("Maximum_Value").equalsIgnoreCase("Decimal(65, 30)"));


        // Validate dateTime64 records.
        Assert.assertTrue(dateTimeTable.get("Type").equalsIgnoreCase("String"));
        Assert.assertTrue(dateTimeTable.get("Minimum_Value").equalsIgnoreCase("DateTime64(3)"));
        Assert.assertTrue(dateTimeTable.get("Mid_Value").equalsIgnoreCase("DateTime64(3)"));
        Assert.assertTrue(dateTimeTable.get("Maximum_Value").equalsIgnoreCase("DateTime64(3)"));
        Assert.assertTrue(dateTimeTable.get("Null_Value").equalsIgnoreCase("Nullable(DateTime64(3))"));

        // Validate timestamp records
        Assert.assertTrue(timestampTable.get("Type").equalsIgnoreCase("String"));
        Assert.assertTrue(timestampTable.get("Minimum_Value").equalsIgnoreCase("DateTime64(3)"));
        Assert.assertTrue(timestampTable.get("Mid_Value").equalsIgnoreCase("DateTime64(3)"));
        Assert.assertTrue(timestampTable.get("Maximum_Value").equalsIgnoreCase("DateTime64(3)"));
        Assert.assertTrue(timestampTable.get("Null_Value").equalsIgnoreCase("Nullable(DateTime64(3))"));

        writer.getConnection().close();
        //Thread.sleep(10000);

         writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null);
        // Validate temporal_types_DATE data.
        ResultSet dateResult = writer.executeQueryWithResultSet("select * from temporal_types_DATE");

        while(dateResult.next()) {
            Assert.assertTrue(dateResult.getDate("Minimum_Value").toString().equalsIgnoreCase("1925-01-01"));
            Assert.assertTrue(dateResult.getDate("Mid_Value").toString().equalsIgnoreCase("2022-09-29"));
            Assert.assertTrue(dateResult.getDate("Maximum_Value").toString().equalsIgnoreCase("2283-11-11"));
        }
        // Validate temporal_types_DATETIME data.
        ResultSet dateTimeResult = writer.executeQueryWithResultSet("select * from temporal_types_DATETIME");

        while(dateTimeResult.next()) {
            System.out.println(dateTimeResult.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult.getTimestamp("Maximum_Value").toString());

            Assert.assertTrue(dateTimeResult.getTimestamp("Minimum_Value").toString().equalsIgnoreCase("1925-01-01 00:00:00.0"));
            Assert.assertTrue(dateTimeResult.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-29 01:47:46.0"));
            Assert.assertTrue(dateTimeResult.getTimestamp("Maximum_Value").toString().equalsIgnoreCase("2283-11-11 23:59:59.999"));
        }

//        // DATETIME1
//        ResultSet dateTimeResult1 = writer.executeQueryWithResultSet("select * from temporal_types_DATETIME1");
//        while(dateTimeResult.next()) {
//            Assert.assertTrue(dateTimeResult1.getTimestamp("Minimum_Value").toString().equalsIgnoreCase("1925-01-01 00:00:00.000"));
//            Assert.assertTrue(dateTimeResult1.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-29 01:47:46.000"));
//            Assert.assertTrue(dateTimeResult1.getTimestamp("Maximum_Value").toString().equalsIgnoreCase("2283-11-11 23:59:59.999"));
//        }
//
//        // DATETIME2
//        ResultSet dateTimeResult2 = writer.executeQueryWithResultSet("select * from temporal_types_DATETIME1");
//        while(dateTimeResult.next()) {
//            Assert.assertTrue(dateTimeResult2.getTimestamp("Minimum_Value").toString().equalsIgnoreCase("1925-01-01 00:00:00.000"));
//            Assert.assertTrue(dateTimeResult2.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-29 01:47:46.000"));
//            Assert.assertTrue(dateTimeResult2.getTimestamp("Maximum_Value").toString().equalsIgnoreCase("2283-11-11 23:59:59.999"));
//        }
//
//        // DATETIME3
//        ResultSet dateTimeResult3 = writer.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME1");
//        while(dateTimeResult.next()) {
//            Assert.assertTrue(dateTimeResult3.getTimestamp("Minimum_Value").toString().equalsIgnoreCase("1925-01-01 00:00:00.000"));
//            Assert.assertTrue(dateTimeResult3.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-29 01:47:46.000"));
//            Assert.assertTrue(dateTimeResult3.getTimestamp("Maximum_Value").toString().equalsIgnoreCase("2283-11-11 23:59:59.999"));
//        }


        if(engine.get() != null) {
            engine.get().stop();
        }
        // Files.deleteIfExists(tmpFilePath);
        executorService.shutdown();

        writer.getConnection().close();
    }
}

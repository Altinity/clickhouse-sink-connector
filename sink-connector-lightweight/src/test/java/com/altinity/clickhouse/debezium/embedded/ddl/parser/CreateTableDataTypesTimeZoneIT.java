package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.sql.ResultSet;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;


import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
@Testcontainers
@Tag("datetime")
@DisplayName("Integration Test that tests replication of data types and validates datetime, date limits when the timezone is set to America/Chicago in ClickHouse")
public class CreateTableDataTypesTimeZoneIT {
    protected MySQLContainer mySqlContainer;

    @Container
    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_DOCKER_IMAGE)
            .asCompatibleSubstituteFor("clickhouse"))
            .withInitScript("init_clickhouse_it.sql")
            .withCopyFileToContainer(MountableFile.forClasspathResource("config.xml"), "/etc/clickhouse-server/config.d/config.xml")
            .withUsername("ch_user")
            .withPassword("password")
            .withExposedPorts(8123);


    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse(MYSQL_DOCKER_IMAGE)
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
        Properties props = ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer);
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(props, new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(30000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        DBMetadata metadata = new DBMetadata(props);
        Map<String, String> decimalTable = metadata.getColumnsDataTypesForTable(writer.getConnection(), "numeric_types_DECIMAL_65_30", "employees");
        Map<String, String> dateTimeTable = metadata.getColumnsDataTypesForTable(writer.getConnection(), "temporal_types_DATETIME6", "employees");
        Map<String, String> timestampTable = metadata.getColumnsDataTypesForTable(writer.getConnection(), "temporal_types_TIMESTAMP6", "employees");

        // Validate all decimal records.
        Assert.assertTrue(decimalTable.get("Type").equalsIgnoreCase("String"));
        Assert.assertTrue(decimalTable.get("Minimum_Value").equalsIgnoreCase("Decimal(65, 30)"));
        Assert.assertTrue(decimalTable.get("Zero_Value").equalsIgnoreCase("Decimal(65, 30)"));
        Assert.assertTrue(decimalTable.get("Maximum_Value").equalsIgnoreCase("Decimal(65, 30)"));


        // Validate dateTime64 records.
        Assert.assertTrue(dateTimeTable.get("Type").equalsIgnoreCase("String"));
        Assert.assertTrue(dateTimeTable.get("Minimum_Value").equalsIgnoreCase("DateTime64(6)"));
        Assert.assertTrue(dateTimeTable.get("Mid_Value").equalsIgnoreCase("DateTime64(6)"));
        Assert.assertTrue(dateTimeTable.get("Maximum_Value").equalsIgnoreCase("DateTime64(6)"));
        Assert.assertTrue(dateTimeTable.get("Null_Value").equalsIgnoreCase("Nullable(DateTime64(6))"));

        // Validate timestamp records
        Assert.assertTrue(timestampTable.get("Type").equalsIgnoreCase("String"));
        Assert.assertTrue(timestampTable.get("Minimum_Value").equalsIgnoreCase("DateTime64(6)"));
        Assert.assertTrue(timestampTable.get("Mid_Value").equalsIgnoreCase("DateTime64(6)"));
        Assert.assertTrue(timestampTable.get("Maximum_Value").equalsIgnoreCase("DateTime64(6)"));
        Assert.assertTrue(timestampTable.get("Null_Value").equalsIgnoreCase("Nullable(DateTime64(6))"));

        //writer.getConnection().close();
        Thread.sleep(10000);

        // Validate temporal_types_DATE data.
        ResultSet dateResult = ITCommon.executeQueryWithResultSet("select * from employees.temporal_types_DATE", writer.getConnection());
        boolean dateResultValueChecked = false;
        while(dateResult.next()) {
            dateResultValueChecked = true;


            // Use getDate() for Date32 columns: the jdbc 0.9.x V2 driver
            // (correctly) refuses getTimestamp() on a Date32 value.
            System.out.println(dateResult.getDate("Mid_Value").toString());
            System.out.println(dateResult.getDate("Maximum_Value").toString());
            System.out.println(dateResult.getDate("Minimum_Value").toString());

            Assert.assertTrue(dateResult.getDate("Mid_Value").toString().contains("2022-09-29"));
            Assert.assertTrue(dateResult.getDate("Maximum_Value").toString().contains("2299-12-31"));
            Assert.assertTrue(dateResult.getDate("Minimum_Value").toString().contains("1900-01-01"));
        }
        Assert.assertTrue(dateResultValueChecked);

        // Validate temporal_types_DATETIME data.
        ResultSet dateTimeResult = ITCommon.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME", writer.getConnection());
        boolean dateTimeResultValueChecked = false;


        /**
         * 2022-09-29 00:00:00.0
         * 2283-11-11 00:00:00.0
         * 1925-01-01 00:00:00.0
         * DATE TIME
         * 2022-09-28 20:47:46.0
         * 1970-05-01 07:43:11.999
         * 1900-01-01 18:09:24.0
         * DATE TIME 1
         * 2022-09-28 20:48:25.0
         * 1970-05-01 07:43:11.999
         * 1900-01-01 18:09:24.0
         * DATE TIME 2
         * 2022-09-28 20:49:05.0
         * 1970-05-01 07:43:11.999
         * 1900-01-01 18:09:24.0
         * DATE TIME 3
         * 2022-09-28 20:49:22.0
         * 1970-05-01 07:43:11.999
         * 1900-01-01 18:09:24.0
         * DATE TIME 4
         * 2022-09-28 20:50:12.0
         * 1970-05-01 07:43:11.999
         * 1900-01-01 18:09:24.0
         * DATE TIME 5
         * 2022-09-28 20:50:28.0
         * 1970-05-01 07:43:11.999
         * 1900-01-01 18:09:24.0
         * DATE TIME 6
         * 2022-09-28 20:50:56.0
         * 1970-05-01 07:43:11.999
         * 1900-01-01 18:09:24.0
         * DATE TIME 6
         * 2022-09-28 20:50:56.0
         * 1970-05-01 07:43:11.999
         * 1900-01-01 18:09:24.0
         */
        while(dateTimeResult.next()) {
            System.out.println("DATE TIME");
            dateTimeResultValueChecked = true;

            System.out.println(dateTimeResult.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult.getTimestamp("Maximum_Value").toString());
            System.out.println(dateTimeResult.getTimestamp("Minimum_Value").toString());

            Assert.assertTrue(dateTimeResult.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-29 01:47:46.0"));
            Assert.assertTrue(dateTimeResult.getTimestamp("Maximum_Value").toString().equalsIgnoreCase("2299-12-31 23:59:59.0"));
            Assert.assertTrue(dateTimeResult.getTimestamp("Minimum_Value").toString().equalsIgnoreCase("1900-01-01 00:00:00.0"));
        }
        Assert.assertTrue(dateTimeResultValueChecked);

        // DATETIME1
        boolean dateTimeResult1ValueChecked = false;

        ResultSet dateTimeResult1 = ITCommon.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME1", writer.getConnection());
        while(dateTimeResult1.next()) {
            System.out.println("DATE TIME 1");
            dateTimeResult1ValueChecked = true;

            System.out.println(dateTimeResult1.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult1.getTimestamp("Maximum_Value").toString());
            System.out.println(dateTimeResult1.getTimestamp("Minimum_Value").toString());

            Assert.assertTrue(dateTimeResult1.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-29 01:48:25.1"));
            Assert.assertTrue(dateTimeResult1.getTimestamp("Maximum_Value").toString().equalsIgnoreCase("2299-12-31 23:59:59.0"));
            Assert.assertTrue(dateTimeResult1.getTimestamp("Minimum_Value").toString().equalsIgnoreCase("1900-01-01 00:00:00.0"));
        }
        Assert.assertTrue(dateTimeResult1ValueChecked);

        // DATETIME2
        ResultSet dateTimeResult2 = ITCommon.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME2", writer.getConnection());
        while(dateTimeResult2.next()) {
            System.out.println("DATE TIME 2");
            System.out.println(dateTimeResult2.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult2.getTimestamp("Maximum_Value").toString());
            System.out.println(dateTimeResult2.getTimestamp("Minimum_Value").toString());

            Assert.assertTrue(dateTimeResult2.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-29 01:49:05.12"));
            Assert.assertTrue(dateTimeResult2.getTimestamp("Maximum_Value").toString().equalsIgnoreCase("2299-12-31 23:59:59.0"));
            Assert.assertTrue(dateTimeResult2.getTimestamp("Minimum_Value").toString().equalsIgnoreCase("1900-01-01 00:00:00.0"));
        }

        // DATETIME3
        ResultSet dateTimeResult3 = ITCommon.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME3", writer.getConnection());
        while(dateTimeResult3.next()) {
            System.out.println("DATE TIME 3");

            System.out.println(dateTimeResult3.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult3.getTimestamp("Maximum_Value").toString());
            System.out.println(dateTimeResult3.getTimestamp("Minimum_Value").toString());

            Assert.assertTrue(dateTimeResult3.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-29 01:49:22.123"));
            Assert.assertTrue(dateTimeResult3.getTimestamp("Maximum_Value").toString().equalsIgnoreCase("2299-12-31 23:59:59.0"));
            Assert.assertTrue(dateTimeResult3.getTimestamp("Minimum_Value").toString().equalsIgnoreCase("1900-01-01 00:00:00.0"));
        }


        // DATETIME4
        ResultSet dateTimeResult4 = ITCommon.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME4", writer.getConnection());
        while(dateTimeResult4.next()) {
            System.out.println("DATE TIME 4");

            System.out.println(dateTimeResult4.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult4.getTimestamp("Maximum_Value").toString());
            System.out.println(dateTimeResult4.getTimestamp("Minimum_Value").toString());

            Assert.assertTrue(dateTimeResult4.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-29 01:50:12.1234"));
            Assert.assertTrue(dateTimeResult4.getTimestamp("Maximum_Value").toString().equalsIgnoreCase("2299-12-31 23:59:59.0"));
            Assert.assertTrue(dateTimeResult4.getTimestamp("Minimum_Value").toString().equalsIgnoreCase("1900-01-01 00:00:00.0"));

        }


        // DATETIME5
        ResultSet dateTimeResult5 = ITCommon.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME5", writer.getConnection());
        while(dateTimeResult5.next()) {
            System.out.println("DATE TIME 5");

            System.out.println(dateTimeResult5.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult5.getTimestamp("Maximum_Value").toString());
            System.out.println(dateTimeResult5.getTimestamp("Minimum_Value").toString());

            Assert.assertTrue(dateTimeResult5.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-29 01:50:28.12345"));
            Assert.assertTrue(dateTimeResult5.getTimestamp("Maximum_Value").toString().equalsIgnoreCase("2299-12-31 23:59:59.0"));
            Assert.assertTrue(dateTimeResult5.getTimestamp("Minimum_Value").toString().equalsIgnoreCase("1900-01-01 00:00:00.0"));

        }

        // DATETIME6
        ResultSet dateTimeResult6 = ITCommon.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME6", writer.getConnection());
        while(dateTimeResult6.next()) {
            System.out.println("DATE TIME 6");

            System.out.println(dateTimeResult6.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult6.getTimestamp("Maximum_Value").toString());
            System.out.println(dateTimeResult6.getTimestamp("Minimum_Value").toString());

            Assert.assertTrue(dateTimeResult6.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-29 01:50:56.123456"));
            Assert.assertTrue(dateTimeResult6.getTimestamp("Maximum_Value").toString().equalsIgnoreCase("2299-12-31 23:59:59.0"));
            Assert.assertTrue(dateTimeResult6.getTimestamp("Minimum_Value").toString().equalsIgnoreCase("1900-01-01 00:00:00.0"));
            break;

        }

        if(engine.get() != null) {
            engine.get().stop();
        }
        // Files.deleteIfExists(tmpFilePath);
        executorService.shutdown();

        writer.getConnection().close();

        HikariDbSource.close();
    }

}

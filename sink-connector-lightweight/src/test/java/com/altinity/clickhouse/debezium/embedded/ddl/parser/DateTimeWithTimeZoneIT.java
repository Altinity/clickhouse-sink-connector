package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import junit.framework.Assert;
import org.apache.log4j.BasicConfigurator;
import org.junit.jupiter.api.*;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.sql.ResultSet;
import java.util.Properties;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;


import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
@Testcontainers
@Tag("datetime")
@DisplayName("Integration Test that tests replication of data types and validates datetime, date limits when the timezone is set to America/Chicago in ClickHouse")
public class DateTimeWithTimeZoneIT {
    protected MySQLContainer mySqlContainer;
    private TimeZone originalTimeZone;

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
        originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("America/Chicago"));

        mySqlContainer = new MySQLContainer<>(DockerImageName.parse(MYSQL_DOCKER_IMAGE)
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                .withInitScript("datetime.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        clickHouseContainer.start();
        Thread.sleep(15000);
    }

    @AfterEach
    public void tearDown() {
        mySqlContainer.stop();
        clickHouseContainer.stop();
        TimeZone.setDefault(originalTimeZone);
    }

    @Test
    public void testCreateTable() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {

                Properties props = ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer);
                props.setProperty("database.include.list", "datatypes");

                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer), new SourceRecordParserService(),  false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(30000);

        // Create connection.
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        // Validate temporal_types_DATETIME data.
        ResultSet dateTimeResult = ITCommon.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME", writer.getConnection());

        while(dateTimeResult.next()) {
            System.out.println("DATE TIME");
            System.out.println(dateTimeResult.getTimestamp("Minimum_Value").toString());
            System.out.println(dateTimeResult.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult.getTimestamp("Maximum_Value").toString());

            Assert.assertTrue(dateTimeResult.getTimestamp("Minimum_Value").toString().equalsIgnoreCase("1900-01-01 00:00:00.0"));
            Assert.assertTrue(dateTimeResult.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-29 01:47:46.0"));
            Assert.assertTrue(dateTimeResult.getTimestamp("Maximum_Value").toString().equalsIgnoreCase("2299-12-31 23:59:59.0"));
        }

        // DATETIME1
        ResultSet dateTimeResult1 = ITCommon.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME1", writer.getConnection());
        while(dateTimeResult1.next()) {
            System.out.println("DATE TIME 1");
            System.out.println(dateTimeResult1.getTimestamp("Minimum_Value").toString());
            System.out.println(dateTimeResult1.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult1.getTimestamp("Maximum_Value").toString());

            Assert.assertTrue(dateTimeResult1.getTimestamp("Minimum_Value").toString().equalsIgnoreCase("1900-01-01 00:00:00.0"));
            Assert.assertTrue(dateTimeResult1.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-29 01:48:25.1"));
            Assert.assertTrue(dateTimeResult1.getTimestamp("Maximum_Value").toString().equalsIgnoreCase("2299-12-31 23:59:59.0"));
        }

        // DATETIME2
        ResultSet dateTimeResult2 = ITCommon.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME2", writer.getConnection());
        while(dateTimeResult2.next()) {
            System.out.println("DATE TIME 2");
            System.out.println(dateTimeResult2.getTimestamp("Minimum_Value").toString());
            System.out.println(dateTimeResult2.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult2.getTimestamp("Maximum_Value").toString());

            Assert.assertTrue(dateTimeResult2.getTimestamp("Minimum_Value").toString().equalsIgnoreCase("1900-01-01 00:00:00.0"));
            Assert.assertTrue(dateTimeResult2.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-29 01:49:05.12"));
            Assert.assertTrue(dateTimeResult2.getTimestamp("Maximum_Value").toString().equalsIgnoreCase("2299-12-31 23:59:59.0"));
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

        // Validate test_2 DST transition rows (DATETIME(6) around America/Chicago spring-forward and fall-back)
        String[] expectedGatesFrom = {
                "2026-03-17 13:34:41.0",       // id=1: after DST
                "2026-03-08 03:00:00.0",       // id=2: DST boundary (02:00 CST -> 03:00 CDT)
                "2026-03-08 01:59:59.0",       // id=3: last second before gap
                "2026-03-08 03:00:00.0",       // id=4: 02:00 falls in non-existent hour, shifted to 03:00
                "2026-03-08 07:59:59.0",       // id=5: well after transition
                "2026-03-08 08:00:00.0",       // id=6: well after transition
                "2025-11-02 00:59:59.0",       // id=7:  fall-back 2025 - before ambiguous hour
                "2025-11-02 01:00:00.0",       // id=8:  fall-back 2025 - start of ambiguous hour
                "2025-11-02 01:30:00.0",       // id=9:  fall-back 2025 - middle of ambiguous hour
                "2025-11-02 01:59:59.0",       // id=10: fall-back 2025 - end of ambiguous hour
                "2025-11-02 02:00:00.0",       // id=11: fall-back 2025 - first unambiguous second after
                "2025-11-02 03:00:00.0",       // id=12: fall-back 2025 - well after
                "2026-11-01 00:59:59.0",       // id=13: fall-back 2026 - before ambiguous hour
                "2026-11-01 01:00:00.0",       // id=14: fall-back 2026 - start of ambiguous hour
                "2026-11-01 01:30:00.0",       // id=15: fall-back 2026 - middle of ambiguous hour
                "2026-11-01 01:59:59.0",       // id=16: fall-back 2026 - end of ambiguous hour
                "2026-11-01 02:00:00.0",       // id=17: fall-back 2026 - first unambiguous second after
                "2026-11-01 03:00:00.0",       // id=18: fall-back 2026 - well after
                "2027-11-07 00:59:59.0",       // id=19: fall-back 2027 - before ambiguous hour
                "2027-11-07 01:00:00.0",       // id=20: fall-back 2027 - start of ambiguous hour
                "2027-11-07 01:30:00.0",       // id=21: fall-back 2027 - middle of ambiguous hour
                "2027-11-07 01:59:59.0",       // id=22: fall-back 2027 - end of ambiguous hour
                "2027-11-07 02:00:00.0",       // id=23: fall-back 2027 - first unambiguous second after
                "2027-11-07 03:00:00.0"        // id=24: fall-back 2027 - well after
        };

        ResultSet test2Result = ITCommon.executeQueryWithResultSet(
                "select * from employees.test_2 order by id", writer.getConnection());
        int test2RowCount = 0;
        while (test2Result.next()) {
            int id = test2Result.getInt("id");
            String actual = test2Result.getTimestamp("gates_from").toString();
            System.out.println("test_2 id=" + id + " gates_from=" + actual);
            Assert.assertTrue("test_2 row id=" + id + " gates_from mismatch: " + actual,
                    actual.equalsIgnoreCase(expectedGatesFrom[id - 1]));
            test2RowCount++;
        }
        Assert.assertEquals("test_2 should have 24 rows", 24, test2RowCount);

        if(engine.get() != null) {
            engine.get().stop();
        }
        // Files.deleteIfExists(tmpFilePath);
        executorService.shutdown();

        writer.getConnection().close();

        HikariDbSource.close();
    }

}

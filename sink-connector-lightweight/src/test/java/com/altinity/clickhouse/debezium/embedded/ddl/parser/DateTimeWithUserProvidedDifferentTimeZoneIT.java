package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.common.PropertiesHelper;
import com.altinity.clickhouse.debezium.embedded.config.ConfigLoader;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.clickhouse.jdbc.ClickHouseConnection;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@Testcontainers
@DisplayName("Integration Test that validates replication of DateTime columns when user overrides CH server timezone, also MySQL server runs on a different timezone.")
public class DateTimeWithUserProvidedDifferentTimeZoneIT {
    protected MySQLContainer mySqlContainer;

    @Container
    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:latest")
            .asCompatibleSubstituteFor("clickhouse"))
            .withInitScript("init_clickhouse_user_provided_timezone.sql")
            .withCopyFileToContainer(MountableFile.forClasspathResource("config.xml"), "/etc/clickhouse-server/config.d/config.xml")
            .withUsername("ch_user")
            .withPassword("password")
            .withExposedPorts(8123);


    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:8.0.36")
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                .withInitScript("datetime.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .withEnv("TZ", "US/Central")
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

                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(getDebeziumProperties(), new SourceRecordParserService(),
                        new MySQLDDLParserService(new ClickHouseSinkConnectorConfig(new HashMap<>()),
                                "datatypes"), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(30000);
        Connection conn = connectToMySQL();

        String DATETIME_MIN = "1900-01-01 00:00:00.0";
        String DATETIME_MID = "2022-09-29 01:47:46.0";
        String DATETIME_MAX = "2299-12-31 23:59:59.0";

        String DATETIME1_MIN = "1900-01-01 00:00:00.0";
        String DATETIME1_MID = "2022-09-29 01:48:25.1";
        String DATETIME1_MAX = "2299-12-31 23:59:59.9";

        String DATETIME2_MIN = "1900-01-01 00:00:00.0";
        String DATETIME2_MID = "2022-09-29 01:49:05.12";
        String DATETIME2_MAX = "2299-12-31 23:59:59.99";

        String DATETIME3_MIN = "1900-01-01 00:00:00.0";
        String DATETIME3_MID = "2022-09-29 01:49:22.123";
        String DATETIME3_MAX = "2299-12-31 23:59:59.999";

        String DATETIME4_MIN = "1900-01-01 00:00:00.0";
        String DATETIME4_MID = "2022-09-29 01:50:12.1234";
        String DATETIME4_MAX = "2299-12-31 23:59:59.9999";

        String DATETIME5_MIN = "1900-01-01 00:00:00.0";
        String DATETIME5_MID = "2022-09-29 01:50:28.12345";
        String DATETIME5_MAX = "2299-12-31 23:59:59.99999";

        String DATETIME6_MIN = "1900-01-01 00:00:00.0";
        String DATETIME6_MID = "2022-09-29 01:50:56.123456";
        String DATETIME6_MAX = "2299-12-31 23:59:59.999999";

        String jdbcUrl = BaseDbWriter.getConnectionString(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees");
        ClickHouseConnection chConn = BaseDbWriter.createConnection(jdbcUrl, "Client_1",
                clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), new ClickHouseSinkConnectorConfig(new HashMap<>()));

        BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null, chConn);


        // Validate that the MySQL server is set to Central timezone.
        ResultSet mySqlTimeZoneRS = conn.createStatement().executeQuery("select @@system_time_zone\n");
        boolean mySqlTimeZoneValidated = false;
        while(mySqlTimeZoneRS.next()) {
            mySqlTimeZoneValidated = true;
            System.out.println("MySQL Timezone: " + mySqlTimeZoneRS.getString(1));
            //Assert.assertTrue(mySqlTimeZoneRS.getString(1).equalsIgnoreCase("CST"));
        }

        Assert.assertTrue(mySqlTimeZoneValidated);

        // Validate that Clickhouse server is set to Central timezone.
        ResultSet chTimeZoneRS = writer.getConnection().prepareStatement("select timezone()").executeQuery();
        boolean chTimeZoneValidated = false;
        while(chTimeZoneRS.next()) {
            chTimeZoneValidated = true;
            System.out.println("ClickHouse Timezone: " + chTimeZoneRS.getString(1));
            Assert.assertTrue(chTimeZoneRS.getString(1).equalsIgnoreCase("America/Chicago"));
        }
        Assert.assertTrue(chTimeZoneValidated);

        /**
         * DATE TIME
         * 1969-12-31 18:00:00.0
         * 2022-09-28 20:47:46.0
         * 2106-02-07 00:28:15.0
         * DATE TIME 1
         * 1969-12-31 18:00:00.0
         * 2022-09-28 20:48:25.0
         * 2106-02-07 00:28:15.0
         * DATE TIME 2
         * 1969-12-31 18:00:00.0
         * 2022-09-28 20:49:05.0
         * 2106-02-07 00:28:15.0
         */
        // Validate temporal_types_DATETIME data.
        ResultSet dateTimeResult = writer.executeQueryWithResultSet("select * from temporal_types_DATETIME");

        while(dateTimeResult.next()) {
            System.out.println("DATE TIME");
            System.out.println(dateTimeResult.getTimestamp("Minimum_Value").toString());
            System.out.println(dateTimeResult.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult.getTimestamp("Maximum_Value").toString());

            Assert.assertTrue(dateTimeResult.getTimestamp("Minimum_Value").toString().equalsIgnoreCase(DATETIME_MIN));
            Assert.assertTrue(dateTimeResult.getTimestamp("Mid_Value").toString().equalsIgnoreCase(DATETIME_MID));
            Assert.assertTrue(dateTimeResult.getTimestamp("Maximum_Value").toString().equalsIgnoreCase(DATETIME_MAX));
            break;
        }

        // DATETIME1
        ResultSet dateTimeResult1 = writer.executeQueryWithResultSet("select * from temporal_types_DATETIME1");
        while(dateTimeResult1.next()) {
            System.out.println("DATE TIME 1");
            System.out.println(dateTimeResult1.getTimestamp("Minimum_Value").toString());
            System.out.println(dateTimeResult1.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult1.getTimestamp("Maximum_Value").toString());

            Assert.assertTrue(dateTimeResult1.getTimestamp("Minimum_Value").toString().equalsIgnoreCase(DATETIME1_MIN));
            Assert.assertTrue(dateTimeResult1.getTimestamp("Mid_Value").toString().equalsIgnoreCase(DATETIME1_MID));
            Assert.assertTrue(dateTimeResult1.getTimestamp("Maximum_Value").toString().equalsIgnoreCase(DATETIME1_MAX));
        }

        // DATETIME2
        ResultSet dateTimeResult2 = writer.executeQueryWithResultSet("select * from temporal_types_DATETIME2");
        while(dateTimeResult2.next()) {
            System.out.println("DATE TIME 2");
            System.out.println(dateTimeResult2.getTimestamp("Minimum_Value").toString());
            System.out.println(dateTimeResult2.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult2.getTimestamp("Maximum_Value").toString());

            Assert.assertTrue(dateTimeResult2.getTimestamp("Minimum_Value").toString().equalsIgnoreCase(DATETIME2_MIN));
            Assert.assertTrue(dateTimeResult2.getTimestamp("Mid_Value").toString().equalsIgnoreCase(DATETIME2_MID));
            Assert.assertTrue(dateTimeResult2.getTimestamp("Maximum_Value").toString().equalsIgnoreCase(DATETIME2_MAX));
        }

         //DATETIME3
        ResultSet dateTimeResult3 = writer.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME3");
        while(dateTimeResult3.next()) {
            System.out.println("DATE TIME 3");

            System.out.println(dateTimeResult3.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult3.getTimestamp("Maximum_Value").toString());
            System.out.println(dateTimeResult3.getTimestamp("Minimum_Value").toString());

            Assert.assertTrue(dateTimeResult3.getTimestamp("Mid_Value").toString().equalsIgnoreCase(DATETIME3_MID));
            Assert.assertTrue(dateTimeResult3.getTimestamp("Maximum_Value").toString().equalsIgnoreCase(DATETIME3_MAX));
            Assert.assertTrue(dateTimeResult3.getTimestamp("Minimum_Value").toString().equalsIgnoreCase(DATETIME3_MIN));

        }


        // DATETIME4
        ResultSet dateTimeResult4 = writer.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME4");
        while(dateTimeResult4.next()) {
            System.out.println("DATE TIME 4");

            System.out.println(dateTimeResult4.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult4.getTimestamp("Maximum_Value").toString());
            System.out.println(dateTimeResult4.getTimestamp("Minimum_Value").toString());

            Assert.assertTrue(dateTimeResult4.getTimestamp("Mid_Value").toString().equalsIgnoreCase(DATETIME4_MID));
            Assert.assertTrue(dateTimeResult4.getTimestamp("Maximum_Value").toString().equalsIgnoreCase(DATETIME4_MAX));
            Assert.assertTrue(dateTimeResult4.getTimestamp("Minimum_Value").toString().equalsIgnoreCase(DATETIME4_MIN));
        }


        // DATETIME5
        ResultSet dateTimeResult5 = writer.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME5");
        while(dateTimeResult5.next()) {
            System.out.println("DATE TIME 5");

            System.out.println(dateTimeResult5.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult5.getTimestamp("Maximum_Value").toString());
            System.out.println(dateTimeResult5.getTimestamp("Minimum_Value").toString());

            Assert.assertTrue(dateTimeResult5.getTimestamp("Mid_Value").toString().equalsIgnoreCase(DATETIME5_MID));
            Assert.assertTrue(dateTimeResult5.getTimestamp("Maximum_Value").toString().equalsIgnoreCase(DATETIME5_MAX));
            Assert.assertTrue(dateTimeResult5.getTimestamp("Minimum_Value").toString().equalsIgnoreCase(DATETIME5_MIN));

        }

        // DATETIME6
        ResultSet dateTimeResult6 = writer.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME6");
        while(dateTimeResult6.next()) {
            System.out.println("DATE TIME 6");

            System.out.println(dateTimeResult6.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult6.getTimestamp("Maximum_Value").toString());
            System.out.println(dateTimeResult6.getTimestamp("Minimum_Value").toString());

            Assert.assertTrue(dateTimeResult6.getTimestamp("Mid_Value").toString().equalsIgnoreCase(DATETIME6_MID));
            Assert.assertTrue(dateTimeResult6.getTimestamp("Maximum_Value").toString().equalsIgnoreCase(DATETIME6_MAX));
            Assert.assertTrue(dateTimeResult6.getTimestamp("Minimum_Value").toString().equalsIgnoreCase(DATETIME6_MIN));
            break;
        }

        if(engine.get() != null) {
            engine.get().stop();
        }
        // Files.deleteIfExists(tmpFilePath);
        executorService.shutdown();

        writer.getConnection().close();
    }

    protected Properties getDebeziumProperties() throws Exception {

        // Start the debezium embedded application.

        Properties defaultProps = new Properties();
        Properties defaultProperties = PropertiesHelper.getProperties("config.properties");

        defaultProps.putAll(defaultProperties);
        Properties fileProps = new ConfigLoader().load("config.yml");
        defaultProps.putAll(fileProps);

        // **** OVERRIDE set to schema only
        defaultProps.setProperty("snapshot.mode", "initial");
        defaultProps.setProperty("disable.drop.truncate", "true");
        defaultProps.setProperty("auto.create.tables", "true");
        defaultProps.setProperty("enable.snapshot.ddl", "true");

        defaultProps.setProperty("database.hostname", mySqlContainer.getHost());
        defaultProps.setProperty("database.port", String.valueOf(mySqlContainer.getFirstMappedPort()));
        defaultProps.setProperty("database.user", "root");
        defaultProps.setProperty("database.password", "adminpass");

        defaultProps.setProperty("clickhouse.server.url", clickHouseContainer.getHost());
        defaultProps.setProperty("clickhouse.server.port", String.valueOf(clickHouseContainer.getFirstMappedPort()));
        defaultProps.setProperty("clickhouse.server.user", clickHouseContainer.getUsername());
        defaultProps.setProperty("clickhouse.server.password", clickHouseContainer.getPassword());

        defaultProps.setProperty("offset.storage.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));

        defaultProps.setProperty("schema.history.internal.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));

        defaultProps.setProperty("offset.storage.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));

        defaultProps.setProperty("schema.history.internal.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));


//        props.setProperty("database.include.list", "datatypes");
//        props.setProperty("clickhouse.server.database", "datatypes");
        // Override clickhouse server timezone.
        defaultProps.setProperty("database.connectionTimeZone", "UTC");
        defaultProps.setProperty("clickhouse.datetime.timezone", "UTC");
        return defaultProps;

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

}

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
@DisplayName("Integration Test that validates replication of DateTime columns when the clickhouse schema has columns with timezone. Test to cover when MySQL has 2 digit year")
public class DateTimeWithTimeZoneSchemaOnlyIT {
    protected MySQLContainer mySqlContainer;

    @Container
    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:latest")
            .asCompatibleSubstituteFor("clickhouse"))
            .withInitScript("init_clickhouse_schema_only.sql")
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

                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(getDebeziumProperties(), new SourceRecordParserService(),
                        new MySQLDDLParserService(new ClickHouseSinkConnectorConfig(new HashMap<>()), "datatypes"), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(30000);
        Connection conn = connectToMySQL();
        // alter table ship_class change column class_name class_name_new int;
        // alter table ship_class change column tonange tonange_new decimal(10,10);

        conn.prepareStatement("INSERT INTO `temporal_types_DATETIME` VALUES ('DATETIME-INSERT','1000-01-01 00:00:00','2022-09-29 01:47:46','9999-12-31 23:59:59','9999-12-31 23:59:59');\n").execute();
        conn.prepareStatement("INSERT INTO `temporal_types_DATETIME1` VALUES ('DATETIME(1)-INSERT','1000-01-01 00:00:00.0','2022-09-29 01:48:25.1','9999-12-31 23:59:59.9','9999-12-31 23:59:59');").execute();
        conn.prepareStatement("INSERT INTO `temporal_types_DATETIME2` VALUES ('DATETIME(2)-INSERT','1000-01-01 00:00:00.00','2022-09-29 01:49:05.12','9999-12-31 23:59:59.99','9999-12-31 23:59:59');\n").execute();
        conn.prepareStatement("INSERT INTO `temporal_types_DATETIME3` VALUES ('DATETIME(3)-INSERT','1000-01-01 00:00:00.000','2022-09-29 01:49:22.123','9999-12-31 23:59:59.999',NULL)").execute();
        conn.prepareStatement("INSERT INTO `temporal_types_DATETIME4` VALUES ('DATETIME(4)-INSERT','1000-01-01 00:00:00.0000','2022-09-29 01:50:12.1234','9999-12-31 23:59:59.9999',NULL)").execute();
        conn.prepareStatement("INSERT INTO `temporal_types_DATETIME5` VALUES ('DATETIME(5)-INSERT','1000-01-01 00:00:00.00000','2022-09-29 01:50:28.12345','9999-12-31 23:59:59.99999',NULL)").execute();
        conn.prepareStatement("INSERT INTO `temporal_types_DATETIME6` VALUES ('DATETIME(6)-INSERT','1000-01-01 00:00:00.000000','2022-09-29 01:50:56.123456','9999-12-31 23:59:59.999999',NULL)").execute();
        conn.prepareStatement("INSERT INTO `temporal_types_DATETIME6` VALUES ('DATETIME(7)-INSERT','0099-01-01 00:00:00.000000','2022-09-29 01:50:56.123456','9999-12-31 23:59:59.999999',NULL)").execute();

        //conn.prepareStatement("INSERT INTO `temporal_types_DATETIME` VALUES ('DATETIME-INSERT','1000-01-01 00:00:00','2022-09-29 01:47:46','9999-12-31 23:59:59',NULL);\n").execute();

        String jdbcUrl = BaseDbWriter.getConnectionString(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(), "employees");
        ClickHouseConnection conn1 = BaseDbWriter.createConnection(jdbcUrl, "client_1", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), new ClickHouseSinkConnectorConfig(new HashMap<>()));
        BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null,
                conn1);


        writer.getConnection().close();
        Thread.sleep(10000);
        ClickHouseConnection conn2 = BaseDbWriter.createConnection(jdbcUrl, "client_1", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), new ClickHouseSinkConnectorConfig(new HashMap<>()));

         writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null, conn2);

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

            Assert.assertTrue(dateTimeResult.getTimestamp("Minimum_Value").toString().equalsIgnoreCase("1969-12-31 18:00:00.0"));
            Assert.assertTrue(dateTimeResult.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-28 20:47:46.0"));
            Assert.assertTrue(dateTimeResult.getTimestamp("Maximum_Value").toString().equalsIgnoreCase("2106-02-07 00:28:15.0"));
        }

        // DATETIME1
        ResultSet dateTimeResult1 = writer.executeQueryWithResultSet("select * from temporal_types_DATETIME1");
        while(dateTimeResult1.next()) {
            System.out.println("DATE TIME 1");
            System.out.println(dateTimeResult1.getTimestamp("Minimum_Value").toString());
            System.out.println(dateTimeResult1.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult1.getTimestamp("Maximum_Value").toString());

            Assert.assertTrue(dateTimeResult1.getTimestamp("Minimum_Value").toString().equalsIgnoreCase("1969-12-31 18:00:00.0"));
            Assert.assertTrue(dateTimeResult1.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-28 20:48:25.0"));
            Assert.assertTrue(dateTimeResult1.getTimestamp("Maximum_Value").toString().equalsIgnoreCase("2106-02-07 00:28:15.0"));
        }

        // DATETIME2
        ResultSet dateTimeResult2 = writer.executeQueryWithResultSet("select * from temporal_types_DATETIME2");
        while(dateTimeResult2.next()) {
            System.out.println("DATE TIME 2");
            System.out.println(dateTimeResult2.getTimestamp("Minimum_Value").toString());
            System.out.println(dateTimeResult2.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult2.getTimestamp("Maximum_Value").toString());

            Assert.assertTrue(dateTimeResult2.getTimestamp("Minimum_Value").toString().equalsIgnoreCase("1969-12-31 18:00:00.0"));
            Assert.assertTrue(dateTimeResult2.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-28 20:49:05.0"));
            Assert.assertTrue(dateTimeResult2.getTimestamp("Maximum_Value").toString().equalsIgnoreCase("2106-02-07 00:28:15.0"));
        }

         //DATETIME3
        ResultSet dateTimeResult3 = writer.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME3");
        while(dateTimeResult3.next()) {
            System.out.println("DATE TIME 3");

            System.out.println(dateTimeResult3.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult3.getTimestamp("Maximum_Value").toString());
            System.out.println(dateTimeResult3.getTimestamp("Minimum_Value").toString());

            Assert.assertTrue(dateTimeResult3.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-28 20:49:22.0"));
            Assert.assertTrue(dateTimeResult3.getTimestamp("Maximum_Value").toString().equalsIgnoreCase("2106-02-07 00:28:15.0"));
            Assert.assertTrue(dateTimeResult3.getTimestamp("Minimum_Value").toString().equalsIgnoreCase("1969-12-31 18:00:00.0"));
        }


        // DATETIME4
        ResultSet dateTimeResult4 = writer.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME4");
        while(dateTimeResult4.next()) {
            System.out.println("DATE TIME 4");

            System.out.println(dateTimeResult4.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult4.getTimestamp("Maximum_Value").toString());
            System.out.println(dateTimeResult4.getTimestamp("Minimum_Value").toString());

            Assert.assertTrue(dateTimeResult4.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-28 20:50:12.1234"));
            Assert.assertTrue(dateTimeResult4.getTimestamp("Maximum_Value").toString().equalsIgnoreCase("2299-12-31 17:59:59.999999"));
            Assert.assertTrue(dateTimeResult4.getTimestamp("Minimum_Value").toString().equalsIgnoreCase("1900-01-01 18:00:00.0"));

        }


        // DATETIME5
        ResultSet dateTimeResult5 = writer.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME5");
        while(dateTimeResult5.next()) {
            System.out.println("DATE TIME 5");

            System.out.println(dateTimeResult5.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult5.getTimestamp("Maximum_Value").toString());
            System.out.println(dateTimeResult5.getTimestamp("Minimum_Value").toString());

            Assert.assertTrue(dateTimeResult5.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-28 20:50:28.12345"));
            Assert.assertTrue(dateTimeResult5.getTimestamp("Maximum_Value").toString().equalsIgnoreCase("2299-12-31 17:59:59.999999"));
            Assert.assertTrue(dateTimeResult5.getTimestamp("Minimum_Value").toString().equalsIgnoreCase("1900-01-01 18:00:00.0"));

        }

        // DATETIME6
        ResultSet dateTimeResult6 = writer.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME6 where Type = 'DATETIME(6)-INSERT'");
        while(dateTimeResult6.next()) {
            System.out.println("DATE TIME 6");

            System.out.println(dateTimeResult6.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult6.getTimestamp("Maximum_Value").toString());
            System.out.println(dateTimeResult6.getTimestamp("Minimum_Value").toString());

            Assert.assertTrue(dateTimeResult6.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-28 20:50:56.123456"));
            Assert.assertTrue(dateTimeResult6.getTimestamp("Maximum_Value").toString().equalsIgnoreCase("2299-12-31 17:59:59.999999"));
            Assert.assertTrue(dateTimeResult6.getTimestamp("Minimum_Value").toString().equalsIgnoreCase("1900-01-01 18:00:00.0"));
            break;
        }

        // DATETIME6 with 2 digit year
        ResultSet dateTimeResult7 = writer.executeQueryWithResultSet("select * from employees.temporal_types_DATETIME6 where Type = 'DATETIME(7)-INSERT'");
        while(dateTimeResult7.next()) {
            System.out.println("DATE TIME 7");

            System.out.println(dateTimeResult7.getTimestamp("Mid_Value").toString());
            System.out.println(dateTimeResult7.getTimestamp("Maximum_Value").toString());
            System.out.println(dateTimeResult7.getTimestamp("Minimum_Value").toString());

            Assert.assertTrue(dateTimeResult7.getTimestamp("Mid_Value").toString().equalsIgnoreCase("2022-09-28 20:50:56.123456"));
            Assert.assertTrue(dateTimeResult7.getTimestamp("Maximum_Value").toString().equalsIgnoreCase("2299-12-31 17:59:59.999999"));
            Assert.assertTrue(dateTimeResult7.getTimestamp("Minimum_Value").toString().equalsIgnoreCase("1900-01-01 18:00:00.0"));
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
        defaultProps.setProperty("snapshot.mode", "schema_only");
        defaultProps.setProperty("disable.drop.truncate", "true");
        defaultProps.setProperty("auto.create.tables", "false");
        defaultProps.setProperty("enable.snapshot.ddl", "false");

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

        defaultProps.setProperty("enable.time.adjuster", "false");

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

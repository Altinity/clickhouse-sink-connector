package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.AppInjector;
import com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication;
import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.api.DebeziumEmbeddedRestApi;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import com.altinity.clickhouse.sink.connector.model.DBCredentials;
import com.google.common.collect.Maps;
import com.google.inject.Guice;
import com.google.inject.Injector;
import io.debezium.storage.jdbc.offset.JdbcOffsetBackingStoreConfig;
import org.apache.log4j.BasicConfigurator;
import org.junit.jupiter.api.*;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.getDebeziumProperties;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
import static org.junit.Assert.assertTrue;

@Testcontainers
public class DebeziumChangeEventCaptureIT{

    private static final Logger log = LoggerFactory.getLogger(DebeziumChangeEventCaptureIT.class);


    protected MySQLContainer mySqlContainer;

    @Container
    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_DOCKER_IMAGE)
            .asCompatibleSubstituteFor("clickhouse"))
            .withInitScript("init_clickhouse_schema_only_column_timezone.sql")
            //   .withCopyFileToContainer(MountableFile.forClasspathResource("config.xml"), "/etc/clickhouse-server/config.d/config.xml")
            .withUsername("ch_user")
            .withPassword("password")
            .withExposedPorts(8123);
    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse(MYSQL_DOCKER_IMAGE)
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
//                .withInitScript("15k_tables_mysql.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        Thread.sleep(35000);
    }

    @AfterEach()
    public void stop() {
        mySqlContainer.stop();
        clickHouseContainer.stop();
    }


    @Test
    @DisplayName("Test that validates that the sequence number that is created in non-gtid mode is incremented correctly.")
    public void testIncrementingSequenceNumbers() throws Exception {

        Injector injector = Guice.createInjector(new AppInjector());

        Properties props = getDebeziumProperties(mySqlContainer, clickHouseContainer);
        props.setProperty("snapshot.mode", "no_data");
        props.setProperty("schema.history.internal.store.only.captured.tables.ddl", "true");
        props.setProperty("schema.history.internal.store.only.captured.databases.ddl", "true");

        ClickHouseDebeziumEmbeddedApplication clickHouseDebeziumEmbeddedApplication = new ClickHouseDebeziumEmbeddedApplication();
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        Connection conn = null;

        try {
            executorService.execute(() -> {
                try {
                    clickHouseDebeziumEmbeddedApplication.start(injector.getInstance(DebeziumRecordParserService.class), props, false);
                    DebeziumEmbeddedRestApi.startRestApi(props, injector, clickHouseDebeziumEmbeddedApplication.getDebeziumEventCapture()
                            , new Properties());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Thread.sleep(25000);

            conn = ITCommon.connectToMySQL(mySqlContainer);
            conn.prepareStatement("create table `newtable`(col1 varchar(255) not null, col2 int, col3 int, primary key(col1))").execute();

            conn.prepareStatement("insert into newtable values('a', 1, 1)").execute();
            conn.prepareStatement("insert into newtable values('b', 2, 2)").execute();
            conn.prepareStatement("insert into newtable values('c', 3, 3)").execute();
            conn.prepareStatement("insert into newtable values('d', 4, 4)").execute();

            Thread.sleep(20000);

            BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

            long version1 = 1L;
            long version2 = 1L;
            long version3 = 1L;
            long version4 = 1L;

            ResultSet version1Result = ITCommon.executeQueryWithResultSet("select _version from employees.newtable final where col1 = 'a'", writer.getConnection());
            while (version1Result.next()) {
                version1 = version1Result.getLong("_version");
            }

            ResultSet version2Result = ITCommon.executeQueryWithResultSet("select _version from employees.newtable final where col1 = 'b'", writer.getConnection());
            while (version2Result.next()) {
                version2 = version2Result.getLong("_version");
            }

            ResultSet version3Result = ITCommon.executeQueryWithResultSet("select _version from employees.newtable final where col1 = 'c'", writer.getConnection());
            while (version3Result.next()) {
                version3 = version3Result.getLong("_version");
            }

            ResultSet version4Result = ITCommon.executeQueryWithResultSet("select _version from employees.newtable final where col1 = 'd'", writer.getConnection());
            while (version4Result.next()) {
                version4 = version4Result.getLong("_version");
            }

            assertTrue(version4 > version3);
            assertTrue(version3 > version2);
            assertTrue(version2 > version1);
        } finally {
            if (clickHouseDebeziumEmbeddedApplication.getDebeziumEventCapture() != null
                    && clickHouseDebeziumEmbeddedApplication.getDebeziumEventCapture().engine != null) {
                clickHouseDebeziumEmbeddedApplication.getDebeziumEventCapture().engine.close();
            }
            if (conn != null) {
                conn.close();
            }
            executorService.shutdown();
            HikariDbSource.close();
        }
    }

}

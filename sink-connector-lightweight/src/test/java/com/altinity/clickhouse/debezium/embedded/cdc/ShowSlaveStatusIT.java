package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.AppInjector;
import com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication;
import com.altinity.clickhouse.debezium.embedded.api.DebeziumEmbeddedRestApi;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.ErrorLogger;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.log4j.BasicConfigurator;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.getDebeziumPropertiesForSchemaOnly;

@Testcontainers
@DisplayName("Integration test for show-slave-status error table feature")
public class ShowSlaveStatusIT {

    protected MySQLContainer mySqlContainer;

    @Container
    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer(
            DockerImageName.parse(CLICKHOUSE_DOCKER_IMAGE).asCompatibleSubstituteFor("clickhouse"))
            .withInitScript("init_clickhouse_schema_only_column_timezone.sql")
            .withUsername("ch_user")
            .withPassword("password")
            .withExposedPorts(8123);

    @BeforeEach
    public void startContainers() throws InterruptedException {
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
    public void stopContainers() {
        DebeziumEmbeddedRestApi.stop();
        if (mySqlContainer != null && mySqlContainer.isRunning()) {
            mySqlContainer.stop();
        }
        if (clickHouseContainer != null && clickHouseContainer.isRunning()) {
            clickHouseContainer.stop();
        }
        HikariDbSource.close();
    }

    @Test
    @DisplayName("Validates error table creation and show-slave-status response")
    public void showSlaveStatusReturnsLatestErrorWithReplicationContext() throws Exception {
        Injector injector = Guice.createInjector(new AppInjector());
        Properties props = getDebeziumPropertiesForSchemaOnly(mySqlContainer, clickHouseContainer);
        props.setProperty("default.error.table", "replica_source_error");
        props.setProperty("error.logging.enable", "true");

        ClickHouseDebeziumEmbeddedApplication application = new ClickHouseDebeziumEmbeddedApplication();
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                application.start(injector.getInstance(DebeziumRecordParserService.class), props, false);
                DebeziumEmbeddedRestApi.startRestApi(props, injector,
                        application.getDebeziumEventCapture(), new Properties());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(10000);

        String jdbcUrl = BaseDbWriter.getConnectionString(clickHouseContainer.getHost(),
                clickHouseContainer.getFirstMappedPort(), BaseDbWriter.SYSTEM_DB);
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(new HashMap<>());
        Connection chConn = BaseDbWriter.createConnection(jdbcUrl, BaseDbWriter.DATABASE_CLIENT_NAME,
                clickHouseContainer.getUsername(), clickHouseContainer.getPassword(),
                BaseDbWriter.SYSTEM_DB, config);

        boolean errorTableExists = false;
        try (ResultSet tables = chConn.prepareStatement(
                "SELECT count(*) as cnt FROM system.tables WHERE database = 'system' AND name = 'replica_source_error'"
        ).executeQuery()) {
            if (tables.next()) {
                errorTableExists = tables.getInt("cnt") > 0;
            }
        }
        Assert.assertTrue("Error table should be created at connector startup", errorTableExists);

        ErrorLogger.logError(chConn,
                "integration test error",
                null,
                "employees",
                "ALTER TABLE employees.test ADD COLUMN x Int32",
                props.getProperty("name"),
                ErrorLogger.getErrorTableName(new ClickHouseSinkConnectorConfig(
                        com.altinity.clickhouse.debezium.embedded.common.PropertiesHelper.toMap(props))));

        ReplicationStatusSingleton.getInstance().setBinLogFile("mysql-bin.000003");
        ReplicationStatusSingleton.getInstance().setBinLogPosition("1156385");
        ReplicationStatusSingleton.getInstance().setGtid("30fd82c7-0f86-11ee-9e3b-0242c0a86002:1-2442");
        ReplicationStatusSingleton.getInstance().setIsReplicationRunning(true);

        DebeziumJdbcStorageOperations storageOperations = new DebeziumJdbcStorageOperations();
        String response = storageOperations.getErrorTableStatus(chConn, props);

        JSONParser parser = new JSONParser();
        JSONArray statusArray = (JSONArray) parser.parse(response);

        boolean hasReplicaRunning = false;
        boolean hasBinlogFile = false;
        boolean hasError = false;
        for (Object obj : statusArray) {
            JSONObject jsonObject = (JSONObject) obj;
            if (jsonObject.containsKey("Replica_Running")) {
                hasReplicaRunning = true;
                Assert.assertTrue(Boolean.TRUE.equals(jsonObject.get("Replica_Running")));
            }
            if (jsonObject.containsKey("Binlog_File")) {
                hasBinlogFile = true;
                Assert.assertEquals("mysql-bin.000003", jsonObject.get("Binlog_File"));
            }
            if (jsonObject.containsKey("error")) {
                hasError = true;
                Assert.assertEquals("integration test error", jsonObject.get("error"));
                Assert.assertEquals("employees", jsonObject.get("source_database"));
            }
        }

        Assert.assertTrue("Response should include Replica_Running", hasReplicaRunning);
        Assert.assertTrue("Response should include Binlog_File", hasBinlogFile);
        Assert.assertTrue("Response should include latest error row", hasError);

        ClickHouseDebeziumEmbeddedApplication.stop();
        application.getDebeziumEventCapture().stop();
        executorService.shutdown();
    }
}

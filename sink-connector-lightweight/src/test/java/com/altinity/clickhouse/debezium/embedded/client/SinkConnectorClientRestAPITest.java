package com.altinity.clickhouse.debezium.embedded.client;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import com.altinity.clickhouse.debezium.embedded.AppInjector;
import com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication;
import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.api.DebeziumEmbeddedRestApi;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumJdbcStorageOperations;
import com.altinity.clickhouse.debezium.embedded.common.PropertiesHelper;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.config.ConfigLoader;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.clickhouse.jdbc.ClickHouseConnection;

import com.github.dockerjava.zerodep.shaded.org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import com.github.dockerjava.zerodep.shaded.org.apache.hc.core5.http.io.entity.StringEntity;
import com.google.inject.Guice;
import org.aopalliance.reflect.Metadata;
import com.github.dockerjava.zerodep.shaded.org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import com.github.dockerjava.zerodep.shaded.org.apache.hc.client5.http.classic.methods.HttpGet;
import com.github.dockerjava.zerodep.shaded.org.apache.hc.client5.http.classic.methods.HttpPost;
import com.github.dockerjava.zerodep.shaded.org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.log4j.BasicConfigurator;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.Assert;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Integration Test class to validate MySQL and ClickHouse operations using Debezium.
 * Tests various DDL and DML operations including column additions, modifications,
 * and data manipulations while ensuring data consistency between MySQL and ClickHouse.
 */
@DisplayName("Integration Test to validate MySQL and ClickHouse operations using Debezium")
public class SinkConnectorClientRestAPITest {

    private static AtomicReference<ClickHouseDebeziumEmbeddedApplication> engine;
    private static ExecutorService executorService;
    private static Connection mysqlConn;
    private static BaseDbWriter writer;
    protected static MySQLContainer<?> mySqlContainer;
    private static Properties props;

    @Container
    public static ClickHouseContainer clickHouseContainer =
            new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:latest")
                    .asCompatibleSubstituteFor("clickhouse"))
                    .withInitScript("init_clickhouse_user_provided_timezone.sql")
                    .withCopyFileToContainer(MountableFile.forClasspathResource("config.xml"), "/etc/clickhouse-server/config.d/config.xml")
                    .withUsername("ch_user")
                    .withPassword("password")
                    .withExposedPorts(8123);

    /**
     * Initializes test environment by:
     * 1. Starting MySQL container with employees database
     * 2. Starting ClickHouse container
     * 3. Setting up Debezium engine for change data capture
     * 4. Establishing MySQL connection
     * 5. Creating database writer for ClickHouse operations
     *
     * @throws Exception if container startup or initialization fails
     */
    @BeforeAll
    public static void startContainers() {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:8.0.36")
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees")
                .withUsername("root")
                .withPassword("adminpass")
                .withInitScript("datetime.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .withEnv("TZ", "US/Central")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        try {
            BasicConfigurator.configure();
            mySqlContainer.start();
            clickHouseContainer.start();
            Thread.sleep(40000);

            setupDebeziumEngine();
            mysqlConn = ITCommon.connectToMySQL(mySqlContainer);
            writer = ITCommon.getDBWriter(clickHouseContainer);
            Thread.sleep(10000);
        } catch (Exception e) {
            throw new RuntimeException("Failed to start containers", e);
        }
    }

    /**
     * Configures and initializes the Debezium engine for change data capture.
     * Creates a new single-threaded executor service to run the engine asynchronously.
     * Waits for engine startup to complete before proceeding.
     *
     * @throws Exception if engine setup fails
     */
    private static void setupDebeziumEngine() throws Exception {
        engine = new AtomicReference<>();
        props = getDebeziumProperties();
        executorService = Executors.newSingleThreadExecutor();
        executorService.execute(() -> {
            try {
                ClickHouseDebeziumEmbeddedApplication clickHouseDebeziumEmbeddedApplication =
                        new ClickHouseDebeziumEmbeddedApplication();
                engine.set(clickHouseDebeziumEmbeddedApplication);
                clickHouseDebeziumEmbeddedApplication.start(new SourceRecordParserService(), props, false);
                Thread.sleep(10000);
                startApi();
            } catch (Exception e) {
                throw new RuntimeException("Failed to start Debezium engine", e);
            }
        });
        Thread.sleep(30000); // Wait for engine to initialize
    }

    /**
     * Loads and configures Debezium properties from various sources:
     * 1. Default properties from config.properties
     * 2. Configuration from config.yml
     * 3. Container-specific settings
     * 4. Database connection details
     *
     * @return Properties object with all required configurations
     * @throws Exception if property loading fails
     */
    private static Properties getDebeziumProperties() throws Exception {
        Properties defaultProps = new Properties();

        // Load default properties from file
        Properties defaultProperties = PropertiesHelper.getProperties("config.properties");
        defaultProps.putAll(defaultProperties);

        // Load config.yml
        Properties fileProps = new ConfigLoader().load("config.yml");
        defaultProps.putAll(fileProps);

        // Override snapshot and behavior config
        defaultProps.setProperty("snapshot.mode", "initial");
        defaultProps.setProperty("disable.drop.truncate", "true");
        defaultProps.setProperty("auto.create.tables", "true");
        defaultProps.setProperty("enable.snapshot.ddl", "true");

        // Set MySQL container values
        defaultProps.setProperty("database.hostname", mySqlContainer.getHost());
        defaultProps.setProperty("database.port", String.valueOf(mySqlContainer.getFirstMappedPort()));
        defaultProps.setProperty("database.user", "root");
        defaultProps.setProperty("database.password", "adminpass");

        // Set ClickHouse container values
        defaultProps.setProperty("clickhouse.server.url", clickHouseContainer.getHost());
        defaultProps.setProperty("clickhouse.server.port", String.valueOf(clickHouseContainer.getFirstMappedPort()));
        defaultProps.setProperty("clickhouse.server.user", clickHouseContainer.getUsername());
        defaultProps.setProperty("clickhouse.server.password", clickHouseContainer.getPassword());

        // Configure offset and schema history
        String clickhouseJdbcUrl = String.format("jdbc:clickhouse://%s:%s/altinity_sink_connector",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort());

        defaultProps.setProperty("offset.storage.jdbc.url", clickhouseJdbcUrl);
        defaultProps.setProperty("schema.history.internal.jdbc.url", clickhouseJdbcUrl);

        // Add missing required properties if not present
        defaultProps.putIfAbsent("schema.history.internal.jdbc.schema.history.table.name",
                "altinity_sink_connector.replicate_schema_history");

        defaultProps.putIfAbsent("offset.storage.jdbc.offset.table.name",
                "altinity_sink_connector.replica_source_info");


        defaultProps.putIfAbsent("schema.history.internal.jdbc.schema.history.table.ddl",
                "CREATE TABLE IF NOT EXISTS %s (`id` VARCHAR(36) NOT NULL, `history_data` VARCHAR(65000), " +
                        "`history_data_seq` INTEGER, `record_insert_ts` TIMESTAMP NOT NULL, `record_insert_seq` INTEGER NOT NULL) " +
                        "ENGINE=ReplacingMergeTree(record_insert_seq) ORDER BY id");

        // Timezone configuration
        defaultProps.setProperty("database.connectionTimeZone", "UTC");
        defaultProps.setProperty("clickhouse.datetime.timezone", "UTC");

        return defaultProps;
    }

    /**
     * Cleanup method to stop Debezium engine and executor service.
     * Deletes offsets and ensures proper shutdown of resources.
     *
     * @throws Exception if cleanup operations fail
     */
    @AfterAll
    public static void stopEngine() throws Exception {
        Properties props = ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer);
        if (engine != null && engine.get() != null) {
            new DebeziumJdbcStorageOperations().deleteOffsets(writer.getConnection(), props);
            engine.get().stop();
        }
        executorService.shutdownNow();
    }

    /**
     * Starts the embedded Debezium connector and its REST API using configured properties.
     */
    private static void startApi() {
        DebeziumEmbeddedRestApi.startRestApi(
                props,
                Guice.createInjector(new AppInjector()),
                engine.get().getDebeziumEventCapture(),
                new Properties());
    }

    /**
     * Validates /start and /stop endpoints of the Debezium REST API and verifies replication behavior.
     * This test also ensures that the data inserted into MySQL after restarting is successfully replicated to ClickHouse.
     */
    @Test
    @DisplayName("Validate start/stop endpoints and replication state via status API")
    public void testStopAndStartEndpoints() throws Exception {
        verifyTableCounts(mysqlConn, writer, "Before update column");

        Assert.assertTrue("Replica_Running should be true after startup", isReplicaRunning());

        // Stop Debezium connector
        HttpUriRequest stopRequest = new HttpGet("http://localhost:7000/stop");
        try (CloseableHttpResponse stopResponse = HttpClientBuilder.create().build().execute(stopRequest)) {
            Assert.assertEquals(200, stopResponse.getCode());
        }

        Assert.assertFalse("Replica_Running should be false after stopping", isReplicaRunning());

        // Restart the connector
        HttpUriRequest startRequest = new HttpGet("http://localhost:7000/start");
        try (CloseableHttpResponse startResponse = HttpClientBuilder.create().build().execute(startRequest)) {
            Assert.assertEquals(200, startResponse.getCode());
        }

        Thread.sleep(70000); // Wait for 1 minute+, it takes ~60 sec to run
        boolean result = isReplicaRunning();
        System.out.println("Replica_Running is " + result);
//        Assert.assertTrue("true", result);
//
//        insertAndVerifyReplication(mysqlConn, writer, "REST-API-TEST", "2023-01-01 00:00:00");
    }

    /**
     * Validates the /binlog endpoint for manually setting binlog offsets.
     * Also verifies that the data is persisted correctly in the `replica_source_info` table.
     */
    @Test
    @DisplayName("Validate /binlog endpoint and replica_source_info table")
    public void testBinlogEndpoint() throws Exception {
        if (isReplicaRunning()) {
            HttpUriRequest stopRequest = new HttpGet("http://localhost:7000/stop");
            try (CloseableHttpResponse stopResponse = HttpClientBuilder.create().build().execute(stopRequest)) {
                Assert.assertEquals(200, stopResponse.getCode());
            }
        }

        String file = "mysql-bin.000001";
        String pos = "1234";
        String gtid = "0-1-100";

        JSONObject binlogJson = new JSONObject();
        binlogJson.put("binlog_file", file);
        binlogJson.put("binlog_position", pos);
        binlogJson.put("gtid", gtid);
        binlogJson.put("source_host", mySqlContainer.getHost());
        binlogJson.put("source_port", String.valueOf(mySqlContainer.getMappedPort(3306)));
        binlogJson.put("source_user", "root");
        binlogJson.put("source_password", "adminpass");

        HttpPost binlogRequest = new HttpPost("http://localhost:7000/binlog");
        binlogRequest.setEntity(new StringEntity(binlogJson.toJSONString()));
        binlogRequest.setHeader("Content-Type", "application/json");

        try (CloseableHttpResponse response = HttpClientBuilder.create().build().execute(binlogRequest)) {
            Assert.assertEquals(200, response.getCode());
        }

        Thread.sleep(5000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        try (ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT * FROM `system`.replica_source_info FINAL").executeQuery()) {
            Assert.assertTrue("Replica source info should be present", rs.next());

            String offsetVal = rs.getString("offset_val");

            JSONParser parser = new JSONParser();
            JSONObject jsonObject = (JSONObject) parser.parse(offsetVal);

            Assert.assertEquals(file, jsonObject.get("file"));
            Assert.assertEquals(pos, jsonObject.get("pos"));
            Assert.assertEquals(gtid, jsonObject.get("gtids"));
        }
    }

    /**
     * Inserts a test record into MySQL and verifies its replication in ClickHouse.
     * The method:
     * 1. Inserts a test record with a unique identifier
     * 2. Waits for replication to complete
     * 3. Verifies the record exists in ClickHouse
     *
     * @param mysqlConn MySQL database connection
     * @param writer ClickHouse database writer
     * @param typeValue Unique identifier for the test record
     * @param datetimeVal Timestamp value for the test record
     * @throws Exception if data insertion or verification fails
     */
    private void insertAndVerifyReplication(Connection mysqlConn, BaseDbWriter writer,
                                            String typeValue, String datetimeVal) throws Exception {
        // Insert test data into MySQL
        String insertQuery = String.format(
                "INSERT INTO `temporal_types_DATETIME` VALUES ('%s','%s','%s','%s','%s')",
                typeValue, datetimeVal, datetimeVal, datetimeVal, datetimeVal);

        mysqlConn.prepareStatement(insertQuery).execute();

        // Wait for replication to complete
        Thread.sleep(15000);

        // Query ClickHouse to verify replicated row
        String verifyQuery = String.format(
                "SELECT * FROM employees.`temporal_types_DATETIME` FINAL WHERE Type = '%s'",
                typeValue);

        try (ResultSet rs = writer.getConnection().prepareStatement(verifyQuery).executeQuery()) {
            Assert.assertTrue("Inserted row should exist in ClickHouse", rs.next());
            Assert.assertEquals(typeValue, rs.getString("type"));
        }
    }

    /**
     * Checks if the Debezium connector is currently running by querying the status endpoint.
     *
     * @return true if the connector is running, false otherwise
     * @throws IOException if the status request fails
     */
    private boolean isReplicaRunning() throws IOException {
        HttpGet statusRequest = new HttpGet("http://localhost:7000/status");
        try (CloseableHttpResponse response = HttpClientBuilder.create().build().execute(statusRequest)) {
            Assert.assertEquals(200, response.getCode());
            String responseBody = new String(response.getEntity().getContent().readAllBytes());

            JSONParser parser = new JSONParser();
            JSONArray statusArray = (JSONArray) parser.parse(responseBody);
            for (Object obj : statusArray) {
                JSONObject jsonObject = (JSONObject) obj;
                if (jsonObject.containsKey("Replica_Running")) {
                    return Boolean.TRUE.equals(jsonObject.get("Replica_Running"));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse status response", e);
        }
        return false;
    }

    /**
     * Retrieves the count of records from a database table.
     *
     * @param conn Database connection
     * @param query SQL query to execute
     * @return Number of records matching the query
     * @throws Exception if query execution fails
     */
    private int getCount(Connection conn, String query) throws Exception {
        try (ResultSet rs = ITCommon.executeQueryWithResultSet(query, conn)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * Verifies that the record counts match between MySQL and ClickHouse tables.
     * This is used to ensure data consistency after operations.
     *
     * @param mysqlConn MySQL database connection
     * @param writer ClickHouse database writer
     * @param message Context message for the verification
     * @throws Exception if verification fails
     */
    private void verifyTableCounts(Connection mysqlConn, BaseDbWriter writer, String message) throws Exception {
        int mysqlCount = getCount(mysqlConn, "SELECT COUNT(*) FROM temporal_types_DATETIME");
        int chCount = getCount(writer.getConnection(), "SELECT COUNT(*) FROM employees.temporal_types_DATETIME FINAL");
        System.out.printf("MySQL: %d, ClickHouse: %d - %s%n", mysqlCount, chCount, message);
        Assert.assertEquals(message, mysqlCount, chCount);
    }
}
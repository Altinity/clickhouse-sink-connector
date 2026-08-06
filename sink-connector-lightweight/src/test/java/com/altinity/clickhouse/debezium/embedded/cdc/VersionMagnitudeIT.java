package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.AppInjector;
import com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication;
import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.log4j.BasicConfigurator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that the version formula stays in the ts_ms * 1e6 magnitude family (~1.79e18)
 * and can properly supersede pre-existing data written by 2.8.0/2.9.1.
 *
 * <p>This test:
 * 1. Pre-loads a ClickHouse table with rows carrying old-format versions (~1.79e18)
 * 2. Starts the connector which snapshots and streams from MySQL
 * 3. Performs an UPDATE in MySQL
 * 4. Asserts the UPDATE is visible (new version > old version) under FINAL
 * 5. Asserts the new version stays in the same magnitude family (< 2.5e18)
 *
 * <p>This guards against Blocker 1 (magnitude poisoning): any formula that produces
 * versions in a higher regime (~7.67e18) would make rollback impossible.
 */
@Testcontainers
@DisplayName("Version magnitude stays in ts_ms*1e6 family and supersedes 2.8.0 data")
public class VersionMagnitudeIT {

    private static final Logger log = LoggerFactory.getLogger(VersionMagnitudeIT.class);

    private static final long OLD_VERSION_MAGNITUDE = 1_785_000_000_000L * 1_000_000L; // ~1.785e18
    private static final long MAX_ACCEPTABLE_VERSION = 2_500_000_000_000L * 1_000_000L; // 2.5e18

    protected MySQLContainer mySqlContainer;

    private final AtomicReference<Throwable> connectorError = new AtomicReference<>();

    @Container
    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer(
            DockerImageName.parse(CLICKHOUSE_DOCKER_IMAGE).asCompatibleSubstituteFor("clickhouse"))
            .withInitScript("init_clickhouse_it.sql")
            .withUsername("ch_user")
            .withPassword("password")
            .withExposedPorts(8123);

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse(MYSQL_DOCKER_IMAGE)
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                .withInitScript("version_magnitude_mysql_init.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        clickHouseContainer.start();
        Thread.sleep(15000);
    }

    @AfterEach
    public void stopContainers() {
        if (mySqlContainer != null && mySqlContainer.isRunning()) {
            mySqlContainer.stop();
        }
        if (clickHouseContainer != null && clickHouseContainer.isRunning()) {
            clickHouseContainer.stop();
        }
    }

    @Test
    @DisplayName("UPDATE produces version in same magnitude family and supersedes old data")
    public void versionStaysInMagnitudeFamily() throws Exception {
        Properties props = buildProps();
        Injector injector = Guice.createInjector(new AppInjector());

        // Pre-load ClickHouse table with old-format version data (simulating 2.8.0 deployment)
        Connection ch = ITCommon.getDBWriter(clickHouseContainer, "default").getConnection();
        preloadOldVersionData(ch);

        // Verify preload
        try (Statement s = ch.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT count(*), max(_version) FROM magnitude_db.items FINAL WHERE is_deleted = 0")) {
            rs.next();
            int preCount = rs.getInt(1);
            long preMaxVersion = rs.getLong(2);
            log.info("Preloaded {} rows, max _version={}", preCount, preMaxVersion);
            assertEquals(3, preCount);
            assertTrue(preMaxVersion > OLD_VERSION_MAGNITUDE,
                    "Preloaded version should be in the old magnitude family");
        }

        // Start connector - it will snapshot MySQL and stream changes
        ExecutorService exec = startConnector(injector, props);
        Thread.sleep(30000);

        // Perform an UPDATE in MySQL - this should generate a new version
        Connection mysql = ITCommon.connectToMySQL(mySqlContainer);
        try (Statement s = mysql.createStatement()) {
            s.execute("UPDATE magnitude_db.items SET name = 'updated_item_1' WHERE id = 1");
        }

        Thread.sleep(15000);

        // Assert: the UPDATE is visible under FINAL
        ch = ITCommon.getDBWriter(clickHouseContainer, "altinity_sink_connector").getConnection();
        try (Statement s = ch.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT name, _version FROM magnitude_db.items FINAL WHERE id = 1 AND is_deleted = 0")) {
            assertTrue(rs.next(), "Row id=1 should be visible under FINAL");
            String name = rs.getString("name");
            long version = rs.getLong("_version");

            log.info("After UPDATE: name='{}', _version={}", name, version);

            assertEquals("updated_item_1", name,
                    "UPDATE should be visible - new version must supersede old");

            // The version must be > the old preloaded version (supersedes old data)
            assertTrue(version > OLD_VERSION_MAGNITUDE,
                    "New version (" + version + ") must exceed old format version ("
                    + OLD_VERSION_MAGNITUDE + ")");

            // The version must stay in the same magnitude family (< 2.5e18)
            // If it's ~7.67e18, the formula has broken magnitude compatibility
            assertTrue(version < MAX_ACCEPTABLE_VERSION,
                    "New version (" + version + ") exceeds safe magnitude ceiling ("
                    + MAX_ACCEPTABLE_VERSION + ") - rollback to 2.8.0 would be impossible");

            log.info("Version magnitude check PASSED: {} is in [{}, {}]",
                    version, OLD_VERSION_MAGNITUDE, MAX_ACCEPTABLE_VERSION);
        }

        stopConnector(exec);
    }

    // ------------------------------------------------------------------
    // Setup helpers
    // ------------------------------------------------------------------

    private void preloadOldVersionData(Connection ch) throws Exception {
        try (Statement s = ch.createStatement()) {
            s.execute("CREATE DATABASE IF NOT EXISTS magnitude_db");
            s.execute("CREATE TABLE IF NOT EXISTS magnitude_db.items ("
                    + "`id` Int32, "
                    + "`name` String, "
                    + "`_version` UInt64, "
                    + "`is_deleted` UInt8"
                    + ") ENGINE = ReplacingMergeTree(_version, is_deleted) "
                    + "ORDER BY id");

            // Insert rows with old-format versions (~1.785e18 + small offsets)
            long baseVersion = 1_785_678_221_550_000_000L; // typical 2.8.0 version
            s.execute("INSERT INTO magnitude_db.items VALUES "
                    + "(1, 'item_1', " + (baseVersion + 1) + ", 0), "
                    + "(2, 'item_2', " + (baseVersion + 2) + ", 0), "
                    + "(3, 'item_3', " + (baseVersion + 3) + ", 0)");
        }
    }

    private Properties buildProps() throws Exception {
        Properties props = ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer);
        props.setProperty("snapshot.mode", "initial");
        props.setProperty("database.include.list", "magnitude_db");
        props.setProperty("clickhouse.datetime.timezone", "UTC");
        props.setProperty("auto.create.tables", "false");
        props.setProperty("disable.ddl", "true");
        return props;
    }

    private ExecutorService startConnector(Injector injector, Properties props) {
        ClickHouseDebeziumEmbeddedApplication app =
                injector.getInstance(ClickHouseDebeziumEmbeddedApplication.class);
        ExecutorService exec = Executors.newFixedThreadPool(1);
        exec.submit(() -> {
            try {
                app.start(
                        injector.getInstance(DebeziumRecordParserService.class),
                        props, false);
            } catch (Exception e) {
                connectorError.set(e);
                log.error("Connector start failed", e);
            }
        });
        return exec;
    }

    private void stopConnector(ExecutorService exec) throws Exception {
        ClickHouseDebeziumEmbeddedApplication.stop();
        exec.shutdownNow();
        HikariDbSource.close();
        Thread.sleep(5000);
    }
}

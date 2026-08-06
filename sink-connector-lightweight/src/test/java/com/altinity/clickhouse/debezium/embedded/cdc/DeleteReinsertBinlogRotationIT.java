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
import org.junit.jupiter.api.Tag;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for binlog rotation correctness in version numbering.
 *
 * <p>Validates that a DELETE at the end of one binlog file (high {@code pos})
 * does NOT outrank a re-INSERT at the beginning of the next file (low {@code pos})
 * when both events share the same {@code source.ts_ms} second.
 *
 * <p>The old {@code (sourceSec << 32) | pos} formula would fail this test because
 * {@code pos} resets to ~4 on binlog rotation, giving the DELETE a higher version
 * than the subsequent INSERT - the exact same failure mode as issue #1346.
 *
 * <p>The correct formula ({@code ts_ms * 1_000_000 + sequenceNumber}) is monotonic
 * within the batch counter regardless of binlog position, so rotation is safe.
 */
@Testcontainers
@Tag("repro")
@DisplayName("DELETE+re-INSERT across binlog rotation must not stick deleted")
public class DeleteReinsertBinlogRotationIT {

    private static final Logger log = LoggerFactory.getLogger(DeleteReinsertBinlogRotationIT.class);

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
                .withInitScript("binlog_rotation_repro.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .withCommand("--max-binlog-size=4096", "--server-id=1")
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
    @DisplayName("Row re-inserted after binlog rotation is visible under FINAL")
    public void deleteReinsertAcrossBinlogRotation() throws Exception {
        Properties props = buildProps();
        Injector injector = Guice.createInjector(new AppInjector());

        Connection mysql = ITCommon.connectToMySQL(mySqlContainer);

        // Phase 1: Start connector, wait for initial snapshot of the seed row
        ExecutorService exec = startConnector(injector, props);
        Thread.sleep(25000);

        // Verify the seed row (id=1) was captured
        Connection ch = ITCommon.getDBWriter(clickHouseContainer, "altinity_sink_connector")
                .getConnection();
        int liveCount = queryLiveCount(ch);
        log.info("Phase 1 - live rows after snapshot: {}", liveCount);
        assertEquals(1, liveCount, "Seed row should be visible after snapshot");

        // Phase 2: DELETE the row (will be at high pos in current binlog file)
        // Then force binlog rotation by generating traffic that exceeds max_binlog_size (4096)
        // Then re-INSERT the same row (will be at low pos in the new binlog file)
        log.info("Phase 2 - DELETE, force rotation, re-INSERT");

        try (Statement s = mysql.createStatement()) {
            s.execute("DELETE FROM rotation_db.target WHERE id = 1");
        }

        // Generate enough traffic to force binlog rotation (max_binlog_size=4096)
        forceRotation(mysql);

        // Verify rotation happened
        String[] status = showMasterStatus(mysql);
        log.info("After rotation: file={} pos={}", status[0], status[1]);

        // Re-INSERT with the same PK (now in a new binlog file, low pos)
        try (Statement s = mysql.createStatement()) {
            s.execute("INSERT INTO rotation_db.target (id, data) VALUES (1, 'reinserted')");
        }

        // Insert a sentinel row AFTER the re-INSERT so we can confirm the connector
        // has processed everything up to this point
        try (Statement s = mysql.createStatement()) {
            s.execute("INSERT INTO rotation_db.target (id, data) VALUES (9999, 'sentinel')");
        }

        // Phase 3: Wait for the sentinel to appear in ClickHouse (confirms all prior events processed)
        log.info("Phase 3 - waiting for sentinel row to confirm streaming caught up");
        ch = ITCommon.getDBWriter(clickHouseContainer, "altinity_sink_connector").getConnection();
        long deadline = System.currentTimeMillis() + 90_000; // 90s max
        boolean sentinelFound = false;
        while (System.currentTimeMillis() < deadline) {
            try (Statement s = ch.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT count(*) FROM rotation_db.target FINAL WHERE id = 9999 AND is_deleted = 0")) {
                rs.next();
                if (rs.getInt(1) > 0) {
                    sentinelFound = true;
                    break;
                }
            }
            Thread.sleep(3000);
        }
        assertTrue(sentinelFound, "Sentinel row never arrived - connector may not be streaming");
        log.info("Sentinel found - connector has processed all events including re-INSERT");

        // Phase 4: Assert the row is alive (not stuck deleted)
        // Note: queryLiveCount counts all live rows including the sentinel (id=9999)
        ch = ITCommon.getDBWriter(clickHouseContainer, "altinity_sink_connector").getConnection();
        int stuckCount = queryStuckDeleted(ch);
        log.info("Phase 4 - stuck={}", stuckCount);

        assertEquals(0, stuckCount,
                "REGRESSION: DELETE at high pos in old binlog outranked re-INSERT at low pos in new binlog");

        // Verify the re-inserted row specifically
        try (Statement s = ch.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT data FROM rotation_db.target FINAL WHERE id = 1 AND is_deleted = 0")) {
            assertTrue(rs.next(), "Row id=1 should be visible under FINAL after re-INSERT");
            String data = rs.getString("data");
            assertEquals("reinserted", data,
                    "Data should reflect the re-INSERT, not the original seed");
        }

        stopConnector(exec);
    }

    // ------------------------------------------------------------------
    // Connector lifecycle
    // ------------------------------------------------------------------

    private Properties buildProps() throws Exception {
        Properties props = ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer);
        props.setProperty("snapshot.mode", "initial");
        props.setProperty("database.include.list", "rotation_db");
        props.setProperty("clickhouse.datetime.timezone", "UTC");
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

    // ------------------------------------------------------------------
    // MySQL helpers
    // ------------------------------------------------------------------

    private void forceRotation(Connection mysql) throws Exception {
        // Generate enough writes to exceed max_binlog_size (4096 bytes)
        try (Statement s = mysql.createStatement()) {
            for (int i = 0; i < 50; i++) {
                s.execute("INSERT INTO rotation_db.padding (payload) VALUES ('" +
                        "x".repeat(200) + "')");
            }
            s.execute("FLUSH BINARY LOGS");
        }
    }

    private String[] showMasterStatus(Connection mysql) throws Exception {
        try (Statement s = mysql.createStatement();
             ResultSet rs = s.executeQuery("SHOW MASTER STATUS")) {
            rs.next();
            return new String[]{rs.getString("File"), String.valueOf(rs.getLong("Position"))};
        }
    }

    // ------------------------------------------------------------------
    // ClickHouse assertion helpers
    // ------------------------------------------------------------------

    private int queryLiveCount(Connection ch) throws Exception {
        try (Statement s = ch.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT count(*) FROM rotation_db.target FINAL WHERE is_deleted = 0")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int queryStuckDeleted(Connection ch) throws Exception {
        try (Statement s = ch.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT count(*) FROM rotation_db.target FINAL WHERE is_deleted = 1")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}

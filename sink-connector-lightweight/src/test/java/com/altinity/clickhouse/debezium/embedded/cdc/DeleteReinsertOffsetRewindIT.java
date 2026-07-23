package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.AppInjector;
import com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication;
import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.log4j.BasicConfigurator;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic reproduction of the 2.9.1 delete+reinsert "stuck deleted" bug.
 *
 * <p>Background: {@code _version} for the ReplacingMergeTree target is computed as
 * {@code envelope_ts_ms * 1_000_000 + sequenceNumber}, where {@code envelope_ts_ms} is the
 * Debezium envelope timestamp (the wall-clock time the connector PROCESSED the event, read via
 * {@code ClickHouseStruct.getDebeziumTsFromChangeEvent}). Because the timestamp dominates the
 * version, the effective ordering is by processing time.
 *
 * <p>When Debezium re-delivers events after an offset regression (at-least-once semantics), a
 * previously-processed DELETE is re-stamped with a FRESH (later) envelope timestamp. If the
 * matching re-INSERT is NOT re-delivered, the re-delivered DELETE now outranks the original
 * re-INSERT in the ReplacingMergeTree and the row is permanently stuck with {@code is_deleted=1}
 * even though it still exists in MySQL -> silent data loss.
 *
 * <p>This test reproduces that exact sequence deterministically by rewinding the JDBC offset
 * store ({@code altinity_sink_connector.replica_source_info}) to a coordinate captured before the
 * DELETE, restarting so the DELETE is re-delivered, then skipping the re-INSERT by restoring the
 * final offset before the re-INSERT is re-read. Mirrors {@code run_repro_rewind.sh}.
 *
 * <p>Tagged {@code repro}: on the buggy build this test PASSES (asserts rows are stuck deleted).
 * Once the version-inversion bug is fixed, the final assertion must be inverted to
 * {@code stuckDeleted == 0}.
 */
@Testcontainers
@Tag("repro")
@DisplayName("Reproduces 2.9.1 delete+reinsert stuck-deleted via offset rewind redelivery")
public class DeleteReinsertOffsetRewindIT {

    private static final Logger log = LoggerFactory.getLogger(DeleteReinsertOffsetRewindIT.class);

    private static final int ROWS = 100;
    private static final int NOISE_BEFORE = 2000;
    private static final int NOISE_MID = 50000;
    private static final int NOISE_AFTER = 2000;

    private static final String OFFSET_TABLE = "altinity_sink_connector.replica_source_info";

    protected MySQLContainer mySqlContainer;

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
                .withInitScript("delete_reinsert_repro.sql")
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
    @DisplayName("Redelivered DELETE (fresh version) outranks the un-redelivered re-INSERT")
    public void deleteReinsertOffsetRewind() throws Exception {
        Properties props = buildProps();
        Injector injector = Guice.createInjector(new AppInjector());

        Connection mysql = ITCommon.connectToMySQL(mySqlContainer);
        Connection ch = ITCommon.getDBWriter(clickHouseContainer, "altinity_sink_connector")
                .getConnection();

        // ---- Phase 0: start connector, insert baseline rows via binlog, sync ----
        ExecutorService exec = startConnector(injector, props);
        Thread.sleep(25000); // connector startup + schema capture

        insertTargetRows(mysql);
        assertTrue(waitFor(() -> targetLiveCount(ch) == ROWS, 60),
                "baseline: expected " + ROWS + " live rows to replicate");
        stopConnector(exec);

        // ---- Phase 1: build the backlog: noise, DELETE, wide noise gap, re-INSERT, noise ----
        insertNoise(mysql, NOISE_BEFORE);
        String[] preDeleteCoord = showMasterStatus(mysql); // resume point just before DELETE
        log.info("pre-delete coord: file={} pos={}", preDeleteCoord[0], preDeleteCoord[1]);

        try (Statement s = mysql.createStatement()) {
            s.execute("DELETE FROM repro_db.target_table");
        }
        insertNoise(mysql, NOISE_MID);
        insertTargetRows(mysql); // same PKs, re-inserted
        insertNoise(mysql, NOISE_AFTER);

        // ---- Phase 2: full replay -> in-sync baseline; capture the final offset ----
        exec = startConnector(injector, props);
        assertTrue(waitFor(() -> noiseCount(ch) >= NOISE_BEFORE + NOISE_MID + NOISE_AFTER, 180),
                "replay: expected all noise rows to drain");
        assertTrue(waitFor(() -> targetLiveCount(ch) == ROWS && stuckDeletedCount(ch) == 0, 60),
                "replay baseline: expected " + ROWS + " live rows, 0 stuck-deleted");
        stopConnector(exec);

        String[] finalOffset = readOffset(ch); // {id, offset_key, offset_val}
        log.info("final offset val: {}", finalOffset[2]);

        // ---- Phase 3: rewind the JDBC offset store to the pre-DELETE coordinate ----
        String rewoundVal = rewindOffsetVal(finalOffset[2], preDeleteCoord[0],
                Long.parseLong(preDeleteCoord[1]));
        writeSingleOffset(ch, finalOffset[0], finalOffset[1], rewoundVal);
        log.info("rewound offset val: {}", rewoundVal);

        // ---- Phase 4: restart -> DELETE is re-delivered with a fresh (later) version.
        //      Kill the connector before the replay reaches the re-INSERT (wide noise gap). ----
        exec = startConnector(injector, props);
        boolean sawStuck = waitFor(() -> stuckDeletedCount(ch) > 0, 120);
        stopConnector(exec);
        assertTrue(sawStuck, "expected re-delivered DELETEs to land (stuck-deleted > 0) during replay");

        // ---- Phase 5: skip the re-INSERTs by restoring the final offset (past everything) ----
        writeSingleOffset(ch, finalOffset[0], finalOffset[1], finalOffset[2]);

        // ---- Phase 6: final restart resumes past the re-INSERTs; they are never re-read ----
        exec = startConnector(injector, props);
        Thread.sleep(20000);
        stopConnector(exec);

        int stuckDeleted = stuckDeletedCount(ch);
        int liveInMysql = mysqlTargetCount(mysql);

        log.info("FINAL: mysql rows={} ch stuck-deleted={} ch live={}",
                liveInMysql, stuckDeleted, targetLiveCount(ch));

        assertEquals(ROWS, liveInMysql, "sanity: MySQL still has all rows");
        assertTrue(stuckDeleted > 0,
                "BUG REPRODUCED expectation: rows should be stuck is_deleted=1 in ClickHouse "
                        + "after the re-delivered DELETE out-versioned the un-redelivered re-INSERT. "
                        + "If this fails with 0, the version-inversion bug is fixed -- invert this "
                        + "assertion to stuckDeleted == 0.");
    }

    // ------------------------------------------------------------------
    // Connector lifecycle
    // ------------------------------------------------------------------

    private Properties buildProps() throws Exception {
        Properties props = ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer);
        props.setProperty("snapshot.mode", "schema_only");
        props.setProperty("database.include.list", "repro_db");
        props.setProperty("thread.pool.size", "10");
        // Always read the newest offset row regardless of RMT merge state.
        props.setProperty("offset.storage.jdbc.table.select",
                "SELECT id, offset_key, offset_val FROM %s FINAL ORDER BY record_insert_ts, record_insert_seq");
        return props;
    }

    private ExecutorService startConnector(Injector injector, Properties props) {
        ExecutorService exec = Executors.newFixedThreadPool(1);
        exec.execute(() -> {
            try {
                ClickHouseDebeziumEmbeddedApplication.start(
                        injector.getInstance(DebeziumRecordParserService.class), props, false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return exec;
    }

    private void stopConnector(ExecutorService exec) {
        try {
            ClickHouseDebeziumEmbeddedApplication.stop();
        } catch (Exception e) {
            log.warn("stop() failed", e);
        }
        try {
            exec.shutdownNow();
        } catch (Exception e) {
            // best effort
        }
        try {
            HikariDbSource.close();
        } catch (Exception e) {
            // best effort
        }
    }

    // ------------------------------------------------------------------
    // MySQL helpers
    // ------------------------------------------------------------------

    private void insertTargetRows(Connection mysql) throws Exception {
        try (PreparedStatement ps = mysql.prepareStatement(
                "INSERT INTO repro_db.target_table (id, data, version_num) VALUES (?,?,?)")) {
            for (int i = 1; i <= ROWS; i++) {
                ps.setInt(1, i);
                ps.setString(2, "data_" + i);
                ps.setInt(3, 1);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private int mysqlTargetCount(Connection mysql) {
        try (Statement s = mysql.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM repro_db.target_table")) {
            rs.next();
            return rs.getInt(1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Fast bulk noise insert using a cross-join of digit tables, chunked. */
    private void insertNoise(Connection mysql, int total) throws Exception {
        int done = 0;
        while (done < total) {
            int n = Math.min(10000, total - done);
            try (Statement s = mysql.createStatement()) {
                s.execute(
                        "INSERT INTO repro_db.noise (payload) "
                        + "SELECT CONCAT('x', FLOOR(RAND()*1e9)) FROM "
                        + "(SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 "
                        + " UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9 UNION SELECT 10) a,"
                        + "(SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 "
                        + " UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9 UNION SELECT 10) b,"
                        + "(SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 "
                        + " UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9 UNION SELECT 10) c,"
                        + "(SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 "
                        + " UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9 UNION SELECT 10) d "
                        + "LIMIT " + n);
            }
            done += n;
        }
    }

    /** Returns {file, position} from SHOW MASTER STATUS. */
    private String[] showMasterStatus(Connection mysql) throws Exception {
        try (Statement s = mysql.createStatement();
             ResultSet rs = s.executeQuery("SHOW MASTER STATUS")) {
            rs.next();
            return new String[]{rs.getString(1), String.valueOf(rs.getLong(2))};
        }
    }

    // ------------------------------------------------------------------
    // ClickHouse helpers
    // ------------------------------------------------------------------

    private int targetLiveCount(Connection ch) {
        return scalarInt(ch, "SELECT count() FROM repro_db.target_table FINAL WHERE is_deleted=0");
    }

    private int stuckDeletedCount(Connection ch) {
        return scalarInt(ch,
                "SELECT count() FROM (SELECT id, argMax(is_deleted,_version) AS d "
                + "FROM repro_db.target_table GROUP BY id HAVING d = 1)");
    }

    private int noiseCount(Connection ch) {
        return scalarInt(ch, "SELECT count() FROM repro_db.noise");
    }

    private int scalarInt(Connection ch, String sql) {
        try (Statement s = ch.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        } catch (Exception e) {
            // Table may not exist yet early in replay; treat as 0.
            return -1;
        }
    }

    /** Returns {id, offset_key, offset_val} of the current offset row. */
    private String[] readOffset(Connection ch) throws Exception {
        try (Statement s = ch.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT id, offset_key, offset_val FROM " + OFFSET_TABLE + " FINAL LIMIT 1")) {
            assertTrue(rs.next(), "expected an offset row to exist after replay");
            return new String[]{rs.getString(1), rs.getString(2), rs.getString(3)};
        }
    }

    /** Replaces file+pos in the offset JSON and strips intra-event fields. */
    private String rewindOffsetVal(String offsetVal, String file, long pos) throws Exception {
        JSONObject obj = (JSONObject) new JSONParser().parse(offsetVal);
        obj.put("file", file);
        obj.put("pos", pos);
        obj.remove("row");
        obj.remove("event");
        return obj.toJSONString();
    }

    /**
     * Makes {@code offsetVal} the single, authoritative offset row: TRUNCATE then INSERT so the
     * connector deterministically loads exactly this offset on next start (independent of RMT
     * merge timing).
     */
    private void writeSingleOffset(Connection ch, String id, String offsetKey, String offsetVal)
            throws Exception {
        try (Statement s = ch.createStatement()) {
            s.execute("TRUNCATE TABLE " + OFFSET_TABLE);
        }
        try (PreparedStatement ps = ch.prepareStatement(
                "INSERT INTO " + OFFSET_TABLE
                + " (id, offset_key, offset_val, record_insert_ts, record_insert_seq) "
                + "VALUES (?, ?, ?, now(), ?)")) {
            ps.setString(1, id);
            ps.setString(2, offsetKey);
            ps.setString(3, offsetVal);
            ps.setLong(4, 1000);
            ps.execute();
        }
        try (Statement s = ch.createStatement()) {
            s.execute("OPTIMIZE TABLE " + OFFSET_TABLE + " FINAL");
        }
    }

    // ------------------------------------------------------------------
    // Polling
    // ------------------------------------------------------------------

    private boolean waitFor(Callable<Boolean> condition, int timeoutSeconds)
            throws InterruptedException {
        for (int i = 0; i < timeoutSeconds * 5; i++) {
            try {
                if (Boolean.TRUE.equals(condition.call())) {
                    return true;
                }
            } catch (Exception e) {
                // ignore transient errors (table not yet created, connection blips)
            }
            Thread.sleep(200);
        }
        return false;
    }
}

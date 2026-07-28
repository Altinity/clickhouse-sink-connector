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
import java.util.concurrent.atomic.AtomicReference;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression guard for the 2.9.1 delete+reinsert "stuck deleted" bug.
 *
 * <p>Background: {@code _version} for the ReplacingMergeTree target is computed as
 * {@code ts_ms * 1_000_000 + sequenceNumber}. The bug was that {@code ts_ms} used the Debezium
 * ENVELOPE timestamp (the wall-clock time the connector PROCESSED the event). Because the
 * timestamp dominates the version, the effective ordering was by processing time.
 *
 * <p>When Debezium re-delivers events after an offset regression (at-least-once semantics), a
 * previously-processed DELETE was re-stamped with a FRESH (later) envelope timestamp. If the
 * matching re-INSERT was NOT re-delivered, the re-delivered DELETE outranked the original
 * re-INSERT in the ReplacingMergeTree and the row was permanently stuck with {@code is_deleted=1}
 * even though it still existed in MySQL -> silent data loss.
 *
 * <p>The fix anchors the version to the SOURCE commit timestamp ({@code source.ts_ms}, via
 * {@code ClickHouseStruct.getSourceTsFromChangeEvent}), which is identical on every redelivery.
 * A re-delivered DELETE therefore keeps its original (earlier) version and can no longer outrank
 * the later re-INSERT.
 *
 * <p>This test drives that exact sequence deterministically by rewinding the JDBC offset
 * store ({@code altinity_sink_connector.replica_source_info}) to a coordinate captured before the
 * DELETE, restarting so the DELETE is re-delivered, then skipping the re-INSERT by restoring the
 * final offset before the re-INSERT is re-read. Mirrors {@code run_repro_rewind.sh}.
 *
 * <p>Tagged {@code repro}: on a FIXED build this test asserts the rows are NOT stuck
 * ({@code stuckDeleted == 0} and all {@code ROWS} rows live). On the old buggy build it would
 * instead have left rows stuck deleted.
 */
@Testcontainers
@Tag("repro")
@DisplayName("Reproduces 2.9.1 delete+reinsert stuck-deleted via offset rewind redelivery")
public class DeleteReinsertOffsetRewindIT {

    private static final Logger log = LoggerFactory.getLogger(DeleteReinsertOffsetRewindIT.class);

    private static final int ROWS = 100;
    private static final int NOISE_BEFORE = 500;
    // NOISE_MID is the binlog gap between the DELETE and the re-INSERT. It must be large enough
    // that in Phase 4 the connector spends several seconds churning through it, giving the test a
    // reliable window to observe stuck-deleted rows and stop the connector BEFORE the re-INSERT is
    // replayed (which would un-stick them and mask the bug).
    private static final int NOISE_MID = 50000;
    private static final int NOISE_AFTER = 500;

    /**
     * If a progress-tracked {@code waitFor} sees no forward progress for this many seconds, it
     * fails fast rather than blocking for the full timeout: a frozen offset means the sink batch
     * worker has died (an uncaught error escaping the {@code scheduleAtFixedRate} task permanently
     * cancels it) or the offset flush is wedged. Failing fast surfaces that as an actionable error.
     */
    private static final int STALL_SECONDS = 60;

    private static final String OFFSET_TABLE = "altinity_sink_connector.replica_source_info";

    protected MySQLContainer mySqlContainer;

    /**
     * Captures any exception thrown while starting the connector on the background thread so the
     * test can fail fast with the real cause instead of blocking on a blind timeout.
     */
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
        Thread.sleep(20000); // connector re-init (engine restart + offset load) before polling
        int expectedNoise = NOISE_BEFORE + NOISE_MID + NOISE_AFTER;
        assertTrue(
                waitFor(() -> noiseCount(ch) >= expectedNoise, () -> noiseCount(ch), 300),
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
        // On the FIXED build the re-delivered DELETE keeps its original (earlier) source-time
        // version, so it never out-ranks the re-INSERT and rows must NOT go stuck-deleted here.
        // We give the redelivery time to be applied, then observe (non-fatally) that nothing stuck.
        boolean sawStuck = waitFor(() -> stuckDeletedCount(ch) > 0, 120);
        stopConnector(exec);
        log.info("Phase 4: sawStuck={} during re-delivered-DELETE replay (expected false on fixed build)",
                sawStuck);

        // ---- Phase 5: skip the re-INSERTs by restoring the final offset (past everything) ----
        writeSingleOffset(ch, finalOffset[0], finalOffset[1], finalOffset[2]);

        // ---- Phase 6: final restart resumes past the re-INSERTs; they are never re-read ----
        exec = startConnector(injector, props);
        Thread.sleep(20000);
        stopConnector(exec);

        int stuckDeleted = stuckDeletedCount(ch);
        int liveInMysql = mysqlTargetCount(mysql);
        int chLive = targetLiveCount(ch);

        log.info("FINAL: mysql rows={} ch stuck-deleted={} ch live={}",
                liveInMysql, stuckDeleted, chLive);

        assertEquals(ROWS, liveInMysql, "sanity: MySQL still has all rows");
        assertEquals(0, stuckDeleted,
                "REGRESSION: rows are stuck is_deleted=1 in ClickHouse after a re-delivered DELETE "
                        + "out-versioned the un-redelivered re-INSERT. The version must be anchored to "
                        + "the SOURCE commit timestamp (source.ts_ms) so a re-delivered DELETE keeps its "
                        + "original, earlier version -- see ClickHouseStruct.getSourceTsFromChangeEvent.");
        assertEquals(ROWS, chLive,
                "expected all " + ROWS + " rows live in ClickHouse (matching MySQL) after the fix");

        HikariDbSource.close();
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
        // Clear any error captured from a previous run so a stale failure does not trip the next
        // phase's fail-fast check.
        connectorError.set(null);
        ExecutorService exec = Executors.newFixedThreadPool(1);
        exec.execute(() -> {
            try {
                ClickHouseDebeziumEmbeddedApplication.start(
                        injector.getInstance(DebeziumRecordParserService.class), props, false);
            } catch (Throwable e) {
                connectorError.set(e);
                log.error("connector start() failed", e);
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
        // NOTE: Deliberately do NOT call HikariDbSource.close() here. The ClickHouse container is
        // shared across all phases of this test (started once in @BeforeEach), so the connection
        // pool created in Phase 0 stays valid for every subsequent connector restart. Closing it
        // between phases poisons the restart: the connection layer mishandles a cleared pool
        // (HikariDbSource.initiateNewConnectionIfClosed dereferences a null datasource, and
        // DBMetadata's schema lookup on the binlog-reader thread only catches SQLException), so
        // Phase 2 would silently replicate nothing. Final cleanup happens in
        // ResetSharedStateExtension.afterEach at end of the test.
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

    /**
     * Runs a scalar-int query, returning {@code -1} to signal "unknown" (query failed or the target
     * table does not exist yet early in replay). Callers must treat a negative value as unknown --
     * NOT as a real count of zero -- so a transient blip is never mistaken for "no rows".
     */
    private int scalarInt(Connection ch, String sql) {
        try (Statement s = ch.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        } catch (Exception e) {
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
        return waitFor(condition, null, timeoutSeconds);
    }

    /**
     * Polls {@code condition} until it is true or {@code timeoutSeconds} elapses. On every poll it
     * also (a) fails fast if the connector's background {@code start()} threw, and (b) when a
     * {@code progress} supplier is given, fails fast if that value has not advanced for
     * {@link #STALL_SECONDS} -- a frozen offset means the batch worker died, so blocking for the
     * full timeout would only hide the real cause. Progress is logged every ~10s.
     *
     * @param condition the success predicate
     * @param progress  optional monotonic progress metric (e.g. replicated row count); may be null
     * @param timeoutSeconds overall timeout
     * @return true if {@code condition} became true within the timeout
     */
    private boolean waitFor(Callable<Boolean> condition, Callable<Integer> progress,
                            int timeoutSeconds) throws InterruptedException {
        int iterations = timeoutSeconds * 5; // 200ms per poll
        int stallIterations = STALL_SECONDS * 5;
        int lastProgress = Integer.MIN_VALUE;
        int iterationsSinceProgress = 0;
        int iterationsUnknown = 0;
        for (int i = 0; i < iterations; i++) {
            Throwable err = connectorError.get();
            if (err != null) {
                fail("connector failed during replay: " + err, err);
            }

            try {
                if (Boolean.TRUE.equals(condition.call())) {
                    return true;
                }
            } catch (Exception e) {
                // ignore transient errors (table not yet created, connection blips)
            }

            if (progress != null) {
                int current = safeProgress(progress);
                if (current >= 0) {
                    // Observed a real value: clear the "unknown" streak.
                    iterationsUnknown = 0;
                    if (current > lastProgress) {
                        lastProgress = current;
                        iterationsSinceProgress = 0;
                    } else {
                        iterationsSinceProgress++;
                        if (iterationsSinceProgress >= stallIterations) {
                            fail("connector appears STALLED at progress=" + lastProgress + " for "
                                    + STALL_SECONDS + "s (batch worker likely died -- check the "
                                    + "console log for 'Fatal ClickHouse error', 'stopping task', "
                                    + "or 'Code: NNN')");
                        }
                    }
                } else {
                    // A negative reading means the query kept failing (see scalarInt): the target
                    // table for progress does not exist. A brief blip is fine, but if it persists
                    // for the entire stall window the connector is not replicating at all (e.g. the
                    // table was never created), so fail fast rather than burning the full timeout.
                    iterationsUnknown++;
                    if (iterationsUnknown >= stallIterations) {
                        fail("connector NOT replicating: progress query failed for " + STALL_SECONDS
                                + "s (target table never created). Likely a connection/restart "
                                + "issue -- check the console log for connection errors.");
                    }
                }
                if (i % 50 == 0) { // ~ every 10s
                    log.info("waitFor progress={} (elapsed ~{}s)", current, i / 5);
                }
            }

            Thread.sleep(200);
        }
        return false;
    }

    private int safeProgress(Callable<Integer> progress) {
        try {
            Integer v = progress.call();
            return v == null ? Integer.MIN_VALUE : v;
        } catch (Exception e) {
            return Integer.MIN_VALUE;
        }
    }
}

package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.AppInjector;
import com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication;
import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.api.DebeziumEmbeddedRestApi;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.getDebeziumProperties;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end reproduction of the checksum divergence caused by a transaction that
 * commits AFTER newer-timestamped transactions (MySQL, GTID disabled - the
 * sequence-number version path).
 *
 * <p>A MySQL row event carries the timestamp of the STATEMENT that produced it, not of
 * the commit. A transaction that stays open while others commit reaches the binlog
 * after them, with an older {@code source.ts_ms}. Before the fix the connector versioned
 * that late event as {@code olderTs * 1e6 + counter}; because the newer-timestamped
 * commits in between had reset the counter, the late UPDATE ranked BELOW the earlier
 * write of the same key (same source second, higher counter), ReplacingMergeTree kept
 * the stale row, and the MySQL-vs-ClickHouse checksum reported the row divergent while
 * the row counts matched.</p>
 *
 * <p>The scenario is made deterministic with {@code SET TIMESTAMP}, which pins the
 * statement time MySQL writes into the binlog event header (this is what
 * {@code mysqlbinlog} replays rely on), so the exact production sequence is reproduced
 * without racing the wall clock:</p>
 * <ol>
 *   <li>second {@code T-10}: seed rows (leaves the post-start counter domain);</li>
 *   <li>second {@code T}: one transaction writes 50 filler rows and then the key
 *   ({@code val='early'}) - the key's write lands high in second T's counter;</li>
 *   <li>second {@code T}: a second session executes {@code UPDATE key SET val='late'}
 *   and {@code DELETE} of another key, and stays open;</li>
 *   <li>second {@code T+5}: an unrelated commit advances the source clock, which resets
 *   the counter;</li>
 *   <li>the open transaction commits: its events are LAST in the binlog but carry
 *   {@code ts = T}.</li>
 * </ol>
 *
 * <p>Verification is value-level against MySQL - the source of truth - over every row,
 * the same comparison the checksum job performs, plus the {@code _version} ordering
 * that ReplacingMergeTree resolves on.</p>
 */
@Testcontainers
@DisplayName("A transaction committed after newer-timestamped transactions must still win on ReplacingMergeTree")
public class LateCommitVersionOrderIT {

    private static final Logger log = LoggerFactory.getLogger(LateCommitVersionOrderIT.class);

    private static final String TABLE = "late_commit";
    private static final int FILLER_UPDATES = 50;

    protected MySQLContainer mySqlContainer;

    @Container
    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_DOCKER_IMAGE)
            .asCompatibleSubstituteFor("clickhouse"))
            .withInitScript("init_clickhouse_schema_only_column_timezone.sql")
            .withUsername("ch_user")
            .withPassword("password")
            .withExposedPorts(8123);

    @BeforeEach
    public void startContainers() throws InterruptedException {
        // MySQLContainer's own readiness check (a JDBC 'SELECT 1' probe) is used; no
        // wait-strategy override.
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse(MYSQL_DOCKER_IMAGE)
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                .withExtraHost("mysql-server", "0.0.0.0");

        BasicConfigurator.configure();
        mySqlContainer.start();
        clickHouseContainer.start();
        Thread.sleep(35000);
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
    @DisplayName("late commit with an older statement timestamp: ClickHouse converges to MySQL")
    public void lateCommitConvergesToMySql() throws Exception {
        // The version sequence is process-global. Other IT classes in the same JVM
        // leave their high-water mark and anchor behind; start this scenario from the
        // same state a freshly started connector has.
        DebeziumChangeEventCapture.sequenceNumber = DebeziumChangeEventCapture.SEQUENCE_START;
        DebeziumChangeEventCapture.sequenceAnchorTs = 0L;
        DebeziumChangeEventCapture.sequenceHighWaterPosition = null;
        DebeziumChangeEventCapture.sequenceHighWaterEffectiveTs = 0L;
        DebeziumChangeEventCapture.sequenceMaxSourceTs = 0L;

        Injector injector = Guice.createInjector(new AppInjector());

        Properties props = getDebeziumProperties(mySqlContainer, clickHouseContainer);
        props.setProperty("snapshot.mode", "no_data");
        props.setProperty("schema.history.internal.store.only.captured.tables.ddl", "true");
        props.setProperty("schema.history.internal.store.only.captured.databases.ddl", "true");

        ClickHouseDebeziumEmbeddedApplication app = new ClickHouseDebeziumEmbeddedApplication();
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        Connection seedConn = null;
        Connection batchConn = null;
        Connection lateConn = null;
        Connection unrelatedConn = null;
        try {
            executorService.execute(() -> {
                try {
                    app.start(injector.getInstance(DebeziumRecordParserService.class), props, false);
                    DebeziumEmbeddedRestApi.startRestApi(props, injector, app.getDebeziumEventCapture(), new Properties());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            Thread.sleep(25000);

            seedConn = ITCommon.connectToMySQL(mySqlContainer);
            assertNotNull(seedConn, "MySQL connection (seed session)");
            assertGtidDisabled(seedConn);

            // T is a fixed source second. Every statement time below is pinned to it
            // (or to an offset of it) with SET TIMESTAMP.
            final long t = System.currentTimeMillis() / 1000L;

            try (Statement st = seedConn.createStatement()) {
                st.execute("create table `" + TABLE + "`(id int not null, val varchar(64), v int, primary key(id))");
                st.execute("SET TIMESTAMP = " + (t - 10));
                for (int id = 1; id <= 5; id++) {
                    st.execute("insert into " + TABLE + " values(" + id + ", 'seed', 0)");
                }
            }

            // Second T, one transaction: 50 filler writes, then the key's 'early' write.
            batchConn = ITCommon.connectToMySQL(mySqlContainer);
            assertNotNull(batchConn, "MySQL connection (batch session)");
            batchConn.setAutoCommit(false);
            try (Statement st = batchConn.createStatement()) {
                st.execute("SET TIMESTAMP = " + t);
                for (int i = 1; i <= FILLER_UPDATES; i++) {
                    st.execute("update " + TABLE + " set v = " + i + " where id = 2");
                }
                st.execute("update " + TABLE + " set val = 'early', v = 1 where id = 1");
            }
            batchConn.commit();

            // Capture the 'early' write's _version as soon as it lands. It has to be
            // read now: once the late write arrives, a background merge may collapse
            // the superseded row away and it is no longer observable.
            BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
            Connection ch = writer.getConnection();
            assertTrue(ITCommon.waitForData(ch,
                    "select 1 from employees." + TABLE + " where id = 1 and val = 'early'", 90_000),
                    "the 'early' write must reach ClickHouse before the late commit is produced");
            long earlyVersion = -1;
            try (ResultSet rs = ch.prepareStatement(
                    "select max(_version) from employees." + TABLE + " where id = 1 and val = 'early'").executeQuery()) {
                assertTrue(rs.next());
                earlyVersion = rs.getLong(1);
            }
            log.info("id=1 'early' _version={}", earlyVersion);
            assertTrue(earlyVersion > 0, "the 'early' write reached ClickHouse");

            // Second T, a second session: the long transaction. Its statements execute
            // now (statement time T) but it commits last.
            lateConn = ITCommon.connectToMySQL(mySqlContainer);
            assertNotNull(lateConn, "MySQL connection (late session)");
            lateConn.setAutoCommit(false);
            try (Statement st = lateConn.createStatement()) {
                st.execute("SET TIMESTAMP = " + t);
                st.execute("update " + TABLE + " set val = 'late', v = 2 where id = 1");
                // DESTRUCTIVE: deletes one seed row (id=4) of a throwaway table inside the
                // testcontainers MySQL instance this test started, which is discarded when
                // the test ends. The DELETE is the tombstone event under test.
                st.execute("delete from " + TABLE + " where id = 4");
            }

            // Second T+5: an unrelated commit advances the source clock past T by more
            // than one second, which resets the connector's intra-second counter.
            unrelatedConn = ITCommon.connectToMySQL(mySqlContainer);
            assertNotNull(unrelatedConn, "MySQL connection (unrelated session)");
            try (Statement st = unrelatedConn.createStatement()) {
                st.execute("SET TIMESTAMP = " + (t + 5));
                st.execute("update " + TABLE + " set v = 99 where id = 3");
            }

            // The long transaction commits: last in the binlog, statement time T.
            lateConn.commit();

            // Wait for the late events to be applied. On the unfixed code this never
            // converges, so the timeout below is the failure path.
            boolean converged = ITCommon.waitForData(ch,
                    "select 1 from employees." + TABLE + " where id = 1 and val = 'late'", 90_000);
            log.info("late UPDATE visible in ClickHouse (raw rows): {}", converged);
            Thread.sleep(5000);

            // --- 1. Value-level comparison against MySQL, the source of truth. ---
            List<String> mysqlRows = rows(seedConn,
                    "select id, val, v from " + TABLE + " order by id");
            List<String> clickHouseRows = rows(ch,
                    "select id, val, v from employees." + TABLE + " final where is_deleted = 0 order by id");
            log.info("MySQL rows      : {}", mysqlRows);
            log.info("ClickHouse rows : {}", clickHouseRows);
            assertEquals(4, mysqlRows.size(), "sanity: MySQL holds 4 live rows after the DELETE");
            assertTrue(mysqlRows.contains("1|late|2"), "sanity: MySQL holds the late UPDATE");
            assertEquals(mysqlRows, clickHouseRows,
                    "ClickHouse FINAL must reproduce MySQL row for row. A stale 'early' row or a "
                            + "resurrected id=4 means the late commit was versioned below the earlier "
                            + "write of the same key - the production checksum divergence");

            // --- 2. The _version ordering ReplacingMergeTree resolved on. ---
            // FINAL keeps the row with the highest _version; it must be the late write.
            long lateVersion = -1;
            String finalVal = null;
            try (ResultSet rs = ch.prepareStatement(
                    "select val, _version from employees." + TABLE + " final where id = 1").executeQuery()) {
                while (rs.next()) {
                    finalVal = rs.getString(1);
                    lateVersion = rs.getLong(2);
                    log.info("id=1 FINAL row: val={} _version={}", finalVal, lateVersion);
                }
            }
            assertEquals("late", finalVal, "FINAL must resolve id=1 to the late write");
            assertTrue(lateVersion > 0, "the 'late' write reached ClickHouse");
            assertTrue(lateVersion > earlyVersion,
                    "the late commit must carry the higher _version: early=" + earlyVersion
                            + " late=" + lateVersion);

            // --- 3. Prove the scenario actually exercised the defect's preconditions. ---
            // The 'early' write must have been versioned in source second T (its
            // timestamp component, allowing for the counter's carry into the ms field),
            // i.e. SET TIMESTAMP reached the binlog header and Debezium's source.ts_ms.
            long earlyTsComponentMs = earlyVersion / 1_000_000L;
            assertTrue(earlyTsComponentMs >= t * 1000L && earlyTsComponentMs < (t + 3) * 1000L,
                    "the 'early' write must be versioned in source second T (" + t + "), got ts component "
                            + earlyTsComponentMs + " ms - the pinned statement timestamp did not reach the connector");
            // The late commit must have been floored at the T+5 commit that preceded it
            // in the binlog: its timestamp component is at least T+5, not T.
            long lateTsComponentMs = lateVersion / 1_000_000L;
            assertTrue(lateTsComponentMs >= (t + 5) * 1000L,
                    "the late commit must be versioned at or above the newer commit that preceded it "
                            + "in the binlog (T+5 = " + ((t + 5) * 1000L) + " ms), got ts component "
                            + lateTsComponentMs + " ms");
        } finally {
            if (app.getDebeziumEventCapture() != null && app.getDebeziumEventCapture().engine != null) {
                app.getDebeziumEventCapture().engine.close();
            }
            for (Connection c : new Connection[] {seedConn, batchConn, lateConn, unrelatedConn}) {
                if (c != null) {
                    try {
                        c.close();
                    } catch (SQLException e) {
                        log.warn("closing MySQL connection", e);
                    }
                }
            }
            executorService.shutdown();
            HikariDbSource.close();
        }
    }

    /**
     * This scenario is about the sequence-number version path, which only applies when
     * the source does not supply GTIDs (with GTIDs the version is derived from the
     * transaction id). Fail loudly if the container is not in that mode instead of
     * silently testing a different code path.
     */
    private static void assertGtidDisabled(Connection conn) throws SQLException {
        try (ResultSet rs = conn.prepareStatement("select @@gtid_mode").executeQuery()) {
            assertTrue(rs.next());
            assertEquals("OFF", rs.getString(1).toUpperCase(),
                    "this scenario exercises the sequence-number _version path, which requires gtid_mode=OFF");
        }
    }

    private static List<String> rows(Connection conn, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = conn.prepareStatement(sql).executeQuery()) {
            while (rs.next()) {
                out.add(rs.getInt(1) + "|" + rs.getString(2) + "|" + rs.getInt(3));
            }
        }
        return out;
    }
}

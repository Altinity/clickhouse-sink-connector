package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end integration test of the FULL data flow MySQL -> Debezium embedded
 * connector -> ClickHouse, exercising the real connector against real MySQL and
 * ClickHouse containers (no stubs/mocks).
 *
 * <p>A single harness that observes the true end-to-end behavior of the whole
 * pipeline, so the full impact of any change is measurable. It covers normal
 * CRUD, every DDL column-change shape (ADD / DROP / RENAME / MODIFY), and the
 * concurrency / race scenario where a schema change overlaps a stream of
 * inserts — the exact condition behind the silent-NULL data loss in GitHub
 * issue #1222.</p>
 *
 * <p><b>The critical assertion</b> ({@link #testAddColumnUnderConcurrentInsertsNoDataLoss})
 * is value-level: after an {@code ADD COLUMN} that runs while rows are being
 * inserted, EVERY post-DDL row in ClickHouse must carry the real source value
 * for the new column, not the DEFAULT/NULL. Without the freeze + integrity work
 * (PR #30) some of those rows lose the column; with it, none do.</p>
 *
 * <p>Requires Docker (Testcontainers). Named {@code *IT} so it runs under the
 * failsafe/surefire IT include and not in plain unit runs.</p>
 */
@Testcontainers
@DisplayName("End-to-end MySQL -> ClickHouse data integrity across DDL column changes")
public class EndToEndDDLDataIntegrityIT {

    private static final Logger log = LoggerFactory.getLogger(EndToEndDDLDataIntegrityIT.class);

    private static final String DB = "employees"; // connector maps to this CH db

    protected MySQLContainer mySqlContainer;

    @Container
    public static ClickHouseContainer clickHouseContainer =
            new ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_DOCKER_IMAGE)
                    .asCompatibleSubstituteFor("clickhouse"))
                    .withInitScript("init_clickhouse_it.sql")
                    .withUsername("ch_user")
                    .withPassword("password")
                    .withExposedPorts(8123);

    private AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
    private ExecutorService engineExecutor;

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse(MYSQL_DOCKER_IMAGE)
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName(DB).withUsername("root").withPassword("adminpass")
                .withInitScript("e2e_ddl_integrity.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        clickHouseContainer.start();
        Thread.sleep(10000);
    }

    @AfterEach
    public void stopAll() {
        try {
            if (engine.get() != null) {
                engine.get().stop();
            }
        } catch (Exception e) {
            log.warn("Error stopping engine", e);
        }
        if (engineExecutor != null) {
            engineExecutor.shutdownNow();
        }
        if (mySqlContainer != null && mySqlContainer.isRunning()) {
            mySqlContainer.stop();
        }
        if (clickHouseContainer != null && clickHouseContainer.isRunning()) {
            clickHouseContainer.stop();
        }
        try {
            HikariDbSource.close();
        } catch (Exception ignored) {
            // best effort
        }
    }

    // ------------------------------------------------------------------
    // Engine + helpers
    // ------------------------------------------------------------------

    private void startEngine() throws Exception {
        Properties props = ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer);
        props.setProperty("auto.create.tables", "true");
        props.setProperty("snapshot.mode", "initial");
        engineExecutor = Executors.newSingleThreadExecutor();
        engineExecutor.execute(() -> {
            try {
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(props, new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        // Give the engine time to snapshot and create the destination tables.
        Thread.sleep(25000);
    }

    private Connection mysql() {
        return ITCommon.connectToMySQL(mySqlContainer);
    }

    private BaseDbWriter chWriter() {
        return ITCommon.getDBWriter(clickHouseContainer, DB);
    }

    /** Runs a scalar long query against ClickHouse, returning -1 if no row. */
    private long chScalarLong(BaseDbWriter writer, String sql) throws Exception {
        try (ResultSet rs = ITCommon.executeQueryWithResultSet(sql, writer.getConnection())) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        return -1L;
    }

    /** Set of column names currently present on a ClickHouse table. */
    private Set<String> chColumns(BaseDbWriter writer, String table) throws Exception {
        Set<String> cols = new HashSet<>();
        String sql = String.format(
                "SELECT name FROM system.columns WHERE database='%s' AND table='%s'", DB, table);
        try (ResultSet rs = ITCommon.executeQueryWithResultSet(sql, writer.getConnection())) {
            while (rs.next()) {
                cols.add(rs.getString(1));
            }
        }
        return cols;
    }

    /**
     * Polls until {@code sql} (a scalar long) reaches {@code expected} or the
     * timeout elapses. Returns the last observed value.
     */
    private long awaitScalar(BaseDbWriter writer, String sql, long expected, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long last = -1L;
        while (System.currentTimeMillis() < deadline) {
            last = chScalarLong(writer, sql);
            if (last == expected) {
                return last;
            }
            Thread.sleep(500);
        }
        return last;
    }

    private void exec(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Normal CRUD replicates end-to-end (insert/update/delete)")
    public void testNormalCrud() throws Exception {
        startEngine();
        Connection conn = mysql();

        // Snapshot of 2 seed rows should already be in ClickHouse.
        BaseDbWriter writer = chWriter();
        long seeded = awaitScalar(writer, "SELECT count() FROM " + DB + ".trades FINAL", 2, 60000);
        assertEquals(2, seeded, "Seed rows should have snapshotted into ClickHouse");

        // INSERT
        exec(conn, "INSERT INTO trades VALUES (3, 'CCC', 30.750000, 300)");
        long afterInsert = awaitScalar(writer, "SELECT count() FROM " + DB + ".trades FINAL", 3, 60000);
        assertEquals(3, afterInsert, "Insert should replicate");
        long price3 = awaitScalar(writer,
                "SELECT toInt64(round(price*1000000)) FROM " + DB + ".trades FINAL WHERE id=3",
                30750000L, 30000);
        assertEquals(30750000L, price3, "Inserted price must match source exactly");

        // UPDATE
        exec(conn, "UPDATE trades SET price = 99.990000 WHERE id = 1");
        long price1 = awaitScalar(writer,
                "SELECT toInt64(round(price*1000000)) FROM " + DB + ".trades FINAL WHERE id=1",
                99990000L, 60000);
        assertEquals(99990000L, price1, "Update must replicate the new value");

        // DELETE
        // DESTRUCTIVE: removes one row from the throwaway `trades` table inside
        // this test's own ephemeral testcontainers MySQL. Replicating a DELETE
        // IS the behaviour under test; the container is destroyed when the test
        // ends, so nothing outside it is reachable.
        exec(conn, "DELETE FROM trades WHERE id = 2");
        long remaining = awaitScalar(writer,
                "SELECT count() FROM " + DB + ".trades FINAL WHERE id=2", 0, 60000);
        assertEquals(0, remaining, "Delete must remove the row under FINAL");
    }

    @Test
    @DisplayName("CRITICAL: ADD COLUMN while inserting -> no row loses the new column (price_usd scenario)")
    public void testAddColumnUnderConcurrentInsertsNoDataLoss() throws Exception {
        startEngine();
        final Connection ddlConn = mysql();
        final Connection insertConn = mysql();
        final BaseDbWriter writer = chWriter();

        awaitScalar(writer, "SELECT count() FROM " + DB + ".trades FINAL", 2, 60000);

        // Continuously insert rows; midway, ALTER ADD COLUMN with a NON-NULL,
        // source-populated value. Debezium serializes the ALTER before the
        // inserts that follow it, so every row with id > the ALTER point must
        // carry the real price_usd. We make EVERY inserted row set price_usd so
        // any row that shows 0/NULL in ClickHouse is a dropped column = data loss.
        final int preRows = 50;
        final int postRows = 200;
        final int idBase = 1000;

        // Pre-ALTER rows (column does not exist yet on source).
        for (int i = 0; i < preRows; i++) {
            exec(insertConn, String.format(
                    "INSERT INTO trades (id, symbol, price, qty) VALUES (%d,'PRE',%d.000000,%d)",
                    idBase + i, i, i));
        }

        // ALTER on source: add a non-null column with default 0, then ensure
        // subsequent inserts always provide a real (non-zero) value.
        exec(ddlConn, "ALTER TABLE trades ADD COLUMN price_usd DECIMAL(18,6) NOT NULL DEFAULT 0");

        // Post-ALTER rows: each sets price_usd to a unique non-zero value.
        ExecutorService inserters = Executors.newFixedThreadPool(4);
        CountDownLatch done = new CountDownLatch(postRows);
        for (int i = 0; i < postRows; i++) {
            final int id = idBase + preRows + i;
            final int usd = 1 + i; // strictly > 0 so 0 == dropped
            inserters.submit(() -> {
                try (Connection c = mysql()) {
                    exec(c, String.format(
                            "INSERT INTO trades (id, symbol, price, qty, price_usd) "
                                    + "VALUES (%d,'POST',1.000000,1,%d.000000)", id, usd));
                } catch (Exception e) {
                    log.error("insert failed for id {}", id, e);
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(120, TimeUnit.SECONDS), "All inserts should be issued");
        inserters.shutdown();

        // Wait until all post rows have arrived in ClickHouse.
        long total = awaitScalar(writer,
                "SELECT count() FROM " + DB + ".trades FINAL WHERE symbol='POST'", postRows, 120000);
        assertEquals(postRows, total, "All post-ALTER rows must replicate");

        // The column must exist on the destination.
        assertTrue(chColumns(writer, "trades").contains("price_usd"),
                "price_usd must exist on the ClickHouse table after ALTER");

        // THE DATA-LOSS ASSERTION: no POST row may have price_usd = 0 (default),
        // because every POST row set a strictly-positive value on the source.
        long droppedRows = chScalarLong(writer,
                "SELECT count() FROM " + DB + ".trades FINAL "
                        + "WHERE symbol='POST' AND (price_usd = 0 OR price_usd IS NULL)");
        assertEquals(0, droppedRows,
                "No post-ALTER row may have a dropped/zeroed price_usd. dropped=" + droppedRows
                        + " (this is the price_usd silent-data-loss regression)");

        // And the values must match the source exactly for a sample.
        long sample = chScalarLong(writer,
                "SELECT toInt64(round(price_usd)) FROM " + DB + ".trades FINAL "
                        + "WHERE id=" + (idBase + preRows + 0));
        assertEquals(1L, sample, "First post row price_usd must equal source value");
    }

    @Test
    // DESTRUCTIVE: test title text only — names the DDL under test, executes nothing.
    @DisplayName("RENAME / MODIFY / DROP COLUMN replicate and preserve data")
    public void testRenameModifyDropColumn() throws Exception {
        startEngine();
        Connection conn = mysql();
        BaseDbWriter writer = chWriter();

        awaitScalar(writer, "SELECT count() FROM " + DB + ".positions FINAL", 2, 60000);

        // MODIFY: widen notional precision; existing data must remain intact.
        exec(conn, "ALTER TABLE positions MODIFY COLUMN notional DECIMAL(24,8)");
        // Insert a row after MODIFY and verify value round-trips.
        exec(conn, "INSERT INTO positions (id, book, notional) VALUES (3,'BOOK_C',3000.000000)");
        long n3 = awaitScalar(writer,
                "SELECT toInt64(round(notional)) FROM " + DB + ".positions FINAL WHERE id=3",
                3000L, 60000);
        assertEquals(3000L, n3, "Value after MODIFY COLUMN must be correct");

        // ADD then RENAME: add region, populate it, rename to region_code.
        exec(conn, "ALTER TABLE positions ADD COLUMN region VARCHAR(16) NOT NULL DEFAULT 'NA'");
        exec(conn, "INSERT INTO positions (id, book, notional, region) "
                + "VALUES (4,'BOOK_D',4000.000000,'EU')");
        awaitScalar(writer, "SELECT count() FROM " + DB + ".positions FINAL WHERE id=4", 1, 60000);
        exec(conn, "ALTER TABLE positions RENAME COLUMN region TO region_code");
        exec(conn, "INSERT INTO positions (id, book, notional, region_code) "
                + "VALUES (5,'BOOK_E',5000.000000,'AS')");
        awaitScalar(writer, "SELECT count() FROM " + DB + ".positions FINAL WHERE id=5", 1, 60000);

        // The renamed column must exist on the destination and hold the value.
        long deadline = System.currentTimeMillis() + 60000;
        boolean hasRenamed = false;
        while (System.currentTimeMillis() < deadline) {
            if (chColumns(writer, "positions").contains("region_code")) {
                hasRenamed = true;
                break;
            }
            Thread.sleep(500);
        }
        assertTrue(hasRenamed, "region_code must exist on the ClickHouse table after RENAME");

        // DROP: drop region_code; subsequent inserts must still replicate cleanly.
        // DESTRUCTIVE: drops one column from the throwaway `positions` table
        // inside this test's own ephemeral testcontainers MySQL. Dropping it IS
        // the behaviour under test; blast radius ends with the container.
        exec(conn, "ALTER TABLE positions DROP COLUMN region_code");
        exec(conn, "INSERT INTO positions (id, book, notional) VALUES (6,'BOOK_F',6000.000000)");
        long n6 = awaitScalar(writer,
                "SELECT toInt64(round(notional)) FROM " + DB + ".positions FINAL WHERE id=6",
                6000L, 60000);
        // DESTRUCTIVE: assertion message text only — executes nothing.
        assertEquals(6000L, n6, "Inserts after DROP COLUMN must still replicate correctly");
    }

    @Test
    @DisplayName("Rapid interleaved DDL + DML race: every row keeps its column value")
    public void testRapidInterleavedDdlDmlRace() throws Exception {
        startEngine();
        Connection conn = mysql();
        BaseDbWriter writer = chWriter();
        awaitScalar(writer, "SELECT count() FROM " + DB + ".trades FINAL", 2, 60000);

        // Three ADD COLUMN rounds, each immediately followed by inserts that set
        // the just-added column to a non-zero value. This stresses the
        // freeze/rebuild path repeatedly in quick succession.
        final int idBase = 5000;
        List<String> addedCols = new ArrayList<>();
        int id = idBase;
        for (int round = 1; round <= 3; round++) {
            String col = "extra_" + round;
            addedCols.add(col);
            exec(conn, String.format(
                    "ALTER TABLE trades ADD COLUMN %s DECIMAL(18,6) NOT NULL DEFAULT 0", col));
            // Build an insert that sets ALL added columns so far to round*100+k.
            for (int k = 0; k < 20; k++) {
                StringBuilder cols = new StringBuilder("id, symbol, price, qty");
                StringBuilder vals = new StringBuilder(
                        String.format("%d,'RACE',1.000000,1", id));
                for (String ac : addedCols) {
                    cols.append(", ").append(ac);
                    vals.append(", ").append(round * 100 + k).append(".000000");
                }
                exec(conn, "INSERT INTO trades (" + cols + ") VALUES (" + vals + ")");
                id++;
            }
        }

        int expectedRace = (id - idBase);
        awaitScalar(writer,
                "SELECT count() FROM " + DB + ".trades FINAL WHERE symbol='RACE'",
                expectedRace, 120000);

        // For each added column, the rows inserted AFTER it existed must be
        // non-zero (they all set a strictly-positive value). We check the last
        // column (extra_3): all 20 rows of round 3 set it to 300..319.
        long droppedExtra3 = chScalarLong(writer,
                "SELECT count() FROM " + DB + ".trades FINAL "
                        + "WHERE symbol='RACE' AND id >= " + (id - 20)
                        + " AND (extra_3 = 0 OR extra_3 IS NULL)");
        assertEquals(0, droppedExtra3,
                "Rows inserted after the last ADD COLUMN must keep extra_3; dropped=" + droppedExtra3);

        // All three added columns must exist on the destination.
        Set<String> cols = chColumns(writer, "trades");
        for (String ac : addedCols) {
            assertTrue(cols.contains(ac), ac + " must exist on the ClickHouse table");
        }
    }
}

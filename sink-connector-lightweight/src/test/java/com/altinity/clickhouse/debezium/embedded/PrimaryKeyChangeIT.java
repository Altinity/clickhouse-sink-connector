package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import org.apache.log4j.BasicConfigurator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;

/**
 * Spec 06.09 end to end: a source {@code ALTER TABLE} that changes a table's
 * row identity (or widens / renames / drops a key column) is followed on
 * ClickHouse by rebuilding the replica under the new sorting key at the DDL
 * barrier -- MySQL 8.0 -> embedded connector -> ClickHouse, the same harness
 * as the other DDL ITs ({@code AbstractCDCBaseIT} / {@code DDLBaseIT}:
 * testcontainers, {@link ITCommon} properties, {@link BaseDbWriter}).
 *
 * <p>Every scenario: the source table and its rows exist BEFORE the engine
 * starts (snapshot), DML runs under the old key, the ALTER runs, DML runs
 * under the new key including a relocation ({@code UPDATE ... SET <new key>
 * = ...}) and a DELETE; once the online backfill has completed -- the retired
 * table {@code <t>__pk_rebuild_<ts>} / {@code <t>__pk_retired_<ts>} is gone
 * (Spec 06.09 §3.3.2 step 4) -- the replica must hold, value for value, the
 * rows MySQL holds ({@code FINAL}, live rows), keyed by the expected sorting
 * key, with no scratch table left behind and an untouched control table
 * still matching.</p>
 *
 // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
 * <p>Containers are created per test (as {@code TruncateTableIT} does): each
 * scenario needs its own snapshot, and a shared ClickHouse would hand the
 * next engine the previous scenario's offset.</p>
 */
@Testcontainers
@DisplayName("Integration test: primary-key changes rebuild the ClickHouse table at the DDL barrier (Spec 06.09)")
public class PrimaryKeyChangeIT {

    private static final Logger log = LogManager.getLogger(PrimaryKeyChangeIT.class);

    private static final String DB = "employees";
    private static final String CONTROL_TABLE = "t_ctl";

    /** CI containers are slow to start; the rebuild itself is quick. */
    private static final long SNAPSHOT_TIMEOUT_MS = 300_000;
    private static final long CONVERGE_TIMEOUT_MS = 300_000;
    private static final long POLL_MS = 2_000;

    /** Rows of the table whose backfill must take long enough to be observed in flight. */
    private static final int BIG_ROWS = 300_000;

    protected MySQLContainer mySqlContainer;
    protected ClickHouseContainer clickHouseContainer;

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse(MYSQL_DOCKER_IMAGE)
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName(DB).withUsername("root").withPassword("adminpass")
                .withExtraHost("mysql-server", "0.0.0.0")
                // MySQL does not speak HTTP; wait for the port (DDLBaseIT).
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(5)));

        clickHouseContainer = new ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_DOCKER_IMAGE)
                .asCompatibleSubstituteFor("clickhouse"))
                .withInitScript("init_clickhouse_it.sql")
                .withUsername("ch_user")
                .withPassword("password")
                .withExposedPorts(8123)
                .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(5)));

        BasicConfigurator.configure();
        mySqlContainer.start();
        clickHouseContainer.start();
        Thread.sleep(10000);
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

    // ------------------------------------------------------------------
    // Scenarios (Spec 06.09 §5)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The production migration: composite key -> added AUTO_INCREMENT id, source values joined in")
    public void compositeKeyToAutoIncrementId() throws Exception {
        String t = "t_pk_ai";
        new Scenario(t)
                .setup("CREATE TABLE " + t + " (a INT NOT NULL, b INT NOT NULL, v VARCHAR(32), PRIMARY KEY (a, b))",
                        "INSERT INTO " + t + " (a, b, v) VALUES (1, 1, 'a'), (1, 2, 'b'), (2, 1, 'c'), (2, 2, 'd')")
                .oldOrderBy("a, b")
                .oldKeyDml("INSERT INTO " + t + " (a, b, v) VALUES (3, 1, 'e')",
                        "UPDATE " + t + " SET v = 'b2' WHERE a = 1 AND b = 2",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE a = 2 AND b = 1")
                .alter("ALTER TABLE " + t + " DROP PRIMARY KEY, ADD COLUMN id INT UNSIGNED NOT NULL AUTO_INCREMENT "
                        + "FIRST, ADD PRIMARY KEY (id)")
                .newKeyDml("INSERT INTO " + t + " (a, b, v) VALUES (4, 4, 'f')",
                        "UPDATE " + t + " SET v = 'a2' WHERE a = 1 AND b = 1",
                        "UPDATE " + t + " SET id = 100 WHERE a = 3 AND b = 1",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE a = 2 AND b = 2")
                .newOrderBy("id")
                .expectSortingKey("id")
                .expectColumnType("id", "UInt32")
                .run();
    }

    @Test
    @DisplayName("Re-keying onto an existing column: DROP PRIMARY KEY, ADD PRIMARY KEY (b), no source read")
    public void rekeyOntoExistingColumn() throws Exception {
        String t = "t_pk_rekey";
        new Scenario(t)
                .setup("CREATE TABLE " + t + " (id INT NOT NULL, b INT NOT NULL, v VARCHAR(32), PRIMARY KEY (id))",
                        "INSERT INTO " + t + " (id, b, v) VALUES (1, 10, 'a'), (2, 20, 'b'), (3, 30, 'c')")
                .oldOrderBy("id")
                .oldKeyDml("INSERT INTO " + t + " (id, b, v) VALUES (4, 40, 'd')",
                        "UPDATE " + t + " SET v = 'b2' WHERE id = 2",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE id = 3")
                .alter("ALTER TABLE " + t + " DROP PRIMARY KEY, ADD PRIMARY KEY (b)")
                .newKeyDml("INSERT INTO " + t + " (id, b, v) VALUES (5, 50, 'e')",
                        "UPDATE " + t + " SET v = 'a2' WHERE b = 10",
                        "UPDATE " + t + " SET b = 21 WHERE b = 20",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE b = 40")
                .newOrderBy("b")
                .expectSortingKey("b")
                .run();
    }

    @Test
    @DisplayName("Superset key: (a) -> (a, b)")
    public void supersetKey() throws Exception {
        String t = "t_pk_super";
        new Scenario(t)
                .setup("CREATE TABLE " + t + " (a INT NOT NULL, b INT NOT NULL, v VARCHAR(32), PRIMARY KEY (a))",
                        "INSERT INTO " + t + " (a, b, v) VALUES (1, 1, 'a'), (2, 1, 'b'), (3, 1, 'c')")
                .oldOrderBy("a")
                .oldKeyDml("INSERT INTO " + t + " (a, b, v) VALUES (4, 1, 'd')",
                        "UPDATE " + t + " SET v = 'b2' WHERE a = 2",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE a = 3")
                .alter("ALTER TABLE " + t + " DROP PRIMARY KEY, ADD PRIMARY KEY (a, b)")
                .newKeyDml("INSERT INTO " + t + " (a, b, v) VALUES (1, 2, 'e')",
                        "UPDATE " + t + " SET v = 'a2' WHERE a = 1 AND b = 1",
                        "UPDATE " + t + " SET b = 9 WHERE a = 2 AND b = 1",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE a = 4 AND b = 1")
                .newOrderBy("a, b")
                .expectSortingKey("a, b")
                .run();
    }

    @Test
    @DisplayName("ADD PRIMARY KEY on a nullable column: MySQL makes it NOT NULL, the replica key column is non-Nullable")
    public void addPrimaryKeyOnNullableColumn() throws Exception {
        String t = "t_pk_nullable";
        new Scenario(t)
                // No PRIMARY KEY, no UNIQUE: the replica keys the table by every
                // column (Spec 06.05 §3.6) until the source declares a key.
                .setup("SET SESSION sql_generate_invisible_primary_key = OFF",
                        "CREATE TABLE " + t + " (id INT NOT NULL, c INT NULL, v VARCHAR(32))",
                        "INSERT INTO " + t + " (id, c, v) VALUES (1, 10, 'a'), (2, 20, 'b'), (3, 30, 'c')")
                .oldOrderBy("id")
                .oldKeyDml("INSERT INTO " + t + " (id, c, v) VALUES (4, 40, 'd')",
                        "UPDATE " + t + " SET v = 'b2' WHERE id = 2",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE id = 3")
                .alter("ALTER TABLE " + t + " ADD PRIMARY KEY (c)")
                .newKeyDml("INSERT INTO " + t + " (id, c, v) VALUES (5, 50, 'e')",
                        "UPDATE " + t + " SET v = 'a2' WHERE c = 10",
                        "UPDATE " + t + " SET c = 21 WHERE c = 20",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE c = 40")
                .newOrderBy("c")
                .expectSortingKey("c")
                .expectColumnType("c", "Int32")
                .run();
    }

    @Test
    @DisplayName("DROP PRIMARY KEY without a replacement: the all-columns identity of a keyless table")
    public void dropPrimaryKeyBecomesKeyless() throws Exception {
        String t = "t_pk_keyless";
        new Scenario(t)
                .setup("CREATE TABLE " + t + " (id INT NOT NULL, v VARCHAR(32), PRIMARY KEY (id))",
                        "INSERT INTO " + t + " (id, v) VALUES (1, 'a'), (2, 'b'), (3, 'c')")
                .oldOrderBy("id")
                .oldKeyDml("INSERT INTO " + t + " (id, v) VALUES (4, 'd')",
                        "UPDATE " + t + " SET v = 'b2' WHERE id = 2",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE id = 3")
                // Without GIPK the table is left keyless, as the statement says.
                .alter("SET SESSION sql_generate_invisible_primary_key = OFF",
                        "ALTER TABLE " + t + " DROP PRIMARY KEY")
                .newKeyDml("INSERT INTO " + t + " (id, v) VALUES (5, 'e')",
                        "UPDATE " + t + " SET v = 'a2' WHERE id = 1",
                        "UPDATE " + t + " SET id = 6 WHERE id = 4",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE id = 2")
                .newOrderBy("id, v")
                .expectSortingKey("id, v")
                .run();
    }

    @Test
    // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
    @DisplayName("A GIPK table promoted to an explicit key: DROP PRIMARY KEY, DROP COLUMN my_row_id, ADD PRIMARY KEY (id)")
    public void gipkTablePromotedToExplicitKey() throws Exception {
        String t = "t_pk_gipk";
        new Scenario(t)
                // Created under GIPK: MySQL adds my_row_id BIGINT UNSIGNED
                // AUTO_INCREMENT INVISIBLE PRIMARY KEY, which the replica keys by.
                .setup("SET SESSION sql_generate_invisible_primary_key = ON",
                        "CREATE TABLE " + t + " (id INT NOT NULL, v VARCHAR(32))",
                        "INSERT INTO " + t + " (id, v) VALUES (1, 'a'), (2, 'b'), (3, 'c')")
                .oldOrderBy("id")
                .oldKeyDml("INSERT INTO " + t + " (id, v) VALUES (4, 'd')",
                        "UPDATE " + t + " SET v = 'b2' WHERE id = 2",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE id = 3")
                // The same GIPK session: the generated key may only be dropped
                // together with its column and with a replacement key.
                .alter("SET SESSION sql_generate_invisible_primary_key = ON",
                        "ALTER TABLE " + t + " DROP PRIMARY KEY, DROP COLUMN my_row_id, ADD PRIMARY KEY (id)")
                .newKeyDml("INSERT INTO " + t + " (id, v) VALUES (5, 'e')",
                        "UPDATE " + t + " SET v = 'a2' WHERE id = 1",
                        "UPDATE " + t + " SET id = 6 WHERE id = 4",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE id = 2")
                .newOrderBy("id")
                .expectSortingKey("id")
                .expectColumnAbsent("my_row_id")
                .run();
    }

    @Test
    @DisplayName("A widened key column: MODIFY id BIGINT rebuilds under the same key with the wider type")
    public void keyColumnWidened() throws Exception {
        String t = "t_pk_widen";
        new Scenario(t)
                .setup("CREATE TABLE " + t + " (id INT NOT NULL, v VARCHAR(32), PRIMARY KEY (id))",
                        "INSERT INTO " + t + " (id, v) VALUES (1, 'a'), (2, 'b'), (3, 'c')")
                .oldOrderBy("id")
                .oldKeyDml("INSERT INTO " + t + " (id, v) VALUES (4, 'd')",
                        "UPDATE " + t + " SET v = 'b2' WHERE id = 2",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE id = 3")
                .alter("ALTER TABLE " + t + " MODIFY id BIGINT NOT NULL")
                .newKeyDml("INSERT INTO " + t + " (id, v) VALUES (5000000000, 'e')",
                        "UPDATE " + t + " SET v = 'a2' WHERE id = 1",
                        // A value no Int32 can hold: the widening must have landed.
                        "UPDATE " + t + " SET id = 6000000000 WHERE id = 4",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE id = 2")
                .newOrderBy("id")
                .expectSortingKey("id")
                .expectColumnType("id", "Int64")
                .run();
    }

    @Test
    @DisplayName("A renamed key column: CHANGE id ref_id INT rebuilds keyed by the new name")
    public void keyColumnRenamed() throws Exception {
        String t = "t_pk_rename";
        new Scenario(t)
                .setup("CREATE TABLE " + t + " (id INT NOT NULL, v VARCHAR(32), PRIMARY KEY (id))",
                        "INSERT INTO " + t + " (id, v) VALUES (1, 'a'), (2, 'b'), (3, 'c')")
                .oldOrderBy("id")
                .oldKeyDml("INSERT INTO " + t + " (id, v) VALUES (4, 'd')",
                        "UPDATE " + t + " SET v = 'b2' WHERE id = 2",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE id = 3")
                .alter("ALTER TABLE " + t + " CHANGE id ref_id INT NOT NULL")
                .newKeyDml("INSERT INTO " + t + " (ref_id, v) VALUES (5, 'e')",
                        "UPDATE " + t + " SET v = 'a2' WHERE ref_id = 1",
                        "UPDATE " + t + " SET ref_id = 6 WHERE ref_id = 4",
                        // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                        "DELETE FROM " + t + " WHERE ref_id = 2")
                .newOrderBy("ref_id")
                .expectSortingKey("ref_id")
                .expectColumnType("ref_id", "Int32")
                .expectColumnAbsent("id")
                .run();
    }

    @Test
    @DisplayName("Replication continues while the backfill runs: a control INSERT right after the ALTER is visible while the retired table still exists")
    public void replicationContinuesWhileBackfillRuns() throws Exception {
        String t = "t_big";
        Connection mysql = connectToMySQLWithRetry();
        try {
            // 1. Source state before the engine starts: the control table and a
            //    table large enough for its backfill to take seconds.
            execute(mysql, "CREATE TABLE " + CONTROL_TABLE + " (id INT NOT NULL, v VARCHAR(32), PRIMARY KEY (id))");
            execute(mysql, "INSERT INTO " + CONTROL_TABLE + " (id, v) VALUES (1, 'ctl-a'), (2, 'ctl-b')");
            execute(mysql, "CREATE TABLE " + t + " (id INT NOT NULL, b INT NOT NULL, v VARCHAR(32), PRIMARY KEY (id))");
            // One statement from a recursive numbers generator (MySQL 8.0): well under a minute.
            execute(mysql, "SET SESSION cte_max_recursion_depth = " + (BIG_ROWS + 10));
            execute(mysql, "INSERT INTO " + t + " (id, b, v) WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL "
                    + "SELECT n + 1 FROM seq WHERE n < " + BIG_ROWS + ") SELECT n, n, CONCAT('v', n) FROM seq");

            AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
            ExecutorService executorService = Executors.newFixedThreadPool(1);
            Properties props = ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer);
            executorService.execute(() -> {
                try {
                    engine.set(new DebeziumChangeEventCapture());
                    engine.get().setup(props, new SourceRecordParserService(), false);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
            Connection ch = writer.getConnection();
            try {
                // 2. The snapshot rows are in ClickHouse.
                awaitLiveCount(ch, t, BIG_ROWS, SNAPSHOT_TIMEOUT_MS, "snapshot of " + t);
                awaitMatch(mysql, ch, CONTROL_TABLE, "id", SNAPSHOT_TIMEOUT_MS, "snapshot of " + CONTROL_TABLE);

                // 3. The key change, immediately followed by a row for another table.
                execute(mysql, "ALTER TABLE " + t + " DROP PRIMARY KEY, ADD PRIMARY KEY (b)");
                execute(mysql, "INSERT INTO " + CONTROL_TABLE + " (id, v) VALUES (3, 'ctl-c')");

                // 4. The control row must become visible; if the retired table
                //    of t_big still exists at that moment, the copy was
                //    observed running while replication went on.
                boolean controlVisible = false;
                boolean retiredStillPresent = false;
                long deadline = System.currentTimeMillis() + CONVERGE_TIMEOUT_MS;
                while (System.currentTimeMillis() < deadline) {
                    long retired = scratchTables(ch, t);
                    String visible = scalar(ch, "SELECT count() FROM `" + DB + "`.`" + CONTROL_TABLE
                            + "` FINAL WHERE `id` = 3 AND `is_deleted` = 0");
                    if ("1".equals(visible)) {
                        controlVisible = true;
                        retiredStillPresent = retired > 0;
                        break;
                    }
                    Thread.sleep(200);
                }
                Assert.assertTrue("the control row inserted right after the ALTER must reach ClickHouse",
                        controlVisible);
                if (retiredStillPresent) {
                    log.info("Observed: the control row was visible while the retired table of {} still existed "
                            + "(the backfill ran online, Spec 06.09 §3.3)", t);
                } else {
                    log.warn("Race not observed: the backfill of {} had already completed when the control row "
                            + "became visible; the online property is not contradicted, only not witnessed", t);
                }

                // 5. Once the backfill has completed, both tables match value for value.
                awaitBackfillComplete(ch, t, CONVERGE_TIMEOUT_MS);
                awaitMatch(mysql, ch, t, "b", CONVERGE_TIMEOUT_MS, "the rebuild of " + t);
                assertMatch(mysql, ch, CONTROL_TABLE, "id", "the control table " + CONTROL_TABLE);
                Assert.assertEquals("sorting key of " + DB + "." + t, "b",
                        scalar(ch, "SELECT sorting_key FROM system.tables WHERE database = '" + DB
                                + "' AND name = '" + t + "'"));
                Assert.assertEquals("no scratch or retired table may remain", 0L, scratchTables(ch, t));
            } finally {
                if (engine.get() != null) {
                    engine.get().stop();
                }
                executorService.shutdown();
                HikariDbSource.close();
            }
        } finally {
            mysql.close();
        }
    }

    // ------------------------------------------------------------------
    // The online window (Spec 06.09 §3.3.2 steps 7-9, §5): the backfill of a
    // big table is slowed to seconds and an action is taken while the retired
    // table still exists
    // ------------------------------------------------------------------

    /**
     * Session settings of the connector's ClickHouse connections in these
     * scenarios: the list every connection gets when
     * {@code clickhouse.jdbc.settings} is unset
     * ({@code BaseDbWriter.DEFAULT_CUSTOM_SETTINGS}) plus a read-speed cap
     * that makes the backfill's {@code INSERT ... SELECT} of {@link #BIG_ROWS}
     * rows take about 20 s (300k rows at 15k rows/s, enforced from the first
     * block). The backfill opens its connections through
     * {@code BaseDbWriter.createConnection} with the connector config, exactly
     * as the engine's system connection is opened, so the cap reaches it.
     */
    private static final String SLOW_BACKFILL_JDBC_SETTINGS = "input_format_null_as_default=0,"
            + "allow_experimental_object_type=1,insert_allow_materialized_columns=1,"
            + "max_execution_speed=15000,timeout_before_checking_execution_speed=0";

    /** The capped backfill reads BIG_ROWS several times (copy, row count, completeness check): minutes, plus CI margin. */
    private static final long SLOW_BACKFILL_TIMEOUT_MS = 900_000;

    /** How long the swap (the retired table's appearance, or the new sorting key) is awaited after an ALTER. */
    private static final long SWAP_TIMEOUT_MS = 300_000;

    /** How long the test waits after convergence to be sure no late copy statement resurrects rows. */
    private static final long SETTLE_MS = 30_000;

    private static final String BIG_TABLE = "t_big";

    @Test
    @DisplayName("DML on rows the backfill has not copied yet is never shadowed by the copy: UPDATE / DELETE / INSERT / relocation in the window, value-level equality at the end")
    public void dmlDuringBackfillIsShadowedByNewerVersions() throws Exception {
        try (SlowBackfill s = new SlowBackfill(BIG_TABLE)) {
            s.alterAndAwaitWindow("ALTER TABLE " + BIG_TABLE + " DROP PRIMARY KEY, ADD PRIMARY KEY (b)", "b");
            // The copy reads R in its old key order, so the highest ids are copied last.
            execute(s.mysql, "UPDATE " + BIG_TABLE + " SET v = 'updated-in-window' WHERE id BETWEEN 299000 AND 299009");
            // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
            execute(s.mysql, "DELETE FROM " + BIG_TABLE + " WHERE id BETWEEN 299010 AND 299019");
            execute(s.mysql, "INSERT INTO " + BIG_TABLE + " (id, b, c, v) VALUES (300001, 300001, 300001, 'new-a'), "
                    + "(300002, 300002, 300002, 'new-b')");
            execute(s.mysql, "UPDATE " + BIG_TABLE + " SET b = 400000, v = 'relocated-in-window' WHERE id = 299020");
            s.assertConverged("b", "b");
        }
    }

    @Test
    // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
    @DisplayName("TRUNCATE TABLE while the backfill is pending: the backfill is cancelled, the retired copy dropped, the table ends empty, no row is resurrected, a later INSERT replicates")
    public void truncateDuringBackfillLeavesTableEmpty() throws Exception {
        try (SlowBackfill s = new SlowBackfill(BIG_TABLE)) {
            s.alterAndAwaitWindow("ALTER TABLE " + BIG_TABLE + " DROP PRIMARY KEY, ADD PRIMARY KEY (b)", "b");
            // DESTRUCTIVE: the source's own statement, on the test's MySQL container only.
            execute(s.mysql, "TRUNCATE TABLE " + BIG_TABLE);

            // The cancel waits for a copy statement that was in flight (a
            // MergeTree TRUNCATE takes no exclusive lock, so ClickHouse would
            // not), drops the retired copy, and only then does the truncate run
            // on ClickHouse and empty the table.
            awaitBackfillComplete(s.ch, BIG_TABLE, SLOW_BACKFILL_TIMEOUT_MS);
            // DESTRUCTIVE: a log label naming the source statement this test issued above.
            awaitLiveCount(s.ch, BIG_TABLE, 0, SLOW_BACKFILL_TIMEOUT_MS, "the TRUNCATE of " + BIG_TABLE);

            execute(s.mysql, "INSERT INTO " + BIG_TABLE + " (id, b, c, v) VALUES (1, 1, 1, 'after-truncate')");
            awaitMatch(s.mysql, s.ch, BIG_TABLE, "b", CONVERGE_TIMEOUT_MS, "the INSERT after the truncate of " + BIG_TABLE);
            // Nothing may bring the truncated rows back once every statement
            // that was in flight has finished.
            Thread.sleep(SETTLE_MS);
            // DESTRUCTIVE: an assertion label naming the source statement this test issued above.
            assertMatch(s.mysql, s.ch, BIG_TABLE, "b", "no resurrected rows after the truncate of " + BIG_TABLE);
            Assert.assertEquals("the retired copy must be gone", 0L, scratchTables(s.ch, BIG_TABLE));
            Assert.assertEquals("sorting key of " + DB + "." + BIG_TABLE, "b", sortingKey(s.ch, BIG_TABLE));
            assertMatch(s.mysql, s.ch, CONTROL_TABLE, "id", "the control table " + CONTROL_TABLE);
        }
    }

    @Test
    @DisplayName("A second key change while the first backfill is pending: both retired copies are backfilled in order, final equality under the second key")
    public void secondKeyChangeWhileBackfillPending() throws Exception {
        try (SlowBackfill s = new SlowBackfill(BIG_TABLE)) {
            s.alterAndAwaitWindow("ALTER TABLE " + BIG_TABLE + " DROP PRIMARY KEY, ADD PRIMARY KEY (b)", "b");
            execute(s.mysql, "ALTER TABLE " + BIG_TABLE + " DROP PRIMARY KEY, ADD PRIMARY KEY (c)");
            long scratchAtSecondSwap = s.awaitSortingKey("c");
            if (scratchAtSecondSwap >= 2) {
                log.info("Observed: both retired copies of {} existed when the second swap landed (Spec 06.09 §3.3.2 "
                        + "step 8)", BIG_TABLE);
            } else {
                log.warn("Race not observed: the first backfill of {} had completed before the second swap landed; "
                        + "the property is not contradicted, only not witnessed", BIG_TABLE);
            }
            // DML under the final key, including a relocation.
            execute(s.mysql, "INSERT INTO " + BIG_TABLE + " (id, b, c, v) VALUES (300001, 300001, 300001, 'new-a')");
            execute(s.mysql, "UPDATE " + BIG_TABLE + " SET v = 'updated-under-c' WHERE c = 7");
            execute(s.mysql, "UPDATE " + BIG_TABLE + " SET c = 400000 WHERE c = 8");
            // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
            execute(s.mysql, "DELETE FROM " + BIG_TABLE + " WHERE c = 9");
            s.assertConverged("c", "c");
        }
    }

    @Test
    @DisplayName("The engine stopped while the retired table still exists: the restarted engine resumes the backfill from the marker, completes it and drops the retired copy")
    public void restartDuringBackfillResumesFromMarker() throws Exception {
        try (SlowBackfill s = new SlowBackfill(BIG_TABLE)) {
            s.alterAndAwaitWindow("ALTER TABLE " + BIG_TABLE + " DROP PRIMARY KEY, ADD PRIMARY KEY (b)", "b");
            s.stopEngine();
            if (scratchTables(s.ch, BIG_TABLE) > 0) {
                log.info("Observed: the retired table of {} survives the stop; the restarted engine must resume its "
                        + "backfill from the marker (Spec 06.09 §3.3.2 step 6)", BIG_TABLE);
            } else {
                log.warn("Race not observed: the backfill of {} had completed before the engine stopped; the restart "
                        + "finds nothing to resume", BIG_TABLE);
            }
            // The same offset and schema-history storage, in the same ClickHouse.
            Thread.sleep(10_000);
            s.startEngine();
            s.assertConverged("b", "b");
        }
    }

    @Test
    @DisplayName("ADD COLUMN on the source while the backfill runs: the copy omits the column T gained after the swap and the table converges including it")
    public void columnAddedDuringBackfill() throws Exception {
        try (SlowBackfill s = new SlowBackfill(BIG_TABLE)) {
            s.alterAndAwaitWindow("ALTER TABLE " + BIG_TABLE + " DROP PRIMARY KEY, ADD PRIMARY KEY (b)", "b");
            execute(s.mysql, "ALTER TABLE " + BIG_TABLE + " ADD COLUMN extra INT NULL");
            execute(s.mysql, "UPDATE " + BIG_TABLE + " SET extra = id WHERE id IN (5, 150000, 299999)");
            s.assertConverged("b", "b");
            Assert.assertEquals("type of " + BIG_TABLE + ".extra", "Nullable(Int32)",
                    scalar(s.ch, "SELECT type FROM system.columns WHERE database = '" + DB + "' AND table = '"
                            + BIG_TABLE + "' AND name = 'extra'"));
        }
    }

    /**
     * One slow-backfill scenario: the control table and {@code table}
     * ({@code id} key, {@code b} and {@code c} unique candidates, {@code v})
     * seeded with {@link #BIG_ROWS} rows before the engine starts; the engine
     * runs with {@link #SLOW_BACKFILL_JDBC_SETTINGS}; the test's own
     * ClickHouse connection is a direct one (no pool), so it neither inherits
     * the read cap nor seeds the engine's pool first with a config that lacks
     * it (pools are keyed by server and database name and keep the settings
     * of whoever created them).
     */
    private final class SlowBackfill implements AutoCloseable {
        final String table;
        final Connection mysql;
        final Connection ch;
        final Properties props;
        private final AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        private ExecutorService engineThread;

        SlowBackfill(String table) throws Exception {
            this.table = table;
            this.mysql = connectToMySQLWithRetry();
            execute(mysql, "CREATE TABLE " + CONTROL_TABLE + " (id INT NOT NULL, v VARCHAR(32), PRIMARY KEY (id))");
            execute(mysql, "INSERT INTO " + CONTROL_TABLE + " (id, v) VALUES (1, 'ctl-a'), (2, 'ctl-b')");
            execute(mysql, "CREATE TABLE " + table + " (id INT NOT NULL, b INT NOT NULL, c INT NOT NULL, v VARCHAR(32), "
                    + "PRIMARY KEY (id))");
            // One statement from a recursive numbers generator (MySQL 8.0): well under a minute.
            execute(mysql, "SET SESSION cte_max_recursion_depth = " + (BIG_ROWS + 10));
            execute(mysql, "INSERT INTO " + table + " (id, b, c, v) WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL "
                    + "SELECT n + 1 FROM seq WHERE n < " + BIG_ROWS + ") SELECT n, n, n, CONCAT('v', n) FROM seq");

            this.props = ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer);
            this.props.setProperty("clickhouse.jdbc.settings", SLOW_BACKFILL_JDBC_SETTINGS);
            this.ch = directClickHouseConnection();
            startEngine();
            awaitLiveCount(ch, table, BIG_ROWS, SNAPSHOT_TIMEOUT_MS, "snapshot of " + table);
            awaitMatch(mysql, ch, CONTROL_TABLE, "id", SNAPSHOT_TIMEOUT_MS, "snapshot of " + CONTROL_TABLE);
        }

        /** Starts an engine on {@link #props}; a second start after {@link #stopEngine} resumes from the same storage. */
        void startEngine() {
            engineThread = Executors.newFixedThreadPool(1);
            engineThread.execute(() -> {
                try {
                    engine.set(new DebeziumChangeEventCapture());
                    engine.get().setup(props, new SourceRecordParserService(), false);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        /** Stops the engine the way a restart would (the backfill thread is interrupted with it). */
        void stopEngine() throws Exception {
            DebeziumChangeEventCapture running = engine.getAndSet(null);
            if (running != null) {
                running.stop();
            }
            if (engineThread != null) {
                engineThread.shutdown();
                engineThread = null;
            }
        }

        /**
         * Issues the key change and waits for its swap: returns once the
         * retired table exists (the window is open) or, when the backfill was
         * already complete by then (fast CI), once the sorting key has changed
         * -- logged at WARN, never a failure: the action still runs.
         */
        boolean alterAndAwaitWindow(String alter, String newSortingKey) throws Exception {
            execute(mysql, alter);
            long deadline = System.currentTimeMillis() + SWAP_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                if (scratchTables(ch, table) > 0) {
                    log.info("Observed: the retired table of {} exists; the action runs inside the backfill window", table);
                    return true;
                }
                if (newSortingKey.equals(sortingKey(ch, table))) {
                    log.warn("Window not witnessed: the backfill of {} had already completed when the swap was first "
                            + "seen; the action runs anyway and the end state is still asserted", table);
                    return false;
                }
                Thread.sleep(200);
            }
            Assert.fail("the key change of " + DB + "." + table + " did not reach ClickHouse within " + SWAP_TIMEOUT_MS
                    + " ms: neither a retired table nor the sorting key (" + newSortingKey + ")");
            return false;
        }

        /** Waits until the table is keyed by {@code sortingKey}; returns how many scratch/retired tables exist then. */
        long awaitSortingKey(String sortingKey) throws Exception {
            long deadline = System.currentTimeMillis() + SWAP_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                long scratch = scratchTables(ch, table);
                if (sortingKey.equals(sortingKey(ch, table))) {
                    return scratch;
                }
                Thread.sleep(200);
            }
            Assert.fail("the key change of " + DB + "." + table + " to (" + sortingKey + ") did not reach ClickHouse "
                    + "within " + SWAP_TIMEOUT_MS + " ms");
            return 0;
        }

        /** The backfill completes, the table matches value for value under {@code orderBy}, the key and the control table are as expected. */
        void assertConverged(String orderBy, String sortingKey) throws Exception {
            awaitBackfillComplete(ch, table, SLOW_BACKFILL_TIMEOUT_MS);
            awaitMatch(mysql, ch, table, orderBy, SLOW_BACKFILL_TIMEOUT_MS, "the rebuild of " + table);
            Assert.assertEquals("sorting key of " + DB + "." + table, sortingKey, sortingKey(ch, table));
            Assert.assertEquals("no scratch or retired table may remain", 0L, scratchTables(ch, table));
            assertMatch(mysql, ch, CONTROL_TABLE, "id", "the control table " + CONTROL_TABLE);
        }

        @Override
        public void close() throws Exception {
            try {
                stopEngine();
            } finally {
                try {
                    ch.close();
                } catch (SQLException e) {
                    log.warn("could not close the test's ClickHouse connection", e);
                }
                HikariDbSource.close();
                mysql.close();
            }
        }
    }

    /**
     * A direct, un-pooled ClickHouse connection for the test's own reads. It
     * must not go through {@code HikariDbSource}: the pool is keyed by server
     * and database name and keeps the settings of whoever created it, so a
     * test connection opened first with the default config would hand the
     * engine's backfill un-capped connections, and one opened second would
     * inherit the cap and slow every assertion.
     */
    private Connection directClickHouseConnection() {
        Map<String, String> config = new HashMap<>();
        config.put("connection.pool.disable", "true");
        String url = BaseDbWriter.getConnectionString(clickHouseContainer.getHost(),
                clickHouseContainer.getFirstMappedPort(), DB);
        Connection conn = BaseDbWriter.createConnection(url, BaseDbWriter.DATABASE_CLIENT_NAME,
                clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), DB,
                new ClickHouseSinkConnectorConfig(config));
        Assert.assertNotNull("could not open a direct ClickHouse connection", conn);
        return conn;
    }

    private static String sortingKey(Connection ch, String table) throws SQLException {
        return scalar(ch, "SELECT sorting_key FROM system.tables WHERE database = '" + DB + "' AND name = '" + table + "'");
    }

    // ------------------------------------------------------------------
    // The shared scenario runner
    // ------------------------------------------------------------------

    /** One primary-key change scenario: setup before the snapshot, DML under the old key, ALTER, DML under the new key. */
    private final class Scenario {
        private final String table;
        private final List<String> setup = new ArrayList<>();
        private final List<String> oldKeyDml = new ArrayList<>();
        private final List<String> alter = new ArrayList<>();
        private final List<String> newKeyDml = new ArrayList<>();
        private final List<String[]> expectedColumnTypes = new ArrayList<>();
        private final List<String> absentColumns = new ArrayList<>();
        private String oldOrderBy;
        private String newOrderBy;
        private String expectedSortingKey;

        Scenario(String table) {
            this.table = table;
        }

        Scenario setup(String... sql) {
            setup.addAll(Arrays.asList(sql));
            return this;
        }

        Scenario oldOrderBy(String orderBy) {
            this.oldOrderBy = orderBy;
            return this;
        }

        Scenario oldKeyDml(String... sql) {
            oldKeyDml.addAll(Arrays.asList(sql));
            return this;
        }

        Scenario alter(String... sql) {
            alter.addAll(Arrays.asList(sql));
            return this;
        }

        Scenario newKeyDml(String... sql) {
            newKeyDml.addAll(Arrays.asList(sql));
            return this;
        }

        Scenario newOrderBy(String orderBy) {
            this.newOrderBy = orderBy;
            return this;
        }

        Scenario expectSortingKey(String sortingKey) {
            this.expectedSortingKey = sortingKey;
            return this;
        }

        Scenario expectColumnType(String column, String type) {
            expectedColumnTypes.add(new String[] {column, type});
            return this;
        }

        Scenario expectColumnAbsent(String column) {
            absentColumns.add(column);
            return this;
        }

        void run() throws Exception {
            // One MySQL connection for the whole scenario, so SET SESSION
            // statements govern the CREATE / ALTER that follow them.
            Connection mysql = connectToMySQLWithRetry();
            try {
                // 1. Source state before the engine starts: the scenario table
                //    and the untouched control table are snapshotted.
                execute(mysql, "CREATE TABLE " + CONTROL_TABLE + " (id INT NOT NULL, v VARCHAR(32), PRIMARY KEY (id))");
                execute(mysql, "INSERT INTO " + CONTROL_TABLE + " (id, v) VALUES (1, 'ctl-a'), (2, 'ctl-b')");
                for (String sql : setup) {
                    execute(mysql, sql);
                }

                AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
                ExecutorService executorService = Executors.newFixedThreadPool(1);
                Properties props = ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer);
                executorService.execute(() -> {
                    try {
                        engine.set(new DebeziumChangeEventCapture());
                        engine.get().setup(props, new SourceRecordParserService(), false);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
                Connection ch = writer.getConnection();
                try {
                    // 2. The snapshot rows are in ClickHouse.
                    awaitMatch(mysql, ch, table, oldOrderBy, SNAPSHOT_TIMEOUT_MS, "snapshot of " + table);
                    awaitMatch(mysql, ch, CONTROL_TABLE, "id", SNAPSHOT_TIMEOUT_MS, "snapshot of " + CONTROL_TABLE);

                    // 3. DML under the old key.
                    for (String sql : oldKeyDml) {
                        execute(mysql, sql);
                    }
                    awaitMatch(mysql, ch, table, oldOrderBy, CONVERGE_TIMEOUT_MS, "DML under the old key of " + table);

                    // 4. The primary-key change, then DML under the new key,
                    //    including a relocation and a DELETE. The swap is
                    //    inside the barrier; the copy runs online afterwards,
                    //    so the comparison waits for the backfill's completion
                    //    signal first: the retired table is gone.
                    for (String sql : alter) {
                        execute(mysql, sql);
                    }
                    for (String sql : newKeyDml) {
                        execute(mysql, sql);
                    }
                    awaitBackfillComplete(ch, table, CONVERGE_TIMEOUT_MS);
                    awaitMatch(mysql, ch, table, newOrderBy, CONVERGE_TIMEOUT_MS,
                            "the rebuild and the DML under the new key of " + table);

                    // 5. The replica is keyed as the source is, no scratch table
                    //    remains, the control table is untouched.
                    Assert.assertEquals("sorting key of " + DB + "." + table, expectedSortingKey,
                            scalar(ch, "SELECT sorting_key FROM system.tables WHERE database = '" + DB
                                    + "' AND name = '" + table + "'"));
                    for (String[] expected : expectedColumnTypes) {
                        Assert.assertEquals("type of " + table + "." + expected[0], expected[1],
                                scalar(ch, "SELECT type FROM system.columns WHERE database = '" + DB + "' AND table = '"
                                        + table + "' AND name = '" + expected[0] + "'"));
                    }
                    for (String absent : absentColumns) {
                        Assert.assertEquals(table + "." + absent + " must be gone", "0",
                                scalar(ch, "SELECT count() FROM system.columns WHERE database = '" + DB
                                        + "' AND table = '" + table + "' AND name = '" + absent + "'"));
                    }
                    Assert.assertEquals("no __pk_rebuild_ / __pk_retired_ table may remain", 0L,
                            scratchTables(ch, table));
                    assertMatch(mysql, ch, CONTROL_TABLE, "id", "the control table " + CONTROL_TABLE);
                    Assert.assertEquals("sorting key of the control table", "id",
                            scalar(ch, "SELECT sorting_key FROM system.tables WHERE database = '" + DB
                                    + "' AND name = '" + CONTROL_TABLE + "'"));
                } finally {
                    // Release the engine and its port unconditionally, so one
                    // failed assertion does not cascade into the next test.
                    if (engine.get() != null) {
                        engine.get().stop();
                    }
                    executorService.shutdown();
                    HikariDbSource.close();
                }
            } finally {
                mysql.close();
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers: execute, poll, compare value by value
    // ------------------------------------------------------------------

    private Connection connectToMySQLWithRetry() throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            Connection conn = ITCommon.connectToMySQL(mySqlContainer);
            if (conn != null) {
                return conn;
            }
            Thread.sleep(3000);
        }
        throw new AssertionError("could not connect to the MySQL container");
    }

    private static void execute(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static String scalar(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /**
     * The scratch / retired tables of {@code table} ({@code <table>__pk_rebuild_%},
     * {@code <table>__pk_retired_%}, including the key map). Their absence is the
     * backfill's completion signal (Spec 06.09 §3.3.2 step 4).
     */
    private static long scratchTables(Connection ch, String table) throws SQLException {
        return Long.parseLong(scalar(ch, "SELECT count() FROM system.tables WHERE database = '" + DB
                + "' AND (name LIKE '" + table + "\\_\\_pk\\_rebuild\\_%' OR name LIKE '" + table
                + "\\_\\_pk\\_retired\\_%')"));
    }

    /** Polls until the retired table of {@code table} is gone, i.e. the online backfill has completed. */
    private static void awaitBackfillComplete(Connection ch, String table, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (scratchTables(ch, table) == 0) {
                return;
            }
            Thread.sleep(POLL_MS);
        }
        Assert.fail("the backfill of " + DB + "." + table + " did not complete within " + timeoutMs
                + " ms: its retired table is still present (see the pk-rebuild-backfill log lines)");
    }

    /** Polls until the replica holds {@code expected} live rows of {@code table}. */
    private static void awaitLiveCount(Connection ch, String table, long expected, long timeoutMs, String what)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                last = scalar(ch, "SELECT count() FROM `" + DB + "`.`" + table + "` FINAL WHERE `is_deleted` = 0");
                if (String.valueOf(expected).equals(last)) {
                    return;
                }
            } catch (SQLException e) {
                // The table may not exist on the replica yet; keep polling.
            }
            Thread.sleep(POLL_MS);
        }
        Assert.fail(what + ": expected " + expected + " live rows in " + DB + "." + table + ", found " + last);
    }

    /** A query result as rows of strings, plus the column names it was read with. */
    private static final class Rows {
        final List<String> columns;
        final List<List<String>> values;

        Rows(List<String> columns, List<List<String>> values) {
            this.columns = columns;
            this.values = values;
        }
    }

    private static Rows read(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                columns.add(md.getColumnLabel(i));
            }
            List<List<String>> values = new ArrayList<>();
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= columns.size(); i++) {
                    row.add(normalise(rs.getString(i)));
                }
                values.add(row);
            }
            return new Rows(columns, values);
        }
    }

    /** Every column is compared as a string; trailing zeros/whitespace differences are not expected for the types used. */
    private static String normalise(String value) {
        return value == null ? null : value.trim();
    }

    /** The source rows, every visible column, ordered by the given key. */
    private static Rows sourceRows(Connection mysql, String table, String orderBy) throws SQLException {
        return read(mysql, "SELECT * FROM `" + table + "` ORDER BY " + orderBy);
    }

    /** The replica's live rows under the source's column list, ordered by the same key. */
    private static Rows replicaRows(Connection ch, String table, List<String> columns, String orderBy)
            throws SQLException {
        StringBuilder select = new StringBuilder("SELECT ");
        for (int i = 0; i < columns.size(); i++) {
            select.append(i == 0 ? "" : ", ").append('`').append(columns.get(i)).append('`');
        }
        select.append(" FROM `").append(DB).append("`.`").append(table).append("` FINAL WHERE `is_deleted` = 0 ORDER BY ")
                .append(orderBy);
        return read(ch, select.toString());
    }

    /** Polls until the replica holds exactly the source's rows, then asserts it (with the diff on timeout). */
    private static void awaitMatch(Connection mysql, Connection ch, String table, String orderBy, long timeoutMs,
                                   String what) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                Rows source = sourceRows(mysql, table, orderBy);
                Rows replica = replicaRows(ch, table, source.columns, orderBy);
                if (Objects.equals(source.values, replica.values)) {
                    return;
                }
            } catch (SQLException e) {
                // The table or a column may not exist on the replica yet
                // (before the snapshot DDL, or mid-statement); keep polling.
            }
            Thread.sleep(POLL_MS);
        }
        assertMatch(mysql, ch, table, orderBy, what);
    }

    private static void assertMatch(Connection mysql, Connection ch, String table, String orderBy, String what)
            throws SQLException {
        Rows source = sourceRows(mysql, table, orderBy);
        List<List<String>> replica;
        try {
            replica = replicaRows(ch, table, source.columns, orderBy).values;
        } catch (SQLException e) {
            replica = Collections.singletonList(Collections.singletonList("<query failed: " + e.getMessage() + ">"));
        }
        Assert.assertEquals(what + ": ClickHouse " + DB + "." + table + " must hold the MySQL rows value for value "
                + "(columns " + source.columns + ")", source.values, replica);
    }
}

package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.CacheInvalidationManager;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;

/**
 * Integration Test that reproduces the schema-change race condition described in
 * issue #1319.
 *
 * <p>Under many rapid {@code ALTER TABLE ADD COLUMN} + {@code INSERT} cycles, the cache
 * invalidation signal fired by {@code DebeziumChangeEventCapture.performDDLOperation} is
 * processed before ClickHouse has made the newly added column visible in
 * {@code system.columns}. The rebuilt {@code DbWriter} therefore caches stale column
 * metadata and silently drops the value for the newly added column (it replicates as
 * NULL).
 *
 * <p>This test asserts the CORRECT behaviour (every inserted value survives). It is
 * therefore expected to FAIL on the buggy revision (that failure is the reproduction of
 * #1319) and to pass once the race is fixed.
 *
 * <p>{@code FailFastListener} calls {@code System.exit(-1)} on any test failure, so run
 * this test in isolation:
 * <pre>mvn test -Dtest=AlterTableAddColumnRaceIT</pre>
 */
@DisplayName("Integration Test that reproduces the ADD COLUMN cache race condition (issue #1319)")
@Testcontainers
public class AlterTableAddColumnRaceIT extends DDLBaseIT {

    private static final int NUM_CYCLES = 60;

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse(MYSQL_DOCKER_IMAGE)
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                .withInitScript("alter_ddl_add_column_race.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        clickHouseContainer.start();

        // Clear any pending invalidations from previous tests
        CacheInvalidationManager.getInstance().clearAll();

        Thread.sleep(15000);
    }

    @Test
    @DisplayName("Rapid ALTER TABLE ADD COLUMN + INSERT cycles must not silently drop values")
    public void testAddColumnRaceCondition() throws Exception {

        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                Properties properties = getDebeziumProperties();
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(properties, new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Wait for initial snapshot replication (seed row with id=0).
        Thread.sleep(30000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        Connection chConn = writer.getConnection();

        // Verify the seed row replicated so we know the pipeline is live.
        try (Statement stmt = chConn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT id, name FROM employees.race_test WHERE id = 0");
            Assert.assertTrue("Seed row should be replicated", rs.next());
            Assert.assertEquals("seed", rs.getString("name"));
            rs.close();
        }

        // Drive the race: for each cycle add a new column and immediately insert a row
        // that populates only that new column. No sleep between cycles so the INSERT
        // races the schema propagation of the preceding ALTER.
        Connection mysqlConn = connectToMySQL();
        for (int i = 1; i <= NUM_CYCLES; i++) {
            int value = i * 100;
            try (Statement stmt = mysqlConn.createStatement()) {
                stmt.execute("ALTER TABLE race_test ADD COLUMN col" + i + " INT");
                stmt.execute("INSERT INTO race_test (id, name, col" + i + ") VALUES ("
                        + i + ", 'row" + i + "', " + value + ")");
            }
        }

        // Wait for replication to settle: poll until all rows arrive (seed + NUM_CYCLES),
        // then a short final grace period for the last INSERT batches to flush.
        waitForRowCount(chConn, NUM_CYCLES + 1, 90000);
        Thread.sleep(10000);

        // Verify every newly added column carries its inserted value (not NULL / stale).
        List<String> lostColumns = new ArrayList<>();
        for (int i = 1; i <= NUM_CYCLES; i++) {
            int expected = i * 100;
            try (Statement stmt = chConn.createStatement()) {
                ResultSet rs = stmt.executeQuery(
                        "SELECT col" + i + " FROM employees.race_test FINAL WHERE id = " + i);
                if (!rs.next()) {
                    lostColumns.add("col" + i + " (row missing)");
                } else {
                    int actual = rs.getInt("col" + i);
                    if (rs.wasNull()) {
                        lostColumns.add("col" + i + " (NULL)");
                    } else if (actual != expected) {
                        lostColumns.add("col" + i + " (expected " + expected + " got " + actual + ")");
                    }
                }
                rs.close();
            }
        }

        Assert.assertTrue(
                lostColumns.size() + "/" + NUM_CYCLES
                        + " newly added columns lost their inserted value (issue #1319): " + lostColumns,
                lostColumns.isEmpty());

        // Cleanup
        if (engine.get() != null) {
            engine.get().stop();
        }
        executorService.shutdown();
        HikariDbSource.close();
    }

    /**
     * Polls ClickHouse until the {@code race_test} row count reaches the expected value or
     * the timeout elapses.
     */
    private void waitForRowCount(Connection chConn, int expectedCount, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (Statement stmt = chConn.createStatement()) {
                ResultSet rs = stmt.executeQuery(
                        "SELECT count(*) AS cnt FROM employees.race_test FINAL");
                if (rs.next() && rs.getInt("cnt") >= expectedCount) {
                    rs.close();
                    return;
                }
                rs.close();
            }
            Thread.sleep(2000);
        }
    }
}

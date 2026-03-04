package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E integration test for MySQL {@code snapshot.mode=schema_only} with bulk load.
 *
 * <p>Simulates the production workflow where:
 * <ol>
 *   <li>The connector starts in CDC-only mode ({@code schema_only} captures schema but not data)</li>
 *   <li>A Python bulk load script loads initial data from MySQL → ClickHouse</li>
 *   <li>CDC captures ongoing INSERT/UPDATE/DELETE changes</li>
 * </ol>
 *
 * <p>The Python bulk load step is simulated by reading data from MySQL via JDBC
 * and inserting it into ClickHouse via JDBC, which mimics what
 * {@code mysql_dumper.py} does in production.
 *
 * <p>Uses {@code init_mysql_all_types.sql} which creates three tables:
 * {@code all_types_test}, {@code snapshot_test}, {@code ddl_test}.
 *
 * <p>Containers are started once per test class via {@code @BeforeAll} /
 * {@code @AfterAll} to avoid Docker socket exhaustion.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MySQLSnapshotNeverBulkLoadE2EIT {

    private static final Logger log = LoggerFactory.getLogger(MySQLSnapshotNeverBulkLoadE2EIT.class);

    /** Max seconds to poll for CDC convergence. */
    private static final int CDC_POLL_TIMEOUT_SECONDS = 30;

    private Network network;
    private ClickHouseContainer clickHouseContainer;
    private MySQLContainer<?> mySqlContainer;

    // -------------------------------------------------------------------------
    // Container lifecycle
    // -------------------------------------------------------------------------

    @BeforeAll
    void startContainers() throws InterruptedException {
        Exception lastEx = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                tryStartContainers();
                return;
            } catch (Exception e) {
                lastEx = e;
                log.warn("Container startup attempt {} failed: {}. Retrying in {}s…",
                        attempt, e.getMessage(), attempt * 10);
                if (network != null) {
                    try { network.close(); } catch (Exception ignored) {}
                    network = null;
                }
                Thread.sleep(attempt * 10_000L);
            }
        }
        throw new RuntimeException("Container startup failed after 3 attempts", lastEx);
    }

    private void tryStartContainers() throws InterruptedException {
        network = Network.newNetwork();

        clickHouseContainer = new ClickHouseContainer(
                DockerImageName.parse(CLICKHOUSE_DOCKER_IMAGE).asCompatibleSubstituteFor("clickhouse"))
                .withInitScript("init_clickhouse_it.sql")
                .withUsername("ch_user")
                .withPassword("password")
                .withExposedPorts(8123)
                .withNetwork(network);

        mySqlContainer = new MySQLContainer<>(DockerImageName.parse(MYSQL_DOCKER_IMAGE)
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees")
                .withUsername("root")
                .withPassword("adminpass")
                .withInitScript("init_mysql_all_types.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306))
                .withNetwork(network);

        clickHouseContainer.start();
        Thread.sleep(3_000);
        mySqlContainer.start();
        Thread.sleep(15_000);
    }

    @AfterAll
    void stopContainers() {
        HikariDbSource.close();
        if (mySqlContainer != null) mySqlContainer.stop();
        if (clickHouseContainer != null) clickHouseContainer.stop();
        if (network != null) {
            try { network.close(); } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Properties — snapshot.mode=schema_only (MySQL equivalent of "never" for data)
    // -------------------------------------------------------------------------

    private Properties getProperties() throws Exception {
        Properties props = ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer);
        props.put("table.include.list",
                "employees.snapshot_test,employees.ddl_test");
        props.put("database.allowPublicKeyRetrieval", "true");
        props.put("snapshot.mode", "schema_only");
        props.put("skipped.operations", "none");
        props.put("disable.drop.truncate", "false");
        return props;
    }

    // -------------------------------------------------------------------------
    // Bulk load simulation: MySQL → ClickHouse via JDBC
    // -------------------------------------------------------------------------

    /**
     * Simulates the Python bulk load step ({@code mysql_dumper.py}).
     * Creates ClickHouse tables (ReplacingMergeTree) and copies all rows
     * from MySQL into ClickHouse using JDBC.
     */
    private void simulateBulkLoad(Connection mysqlConn, Connection chConn) throws Exception {
        Statement chStmt = chConn.createStatement();

        // Create snapshot_test table in ClickHouse (ReplacingMergeTree)
        chStmt.execute("CREATE TABLE IF NOT EXISTS employees.snapshot_test (" +
                "id Int32, " +
                "name Nullable(String), " +
                "value Nullable(Int32), " +
                "is_deleted UInt8 DEFAULT 0, " +
                "_version UInt64 DEFAULT 0" +
                ") ENGINE = ReplacingMergeTree(_version, is_deleted) ORDER BY id");

        // Create ddl_test table in ClickHouse (ReplacingMergeTree)
        chStmt.execute("CREATE TABLE IF NOT EXISTS employees.ddl_test (" +
                "id Int32, " +
                "name Nullable(String), " +
                "value Nullable(Int32), " +
                "is_deleted UInt8 DEFAULT 0, " +
                "_version UInt64 DEFAULT 0" +
                ") ENGINE = ReplacingMergeTree(_version, is_deleted) ORDER BY id");

        // Bulk load snapshot_test: read from MySQL, insert into CH
        ResultSet mysqlRs = mysqlConn.prepareStatement(
                "SELECT id, name, value FROM employees.snapshot_test"
        ).executeQuery();
        while (mysqlRs.next()) {
            PreparedStatement chInsert = chConn.prepareStatement(
                    "INSERT INTO employees.snapshot_test (id, name, value, is_deleted, _version) " +
                            "VALUES (?, ?, ?, 0, 1)");
            chInsert.setInt(1, mysqlRs.getInt("id"));
            chInsert.setString(2, mysqlRs.getString("name"));
            chInsert.setInt(3, mysqlRs.getInt("value"));
            chInsert.execute();
        }

        // Bulk load ddl_test: read from MySQL, insert into CH
        mysqlRs = mysqlConn.prepareStatement(
                "SELECT id, name, value FROM employees.ddl_test"
        ).executeQuery();
        while (mysqlRs.next()) {
            PreparedStatement chInsert = chConn.prepareStatement(
                    "INSERT INTO employees.ddl_test (id, name, value, is_deleted, _version) " +
                            "VALUES (?, ?, ?, 0, 1)");
            chInsert.setInt(1, mysqlRs.getInt("id"));
            chInsert.setString(2, mysqlRs.getString("name"));
            chInsert.setInt(3, mysqlRs.getInt("value"));
            chInsert.execute();
        }

        log.info("Bulk load simulation complete — data copied from MySQL to ClickHouse");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private int getClickHouseRowCount(BaseDbWriter writer, String table) throws Exception {
        ResultSet rs = writer.getConnection()
                .prepareStatement("SELECT count(*) as cnt FROM employees." + table +
                        " FINAL WHERE is_deleted = 0")
                .executeQuery();
        rs.next();
        return rs.getInt("cnt");
    }

    private int getMySQLRowCount(Connection conn, String table) throws Exception {
        ResultSet rs = conn.prepareStatement("SELECT count(*) as cnt FROM employees." + table)
                .executeQuery();
        rs.next();
        return rs.getInt("cnt");
    }

    /**
     * Polls ClickHouse until the expected row count is reached or timeout expires.
     */
    private boolean waitForRowCount(BaseDbWriter writer, String table, int expected,
                                    int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            int actual = getClickHouseRowCount(writer, table);
            if (actual == expected) return true;
            Thread.sleep(1_000);
        }
        return false;
    }

    /**
     * Polls ClickHouse until a condition query returns at least one row.
     */
    private boolean waitForCondition(Connection chConn, String query,
                                     int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            ResultSet rs = chConn.prepareStatement(query).executeQuery();
            if (rs.next()) return true;
            Thread.sleep(1_000);
        }
        return false;
    }

    // =========================================================================
    // Test 1: Bulk-loaded data is present in ClickHouse
    // =========================================================================

    @Test
    @DisplayName("E2E snapshot.mode=schema_only — Bulk-loaded rows exist in ClickHouse before CDC starts")
    public void testBulkLoadDataPresent() throws Exception {
        // Step 1: Simulate bulk load (MySQL → CH)
        Connection mysqlConn = ITCommon.connectToMySQL(mySqlContainer);
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        simulateBulkLoad(mysqlConn, writer.getConnection());

        // Verify bulk-loaded data in snapshot_test (5 rows)
        int chSnapshotCount = getClickHouseRowCount(writer, "snapshot_test");
        int mysqlSnapshotCount = getMySQLRowCount(mysqlConn, "snapshot_test");
        assertEquals(mysqlSnapshotCount, chSnapshotCount,
                "snapshot_test: CH row count (" + chSnapshotCount +
                        ") should match MySQL (" + mysqlSnapshotCount + ") after bulk load");
        assertEquals(5, chSnapshotCount, "snapshot_test should have 5 bulk-loaded rows");

        // Verify bulk-loaded data in ddl_test (2 rows)
        int chDdlCount = getClickHouseRowCount(writer, "ddl_test");
        int mysqlDdlCount = getMySQLRowCount(mysqlConn, "ddl_test");
        assertEquals(mysqlDdlCount, chDdlCount,
                "ddl_test: CH row count (" + chDdlCount +
                        ") should match MySQL (" + mysqlDdlCount + ") after bulk load");
        assertEquals(2, chDdlCount, "ddl_test should have 2 bulk-loaded rows");

        // Verify specific values from snapshot_test
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM employees.snapshot_test FINAL WHERE id = 1 AND is_deleted = 0"
        ).executeQuery();
        assertTrue(rs.next(), "Row id=1 should exist in snapshot_test after bulk load");
        assertEquals("alpha", rs.getString("name"));
        assertEquals(100, rs.getInt("value"));

        // Verify specific values from ddl_test
        rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM employees.ddl_test FINAL WHERE id = 1 AND is_deleted = 0"
        ).executeQuery();
        assertTrue(rs.next(), "Row id=1 should exist in ddl_test after bulk load");
        assertEquals("initial", rs.getString("name"));
        assertEquals(100, rs.getInt("value"));

        mysqlConn.close();
    }

    // =========================================================================
    // Test 2: CDC INSERT after bulk load
    // =========================================================================

    @Test
    @DisplayName("E2E snapshot.mode=schema_only — New inserts are captured via CDC after bulk load")
    public void testCdcInsertAfterBulkLoad() throws Exception {
        // Step 1: Bulk load
        Connection mysqlConn = ITCommon.connectToMySQL(mySqlContainer);
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        simulateBulkLoad(mysqlConn, writer.getConnection());

        // Step 2: Start Debezium engine in CDC-only mode (snapshot.mode=schema_only)
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(getProperties(), new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Wait for CDC engine to initialize
        Thread.sleep(30_000);

        // Step 3: Insert new rows in MySQL
        mysqlConn.prepareStatement(
                "INSERT INTO employees.snapshot_test (name, value) VALUES ('cdc_insert_1', 601)"
        ).execute();
        mysqlConn.prepareStatement(
                "INSERT INTO employees.snapshot_test (name, value) VALUES ('cdc_insert_2', 602)"
        ).execute();

        // Step 4: Wait for CDC convergence and verify
        boolean converged = waitForRowCount(writer, "snapshot_test", 7, CDC_POLL_TIMEOUT_SECONDS);
        assertTrue(converged, "CDC inserts should bring snapshot_test to 7 rows (5 bulk + 2 CDC)");

        // Verify specific CDC row
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM employees.snapshot_test FINAL " +
                        "WHERE name = 'cdc_insert_1' AND is_deleted = 0"
        ).executeQuery();
        assertTrue(rs.next(), "CDC-inserted row 'cdc_insert_1' should exist in ClickHouse");
        assertEquals("cdc_insert_1", rs.getString("name"));
        assertEquals(601, rs.getInt("value"));

        rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM employees.snapshot_test FINAL " +
                        "WHERE name = 'cdc_insert_2' AND is_deleted = 0"
        ).executeQuery();
        assertTrue(rs.next(), "CDC-inserted row 'cdc_insert_2' should exist in ClickHouse");
        assertEquals(602, rs.getInt("value"));

        mysqlConn.close();
        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 3: CDC UPDATE after bulk load
    // =========================================================================

    @Test
    @DisplayName("E2E snapshot.mode=schema_only — Updates are captured via CDC after bulk load")
    public void testCdcUpdateAfterBulkLoad() throws Exception {
        // Step 1: Bulk load
        Connection mysqlConn = ITCommon.connectToMySQL(mySqlContainer);
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        simulateBulkLoad(mysqlConn, writer.getConnection());

        // Step 2: Start Debezium engine in CDC-only mode
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(getProperties(), new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Wait for CDC engine to initialize
        Thread.sleep(30_000);

        // Step 3: Update a row in MySQL
        mysqlConn.prepareStatement(
                "UPDATE employees.snapshot_test SET name = 'alpha_updated', value = 999 WHERE id = 1"
        ).execute();

        // Step 4: Wait for the update to appear in ClickHouse
        boolean found = waitForCondition(writer.getConnection(),
                "SELECT 1 FROM employees.snapshot_test FINAL " +
                        "WHERE id = 1 AND name = 'alpha_updated' AND is_deleted = 0",
                CDC_POLL_TIMEOUT_SECONDS);
        assertTrue(found, "Updated row (id=1) should reflect name='alpha_updated' in ClickHouse");

        // Verify the updated value
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM employees.snapshot_test FINAL " +
                        "WHERE id = 1 AND is_deleted = 0"
        ).executeQuery();
        assertTrue(rs.next(), "Row id=1 should exist after update");
        assertEquals("alpha_updated", rs.getString("name"));
        assertEquals(999, rs.getInt("value"));

        // Row count should remain the same (5 rows — update, not insert)
        int count = getClickHouseRowCount(writer, "snapshot_test");
        assertEquals(5, count, "Row count should remain 5 after update (no new rows)");

        mysqlConn.close();
        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 4: CDC DELETE after bulk load (soft delete with is_deleted=1)
    // =========================================================================

    @Test
    @DisplayName("E2E snapshot.mode=schema_only — Deletes are captured via CDC as soft deletes (is_deleted=1)")
    public void testCdcDeleteAfterBulkLoad() throws Exception {
        // Step 1: Bulk load
        Connection mysqlConn = ITCommon.connectToMySQL(mySqlContainer);
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        simulateBulkLoad(mysqlConn, writer.getConnection());

        // Step 2: Start Debezium engine in CDC-only mode
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(getProperties(), new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Wait for CDC engine to initialize
        Thread.sleep(30_000);

        // Step 3: Delete a row in MySQL
        mysqlConn.prepareStatement(
                "DELETE FROM employees.snapshot_test WHERE id = 5"
        ).execute();

        // Step 4: Wait for the soft delete to appear in ClickHouse
        boolean found = waitForCondition(writer.getConnection(),
                "SELECT 1 FROM employees.snapshot_test FINAL WHERE id = 5 AND is_deleted = 1",
                CDC_POLL_TIMEOUT_SECONDS);
        assertTrue(found, "Deleted row (id=5) should have is_deleted=1 in ClickHouse");

        // Active row count should be 4 (5 - 1 deleted)
        int activeCount = getClickHouseRowCount(writer, "snapshot_test");
        assertEquals(4, activeCount,
                "Active row count should be 4 after deleting id=5 (5 bulk - 1 delete)");

        mysqlConn.close();
        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 5: Combined data integrity after bulk load + CDC operations
    // =========================================================================

    @Test
    @DisplayName("E2E snapshot.mode=schema_only — Combined data integrity after bulk load + CDC INSERT/UPDATE/DELETE")
    public void testBulkLoadAndCdcDataIntegrity() throws Exception {
        // Step 1: Bulk load
        Connection mysqlConn = ITCommon.connectToMySQL(mySqlContainer);
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        simulateBulkLoad(mysqlConn, writer.getConnection());

        // Step 2: Start Debezium engine in CDC-only mode
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(getProperties(), new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Wait for CDC engine to initialize
        Thread.sleep(30_000);

        // Step 3: Perform mixed DML operations on MySQL
        // INSERT two new rows
        mysqlConn.prepareStatement(
                "INSERT INTO employees.snapshot_test (name, value) VALUES ('integrity_new1', 1001)"
        ).execute();
        mysqlConn.prepareStatement(
                "INSERT INTO employees.snapshot_test (name, value) VALUES ('integrity_new2', 1002)"
        ).execute();

        // UPDATE an existing bulk-loaded row
        mysqlConn.prepareStatement(
                "UPDATE employees.snapshot_test SET name = 'beta_modified', value = 222 WHERE id = 2"
        ).execute();

        // DELETE an existing bulk-loaded row
        mysqlConn.prepareStatement(
                "DELETE FROM employees.snapshot_test WHERE id = 4"
        ).execute();

        // Step 4: Wait for CDC convergence
        // Expected: 5 bulk + 2 inserts - 1 delete = 6 active rows
        boolean converged = waitForRowCount(writer, "snapshot_test", 6, CDC_POLL_TIMEOUT_SECONDS);
        assertTrue(converged,
                "After bulk load + 2 inserts + 1 update + 1 delete, should have 6 active rows");

        // Step 5: Verify individual row states

        // Original unchanged rows (id=1, id=3, id=5) should be intact
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM employees.snapshot_test FINAL " +
                        "WHERE id = 1 AND is_deleted = 0"
        ).executeQuery();
        assertTrue(rs.next(), "Unchanged row id=1 should still exist");
        assertEquals("alpha", rs.getString("name"));
        assertEquals(100, rs.getInt("value"));

        rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM employees.snapshot_test FINAL " +
                        "WHERE id = 3 AND is_deleted = 0"
        ).executeQuery();
        assertTrue(rs.next(), "Unchanged row id=3 should still exist");
        assertEquals("gamma", rs.getString("name"));
        assertEquals(300, rs.getInt("value"));

        // Updated row (id=2) should reflect new values
        rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM employees.snapshot_test FINAL " +
                        "WHERE id = 2 AND is_deleted = 0"
        ).executeQuery();
        assertTrue(rs.next(), "Updated row id=2 should exist");
        assertEquals("beta_modified", rs.getString("name"));
        assertEquals(222, rs.getInt("value"));

        // Deleted row (id=4) should be soft-deleted
        rs = writer.getConnection().prepareStatement(
                "SELECT is_deleted FROM employees.snapshot_test FINAL WHERE id = 4"
        ).executeQuery();
        assertTrue(rs.next(), "Deleted row id=4 should still be in table with is_deleted=1");
        assertEquals(1, rs.getInt("is_deleted"));

        // CDC-inserted rows should exist
        rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM employees.snapshot_test FINAL " +
                        "WHERE name = 'integrity_new1' AND is_deleted = 0"
        ).executeQuery();
        assertTrue(rs.next(), "CDC-inserted row 'integrity_new1' should exist");
        assertEquals(1001, rs.getInt("value"));

        rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM employees.snapshot_test FINAL " +
                        "WHERE name = 'integrity_new2' AND is_deleted = 0"
        ).executeQuery();
        assertTrue(rs.next(), "CDC-inserted row 'integrity_new2' should exist");
        assertEquals(1002, rs.getInt("value"));

        // Verify ddl_test was not affected by snapshot_test DML
        int ddlCount = getClickHouseRowCount(writer, "ddl_test");
        assertEquals(2, ddlCount,
                "ddl_test should still have 2 rows (unaffected by snapshot_test DML)");

        mysqlConn.close();
        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }
}

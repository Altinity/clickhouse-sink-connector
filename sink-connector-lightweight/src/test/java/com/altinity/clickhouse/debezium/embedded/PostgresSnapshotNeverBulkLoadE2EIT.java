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
import org.testcontainers.Testcontainers;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
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
import static com.altinity.clickhouse.debezium.embedded.PostgresProperties.getDefaultProperties;
import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E integration test for PostgreSQL {@code snapshot.mode=never} with bulk load.
 *
 * <p>Simulates the production workflow where:
 * <ol>
 *   <li>The connector starts in CDC-only mode (no initial snapshot)</li>
 *   <li>A Python bulk load script loads initial data from PostgreSQL → ClickHouse</li>
 *   <li>CDC captures ongoing INSERT/UPDATE/DELETE changes</li>
 * </ol>
 *
 * <p>The Python bulk load step is simulated by reading data from PostgreSQL via JDBC
 * and inserting it into ClickHouse via JDBC, which mimics what
 * {@code postgres_dumper.py} does in production.
 *
 * <p>Uses {@code init_postgres_all_types.sql} which creates three tables:
 * {@code all_types_test}, {@code snapshot_test}, {@code snapshot_secondary}.
 *
 * <p>Containers are started once per test class via {@code @BeforeAll} /
 * {@code @AfterAll} to avoid Podman socket exhaustion.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PostgresSnapshotNeverBulkLoadE2EIT {

    private static final Logger log = LoggerFactory.getLogger(PostgresSnapshotNeverBulkLoadE2EIT.class);

    private static final DockerImageName PG_IMAGE =
            DockerImageName.parse("debezium/postgres:15-alpine").asCompatibleSubstituteFor("postgres");

    /** Max seconds to poll for CDC convergence. */
    private static final int CDC_POLL_TIMEOUT_SECONDS = 30;

    private Network network;
    private ClickHouseContainer clickHouseContainer;
    private PostgreSQLContainer<?> postgreSQLContainer;

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

        postgreSQLContainer = new PostgreSQLContainer<>(PG_IMAGE)
                .withInitScript("init_postgres_all_types.sql")
                .withDatabaseName("public")
                .withUsername("root")
                .withPassword("root")
                .withExposedPorts(5432)
                .withCommand("postgres -c wal_level=logical")
                .withNetworkAliases("postgres")
                .withAccessToHost(true)
                .withNetwork(network);

        clickHouseContainer.start();
        Thread.sleep(3_000);
        postgreSQLContainer.start();

        Thread.sleep(10_000);
        Testcontainers.exposeHostPorts(postgreSQLContainer.getFirstMappedPort());
    }

    @AfterAll
    void stopContainers() {
        HikariDbSource.close();
        if (postgreSQLContainer != null) postgreSQLContainer.stop();
        if (clickHouseContainer != null) clickHouseContainer.stop();
        if (network != null) {
            try { network.close(); } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Properties — snapshot.mode=never
    // -------------------------------------------------------------------------

    private Properties getProperties() throws Exception {
        Properties properties = getDefaultProperties(postgreSQLContainer, clickHouseContainer);
        properties.put("plugin.name", "pgoutput");
        properties.put("plugin.path", "/");
        properties.put("table.include.list",
                "public.snapshot_test,public.snapshot_secondary");
        properties.put("slot.max.retries", "6");
        properties.put("slot.retry.delay.ms", "5000");
        properties.put("database.allowPublicKeyRetrieval", "true");
        properties.put("snapshot.mode", "never");
        properties.put("skipped.operations", "none");
        properties.put("disable.drop.truncate", "false");
        return properties;
    }

    // -------------------------------------------------------------------------
    // Bulk load simulation: PostgreSQL → ClickHouse via JDBC
    // -------------------------------------------------------------------------

    /**
     * Simulates the Python bulk load step ({@code postgres_dumper.py}).
     * Creates ClickHouse tables (ReplacingMergeTree) and copies all rows
     * from PostgreSQL into ClickHouse using JDBC.
     */
    private void simulateBulkLoad(Connection pgConn, Connection chConn) throws Exception {
        Statement chStmt = chConn.createStatement();

        // Create snapshot_test table in ClickHouse (ReplacingMergeTree)
        chStmt.execute("CREATE TABLE IF NOT EXISTS public.snapshot_test (" +
                "id Int32, " +
                "name Nullable(String), " +
                "value Nullable(Int32), " +
                "created_at Nullable(DateTime64(6)), " +
                "is_deleted UInt8 DEFAULT 0, " +
                "_version UInt64 DEFAULT 0" +
                ") ENGINE = ReplacingMergeTree(_version, is_deleted) ORDER BY id");

        // Create snapshot_secondary table in ClickHouse (ReplacingMergeTree)
        chStmt.execute("CREATE TABLE IF NOT EXISTS public.snapshot_secondary (" +
                "id Int32, " +
                "description Nullable(String), " +
                "amount Nullable(Decimal(15,2)), " +
                "active Nullable(UInt8), " +
                "is_deleted UInt8 DEFAULT 0, " +
                "_version UInt64 DEFAULT 0" +
                ") ENGINE = ReplacingMergeTree(_version, is_deleted) ORDER BY id");

        // Bulk load snapshot_test: read from PG, insert into CH
        ResultSet pgRs = pgConn.prepareStatement(
                "SELECT id, name, value, created_at FROM public.snapshot_test"
        ).executeQuery();
        while (pgRs.next()) {
            PreparedStatement chInsert = chConn.prepareStatement(
                    "INSERT INTO public.snapshot_test (id, name, value, created_at, is_deleted, _version) " +
                            "VALUES (?, ?, ?, ?, 0, 1)");
            chInsert.setInt(1, pgRs.getInt("id"));
            chInsert.setString(2, pgRs.getString("name"));
            chInsert.setInt(3, pgRs.getInt("value"));
            chInsert.setTimestamp(4, pgRs.getTimestamp("created_at"));
            chInsert.execute();
        }

        // Bulk load snapshot_secondary: read from PG, insert into CH
        pgRs = pgConn.prepareStatement(
                "SELECT id, description, amount, active FROM public.snapshot_secondary"
        ).executeQuery();
        while (pgRs.next()) {
            PreparedStatement chInsert = chConn.prepareStatement(
                    "INSERT INTO public.snapshot_secondary (id, description, amount, active, is_deleted, _version) " +
                            "VALUES (?, ?, ?, ?, 0, 1)");
            chInsert.setInt(1, pgRs.getInt("id"));
            chInsert.setString(2, pgRs.getString("description"));
            chInsert.setBigDecimal(3, pgRs.getBigDecimal("amount"));
            chInsert.setInt(4, pgRs.getBoolean("active") ? 1 : 0);
            chInsert.execute();
        }

        log.info("Bulk load simulation complete — data copied from PostgreSQL to ClickHouse");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private int getClickHouseRowCount(BaseDbWriter writer, String table) throws Exception {
        ResultSet rs = writer.getConnection()
                .prepareStatement("SELECT count(*) as cnt FROM public." + table +
                        " FINAL WHERE is_deleted = 0")
                .executeQuery();
        rs.next();
        return rs.getInt("cnt");
    }

    private int getPostgresRowCount(Connection pgConn, String table) throws Exception {
        ResultSet rs = pgConn.prepareStatement("SELECT count(*) as cnt FROM public." + table)
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
    @DisplayName("E2E snapshot.mode=never — Bulk-loaded rows exist in ClickHouse before CDC starts")
    public void testBulkLoadDataPresent() throws Exception {
        // Step 1: Simulate bulk load (PG → CH)
        Connection pgConn = ITCommon.connectToPostgreSQL(postgreSQLContainer);
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");
        simulateBulkLoad(pgConn, writer.getConnection());

        // Verify bulk-loaded data in snapshot_test (5 rows)
        int chSnapshotCount = getClickHouseRowCount(writer, "snapshot_test");
        int pgSnapshotCount = getPostgresRowCount(pgConn, "snapshot_test");
        assertEquals(pgSnapshotCount, chSnapshotCount,
                "snapshot_test: CH row count (" + chSnapshotCount +
                        ") should match PG (" + pgSnapshotCount + ") after bulk load");
        assertEquals(5, chSnapshotCount, "snapshot_test should have 5 bulk-loaded rows");

        // Verify bulk-loaded data in snapshot_secondary (3 rows)
        int chSecondaryCount = getClickHouseRowCount(writer, "snapshot_secondary");
        int pgSecondaryCount = getPostgresRowCount(pgConn, "snapshot_secondary");
        assertEquals(pgSecondaryCount, chSecondaryCount,
                "snapshot_secondary: CH row count (" + chSecondaryCount +
                        ") should match PG (" + pgSecondaryCount + ") after bulk load");
        assertEquals(3, chSecondaryCount, "snapshot_secondary should have 3 bulk-loaded rows");

        // Verify specific values from snapshot_test
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM public.snapshot_test FINAL WHERE id = 1 AND is_deleted = 0"
        ).executeQuery();
        assertTrue(rs.next(), "Row id=1 should exist in snapshot_test after bulk load");
        assertEquals("row1", rs.getString("name"));
        assertEquals(100, rs.getInt("value"));

        // Verify specific values from snapshot_secondary
        rs = writer.getConnection().prepareStatement(
                "SELECT description, amount FROM public.snapshot_secondary FINAL WHERE id = 1 AND is_deleted = 0"
        ).executeQuery();
        assertTrue(rs.next(), "Row id=1 should exist in snapshot_secondary after bulk load");
        assertEquals("First entry", rs.getString("description"));

        pgConn.close();
    }

    // =========================================================================
    // Test 2: CDC INSERT after bulk load
    // =========================================================================

    @Test
    @DisplayName("E2E snapshot.mode=never — New inserts are captured via CDC after bulk load")
    public void testCdcInsertAfterBulkLoad() throws Exception {
        // Step 1: Bulk load
        Connection pgConn = ITCommon.connectToPostgreSQL(postgreSQLContainer);
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");
        simulateBulkLoad(pgConn, writer.getConnection());

        // Step 2: Start Debezium engine in CDC-only mode (snapshot.mode=never)
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

        // Step 3: Insert new rows in PostgreSQL
        pgConn.prepareStatement(
                "INSERT INTO snapshot_test (name, value) VALUES ('cdc_insert_1', 601)"
        ).execute();
        pgConn.prepareStatement(
                "INSERT INTO snapshot_test (name, value) VALUES ('cdc_insert_2', 602)"
        ).execute();

        // Step 4: Wait for CDC convergence and verify
        boolean converged = waitForRowCount(writer, "snapshot_test", 7, CDC_POLL_TIMEOUT_SECONDS);
        assertTrue(converged, "CDC inserts should bring snapshot_test to 7 rows (5 bulk + 2 CDC)");

        // Verify specific CDC row
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM public.snapshot_test FINAL " +
                        "WHERE name = 'cdc_insert_1' AND is_deleted = 0"
        ).executeQuery();
        assertTrue(rs.next(), "CDC-inserted row 'cdc_insert_1' should exist in ClickHouse");
        assertEquals("cdc_insert_1", rs.getString("name"));
        assertEquals(601, rs.getInt("value"));

        rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM public.snapshot_test FINAL " +
                        "WHERE name = 'cdc_insert_2' AND is_deleted = 0"
        ).executeQuery();
        assertTrue(rs.next(), "CDC-inserted row 'cdc_insert_2' should exist in ClickHouse");
        assertEquals(602, rs.getInt("value"));

        pgConn.close();
        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 3: CDC UPDATE after bulk load
    // =========================================================================

    @Test
    @DisplayName("E2E snapshot.mode=never — Updates are captured via CDC after bulk load")
    public void testCdcUpdateAfterBulkLoad() throws Exception {
        // Step 1: Bulk load
        Connection pgConn = ITCommon.connectToPostgreSQL(postgreSQLContainer);
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");
        simulateBulkLoad(pgConn, writer.getConnection());

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

        // Step 3: Update a row in PostgreSQL
        pgConn.prepareStatement(
                "UPDATE snapshot_test SET name = 'row1_updated', value = 999 WHERE id = 1"
        ).execute();

        // Step 4: Wait for the update to appear in ClickHouse
        boolean found = waitForCondition(writer.getConnection(),
                "SELECT 1 FROM public.snapshot_test FINAL " +
                        "WHERE id = 1 AND name = 'row1_updated' AND is_deleted = 0",
                CDC_POLL_TIMEOUT_SECONDS);
        assertTrue(found, "Updated row (id=1) should reflect name='row1_updated' in ClickHouse");

        // Verify the updated value
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM public.snapshot_test FINAL " +
                        "WHERE id = 1 AND is_deleted = 0"
        ).executeQuery();
        assertTrue(rs.next(), "Row id=1 should exist after update");
        assertEquals("row1_updated", rs.getString("name"));
        assertEquals(999, rs.getInt("value"));

        // Row count should remain the same (5 rows — update, not insert)
        int count = getClickHouseRowCount(writer, "snapshot_test");
        assertEquals(5, count, "Row count should remain 5 after update (no new rows)");

        pgConn.close();
        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 4: CDC DELETE after bulk load (soft delete with is_deleted=1)
    // =========================================================================

    @Test
    @DisplayName("E2E snapshot.mode=never — Deletes are captured via CDC as soft deletes (is_deleted=1)")
    public void testCdcDeleteAfterBulkLoad() throws Exception {
        // Step 1: Bulk load
        Connection pgConn = ITCommon.connectToPostgreSQL(postgreSQLContainer);
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");
        simulateBulkLoad(pgConn, writer.getConnection());

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

        // Step 3: Delete a row in PostgreSQL
        pgConn.prepareStatement(
                "DELETE FROM snapshot_test WHERE id = 5"
        ).execute();

        // Step 4: Wait for the soft delete to appear in ClickHouse
        // After deletion, the row should have is_deleted=1 in ClickHouse
        boolean found = waitForCondition(writer.getConnection(),
                "SELECT 1 FROM public.snapshot_test FINAL WHERE id = 5 AND is_deleted = 1",
                CDC_POLL_TIMEOUT_SECONDS);
        assertTrue(found, "Deleted row (id=5) should have is_deleted=1 in ClickHouse");

        // Active row count should be 4 (5 - 1 deleted)
        int activeCount = getClickHouseRowCount(writer, "snapshot_test");
        assertEquals(4, activeCount,
                "Active row count should be 4 after deleting id=5 (5 bulk - 1 delete)");

        pgConn.close();
        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 5: Combined data integrity after bulk load + CDC operations
    // =========================================================================

    @Test
    @DisplayName("E2E snapshot.mode=never — Combined data integrity after bulk load + CDC INSERT/UPDATE/DELETE")
    public void testBulkLoadAndCdcDataIntegrity() throws Exception {
        // Step 1: Bulk load
        Connection pgConn = ITCommon.connectToPostgreSQL(postgreSQLContainer);
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");
        simulateBulkLoad(pgConn, writer.getConnection());

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

        // Step 3: Perform mixed DML operations on PostgreSQL
        // INSERT two new rows
        pgConn.prepareStatement(
                "INSERT INTO snapshot_test (name, value) VALUES ('integrity_new1', 1001)"
        ).execute();
        pgConn.prepareStatement(
                "INSERT INTO snapshot_test (name, value) VALUES ('integrity_new2', 1002)"
        ).execute();

        // UPDATE an existing bulk-loaded row
        pgConn.prepareStatement(
                "UPDATE snapshot_test SET name = 'row2_modified', value = 222 WHERE id = 2"
        ).execute();

        // DELETE an existing bulk-loaded row
        pgConn.prepareStatement(
                "DELETE FROM snapshot_test WHERE id = 4"
        ).execute();

        // Step 4: Wait for CDC convergence
        // Expected: 5 bulk + 2 inserts - 1 delete = 6 active rows
        boolean converged = waitForRowCount(writer, "snapshot_test", 6, CDC_POLL_TIMEOUT_SECONDS);
        assertTrue(converged,
                "After bulk load + 2 inserts + 1 update + 1 delete, should have 6 active rows");

        // Step 5: Verify individual row states

        // Original unchanged rows (id=1, id=3, id=5) should be intact
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM public.snapshot_test FINAL " +
                        "WHERE id = 1 AND is_deleted = 0"
        ).executeQuery();
        assertTrue(rs.next(), "Unchanged row id=1 should still exist");
        assertEquals("row1", rs.getString("name"));
        assertEquals(100, rs.getInt("value"));

        rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM public.snapshot_test FINAL " +
                        "WHERE id = 3 AND is_deleted = 0"
        ).executeQuery();
        assertTrue(rs.next(), "Unchanged row id=3 should still exist");
        assertEquals("row3", rs.getString("name"));
        assertEquals(300, rs.getInt("value"));

        // Updated row (id=2) should reflect new values
        rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM public.snapshot_test FINAL " +
                        "WHERE id = 2 AND is_deleted = 0"
        ).executeQuery();
        assertTrue(rs.next(), "Updated row id=2 should exist");
        assertEquals("row2_modified", rs.getString("name"));
        assertEquals(222, rs.getInt("value"));

        // Deleted row (id=4) should be soft-deleted
        rs = writer.getConnection().prepareStatement(
                "SELECT is_deleted FROM public.snapshot_test FINAL WHERE id = 4"
        ).executeQuery();
        assertTrue(rs.next(), "Deleted row id=4 should still be in table with is_deleted=1");
        assertEquals(1, rs.getInt("is_deleted"));

        // CDC-inserted rows should exist
        rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM public.snapshot_test FINAL " +
                        "WHERE name = 'integrity_new1' AND is_deleted = 0"
        ).executeQuery();
        assertTrue(rs.next(), "CDC-inserted row 'integrity_new1' should exist");
        assertEquals(1001, rs.getInt("value"));

        rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM public.snapshot_test FINAL " +
                        "WHERE name = 'integrity_new2' AND is_deleted = 0"
        ).executeQuery();
        assertTrue(rs.next(), "CDC-inserted row 'integrity_new2' should exist");
        assertEquals(1002, rs.getInt("value"));

        // Verify snapshot_secondary was not affected by snapshot_test DML
        int secCount = getClickHouseRowCount(writer, "snapshot_secondary");
        assertEquals(3, secCount,
                "snapshot_secondary should still have 3 rows (unaffected by snapshot_test DML)");

        pgConn.close();
        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }
}

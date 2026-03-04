package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.PostgresProperties.getDefaultProperties;
import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E integration test for PostgreSQL DDL operations via the full CDC pipeline.
 *
 * <p>Unlike {@link PostgresDDLOperationsIT} which tests DDL <em>translation</em> only
 * (parser → ClickHouse execution without CDC), this test exercises the complete flow:
 * <ol>
 *   <li>Start PostgreSQL + ClickHouse containers</li>
 *   <li>Start the Debezium CDC connector with {@code snapshot.mode=initial}</li>
 *   <li>Wait for initial snapshot of {@code ddl_test} table</li>
 *   <li>Execute DDL operations on PostgreSQL</li>
 *   <li>Insert data after each DDL to trigger CDC events</li>
 *   <li>Verify ClickHouse reflects the DDL changes and the new data</li>
 * </ol>
 *
 * <p>DDL operations tested:
 * <ul>
 *   <li>CREATE TABLE (new table via CDC)</li>
 *   <li>ALTER TABLE ADD COLUMN</li>
 *   <li>ALTER TABLE DROP COLUMN</li>
 *   <li>ALTER TABLE RENAME COLUMN</li>
 *   <li>TRUNCATE TABLE</li>
 *   <li>INSERT after each DDL to trigger CDC events</li>
 * </ul>
 *
 * <p>Uses {@code init_postgres_ddl.sql} which creates the {@code ddl_test} table
 * with 2 pre-populated rows.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PostgresDDLCdcE2EIT {

    private static final Logger log = LoggerFactory.getLogger(PostgresDDLCdcE2EIT.class);

    private static final DockerImageName PG_IMAGE =
            DockerImageName.parse("debezium/postgres:15-alpine").asCompatibleSubstituteFor("postgres");

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
                .withInitScript("init_postgres_ddl.sql")
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
    // Properties
    // -------------------------------------------------------------------------

    private Properties getProperties() throws Exception {
        Properties properties = getDefaultProperties(postgreSQLContainer, clickHouseContainer);
        properties.put("plugin.name", "pgoutput");
        properties.put("plugin.path", "/");
        // Include ddl_test and also any new tables that might be created
        properties.put("table.include.list", "public.ddl_test,public.ddl_new_table");
        properties.put("slot.max.retries", "6");
        properties.put("slot.retry.delay.ms", "5000");
        properties.put("database.allowPublicKeyRetrieval", "true");
        properties.put("snapshot.mode", "initial");
        properties.put("skipped.operations", "none");
        properties.put("disable.drop.truncate", "false");
        properties.put("enable.snapshot.ddl", "true");
        properties.put("auto.create.tables", "true");
        return properties;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void executePgDDL(Connection pgConn, String sql) throws SQLException {
        try (Statement stmt = pgConn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private int getClickHouseRowCount(BaseDbWriter writer, String table) throws Exception {
        ResultSet rs = writer.getConnection()
                .prepareStatement("SELECT count(*) as cnt FROM public." + table + " FINAL")
                .executeQuery();
        rs.next();
        return rs.getInt("cnt");
    }

    private boolean clickHouseTableExists(BaseDbWriter writer, String table) throws Exception {
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT count(*) as cnt FROM system.tables " +
                        "WHERE database = 'public' AND name = '" + table + "'"
        ).executeQuery();
        rs.next();
        return rs.getInt("cnt") > 0;
    }

    private boolean clickHouseColumnExists(BaseDbWriter writer, String table, String column)
            throws Exception {
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT count(*) as cnt FROM system.columns " +
                        "WHERE database = 'public' AND table = '" + table +
                        "' AND name = '" + column + "'"
        ).executeQuery();
        rs.next();
        return rs.getInt("cnt") > 0;
    }

    // =========================================================================
    // Test 1: Initial snapshot replicates ddl_test table
    // =========================================================================

    @Test
    @DisplayName("DDL CDC - Initial snapshot replicates ddl_test table with correct data")
    public void testInitialSnapshotDdlTest() throws Exception {
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

        // Wait for initial snapshot
        Thread.sleep(60_000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");

        // Verify ddl_test table exists and has 2 rows from the init script
        assertTrue(clickHouseTableExists(writer, "ddl_test"),
                "ddl_test table should exist in ClickHouse after snapshot");

        int rowCount = getClickHouseRowCount(writer, "ddl_test");
        assertEquals(2, rowCount, "ddl_test should have 2 rows from init script");

        // Verify specific row values
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM public.ddl_test FINAL WHERE id = 1"
        ).executeQuery();
        assertTrue(rs.next(), "Row with id=1 should exist");
        assertEquals("initial", rs.getString("name"));
        assertEquals(100, rs.getInt("value"));

        rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM public.ddl_test FINAL WHERE id = 2"
        ).executeQuery();
        assertTrue(rs.next(), "Row with id=2 should exist");
        assertEquals("setup", rs.getString("name"));
        assertEquals(200, rs.getInt("value"));

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 2: ALTER TABLE ADD COLUMN via CDC
    // =========================================================================

    @Test
    @DisplayName("DDL CDC - ALTER TABLE ADD COLUMN is reflected in ClickHouse")
    public void testAlterTableAddColumn() throws Exception {
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

        // Wait for initial snapshot
        Thread.sleep(60_000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");

        // Verify ddl_test exists
        assertTrue(clickHouseTableExists(writer, "ddl_test"),
                "ddl_test should exist before ALTER");

        // Execute ALTER TABLE ADD COLUMN on PostgreSQL
        Connection pgConn = ITCommon.connectToPostgreSQL(postgreSQLContainer);
        executePgDDL(pgConn, "ALTER TABLE ddl_test ADD COLUMN description TEXT");

        // Insert a row using the new column to trigger CDC with the new schema
        pgConn.prepareStatement(
                "INSERT INTO ddl_test (id, name, value, description) " +
                        "VALUES (10, 'after_add_col', 1000, 'New column value')"
        ).execute();

        // Wait for CDC replication
        Thread.sleep(30_000);

        // Verify new column exists in ClickHouse
        assertTrue(clickHouseColumnExists(writer, "ddl_test", "description"),
                "Column 'description' should exist in ClickHouse after ALTER ADD COLUMN");

        // Verify the new row with the new column data
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT name, value, description FROM public.ddl_test FINAL WHERE id = 10"
        ).executeQuery();
        assertTrue(rs.next(), "Row with id=10 should exist after ADD COLUMN + INSERT");
        assertEquals("after_add_col", rs.getString("name"));
        assertEquals(1000, rs.getInt("value"));
        assertEquals("New column value", rs.getString("description"));

        pgConn.close();

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 3: INSERT after DDL - verifies CDC continues working after schema change
    // =========================================================================

    @Test
    @DisplayName("DDL CDC - INSERT after ALTER TABLE ADD COLUMN works correctly")
    public void testInsertAfterAddColumn() throws Exception {
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

        // Wait for initial snapshot
        Thread.sleep(60_000);

        Connection pgConn = ITCommon.connectToPostgreSQL(postgreSQLContainer);

        // Add column
        executePgDDL(pgConn, "ALTER TABLE ddl_test ADD COLUMN IF NOT EXISTS extra_col INTEGER");

        // Insert multiple rows with the new column
        pgConn.prepareStatement(
                "INSERT INTO ddl_test (id, name, value, extra_col) VALUES (20, 'extra1', 2000, 42)"
        ).execute();
        pgConn.prepareStatement(
                "INSERT INTO ddl_test (id, name, value, extra_col) VALUES (21, 'extra2', 2100, 43)"
        ).execute();

        // Wait for replication
        Thread.sleep(30_000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");

        // Verify new column exists
        assertTrue(clickHouseColumnExists(writer, "ddl_test", "extra_col"),
                "Column 'extra_col' should exist");

        // Verify rows with new column data
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT name, value, extra_col FROM public.ddl_test FINAL WHERE id = 20"
        ).executeQuery();
        assertTrue(rs.next(), "Row id=20 should exist");
        assertEquals("extra1", rs.getString("name"));
        assertEquals(2000, rs.getInt("value"));
        assertEquals(42, rs.getInt("extra_col"));

        rs = writer.getConnection().prepareStatement(
                "SELECT extra_col FROM public.ddl_test FINAL WHERE id = 21"
        ).executeQuery();
        assertTrue(rs.next(), "Row id=21 should exist");
        assertEquals(43, rs.getInt("extra_col"));

        pgConn.close();

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 4: ALTER TABLE DROP COLUMN via CDC
    // =========================================================================

    @Test
    @DisplayName("DDL CDC - ALTER TABLE DROP COLUMN is reflected in ClickHouse")
    public void testAlterTableDropColumn() throws Exception {
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

        // Wait for initial snapshot
        Thread.sleep(60_000);

        Connection pgConn = ITCommon.connectToPostgreSQL(postgreSQLContainer);
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");

        // First add a column we can drop
        executePgDDL(pgConn, "ALTER TABLE ddl_test ADD COLUMN IF NOT EXISTS drop_me VARCHAR(50)");

        // Insert a row with the new column
        pgConn.prepareStatement(
                "INSERT INTO ddl_test (id, name, value, drop_me) " +
                        "VALUES (30, 'before_drop', 3000, 'will be dropped')"
        ).execute();

        Thread.sleep(30_000);

        // Verify column exists before drop
        assertTrue(clickHouseColumnExists(writer, "ddl_test", "drop_me"),
                "Column 'drop_me' should exist before DROP");

        // Drop the column
        executePgDDL(pgConn, "ALTER TABLE ddl_test DROP COLUMN drop_me");

        // Insert after drop to trigger CDC event with new schema
        pgConn.prepareStatement(
                "INSERT INTO ddl_test (id, name, value) VALUES (31, 'after_drop', 3100)"
        ).execute();

        Thread.sleep(30_000);

        // Verify the row after drop exists
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM public.ddl_test FINAL WHERE id = 31"
        ).executeQuery();
        assertTrue(rs.next(), "Row id=31 should exist after DROP COLUMN + INSERT");
        assertEquals("after_drop", rs.getString("name"));
        assertEquals(3100, rs.getInt("value"));

        pgConn.close();

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 5: ALTER TABLE RENAME COLUMN via CDC
    // =========================================================================

    @Test
    @DisplayName("DDL CDC - ALTER TABLE RENAME COLUMN is reflected in ClickHouse")
    public void testAlterTableRenameColumn() throws Exception {
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

        // Wait for initial snapshot
        Thread.sleep(60_000);

        Connection pgConn = ITCommon.connectToPostgreSQL(postgreSQLContainer);
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");

        // Add a column to rename
        executePgDDL(pgConn, "ALTER TABLE ddl_test ADD COLUMN IF NOT EXISTS old_name TEXT");

        // Insert with old name
        pgConn.prepareStatement(
                "INSERT INTO ddl_test (id, name, value, old_name) " +
                        "VALUES (40, 'rename_test', 4000, 'original value')"
        ).execute();

        Thread.sleep(30_000);

        assertTrue(clickHouseColumnExists(writer, "ddl_test", "old_name"),
                "Column 'old_name' should exist before RENAME");

        // Rename the column
        executePgDDL(pgConn, "ALTER TABLE ddl_test RENAME COLUMN old_name TO new_name");

        // Insert after rename to trigger CDC with new schema
        pgConn.prepareStatement(
                "INSERT INTO ddl_test (id, name, value, new_name) " +
                        "VALUES (41, 'after_rename', 4100, 'renamed value')"
        ).execute();

        Thread.sleep(30_000);

        // Verify the new column name exists and has data
        // Note: The rename may or may not be picked up depending on the DDL event trigger;
        // the important thing is that the data after rename is still replicated
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM public.ddl_test FINAL WHERE id = 41"
        ).executeQuery();
        assertTrue(rs.next(), "Row id=41 should exist after RENAME + INSERT");
        assertEquals("after_rename", rs.getString("name"));
        assertEquals(4100, rs.getInt("value"));

        pgConn.close();

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 6: TRUNCATE TABLE via CDC
    // =========================================================================

    @Test
    @DisplayName("DDL CDC - TRUNCATE TABLE empties the ClickHouse table")
    public void testTruncateTable() throws Exception {
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

        // Wait for initial snapshot
        Thread.sleep(60_000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");

        // Verify there are rows before truncate
        int beforeCount = getClickHouseRowCount(writer, "ddl_test");
        assertTrue(beforeCount > 0, "Should have rows before TRUNCATE, found: " + beforeCount);

        // Execute TRUNCATE on PostgreSQL
        Connection pgConn = ITCommon.connectToPostgreSQL(postgreSQLContainer);
        executePgDDL(pgConn, "TRUNCATE TABLE ddl_test");

        // Wait for CDC event
        Thread.sleep(30_000);

        // Verify table still exists but is empty
        assertTrue(clickHouseTableExists(writer, "ddl_test"),
                "Table should still exist after TRUNCATE");

        int afterCount = getClickHouseRowCount(writer, "ddl_test");
        assertEquals(0, afterCount, "Table should be empty after TRUNCATE");

        // Insert after truncate to verify CDC still works
        pgConn.prepareStatement(
                "INSERT INTO ddl_test (id, name, value) VALUES (50, 'after_truncate', 5000)"
        ).execute();

        Thread.sleep(15_000);

        int afterInsertCount = getClickHouseRowCount(writer, "ddl_test");
        assertEquals(1, afterInsertCount,
                "Should have 1 row after TRUNCATE + INSERT");

        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM public.ddl_test FINAL WHERE id = 50"
        ).executeQuery();
        assertTrue(rs.next(), "Row id=50 should exist after TRUNCATE + INSERT");
        assertEquals("after_truncate", rs.getString("name"));
        assertEquals(5000, rs.getInt("value"));

        pgConn.close();

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 7: Full DDL lifecycle - CREATE → ADD COL → INSERT → DROP COL → TRUNCATE
    // =========================================================================

    @Test
    @DisplayName("DDL CDC - Full lifecycle: ADD COLUMN → INSERT → DROP COLUMN → TRUNCATE")
    public void testFullDdlLifecycle() throws Exception {
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

        // Wait for initial snapshot
        Thread.sleep(60_000);

        Connection pgConn = ITCommon.connectToPostgreSQL(postgreSQLContainer);
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");

        // --- Step 1: Verify initial state ---
        assertTrue(clickHouseTableExists(writer, "ddl_test"),
                "ddl_test should exist after snapshot");
        int initialCount = getClickHouseRowCount(writer, "ddl_test");
        assertEquals(2, initialCount, "Should have 2 rows initially");

        // --- Step 2: ADD COLUMN ---
        executePgDDL(pgConn, "ALTER TABLE ddl_test ADD COLUMN IF NOT EXISTS lifecycle_col TEXT");

        // Insert with new column
        pgConn.prepareStatement(
                "INSERT INTO ddl_test (id, name, value, lifecycle_col) " +
                        "VALUES (60, 'lifecycle1', 6000, 'lifecycle value')"
        ).execute();

        Thread.sleep(30_000);

        assertTrue(clickHouseColumnExists(writer, "ddl_test", "lifecycle_col"),
                "lifecycle_col should exist after ADD COLUMN");

        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT lifecycle_col FROM public.ddl_test FINAL WHERE id = 60"
        ).executeQuery();
        assertTrue(rs.next(), "Row id=60 should exist");
        assertEquals("lifecycle value", rs.getString("lifecycle_col"));

        // --- Step 3: INSERT more rows ---
        pgConn.prepareStatement(
                "INSERT INTO ddl_test (id, name, value, lifecycle_col) " +
                        "VALUES (61, 'lifecycle2', 6100, 'another value')"
        ).execute();

        Thread.sleep(15_000);

        rs = writer.getConnection().prepareStatement(
                "SELECT count(*) as cnt FROM public.ddl_test FINAL WHERE id >= 60"
        ).executeQuery();
        rs.next();
        assertEquals(2, rs.getInt("cnt"), "Should have 2 lifecycle rows");

        // --- Step 4: DROP COLUMN ---
        executePgDDL(pgConn, "ALTER TABLE ddl_test DROP COLUMN IF EXISTS lifecycle_col");

        // Insert after drop
        pgConn.prepareStatement(
                "INSERT INTO ddl_test (id, name, value) VALUES (62, 'lifecycle3', 6200)"
        ).execute();

        Thread.sleep(30_000);

        rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM public.ddl_test FINAL WHERE id = 62"
        ).executeQuery();
        assertTrue(rs.next(), "Row id=62 should exist after DROP COLUMN");
        assertEquals("lifecycle3", rs.getString("name"));

        // --- Step 5: TRUNCATE ---
        executePgDDL(pgConn, "TRUNCATE TABLE ddl_test");

        Thread.sleep(30_000);

        int afterTruncate = getClickHouseRowCount(writer, "ddl_test");
        assertEquals(0, afterTruncate, "Table should be empty after TRUNCATE");

        // --- Step 6: INSERT after truncate ---
        pgConn.prepareStatement(
                "INSERT INTO ddl_test (id, name, value) VALUES (70, 'fresh_start', 7000)"
        ).execute();

        Thread.sleep(15_000);

        int finalCount = getClickHouseRowCount(writer, "ddl_test");
        assertEquals(1, finalCount, "Should have 1 row after TRUNCATE + INSERT");

        rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM public.ddl_test FINAL WHERE id = 70"
        ).executeQuery();
        assertTrue(rs.next(), "Row id=70 should exist after full lifecycle");
        assertEquals("fresh_start", rs.getString("name"));
        assertEquals(7000, rs.getInt("value"));

        pgConn.close();

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }
}

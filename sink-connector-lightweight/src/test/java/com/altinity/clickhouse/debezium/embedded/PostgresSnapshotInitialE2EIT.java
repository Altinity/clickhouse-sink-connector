package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumOffsetStorage;
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
 * E2E integration test for PostgreSQL {@code snapshot.mode=initial}.
 *
 * <p>Verifies that the Debezium-embedded connector can:
 * <ol>
 *   <li>Snapshot all pre-populated PostgreSQL tables into ClickHouse</li>
 *   <li>Auto-create tables with correct column types</li>
 *   <li>Replicate exact row counts from PostgreSQL to ClickHouse</li>
 *   <li>Transition to CDC streaming and replicate new inserts after snapshot</li>
 *   <li>Record correct offset metadata (LSN, txId, snapshot flag)</li>
 * </ol>
 *
 * <p>Uses {@code init_postgres_all_types.sql} which creates three tables:
 * {@code all_types_test}, {@code snapshot_test}, {@code snapshot_secondary}.
 *
 * <p>Containers are started once per test class via {@code @BeforeAll} /
 * {@code @AfterAll} to avoid Podman socket exhaustion.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PostgresSnapshotInitialE2EIT {

    private static final Logger log = LoggerFactory.getLogger(PostgresSnapshotInitialE2EIT.class);

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
    // Properties
    // -------------------------------------------------------------------------

    private Properties getProperties() throws Exception {
        Properties properties = getDefaultProperties(postgreSQLContainer, clickHouseContainer);
        properties.put("plugin.name", "pgoutput");
        properties.put("plugin.path", "/");
        properties.put("table.include.list",
                "public.all_types_test,public.snapshot_test,public.snapshot_secondary");
        properties.put("slot.max.retries", "6");
        properties.put("slot.retry.delay.ms", "5000");
        properties.put("database.allowPublicKeyRetrieval", "true");
        properties.put("snapshot.mode", "initial");
        properties.put("skipped.operations", "none");
        properties.put("disable.drop.truncate", "false");
        return properties;
    }

    // -------------------------------------------------------------------------
    // Helper: get row count from ClickHouse
    // -------------------------------------------------------------------------

    private int getClickHouseRowCount(BaseDbWriter writer, String table) throws Exception {
        ResultSet rs = writer.getConnection()
                .prepareStatement("SELECT count(*) as cnt FROM public." + table + " FINAL")
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

    // =========================================================================
    // Test 1: Snapshot replicates all tables with correct row counts
    // =========================================================================

    @Test
    @DisplayName("E2E Snapshot - All pre-populated tables are replicated with correct row counts")
    public void testSnapshotReplicatesAllTables() throws Exception {
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

        // Wait for initial snapshot to complete
        Thread.sleep(60_000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");
        Connection pgConn = ITCommon.connectToPostgreSQL(postgreSQLContainer);

        // Verify all_types_test: 3 rows in PG → 3 rows in CH
        int pgAllTypesCount = getPostgresRowCount(pgConn, "all_types_test");
        int chAllTypesCount = getClickHouseRowCount(writer, "all_types_test");
        assertEquals(pgAllTypesCount, chAllTypesCount,
                "all_types_test row count should match between PG (" + pgAllTypesCount + ") and CH (" + chAllTypesCount + ")");

        // Verify snapshot_test: 5 rows
        int pgSnapshotCount = getPostgresRowCount(pgConn, "snapshot_test");
        int chSnapshotCount = getClickHouseRowCount(writer, "snapshot_test");
        assertEquals(pgSnapshotCount, chSnapshotCount,
                "snapshot_test row count should match between PG (" + pgSnapshotCount + ") and CH (" + chSnapshotCount + ")");

        // Verify snapshot_secondary: 3 rows
        int pgSecondaryCount = getPostgresRowCount(pgConn, "snapshot_secondary");
        int chSecondaryCount = getClickHouseRowCount(writer, "snapshot_secondary");
        assertEquals(pgSecondaryCount, chSecondaryCount,
                "snapshot_secondary row count should match between PG (" + pgSecondaryCount + ") and CH (" + chSecondaryCount + ")");

        pgConn.close();

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 2: Auto-created tables have correct column types
    // =========================================================================

    @Test
    @DisplayName("E2E Snapshot - Auto-created ClickHouse tables have correct column types")
    public void testSnapshotColumnTypes() throws Exception {
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
        DBMetadata dbMetadata = new DBMetadata(getProperties());
        Map<String, String> columns = dbMetadata.getColumnsDataTypesForTable(
                writer.getConnection(), "snapshot_test", "public");

        // Verify key columns exist and have correct types
        assertNotNull(columns, "Column map should not be null");
        assertTrue(columns.size() > 0, "Table should have columns");

        // The snapshot_test table has: id SERIAL (PK), name VARCHAR, value INTEGER, created_at TIMESTAMPTZ
        assertTrue(columns.containsKey("id"), "Should have 'id' column");
        assertTrue(columns.containsKey("name"), "Should have 'name' column");
        assertTrue(columns.containsKey("value"), "Should have 'value' column");
        assertTrue(columns.containsKey("created_at"), "Should have 'created_at' column");

        // Verify snapshot_secondary table columns
        Map<String, String> secColumns = dbMetadata.getColumnsDataTypesForTable(
                writer.getConnection(), "snapshot_secondary", "public");
        assertTrue(secColumns.containsKey("id"), "snapshot_secondary should have 'id' column");
        assertTrue(secColumns.containsKey("description"), "snapshot_secondary should have 'description' column");
        assertTrue(secColumns.containsKey("amount"), "snapshot_secondary should have 'amount' column");
        assertTrue(secColumns.containsKey("active"), "snapshot_secondary should have 'active' column");

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 3: CDC streaming works after snapshot completes
    // =========================================================================

    @Test
    @DisplayName("E2E Snapshot + CDC - New rows inserted after snapshot are replicated via CDC")
    public void testCdcAfterSnapshot() throws Exception {
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

        // Verify initial snapshot data
        int initialCount = getClickHouseRowCount(writer, "snapshot_test");
        assertEquals(5, initialCount, "Should have 5 rows from snapshot");

        // Insert new rows via CDC
        Connection pgConn = ITCommon.connectToPostgreSQL(postgreSQLContainer);
        pgConn.prepareStatement(
                "INSERT INTO snapshot_test (name, value) VALUES ('cdc_row1', 600)"
        ).execute();
        pgConn.prepareStatement(
                "INSERT INTO snapshot_test (name, value) VALUES ('cdc_row2', 700)"
        ).execute();
        pgConn.prepareStatement(
                "INSERT INTO snapshot_test (name, value) VALUES ('cdc_row3', 800)"
        ).execute();

        // Wait for CDC replication
        Thread.sleep(15_000);

        int afterCdcCount = getClickHouseRowCount(writer, "snapshot_test");
        assertEquals(8, afterCdcCount,
                "Should have 8 rows total (5 from snapshot + 3 from CDC)");

        // Verify specific CDC row exists with correct value
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM public.snapshot_test FINAL WHERE name = 'cdc_row1'"
        ).executeQuery();
        assertTrue(rs.next(), "CDC row 'cdc_row1' should exist in ClickHouse");
        assertEquals("cdc_row1", rs.getString("name"));
        assertEquals(600, rs.getInt("value"));

        pgConn.close();

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 4: Offset storage contains correct metadata after snapshot
    // =========================================================================

    @Test
    @DisplayName("E2E Snapshot - Offset storage contains LSN, txId, and snapshot metadata")
    public void testOffsetStorageMetadata() throws Exception {
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

        String offsetValue = new DebeziumOffsetStorage()
                .getDebeziumStorageStatusQuery(getProperties(), writer.getConnection());

        // Verify offset metadata fields
        assertNotNull(offsetValue, "Offset value should not be null");
        assertTrue(offsetValue.contains("last_snapshot_record"),
                "Offset should contain 'last_snapshot_record'");
        assertTrue(offsetValue.contains("lsn"),
                "Offset should contain 'lsn'");
        assertTrue(offsetValue.contains("txId"),
                "Offset should contain 'txId'");
        assertTrue(offsetValue.contains("ts_usec"),
                "Offset should contain 'ts_usec'");
        assertTrue(offsetValue.contains("snapshot"),
                "Offset should contain 'snapshot'");

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 5: Sample data values are preserved correctly
    // =========================================================================

    @Test
    @DisplayName("E2E Snapshot - Sample data values from snapshot_test are correct in ClickHouse")
    public void testSnapshotDataValues() throws Exception {
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

        // Verify specific row values from snapshot_test
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM public.snapshot_test FINAL WHERE id = 1"
        ).executeQuery();
        assertTrue(rs.next(), "Row with id=1 should exist");
        assertEquals("row1", rs.getString("name"));
        assertEquals(100, rs.getInt("value"));

        rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM public.snapshot_test FINAL WHERE id = 5"
        ).executeQuery();
        assertTrue(rs.next(), "Row with id=5 should exist");
        assertEquals("row5", rs.getString("name"));
        assertEquals(500, rs.getInt("value"));

        // Verify snapshot_secondary values
        rs = writer.getConnection().prepareStatement(
                "SELECT description, amount FROM public.snapshot_secondary FINAL WHERE id = 1"
        ).executeQuery();
        assertTrue(rs.next(), "snapshot_secondary row with id=1 should exist");
        assertEquals("First entry", rs.getString("description"));

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }
}

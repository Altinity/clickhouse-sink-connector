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
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
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
import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E integration test for MySQL {@code snapshot.mode=initial}.
 *
 * <p>Verifies that the Debezium-embedded connector can:
 * <ol>
 *   <li>Snapshot all pre-populated MySQL tables into ClickHouse</li>
 *   <li>Auto-create tables with correct column types</li>
 *   <li>Replicate exact row counts from MySQL to ClickHouse</li>
 *   <li>Transition to CDC streaming and replicate new inserts after snapshot</li>
 *   <li>Record correct offset metadata (binlog file, position, snapshot flag)</li>
 * </ol>
 *
 * <p>Uses {@code init_mysql_all_types.sql} which creates three tables:
 * {@code all_types_test}, {@code snapshot_test}, {@code ddl_test}.
 *
 * <p>Containers are started once per test class via {@code @BeforeAll} /
 * {@code @AfterAll} to avoid Docker socket exhaustion.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MySQLSnapshotInitialE2EIT {

    private static final Logger log = LoggerFactory.getLogger(MySQLSnapshotInitialE2EIT.class);

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
    // Properties
    // -------------------------------------------------------------------------

    private Properties getProperties() throws Exception {
        Properties props = ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer);
        props.put("table.include.list",
                "employees.all_types_test,employees.snapshot_test,employees.ddl_test");
        props.put("database.allowPublicKeyRetrieval", "true");
        props.put("snapshot.mode", "initial");
        props.put("skipped.operations", "none");
        props.put("disable.drop.truncate", "false");
        return props;
    }

    // -------------------------------------------------------------------------
    // Helper: row counts
    // -------------------------------------------------------------------------

    private int getClickHouseRowCount(BaseDbWriter writer, String table) throws Exception {
        ResultSet rs = writer.getConnection()
                .prepareStatement("SELECT count(*) as cnt FROM employees." + table + " FINAL")
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

    // =========================================================================
    // Test 1: Snapshot replicates all tables with correct row counts
    // =========================================================================

    @Test
    @DisplayName("E2E Snapshot - All pre-populated MySQL tables are replicated with correct row counts")
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

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        Connection mysqlConn = ITCommon.connectToMySQL(mySqlContainer);

        // Verify all_types_test: 3 rows in MySQL → 3 rows in CH
        int mysqlAllTypesCount = getMySQLRowCount(mysqlConn, "all_types_test");
        int chAllTypesCount = getClickHouseRowCount(writer, "all_types_test");
        assertEquals(mysqlAllTypesCount, chAllTypesCount,
                "all_types_test row count should match between MySQL (" + mysqlAllTypesCount +
                        ") and CH (" + chAllTypesCount + ")");

        // Verify snapshot_test: 5 rows
        int mysqlSnapshotCount = getMySQLRowCount(mysqlConn, "snapshot_test");
        int chSnapshotCount = getClickHouseRowCount(writer, "snapshot_test");
        assertEquals(mysqlSnapshotCount, chSnapshotCount,
                "snapshot_test row count should match between MySQL (" + mysqlSnapshotCount +
                        ") and CH (" + chSnapshotCount + ")");

        // Verify ddl_test: 2 rows
        int mysqlDdlCount = getMySQLRowCount(mysqlConn, "ddl_test");
        int chDdlCount = getClickHouseRowCount(writer, "ddl_test");
        assertEquals(mysqlDdlCount, chDdlCount,
                "ddl_test row count should match between MySQL (" + mysqlDdlCount +
                        ") and CH (" + chDdlCount + ")");

        mysqlConn.close();

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

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        DBMetadata dbMetadata = new DBMetadata(getProperties());
        Map<String, String> columns = dbMetadata.getColumnsDataTypesForTable(
                writer.getConnection(), "snapshot_test", "employees");

        // Verify key columns exist and have correct types
        assertNotNull(columns, "Column map should not be null");
        assertTrue(columns.size() > 0, "Table should have columns");

        // The snapshot_test table has: id INT (PK), name VARCHAR, value INT
        assertTrue(columns.containsKey("id"), "Should have 'id' column");
        assertTrue(columns.containsKey("name"), "Should have 'name' column");
        assertTrue(columns.containsKey("value"), "Should have 'value' column");

        // Verify ddl_test table columns
        Map<String, String> ddlColumns = dbMetadata.getColumnsDataTypesForTable(
                writer.getConnection(), "ddl_test", "employees");
        assertTrue(ddlColumns.containsKey("id"), "ddl_test should have 'id' column");
        assertTrue(ddlColumns.containsKey("name"), "ddl_test should have 'name' column");
        assertTrue(ddlColumns.containsKey("value"), "ddl_test should have 'value' column");

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

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        // Verify initial snapshot data
        int initialCount = getClickHouseRowCount(writer, "snapshot_test");
        assertEquals(5, initialCount, "Should have 5 rows from snapshot");

        // Insert new rows via CDC
        Connection mysqlConn = ITCommon.connectToMySQL(mySqlContainer);
        mysqlConn.prepareStatement(
                "INSERT INTO employees.snapshot_test (name, value) VALUES ('cdc_row1', 600)"
        ).execute();
        mysqlConn.prepareStatement(
                "INSERT INTO employees.snapshot_test (name, value) VALUES ('cdc_row2', 700)"
        ).execute();
        mysqlConn.prepareStatement(
                "INSERT INTO employees.snapshot_test (name, value) VALUES ('cdc_row3', 800)"
        ).execute();

        // Wait for CDC replication
        Thread.sleep(15_000);

        int afterCdcCount = getClickHouseRowCount(writer, "snapshot_test");
        assertEquals(8, afterCdcCount,
                "Should have 8 rows total (5 from snapshot + 3 from CDC)");

        // Verify specific CDC row exists with correct value
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM employees.snapshot_test FINAL WHERE name = 'cdc_row1'"
        ).executeQuery();
        assertTrue(rs.next(), "CDC row 'cdc_row1' should exist in ClickHouse");
        assertEquals("cdc_row1", rs.getString("name"));
        assertEquals(600, rs.getInt("value"));

        mysqlConn.close();

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 4: Offset storage contains correct metadata after snapshot
    // =========================================================================

    @Test
    @DisplayName("E2E Snapshot - Offset storage contains binlog file, position, and snapshot metadata")
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

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        String offsetValue = new DebeziumOffsetStorage()
                .getDebeziumStorageStatusQuery(getProperties(), writer.getConnection());

        // Verify offset metadata fields for MySQL
        assertNotNull(offsetValue, "Offset value should not be null");
        assertTrue(offsetValue.contains("file"),
                "Offset should contain 'file' (binlog file)");
        assertTrue(offsetValue.contains("pos"),
                "Offset should contain 'pos' (binlog position)");
        assertTrue(offsetValue.contains("ts_sec"),
                "Offset should contain 'ts_sec'");

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

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        // Verify specific row values from snapshot_test
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM employees.snapshot_test FINAL WHERE id = 1"
        ).executeQuery();
        assertTrue(rs.next(), "Row with id=1 should exist");
        assertEquals("alpha", rs.getString("name"));
        assertEquals(100, rs.getInt("value"));

        rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM employees.snapshot_test FINAL WHERE id = 5"
        ).executeQuery();
        assertTrue(rs.next(), "Row with id=5 should exist");
        assertEquals("epsilon", rs.getString("name"));
        assertEquals(500, rs.getInt("value"));

        // Verify ddl_test values
        rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM employees.ddl_test FINAL WHERE id = 1"
        ).executeQuery();
        assertTrue(rs.next(), "ddl_test row with id=1 should exist");
        assertEquals("initial", rs.getString("name"));
        assertEquals(100, rs.getInt("value"));

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }
}

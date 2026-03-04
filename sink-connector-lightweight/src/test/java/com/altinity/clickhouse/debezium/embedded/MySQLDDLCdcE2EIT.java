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
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
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
import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E integration test for MySQL DDL operations via the full CDC pipeline.
 *
 * <p>Unlike the unit-level DDL parser tests (e.g. {@code AlterTableAddColumnIT}),
 * this test exercises the complete flow:
 * <ol>
 *   <li>Start MySQL + ClickHouse containers</li>
 *   <li>Start the Debezium CDC connector with {@code snapshot.mode=initial}</li>
 *   <li>Wait for initial snapshot of {@code ddl_test} table</li>
 *   <li>Execute DDL operations on MySQL</li>
 *   <li>Insert data after each DDL to trigger CDC events</li>
 *   <li>Verify ClickHouse reflects the DDL changes and the new data</li>
 * </ol>
 *
 * <p>DDL operations tested:
 * <ul>
 *   <li>CREATE TABLE (new table via CDC)</li>
 *   <li>ALTER TABLE ADD COLUMN</li>
 *   <li>ALTER TABLE MODIFY COLUMN (type change)</li>
 *   <li>ALTER TABLE DROP COLUMN</li>
 *   <li>RENAME TABLE</li>
 *   <li>TRUNCATE TABLE</li>
 *   <li>INSERT after each DDL to verify CDC continues working</li>
 * </ul>
 *
 * <p>Uses {@code init_mysql_all_types.sql} which creates the {@code ddl_test} table
 * with 2 pre-populated rows.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MySQLDDLCdcE2EIT {

    private static final Logger log = LoggerFactory.getLogger(MySQLDDLCdcE2EIT.class);

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
                "employees.ddl_test,employees.ddl_new_table,employees.ddl_test_renamed," +
                        "employees.all_types_test,employees.snapshot_test");
        props.put("database.allowPublicKeyRetrieval", "true");
        props.put("snapshot.mode", "initial");
        props.put("skipped.operations", "none");
        props.put("disable.drop.truncate", "false");
        props.put("enable.snapshot.ddl", "true");
        props.put("auto.create.tables", "true");
        return props;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void executeMySQLDDL(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private int getClickHouseRowCount(BaseDbWriter writer, String table) throws Exception {
        ResultSet rs = writer.getConnection()
                .prepareStatement("SELECT count(*) as cnt FROM employees." + table + " FINAL")
                .executeQuery();
        rs.next();
        return rs.getInt("cnt");
    }

    private boolean clickHouseTableExists(BaseDbWriter writer, String table) throws Exception {
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT count(*) as cnt FROM system.tables " +
                        "WHERE database = 'employees' AND name = '" + table + "'"
        ).executeQuery();
        rs.next();
        return rs.getInt("cnt") > 0;
    }

    private boolean clickHouseColumnExists(BaseDbWriter writer, String table, String column)
            throws Exception {
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT count(*) as cnt FROM system.columns " +
                        "WHERE database = 'employees' AND table = '" + table +
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

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        // Verify ddl_test table exists and has 2 rows from the init script
        assertTrue(clickHouseTableExists(writer, "ddl_test"),
                "ddl_test table should exist in ClickHouse after snapshot");

        int rowCount = getClickHouseRowCount(writer, "ddl_test");
        assertEquals(2, rowCount, "ddl_test should have 2 rows from init script");

        // Verify specific row values
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM employees.ddl_test FINAL WHERE id = 1"
        ).executeQuery();
        assertTrue(rs.next(), "Row with id=1 should exist");
        assertEquals("initial", rs.getString("name"));
        assertEquals(100, rs.getInt("value"));

        rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM employees.ddl_test FINAL WHERE id = 2"
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

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        // Verify ddl_test exists
        assertTrue(clickHouseTableExists(writer, "ddl_test"),
                "ddl_test should exist before ALTER");

        // Execute ALTER TABLE ADD COLUMN on MySQL
        Connection mysqlConn = ITCommon.connectToMySQL(mySqlContainer);
        executeMySQLDDL(mysqlConn, "ALTER TABLE employees.ddl_test ADD COLUMN description TEXT");

        // Insert a row using the new column to trigger CDC with the new schema
        mysqlConn.prepareStatement(
                "INSERT INTO employees.ddl_test (id, name, value, description) " +
                        "VALUES (10, 'after_add_col', 1000, 'New column value')"
        ).execute();

        // Wait for CDC replication
        Thread.sleep(30_000);

        // Verify new column exists in ClickHouse
        assertTrue(clickHouseColumnExists(writer, "ddl_test", "description"),
                "Column 'description' should exist in ClickHouse after ALTER ADD COLUMN");

        // Verify the new row with the new column data
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT name, value, description FROM employees.ddl_test FINAL WHERE id = 10"
        ).executeQuery();
        assertTrue(rs.next(), "Row with id=10 should exist after ADD COLUMN + INSERT");
        assertEquals("after_add_col", rs.getString("name"));
        assertEquals(1000, rs.getInt("value"));
        assertEquals("New column value", rs.getString("description"));

        mysqlConn.close();

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

        Connection mysqlConn = ITCommon.connectToMySQL(mySqlContainer);

        // Add column
        executeMySQLDDL(mysqlConn, "ALTER TABLE employees.ddl_test ADD COLUMN extra_col INT");

        // Insert multiple rows with the new column
        mysqlConn.prepareStatement(
                "INSERT INTO employees.ddl_test (id, name, value, extra_col) VALUES (20, 'extra1', 2000, 42)"
        ).execute();
        mysqlConn.prepareStatement(
                "INSERT INTO employees.ddl_test (id, name, value, extra_col) VALUES (21, 'extra2', 2100, 43)"
        ).execute();

        // Wait for replication
        Thread.sleep(30_000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        // Verify new column exists
        assertTrue(clickHouseColumnExists(writer, "ddl_test", "extra_col"),
                "Column 'extra_col' should exist");

        // Verify rows with new column data
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT name, value, extra_col FROM employees.ddl_test FINAL WHERE id = 20"
        ).executeQuery();
        assertTrue(rs.next(), "Row id=20 should exist");
        assertEquals("extra1", rs.getString("name"));
        assertEquals(2000, rs.getInt("value"));
        assertEquals(42, rs.getInt("extra_col"));

        rs = writer.getConnection().prepareStatement(
                "SELECT extra_col FROM employees.ddl_test FINAL WHERE id = 21"
        ).executeQuery();
        assertTrue(rs.next(), "Row id=21 should exist");
        assertEquals(43, rs.getInt("extra_col"));

        mysqlConn.close();

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 4: ALTER TABLE MODIFY COLUMN (type change) via CDC
    // =========================================================================

    @Test
    @DisplayName("DDL CDC - ALTER TABLE MODIFY COLUMN changes type in ClickHouse")
    public void testAlterTableModifyColumn() throws Exception {
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

        Connection mysqlConn = ITCommon.connectToMySQL(mySqlContainer);
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        // Add a column we can modify the type of
        executeMySQLDDL(mysqlConn, "ALTER TABLE employees.ddl_test ADD COLUMN modify_col VARCHAR(50)");

        // Insert with original type
        mysqlConn.prepareStatement(
                "INSERT INTO employees.ddl_test (id, name, value, modify_col) " +
                        "VALUES (30, 'before_modify', 3000, 'short text')"
        ).execute();

        Thread.sleep(30_000);

        // Modify the column type from VARCHAR(50) to TEXT
        executeMySQLDDL(mysqlConn, "ALTER TABLE employees.ddl_test MODIFY COLUMN modify_col TEXT");

        // Insert with new type
        mysqlConn.prepareStatement(
                "INSERT INTO employees.ddl_test (id, name, value, modify_col) " +
                        "VALUES (31, 'after_modify', 3100, 'This is a much longer text value after modify')"
        ).execute();

        Thread.sleep(30_000);

        // Verify the row after MODIFY exists
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT name, value, modify_col FROM employees.ddl_test FINAL WHERE id = 31"
        ).executeQuery();
        assertTrue(rs.next(), "Row id=31 should exist after MODIFY COLUMN + INSERT");
        assertEquals("after_modify", rs.getString("name"));
        assertEquals(3100, rs.getInt("value"));
        assertEquals("This is a much longer text value after modify", rs.getString("modify_col"));

        mysqlConn.close();

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 5: ALTER TABLE DROP COLUMN via CDC
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

        Connection mysqlConn = ITCommon.connectToMySQL(mySqlContainer);
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        // First add a column we can drop
        executeMySQLDDL(mysqlConn, "ALTER TABLE employees.ddl_test ADD COLUMN drop_me VARCHAR(50)");

        // Insert a row with the new column
        mysqlConn.prepareStatement(
                "INSERT INTO employees.ddl_test (id, name, value, drop_me) " +
                        "VALUES (40, 'before_drop', 4000, 'will be dropped')"
        ).execute();

        Thread.sleep(30_000);

        // Verify column exists before drop
        assertTrue(clickHouseColumnExists(writer, "ddl_test", "drop_me"),
                "Column 'drop_me' should exist before DROP");

        // Drop the column
        executeMySQLDDL(mysqlConn, "ALTER TABLE employees.ddl_test DROP COLUMN drop_me");

        // Insert after drop to trigger CDC event with new schema
        mysqlConn.prepareStatement(
                "INSERT INTO employees.ddl_test (id, name, value) VALUES (41, 'after_drop', 4100)"
        ).execute();

        Thread.sleep(30_000);

        // Verify the row after drop exists
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM employees.ddl_test FINAL WHERE id = 41"
        ).executeQuery();
        assertTrue(rs.next(), "Row id=41 should exist after DROP COLUMN + INSERT");
        assertEquals("after_drop", rs.getString("name"));
        assertEquals(4100, rs.getInt("value"));

        mysqlConn.close();

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 6: CREATE TABLE via CDC (new table after connector starts)
    // =========================================================================

    @Test
    @DisplayName("DDL CDC - CREATE TABLE via CDC creates new table in ClickHouse")
    public void testCreateTableViaCdc() throws Exception {
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

        Connection mysqlConn = ITCommon.connectToMySQL(mySqlContainer);
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        // Create a new table via DDL
        executeMySQLDDL(mysqlConn,
                "CREATE TABLE employees.ddl_new_table (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "name VARCHAR(100), " +
                        "score DECIMAL(10,2), " +
                        "active BOOLEAN" +
                        ") ENGINE=InnoDB");

        // Insert rows into the new table
        mysqlConn.prepareStatement(
                "INSERT INTO employees.ddl_new_table (name, score, active) " +
                        "VALUES ('new_table_row1', 95.50, true)"
        ).execute();
        mysqlConn.prepareStatement(
                "INSERT INTO employees.ddl_new_table (name, score, active) " +
                        "VALUES ('new_table_row2', 88.25, false)"
        ).execute();

        Thread.sleep(30_000);

        // Verify new table was created in ClickHouse
        assertTrue(clickHouseTableExists(writer, "ddl_new_table"),
                "ddl_new_table should exist in ClickHouse after CREATE TABLE via CDC");

        // Verify rows exist
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT count(*) as cnt FROM employees.ddl_new_table FINAL"
        ).executeQuery();
        rs.next();
        int rowCount = rs.getInt("cnt");
        assertEquals(2, rowCount, "ddl_new_table should have 2 rows");

        // Verify specific values
        rs = writer.getConnection().prepareStatement(
                "SELECT name, score FROM employees.ddl_new_table FINAL WHERE id = 1"
        ).executeQuery();
        assertTrue(rs.next(), "Row id=1 should exist in ddl_new_table");
        assertEquals("new_table_row1", rs.getString("name"));

        mysqlConn.close();

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 7: TRUNCATE TABLE via CDC
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

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        // Verify there are rows before truncate
        int beforeCount = getClickHouseRowCount(writer, "ddl_test");
        assertTrue(beforeCount > 0, "Should have rows before TRUNCATE, found: " + beforeCount);

        // Execute TRUNCATE on MySQL
        Connection mysqlConn = ITCommon.connectToMySQL(mySqlContainer);
        executeMySQLDDL(mysqlConn, "TRUNCATE TABLE employees.ddl_test");

        // Wait for CDC event
        Thread.sleep(30_000);

        // Verify table still exists but is empty
        assertTrue(clickHouseTableExists(writer, "ddl_test"),
                "Table should still exist after TRUNCATE");

        int afterCount = getClickHouseRowCount(writer, "ddl_test");
        assertEquals(0, afterCount, "Table should be empty after TRUNCATE");

        // Insert after truncate to verify CDC still works
        mysqlConn.prepareStatement(
                "INSERT INTO employees.ddl_test (id, name, value) VALUES (50, 'after_truncate', 5000)"
        ).execute();

        Thread.sleep(15_000);

        int afterInsertCount = getClickHouseRowCount(writer, "ddl_test");
        assertEquals(1, afterInsertCount,
                "Should have 1 row after TRUNCATE + INSERT");

        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM employees.ddl_test FINAL WHERE id = 50"
        ).executeQuery();
        assertTrue(rs.next(), "Row id=50 should exist after TRUNCATE + INSERT");
        assertEquals("after_truncate", rs.getString("name"));
        assertEquals(5000, rs.getInt("value"));

        mysqlConn.close();

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 8: RENAME TABLE via CDC
    // =========================================================================

    @Test
    @DisplayName("DDL CDC - RENAME TABLE is reflected in ClickHouse")
    public void testRenameTable() throws Exception {
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

        Connection mysqlConn = ITCommon.connectToMySQL(mySqlContainer);
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        // Verify ddl_test exists before rename
        assertTrue(clickHouseTableExists(writer, "ddl_test"),
                "ddl_test should exist before RENAME");

        // Rename the table
        executeMySQLDDL(mysqlConn,
                "RENAME TABLE employees.ddl_test TO employees.ddl_test_renamed");

        // Insert into the renamed table to trigger CDC
        mysqlConn.prepareStatement(
                "INSERT INTO employees.ddl_test_renamed (id, name, value) VALUES (60, 'after_rename', 6000)"
        ).execute();

        Thread.sleep(30_000);

        // Verify the renamed table exists in ClickHouse
        // Note: MySQL DDL parser handles RENAME TABLE and propagates it to ClickHouse
        assertTrue(clickHouseTableExists(writer, "ddl_test_renamed"),
                "ddl_test_renamed should exist in ClickHouse after RENAME TABLE");

        // Verify data in the renamed table
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM employees.ddl_test_renamed FINAL WHERE id = 60"
        ).executeQuery();
        assertTrue(rs.next(), "Row id=60 should exist in renamed table");
        assertEquals("after_rename", rs.getString("name"));
        assertEquals(6000, rs.getInt("value"));

        // Rename back for other tests
        executeMySQLDDL(mysqlConn,
                "RENAME TABLE employees.ddl_test_renamed TO employees.ddl_test");

        Thread.sleep(10_000);

        mysqlConn.close();

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 9: Full DDL lifecycle
    // =========================================================================

    @Test
    @DisplayName("DDL CDC - Full lifecycle: ADD COLUMN → INSERT → MODIFY → DROP COLUMN → TRUNCATE")
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

        Connection mysqlConn = ITCommon.connectToMySQL(mySqlContainer);
        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        // --- Step 1: Verify initial state ---
        assertTrue(clickHouseTableExists(writer, "ddl_test"),
                "ddl_test should exist after snapshot");
        int initialCount = getClickHouseRowCount(writer, "ddl_test");
        assertEquals(2, initialCount, "Should have 2 rows initially");

        // --- Step 2: ADD COLUMN ---
        executeMySQLDDL(mysqlConn,
                "ALTER TABLE employees.ddl_test ADD COLUMN lifecycle_col VARCHAR(200)");

        // Insert with new column
        mysqlConn.prepareStatement(
                "INSERT INTO employees.ddl_test (id, name, value, lifecycle_col) " +
                        "VALUES (70, 'lifecycle1', 7000, 'lifecycle value')"
        ).execute();

        Thread.sleep(30_000);

        assertTrue(clickHouseColumnExists(writer, "ddl_test", "lifecycle_col"),
                "lifecycle_col should exist after ADD COLUMN");

        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT lifecycle_col FROM employees.ddl_test FINAL WHERE id = 70"
        ).executeQuery();
        assertTrue(rs.next(), "Row id=70 should exist");
        assertEquals("lifecycle value", rs.getString("lifecycle_col"));

        // --- Step 3: INSERT more rows ---
        mysqlConn.prepareStatement(
                "INSERT INTO employees.ddl_test (id, name, value, lifecycle_col) " +
                        "VALUES (71, 'lifecycle2', 7100, 'another value')"
        ).execute();

        Thread.sleep(15_000);

        rs = writer.getConnection().prepareStatement(
                "SELECT count(*) as cnt FROM employees.ddl_test FINAL WHERE id >= 70"
        ).executeQuery();
        rs.next();
        assertEquals(2, rs.getInt("cnt"), "Should have 2 lifecycle rows");

        // --- Step 4: MODIFY COLUMN (change type from VARCHAR to TEXT) ---
        executeMySQLDDL(mysqlConn,
                "ALTER TABLE employees.ddl_test MODIFY COLUMN lifecycle_col TEXT");

        mysqlConn.prepareStatement(
                "INSERT INTO employees.ddl_test (id, name, value, lifecycle_col) " +
                        "VALUES (72, 'lifecycle3', 7200, 'Modified column type value - much longer text now')"
        ).execute();

        Thread.sleep(30_000);

        rs = writer.getConnection().prepareStatement(
                "SELECT lifecycle_col FROM employees.ddl_test FINAL WHERE id = 72"
        ).executeQuery();
        assertTrue(rs.next(), "Row id=72 should exist after MODIFY COLUMN");
        assertEquals("Modified column type value - much longer text now",
                rs.getString("lifecycle_col"));

        // --- Step 5: DROP COLUMN ---
        executeMySQLDDL(mysqlConn,
                "ALTER TABLE employees.ddl_test DROP COLUMN lifecycle_col");

        // Insert after drop
        mysqlConn.prepareStatement(
                "INSERT INTO employees.ddl_test (id, name, value) VALUES (73, 'lifecycle4', 7300)"
        ).execute();

        Thread.sleep(30_000);

        rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM employees.ddl_test FINAL WHERE id = 73"
        ).executeQuery();
        assertTrue(rs.next(), "Row id=73 should exist after DROP COLUMN");
        assertEquals("lifecycle4", rs.getString("name"));

        // --- Step 6: TRUNCATE ---
        executeMySQLDDL(mysqlConn, "TRUNCATE TABLE employees.ddl_test");

        Thread.sleep(30_000);

        int afterTruncate = getClickHouseRowCount(writer, "ddl_test");
        assertEquals(0, afterTruncate, "Table should be empty after TRUNCATE");

        // --- Step 7: INSERT after truncate ---
        mysqlConn.prepareStatement(
                "INSERT INTO employees.ddl_test (id, name, value) VALUES (80, 'fresh_start', 8000)"
        ).execute();

        Thread.sleep(15_000);

        int finalCount = getClickHouseRowCount(writer, "ddl_test");
        assertEquals(1, finalCount, "Should have 1 row after TRUNCATE + INSERT");

        rs = writer.getConnection().prepareStatement(
                "SELECT name, value FROM employees.ddl_test FINAL WHERE id = 80"
        ).executeQuery();
        assertTrue(rs.next(), "Row id=80 should exist after full lifecycle");
        assertEquals("fresh_start", rs.getString("name"));
        assertEquals(8000, rs.getInt("value"));

        mysqlConn.close();

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }
}

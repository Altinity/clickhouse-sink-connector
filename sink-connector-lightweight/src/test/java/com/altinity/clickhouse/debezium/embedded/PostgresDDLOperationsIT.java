package com.altinity.clickhouse.debezium.embedded;

import com.altinity.clickhouse.debezium.embedded.ddl.parser.PostgreSQLDDLParserService;
import org.junit.jupiter.api.*;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetProvider;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for PostgreSQL DDL translation and execution on ClickHouse.
 *
 * <p>These tests validate that PostgreSQL DDL statements are correctly translated
 * by {@link PostgreSQLDDLParserService} and that the resulting ClickHouse DDL
 * statements can be executed successfully on a live ClickHouse instance.</p>
 *
 * <p>Only a ClickHouse container is needed here – no PostgreSQL container is
 * required because the translation layer is tested programmatically.</p>
 *
 * <p>Environment requirements (Podman socket):
 * <pre>
 *   export DOCKER_HOST=unix:///home/user/.podman/podman.sock
 *   export TESTCONTAINERS_RYUK_DISABLED=true
 * </pre>
 * </p>
 */
@DisplayName("PostgreSQL DDL Operations Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Testcontainers
public class PostgresDDLOperationsIT {

    /** Database used in all tests – must match the ClickHouse database created below. */
    private static final String TEST_DB = "ddl_test_db";

    static ClickHouseContainer clickHouseContainer;
    static PostgreSQLDDLParserService parser;

    // -----------------------------------------------------------------------
    // Container lifecycle
    // -----------------------------------------------------------------------

    @BeforeAll
    static void setupAll() throws Exception {
        clickHouseContainer = new ClickHouseContainer(
                DockerImageName.parse(CLICKHOUSE_DOCKER_IMAGE)
                        .asCompatibleSubstituteFor("clickhouse"))
                .withUsername("ch_user")
                .withPassword("password")
                .withExposedPorts(8123);

        clickHouseContainer.start();

        // Create the test database (fresh connection, no shared state)
        executeUpdate("CREATE DATABASE IF NOT EXISTS " + TEST_DB);

        // Build the parser using the ClickHouse test database as the destination
        parser = new PostgreSQLDDLParserService(null, null, TEST_DB);
    }

    @AfterAll
    static void teardownAll() throws Exception {
        try {
            executeUpdate("DROP DATABASE IF EXISTS " + TEST_DB);
        } catch (Exception ignored) {
        }
        if (clickHouseContainer != null && clickHouseContainer.isRunning()) {
            clickHouseContainer.stop();
        }
    }

    // -----------------------------------------------------------------------
    // Helper utilities
    // -----------------------------------------------------------------------

    private static Connection openClickHouseConnection() throws SQLException {
        // socket_timeout (ms) prevents stale-connection hangs in the JDBC HTTP pool.
        String url = String.format("jdbc:clickhouse://%s:%d?socket_timeout=30000",
                clickHouseContainer.getHost(),
                clickHouseContainer.getFirstMappedPort());
        return DriverManager.getConnection(url,
                clickHouseContainer.getUsername(),
                clickHouseContainer.getPassword());
    }

    /**
     * Executes a DDL/DML statement using a <em>fresh</em> JDBC connection each time.
     *
     * <p>Using a dedicated connection per statement prevents stale-connection hangs
     * that occur when the ClickHouse server closes the TCP connection after a mutation
     * (e.g., ALTER TABLE DROP COLUMN) but the JDBC connection pool retains it as
     * reusable.  Reusing such a stale connection causes the next HTTP request to wait
     * indefinitely for a response that never arrives.</p>
     */
    private static void executeUpdate(String sql) throws SQLException {
        try (Connection conn = openClickHouseConnection();
             Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    /**
     * Executes a query using a <em>fresh</em> JDBC connection and returns results
     * as an in-memory {@link CachedRowSet} that survives connection close.
     *
     * <p>Using a dedicated connection per query (just like {@link #executeUpdate})
     * prevents stale-connection hangs: the ClickHouse server can close the TCP
     * connection after any mutation, but because we never reuse connections here
     * the pool never hands back a dead socket.</p>
     */
    private static ResultSet executeQuery(String sql) throws SQLException {
        try (Connection conn = openClickHouseConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            CachedRowSet crs = RowSetProvider.newFactory().createCachedRowSet();
            crs.populate(rs);
            return crs;
        }
    }

    /**
     * Translates a PostgreSQL DDL statement via the parser and executes each
     * resulting statement on ClickHouse.  Returns the translated SQL string for
     * assertion purposes.
     */
    private static String translateAndExecute(String pgSql) throws SQLException {
        StringBuffer buf = new StringBuffer();
        parser.parseSql(pgSql, null, buf);
        String chSql = buf.toString().trim();
        if (!chSql.isEmpty()) {
            // ClickHouse may return multiple statements split by newlines; execute each
            for (String stmt : chSql.split(";\n|;\r\n")) {
                String trimmed = stmt.trim();
                if (!trimmed.isEmpty()) {
                    executeUpdate(trimmed);
                }
            }
        }
        return chSql;
    }

    /**
     * Returns the list of column names for a table from the ClickHouse system tables.
     */
    private static List<String> getColumnNames(String tableName) throws SQLException {
        String sql = String.format(
                "SELECT name FROM system.columns WHERE database = '%s' AND table = '%s' ORDER BY position",
                TEST_DB, tableName);
        ResultSet rs = executeQuery(sql);
        List<String> cols = new ArrayList<>();
        while (rs.next()) {
            cols.add(rs.getString("name"));
        }
        return cols;
    }

    /**
     * Returns the type string for a specific column.
     */
    private static String getColumnType(String tableName, String columnName) throws SQLException {
        String sql = String.format(
                "SELECT type FROM system.columns WHERE database = '%s' AND table = '%s' AND name = '%s'",
                TEST_DB, tableName, columnName);
        ResultSet rs = executeQuery(sql);
        if (rs.next()) {
            return rs.getString("type");
        }
        return null;
    }

    /**
     * Returns true if the specified table exists in ClickHouse.
     */
    private static boolean tableExists(String tableName) throws SQLException {
        String sql = String.format(
                "SELECT count(*) as cnt FROM system.tables WHERE database = '%s' AND name = '%s'",
                TEST_DB, tableName);
        ResultSet rs = executeQuery(sql);
        return rs.next() && rs.getInt("cnt") > 0;
    }

    /**
     * Returns the full engine string for a table from system.tables.
     */
    private static String getTableEngine(String tableName) throws SQLException {
        String sql = String.format(
                "SELECT engine FROM system.tables WHERE database = '%s' AND name = '%s'",
                TEST_DB, tableName);
        ResultSet rs = executeQuery(sql);
        if (rs.next()) {
            return rs.getString("engine");
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Test 1: CREATE TABLE
    // -----------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("CREATE TABLE - translates and executes successfully on ClickHouse")
    void testCreateTableTranslationAndExecution() throws Exception {
        String pgSql = "CREATE TABLE simple_create ("
                + "id INTEGER PRIMARY KEY, "
                + "name TEXT, "
                + "score NUMERIC(10,2)"
                + ")";

        String chSql = translateAndExecute(pgSql);

        // Verify translated SQL contains expected patterns
        assertTrue(chSql.contains("CREATE TABLE IF NOT EXISTS"), "Output must use IF NOT EXISTS");
        assertTrue(chSql.contains("ENGINE = ReplacingMergeTree"), "Must use ReplacingMergeTree");
        assertTrue(chSql.contains("_sign"), "Must include _sign CDC column");
        assertTrue(chSql.contains("_version"), "Must include _version CDC column");

        // Verify table was actually created in ClickHouse
        assertTrue(tableExists("simple_create"), "Table 'simple_create' should exist after CREATE");

        // Verify engine
        assertEquals("ReplacingMergeTree", getTableEngine("simple_create"),
                "Engine should be ReplacingMergeTree");

        // Verify columns
        List<String> cols = getColumnNames("simple_create");
        assertTrue(cols.contains("id"), "Table should have 'id' column");
        assertTrue(cols.contains("name"), "Table should have 'name' column");
        assertTrue(cols.contains("score"), "Table should have 'score' column");
        assertTrue(cols.contains("_sign"), "Table should have '_sign' CDC column");
        assertTrue(cols.contains("_version"), "Table should have '_version' CDC column");

        // PK column must not be Nullable
        String idType = getColumnType("simple_create", "id");
        assertNotNull(idType, "id column type should not be null");
        assertFalse(idType.startsWith("Nullable"), "PK column 'id' must not be Nullable: " + idType);

        // Non-PK columns must be Nullable
        String nameType = getColumnType("simple_create", "name");
        assertNotNull(nameType, "name column type should not be null");
        assertTrue(nameType.startsWith("Nullable"), "Non-PK column 'name' must be Nullable: " + nameType);
    }

    @Test
    @Order(2)
    @DisplayName("CREATE TABLE - composite primary key uses correct ORDER BY")
    void testCreateTableWithCompositePrimaryKey() throws Exception {
        String pgSql = "CREATE TABLE composite_pk ("
                + "tenant_id INTEGER, "
                + "user_id BIGINT, "
                + "email TEXT, "
                + "CONSTRAINT pk_composite PRIMARY KEY (tenant_id, user_id)"
                + ")";

        String chSql = translateAndExecute(pgSql);

        assertTrue(tableExists("composite_pk"), "Table 'composite_pk' should exist");

        // Both PK columns must NOT be nullable
        String tenantType = getColumnType("composite_pk", "tenant_id");
        String userType = getColumnType("composite_pk", "user_id");
        assertFalse(tenantType.startsWith("Nullable"),
                "PK column tenant_id must not be Nullable: " + tenantType);
        assertFalse(userType.startsWith("Nullable"),
                "PK column user_id must not be Nullable: " + userType);

        // Non-PK column must be nullable
        String emailType = getColumnType("composite_pk", "email");
        assertTrue(emailType.startsWith("Nullable"),
                "Non-PK column email must be Nullable: " + emailType);

        // Both PK columns should appear in the ORDER BY clause of the translated SQL
        assertTrue(chSql.contains("`tenant_id`"), "ORDER BY must include tenant_id");
        assertTrue(chSql.contains("`user_id`"), "ORDER BY must include user_id");
    }

    @Test
    @Order(3)
    @DisplayName("CREATE TABLE - all supported PostgreSQL types map to valid ClickHouse types")
    void testCreateTableWithAllSupportedTypes() throws Exception {
        String pgSql = "CREATE TABLE all_types ("
                + "pk_col BIGSERIAL PRIMARY KEY, "
                + "small_int SMALLINT, "
                + "regular_int INTEGER, "
                + "big_int BIGINT, "
                + "bool_col BOOLEAN, "
                + "float_col REAL, "
                + "double_col DOUBLE PRECISION, "
                + "decimal_col NUMERIC(18, 4), "
                + "text_col TEXT, "
                + "varchar_col VARCHAR(255), "
                + "date_col DATE, "
                + "ts_col TIMESTAMP, "
                + "tstz_col TIMESTAMPTZ, "
                + "uuid_col UUID, "
                + "json_col JSONB, "
                + "bytes_col BYTEA"
                + ")";

        translateAndExecute(pgSql);

        assertTrue(tableExists("all_types"), "Table 'all_types' should exist after CREATE");

        // Spot-check a few type mappings by querying system.columns
        assertEquals("Int64", getColumnType("all_types", "pk_col"),
                "BIGSERIAL PK should map to Int64");
        assertEquals("Nullable(Int16)", getColumnType("all_types", "small_int"),
                "SMALLINT should map to Nullable(Int16)");
        assertEquals("Nullable(Int32)", getColumnType("all_types", "regular_int"),
                "INTEGER should map to Nullable(Int32)");
        assertEquals("Nullable(Int64)", getColumnType("all_types", "big_int"),
                "BIGINT should map to Nullable(Int64)");
        assertEquals("Nullable(UInt8)", getColumnType("all_types", "bool_col"),
                "BOOLEAN should map to Nullable(UInt8)");
        assertEquals("Nullable(Float32)", getColumnType("all_types", "float_col"),
                "REAL should map to Nullable(Float32)");
        assertEquals("Nullable(Float64)", getColumnType("all_types", "double_col"),
                "DOUBLE PRECISION should map to Nullable(Float64)");
        assertEquals("Nullable(Date32)", getColumnType("all_types", "date_col"),
                "DATE should map to Nullable(Date32)");
        assertEquals("Nullable(UUID)", getColumnType("all_types", "uuid_col"),
                "UUID should map to Nullable(UUID)");
    }

    // -----------------------------------------------------------------------
    // Test 2: ALTER TABLE ADD COLUMN
    // -----------------------------------------------------------------------

    @Test
    @Order(10)
    @DisplayName("ALTER TABLE ADD COLUMN - translates and executes successfully on ClickHouse")
    void testAlterTableAddColumn() throws Exception {
        // Ensure the base table exists (create it if the previous test ran in isolation)
        String createSql = "CREATE TABLE alter_add_test ("
                + "id INTEGER PRIMARY KEY, description TEXT)";
        translateAndExecute(createSql);

        assertTrue(tableExists("alter_add_test"), "Base table 'alter_add_test' should exist");

        // Add a new column
        String pgAlterSql = "ALTER TABLE alter_add_test ADD COLUMN new_col NUMERIC(12,4)";
        String chSql = translateAndExecute(pgAlterSql);

        // Verify translated SQL
        assertTrue(chSql.contains("ADD COLUMN IF NOT EXISTS"), "Must use ADD COLUMN IF NOT EXISTS");
        assertTrue(chSql.contains("`new_col`"), "Must include new column name");
        assertTrue(chSql.contains("Nullable(Decimal(12, 4))"), "New column must be Nullable");

        // Verify column was added to the table
        List<String> cols = getColumnNames("alter_add_test");
        assertTrue(cols.contains("new_col"), "Column 'new_col' should exist after ALTER ADD COLUMN");

        // Verify the column type
        String newColType = getColumnType("alter_add_test", "new_col");
        assertNotNull(newColType, "new_col type should not be null");
        assertTrue(newColType.contains("Decimal"), "new_col should be Decimal type: " + newColType);
    }

    @Test
    @Order(11)
    @DisplayName("ALTER TABLE ADD COLUMN - TEXT type creates Nullable(String) column")
    void testAlterTableAddTextColumn() throws Exception {
        // Ensure base table exists
        translateAndExecute("CREATE TABLE alter_text_test (id INTEGER PRIMARY KEY)");

        String pgAlterSql = "ALTER TABLE alter_text_test ADD COLUMN notes TEXT";
        String chSql = translateAndExecute(pgAlterSql);

        assertTrue(chSql.contains("Nullable(String)"), "TEXT column must be Nullable(String)");

        List<String> cols = getColumnNames("alter_text_test");
        assertTrue(cols.contains("notes"), "Column 'notes' should exist after ADD COLUMN");
    }

    @Test
    @Order(12)
    @DisplayName("ALTER TABLE ADD COLUMN - idempotent (IF NOT EXISTS prevents error on re-add)")
    void testAlterTableAddColumnIdempotent() throws Exception {
        translateAndExecute("CREATE TABLE alter_idempotent_test (id INTEGER PRIMARY KEY)");

        String pgAlterSql = "ALTER TABLE alter_idempotent_test ADD COLUMN col1 TEXT";

        // Execute twice – should not throw due to IF NOT EXISTS
        assertDoesNotThrow(() -> translateAndExecute(pgAlterSql),
                "First ADD COLUMN should succeed");
        assertDoesNotThrow(() -> translateAndExecute(pgAlterSql),
                "Second ADD COLUMN should succeed due to IF NOT EXISTS");
    }

    // -----------------------------------------------------------------------
    // Test 3: ALTER TABLE DROP COLUMN
    // -----------------------------------------------------------------------

    @Test
    @Order(20)
    @DisplayName("ALTER TABLE DROP COLUMN - translates and executes successfully on ClickHouse")
    void testAlterTableDropColumn() throws Exception {
        // Create table with a column we will drop
        String createSql = "CREATE TABLE drop_col_test ("
                + "id INTEGER PRIMARY KEY, keep_col TEXT, drop_me INTEGER)";
        translateAndExecute(createSql);

        assertTrue(tableExists("drop_col_test"), "Table 'drop_col_test' should exist");
        List<String> colsBefore = getColumnNames("drop_col_test");
        assertTrue(colsBefore.contains("drop_me"), "Column 'drop_me' should exist before DROP");

        // Drop the column
        String pgAlterSql = "ALTER TABLE drop_col_test DROP COLUMN drop_me";
        String chSql = translateAndExecute(pgAlterSql);

        // Verify translated SQL
        assertTrue(chSql.contains("DROP COLUMN IF EXISTS"), "Must use DROP COLUMN IF EXISTS");
        assertTrue(chSql.contains("`drop_me`"), "Must include dropped column name");

        // Verify the column was removed
        List<String> colsAfter = getColumnNames("drop_col_test");
        assertFalse(colsAfter.contains("drop_me"),
                "Column 'drop_me' should not exist after DROP COLUMN");
        assertTrue(colsAfter.contains("keep_col"),
                "Column 'keep_col' should still exist after DROP");
    }

    @Test
    @Order(21)
    @DisplayName("ALTER TABLE DROP COLUMN - idempotent (IF EXISTS prevents error on re-drop)")
    void testAlterTableDropColumnIdempotent() throws Exception {
        translateAndExecute("CREATE TABLE drop_idempotent_test (id INTEGER PRIMARY KEY, temp_col TEXT)");

        // Drop once
        String pgAlterSql = "ALTER TABLE drop_idempotent_test DROP COLUMN temp_col";
        assertDoesNotThrow(() -> translateAndExecute(pgAlterSql),
                "First DROP COLUMN should succeed");

        // Drop again – IF EXISTS prevents an error
        assertDoesNotThrow(() -> translateAndExecute(pgAlterSql),
                "Second DROP COLUMN should succeed due to IF EXISTS");
    }

    // -----------------------------------------------------------------------
    // Test 4: DROP TABLE
    // -----------------------------------------------------------------------

    @Test
    @Order(30)
    @DisplayName("DROP TABLE - translates to DROP TABLE IF EXISTS and executes on ClickHouse")
    void testDropTable() throws Exception {
        // Create a table to drop
        translateAndExecute("CREATE TABLE to_drop_test (id INTEGER PRIMARY KEY)");
        assertTrue(tableExists("to_drop_test"), "Table should exist before DROP");

        // Drop it
        String pgDropSql = "DROP TABLE to_drop_test";
        String chSql = translateAndExecute(pgDropSql);

        assertTrue(chSql.contains("DROP TABLE IF EXISTS"), "Must use DROP TABLE IF EXISTS");
        assertFalse(tableExists("to_drop_test"), "Table should not exist after DROP");
    }

    @Test
    @Order(31)
    @DisplayName("DROP TABLE IF EXISTS - idempotent (IF EXISTS prevents error)")
    void testDropTableIfExistsIdempotent() throws Exception {
        // Table does not need to exist – IF EXISTS prevents any error
        assertDoesNotThrow(
                () -> translateAndExecute("DROP TABLE IF EXISTS nonexistent_table_xyz"),
                "DROP TABLE IF EXISTS should not throw even when table does not exist");
    }

    // -----------------------------------------------------------------------
    // Test 5: TRUNCATE TABLE
    // -----------------------------------------------------------------------

    @Test
    @Order(40)
    @DisplayName("TRUNCATE TABLE - translates to TRUNCATE TABLE IF EXISTS and executes on ClickHouse")
    void testTruncateTable() throws Exception {
        // Create and populate a table.
        // Translated schema: id Int32 (PK), val Nullable(String), _sign Int8, _version UInt64
        translateAndExecute("CREATE TABLE truncate_test (id INTEGER PRIMARY KEY, val TEXT)");
        executeUpdate("INSERT INTO " + TEST_DB + ".truncate_test"
                + " (id, val, _sign, _version) VALUES (1, 'hello', 1, 1)");

        // Truncate
        String pgTruncateSql = "TRUNCATE TABLE truncate_test";
        String chSql = translateAndExecute(pgTruncateSql);

        assertTrue(chSql.contains("TRUNCATE TABLE IF EXISTS"), "Must use TRUNCATE TABLE IF EXISTS");

        // Verify the table still exists but is empty
        assertTrue(tableExists("truncate_test"), "Table should still exist after TRUNCATE");
        ResultSet rs = executeQuery("SELECT count(*) as cnt FROM " + TEST_DB + ".truncate_test");
        rs.next();
        assertEquals(0, rs.getInt("cnt"), "Table should be empty after TRUNCATE");
    }

    // -----------------------------------------------------------------------
    // Test 6: Full DDL lifecycle – CREATE → INSERT → ALTER → DROP
    // -----------------------------------------------------------------------

    @Test
    @Order(50)
    @DisplayName("Full DDL lifecycle - CREATE → INSERT → ALTER ADD → ALTER DROP → DROP TABLE")
    void testFullDDLLifecycle() throws Exception {
        final String tableName = "lifecycle_test";

        // --- Step 1: CREATE TABLE ---
        String createSql = "CREATE TABLE " + tableName + " ("
                + "id BIGINT PRIMARY KEY, "
                + "username TEXT, "
                + "age INTEGER"
                + ")";
        translateAndExecute(createSql);
        assertTrue(tableExists(tableName), "Table should exist after CREATE");

        List<String> colsAfterCreate = getColumnNames(tableName);
        assertTrue(colsAfterCreate.contains("id"), "id column should exist");
        assertTrue(colsAfterCreate.contains("username"), "username column should exist");
        assertTrue(colsAfterCreate.contains("age"), "age column should exist");

        // --- Step 2: INSERT a row (using ClickHouse DDL-created table) ---
        executeUpdate(String.format(
                "INSERT INTO %s.%s (id, username, age, _sign, _version) VALUES (1, 'alice', 30, 1, 1)",
                TEST_DB, tableName));

        ResultSet rs = executeQuery(
                "SELECT count(*) as cnt FROM " + TEST_DB + "." + tableName);
        rs.next();
        assertTrue(rs.getInt("cnt") >= 1, "Table should have at least one row after INSERT");

        // --- Step 3: ALTER TABLE ADD COLUMN ---
        String addColSql = "ALTER TABLE " + tableName + " ADD COLUMN email TEXT";
        String addChSql = translateAndExecute(addColSql);
        assertTrue(addChSql.contains("ADD COLUMN IF NOT EXISTS"), "ADD COLUMN must use IF NOT EXISTS");

        List<String> colsAfterAdd = getColumnNames(tableName);
        assertTrue(colsAfterAdd.contains("email"), "email column should exist after ADD COLUMN");

        // --- Step 4: ALTER TABLE DROP COLUMN ---
        String dropColSql = "ALTER TABLE " + tableName + " DROP COLUMN age";
        String dropColChSql = translateAndExecute(dropColSql);
        assertTrue(dropColChSql.contains("DROP COLUMN IF EXISTS"), "DROP COLUMN must use IF EXISTS");

        List<String> colsAfterDrop = getColumnNames(tableName);
        assertFalse(colsAfterDrop.contains("age"), "age column should not exist after DROP COLUMN");
        assertTrue(colsAfterDrop.contains("id"), "id column should still exist");
        assertTrue(colsAfterDrop.contains("email"), "email column should still exist");

        // --- Step 5: DROP TABLE ---
        String dropTableSql = "DROP TABLE " + tableName;
        String dropChSql = translateAndExecute(dropTableSql);
        assertTrue(dropChSql.contains("DROP TABLE IF EXISTS"), "DROP TABLE must use IF EXISTS");
        assertFalse(tableExists(tableName), "Table should not exist after DROP TABLE");
    }

    // -----------------------------------------------------------------------
    // Test 7: Unsupported DDL is gracefully skipped (no exception)
    // -----------------------------------------------------------------------

    @Test
    @Order(60)
    @DisplayName("Unsupported DDL - CREATE INDEX produces empty output and does not throw")
    void testUnsupportedDDLCreateIndex() {
        assertDoesNotThrow(() -> {
            String chSql = translateAndExecute("CREATE INDEX idx_test ON some_table(col1)");
            assertTrue(chSql.isEmpty(), "Unsupported DDL should produce empty output");
        }, "Unsupported DDL must not throw an exception");
    }

    @Test
    @Order(61)
    @DisplayName("Unsupported DDL - CREATE SEQUENCE produces empty output and does not throw")
    void testUnsupportedDDLCreateSequence() {
        assertDoesNotThrow(() -> {
            String chSql = translateAndExecute("CREATE SEQUENCE my_seq INCREMENT BY 1 START WITH 100");
            assertTrue(chSql.isEmpty(), "CREATE SEQUENCE should produce empty output");
        }, "CREATE SEQUENCE must not throw an exception");
    }

    @Test
    @Order(62)
    @DisplayName("Unsupported DDL - ALTER TABLE SET DEFAULT produces empty output and does not throw")
    void testUnsupportedDDLAlterSetDefault() {
        assertDoesNotThrow(() -> {
            String chSql = translateAndExecute("ALTER TABLE some_table ALTER COLUMN col SET DEFAULT 0");
            assertTrue(chSql.isEmpty(), "Unsupported ALTER TABLE variant should produce empty output");
        }, "Unsupported ALTER TABLE variant must not throw an exception");
    }

    // -----------------------------------------------------------------------
    // Test 8: Parser translation output correctness (without ClickHouse execution)
    // -----------------------------------------------------------------------

    @Test
    @Order(70)
    @DisplayName("Translation only - CREATE TABLE output has all required ClickHouse DDL elements")
    void testTranslationOutputElements() {
        StringBuffer buf = new StringBuffer();
        parser.parseSql(
                "CREATE TABLE translation_check (id INTEGER PRIMARY KEY, data TEXT)",
                null, buf);
        String chSql = buf.toString();

        assertFalse(chSql.isEmpty(), "Translated SQL must not be empty");
        assertTrue(chSql.contains("CREATE TABLE IF NOT EXISTS"), "Must contain CREATE TABLE IF NOT EXISTS");
        assertTrue(chSql.contains("`" + TEST_DB + "`.`translation_check`"), "Must qualify table name");
        assertTrue(chSql.contains("ENGINE = ReplacingMergeTree(`_version`)"), "Must specify ReplacingMergeTree with _version");
        assertTrue(chSql.contains("`_sign` Int8"), "Must contain _sign CDC column");
        assertTrue(chSql.contains("`_version` UInt64"), "Must contain _version CDC column");
        assertTrue(chSql.contains("PRIMARY KEY"), "Must contain PRIMARY KEY clause");
        assertTrue(chSql.contains("ORDER BY"), "Must contain ORDER BY clause");
    }

    @Test
    @Order(71)
    @DisplayName("Translation only - ALTER TABLE RENAME COLUMN output is correct")
    void testRenameColumnTranslationOutput() {
        StringBuffer buf = new StringBuffer();
        parser.parseSql(
                "ALTER TABLE rename_check RENAME COLUMN old_col TO new_col",
                null, buf);
        String chSql = buf.toString();

        assertFalse(chSql.isEmpty(), "Translated SQL must not be empty");
        assertTrue(chSql.contains("RENAME COLUMN"), "Must contain RENAME COLUMN");
        assertTrue(chSql.contains("`old_col`"), "Must contain old column name");
        assertTrue(chSql.contains("`new_col`"), "Must contain new column name");
        assertTrue(chSql.contains("TO"), "Must contain TO keyword");
    }
}

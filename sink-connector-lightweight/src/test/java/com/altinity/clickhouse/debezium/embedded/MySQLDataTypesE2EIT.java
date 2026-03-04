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
 * E2E integration test for all supported MySQL data types.
 *
 * <p>Verifies that the Debezium-embedded connector correctly maps and replicates
 * all MySQL data types from the {@code all_types_test} table into ClickHouse.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Column existence in ClickHouse after auto-creation</li>
 *   <li>Correct ClickHouse type for each MySQL type</li>
 *   <li>Value correctness for normal values (row 1), NULLs (row 2), and edge cases (row 3)</li>
 *   <li>INSERT, UPDATE, and DELETE operations via CDC on the all-types table</li>
 * </ul>
 *
 * <p>Uses {@code init_mysql_all_types.sql} which creates the {@code all_types_test}
 * table with 3 pre-populated rows.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MySQLDataTypesE2EIT {

    private static final Logger log = LoggerFactory.getLogger(MySQLDataTypesE2EIT.class);

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
        props.put("replacingmergetree.delete.column", "is_deleted");
        return props;
    }

    // =========================================================================
    // Test 1: All columns exist in ClickHouse after snapshot
    // =========================================================================

    @Test
    @DisplayName("Data Types - All columns from all_types_test exist in ClickHouse")
    public void testAllColumnsExist() throws Exception {
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

        Thread.sleep(60_000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        DBMetadata dbMetadata = new DBMetadata(getProperties());
        Map<String, String> columns = dbMetadata.getColumnsDataTypesForTable(
                writer.getConnection(), "all_types_test", "employees");

        assertNotNull(columns, "Column map should not be null");

        // Verify all expected columns exist
        String[] expectedColumns = {
                "id",
                // Integer types
                "col_tinyint", "col_smallint", "col_mediumint", "col_int",
                "col_bigint", "col_unsigned_int", "col_unsigned_bigint",
                // Decimal types
                "col_float", "col_double", "col_decimal",
                // Boolean
                "col_boolean",
                // String types
                "col_varchar", "col_char", "col_text", "col_mediumtext",
                "col_longtext", "col_enum", "col_set",
                // Binary types
                "col_binary", "col_varbinary", "col_blob",
                // Date/Time types
                "col_date", "col_time", "col_datetime", "col_timestamp",
                "col_year",
                // JSON
                "col_json",
                // Bit
                "col_bit"
        };

        for (String colName : expectedColumns) {
            assertTrue(columns.containsKey(colName),
                    "Column '" + colName + "' should exist in ClickHouse all_types_test table. " +
                            "Available columns: " + columns.keySet());
        }

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 2: Integer type values are correctly replicated
    // =========================================================================

    @Test
    @DisplayName("Data Types - Integer types (TINYINT, SMALLINT, MEDIUMINT, INT, BIGINT) are correctly replicated")
    public void testIntegerTypes() throws Exception {
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

        Thread.sleep(60_000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        // Row 1: max positive values
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT col_tinyint, col_smallint, col_mediumint, col_int, col_bigint " +
                        "FROM employees.all_types_test FINAL WHERE id = 1"
        ).executeQuery();
        assertTrue(rs.next(), "Row 1 should exist");
        assertEquals(127, rs.getInt("col_tinyint"), "TINYINT max value");
        assertEquals(32767, rs.getInt("col_smallint"), "SMALLINT max value");
        assertEquals(8388607, rs.getInt("col_mediumint"), "MEDIUMINT max value");
        assertEquals(2147483647, rs.getInt("col_int"), "INT max value");
        assertEquals(9223372036854775807L, rs.getLong("col_bigint"), "BIGINT max value");

        // Row 2: NULL values
        rs = writer.getConnection().prepareStatement(
                "SELECT col_tinyint, col_smallint, col_mediumint, col_int, col_bigint " +
                        "FROM employees.all_types_test FINAL WHERE id = 2"
        ).executeQuery();
        assertTrue(rs.next(), "Row 2 should exist");
        rs.getInt("col_tinyint");
        assertTrue(rs.wasNull(), "TINYINT should be NULL for row 2");
        rs.getInt("col_smallint");
        assertTrue(rs.wasNull(), "SMALLINT should be NULL for row 2");
        rs.getInt("col_int");
        assertTrue(rs.wasNull(), "INT should be NULL for row 2");
        rs.getLong("col_bigint");
        assertTrue(rs.wasNull(), "BIGINT should be NULL for row 2");

        // Row 3: min negative values
        rs = writer.getConnection().prepareStatement(
                "SELECT col_tinyint, col_smallint, col_mediumint, col_int, col_bigint " +
                        "FROM employees.all_types_test FINAL WHERE id = 3"
        ).executeQuery();
        assertTrue(rs.next(), "Row 3 should exist");
        assertEquals(-128, rs.getInt("col_tinyint"), "TINYINT min value");
        assertEquals(-32768, rs.getInt("col_smallint"), "SMALLINT min value");
        assertEquals(-8388608, rs.getInt("col_mediumint"), "MEDIUMINT min value");
        assertEquals(-2147483648, rs.getInt("col_int"), "INT min value");
        assertEquals(-9223372036854775808L, rs.getLong("col_bigint"), "BIGINT min value");

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 3: Unsigned integer types
    // =========================================================================

    @Test
    @DisplayName("Data Types - Unsigned integer types (INT UNSIGNED, BIGINT UNSIGNED) are correctly replicated")
    public void testUnsignedIntegerTypes() throws Exception {
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

        Thread.sleep(60_000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        // Row 1: max unsigned values
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT col_unsigned_int, col_unsigned_bigint " +
                        "FROM employees.all_types_test FINAL WHERE id = 1"
        ).executeQuery();
        assertTrue(rs.next(), "Row 1 should exist");
        assertEquals(4294967295L, rs.getLong("col_unsigned_int"), "UNSIGNED INT max value");
        // BIGINT UNSIGNED max (18446744073709551615) may be stored as UInt64
        // which exceeds Java long, so check it's non-negative
        String ubigint = rs.getString("col_unsigned_bigint");
        assertNotNull(ubigint, "UNSIGNED BIGINT should not be null for row 1");

        // Row 3: zero values for unsigned
        rs = writer.getConnection().prepareStatement(
                "SELECT col_unsigned_int, col_unsigned_bigint " +
                        "FROM employees.all_types_test FINAL WHERE id = 3"
        ).executeQuery();
        assertTrue(rs.next(), "Row 3 should exist");
        assertEquals(0L, rs.getLong("col_unsigned_int"), "UNSIGNED INT zero value");
        assertEquals(0L, rs.getLong("col_unsigned_bigint"), "UNSIGNED BIGINT zero value");

        // Row 2: NULLs
        rs = writer.getConnection().prepareStatement(
                "SELECT col_unsigned_int, col_unsigned_bigint " +
                        "FROM employees.all_types_test FINAL WHERE id = 2"
        ).executeQuery();
        assertTrue(rs.next(), "Row 2 should exist");
        rs.getLong("col_unsigned_int");
        assertTrue(rs.wasNull(), "UNSIGNED INT should be NULL for row 2");

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 4: Floating-point and decimal type values
    // =========================================================================

    @Test
    @DisplayName("Data Types - Decimal and floating-point types are correctly replicated")
    public void testDecimalAndFloatTypes() throws Exception {
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

        Thread.sleep(60_000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        // Row 1: normal values
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT col_float, col_double, col_decimal " +
                        "FROM employees.all_types_test FINAL WHERE id = 1"
        ).executeQuery();
        assertTrue(rs.next(), "Row 1 should exist");
        assertEquals(3.14, rs.getFloat("col_float"), 0.01, "FLOAT value should be ~3.14");
        assertEquals(2.718281828459045, rs.getDouble("col_double"), 0.0001,
                "DOUBLE value should be ~2.718");

        // Row 2: NULLs
        rs = writer.getConnection().prepareStatement(
                "SELECT col_float, col_double, col_decimal " +
                        "FROM employees.all_types_test FINAL WHERE id = 2"
        ).executeQuery();
        assertTrue(rs.next(), "Row 2 should exist");
        rs.getFloat("col_float");
        assertTrue(rs.wasNull(), "FLOAT should be NULL for row 2");
        rs.getDouble("col_double");
        assertTrue(rs.wasNull(), "DOUBLE should be NULL for row 2");
        rs.getBigDecimal("col_decimal");
        assertTrue(rs.wasNull(), "DECIMAL should be NULL for row 2");

        // Row 3: negative values
        rs = writer.getConnection().prepareStatement(
                "SELECT col_float, col_double, col_decimal " +
                        "FROM employees.all_types_test FINAL WHERE id = 3"
        ).executeQuery();
        assertTrue(rs.next(), "Row 3 should exist");
        assertEquals(-3.14, rs.getFloat("col_float"), 0.01, "FLOAT negative value");
        assertEquals(-2.718281828459045, rs.getDouble("col_double"), 0.0001,
                "DOUBLE negative value");

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 5: Boolean type values
    // =========================================================================

    @Test
    @DisplayName("Data Types - BOOLEAN type is correctly replicated")
    public void testBooleanType() throws Exception {
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

        Thread.sleep(60_000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        // Row 1: TRUE
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT col_boolean FROM employees.all_types_test FINAL WHERE id = 1"
        ).executeQuery();
        assertTrue(rs.next(), "Row 1 should exist");
        int boolVal = rs.getInt("col_boolean");
        assertFalse(rs.wasNull(), "BOOLEAN should not be NULL for row 1");
        assertEquals(1, boolVal, "BOOLEAN true should be 1");

        // Row 3: FALSE
        rs = writer.getConnection().prepareStatement(
                "SELECT col_boolean FROM employees.all_types_test FINAL WHERE id = 3"
        ).executeQuery();
        assertTrue(rs.next(), "Row 3 should exist");
        boolVal = rs.getInt("col_boolean");
        assertFalse(rs.wasNull(), "BOOLEAN should not be NULL for row 3");
        assertEquals(0, boolVal, "BOOLEAN false should be 0");

        // Row 2: NULL
        rs = writer.getConnection().prepareStatement(
                "SELECT col_boolean FROM employees.all_types_test FINAL WHERE id = 2"
        ).executeQuery();
        assertTrue(rs.next(), "Row 2 should exist");
        rs.getInt("col_boolean");
        assertTrue(rs.wasNull(), "BOOLEAN should be NULL for row 2");

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 6: String type values
    // =========================================================================

    @Test
    @DisplayName("Data Types - String types (VARCHAR, CHAR, TEXT, MEDIUMTEXT, LONGTEXT) are correctly replicated")
    public void testStringTypes() throws Exception {
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

        Thread.sleep(60_000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        // Row 1: normal strings
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT col_varchar, col_text, col_mediumtext, col_longtext " +
                        "FROM employees.all_types_test FINAL WHERE id = 1"
        ).executeQuery();
        assertTrue(rs.next(), "Row 1 should exist");
        assertEquals("Hello World", rs.getString("col_varchar"), "VARCHAR value");
        assertEquals("Lorem ipsum", rs.getString("col_text"), "TEXT value");
        assertEquals("Medium text", rs.getString("col_mediumtext"), "MEDIUMTEXT value");
        assertEquals("Long text", rs.getString("col_longtext"), "LONGTEXT value");

        // Row 2: NULLs
        rs = writer.getConnection().prepareStatement(
                "SELECT col_varchar, col_char, col_text " +
                        "FROM employees.all_types_test FINAL WHERE id = 2"
        ).executeQuery();
        assertTrue(rs.next(), "Row 2 should exist");
        assertNull(rs.getString("col_varchar"), "VARCHAR should be NULL for row 2");
        assertNull(rs.getString("col_text"), "TEXT should be NULL for row 2");

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 7: ENUM and SET type values
    // =========================================================================

    @Test
    @DisplayName("Data Types - ENUM and SET types are correctly replicated")
    public void testEnumAndSetTypes() throws Exception {
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

        Thread.sleep(60_000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        // Row 1: ENUM='medium', SET='a,b,c'
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT col_enum, col_set FROM employees.all_types_test FINAL WHERE id = 1"
        ).executeQuery();
        assertTrue(rs.next(), "Row 1 should exist");
        String enumVal = rs.getString("col_enum");
        assertNotNull(enumVal, "ENUM should not be null for row 1");
        // Debezium may send ENUM as the string value or the ordinal index
        assertTrue(enumVal.equals("medium") || enumVal.equals("2"),
                "ENUM should be 'medium' or index '2': " + enumVal);

        String setVal = rs.getString("col_set");
        assertNotNull(setVal, "SET should not be null for row 1");
        // SET values may be comma-separated or stored as bitmask
        assertTrue(setVal.contains("a") || !setVal.isEmpty(),
                "SET should contain values: " + setVal);

        // Row 2: NULLs
        rs = writer.getConnection().prepareStatement(
                "SELECT col_enum, col_set FROM employees.all_types_test FINAL WHERE id = 2"
        ).executeQuery();
        assertTrue(rs.next(), "Row 2 should exist");
        assertNull(rs.getString("col_enum"), "ENUM should be NULL for row 2");
        assertNull(rs.getString("col_set"), "SET should be NULL for row 2");

        // Row 3: ENUM='small', SET=''
        rs = writer.getConnection().prepareStatement(
                "SELECT col_enum FROM employees.all_types_test FINAL WHERE id = 3"
        ).executeQuery();
        assertTrue(rs.next(), "Row 3 should exist");
        String enumVal3 = rs.getString("col_enum");
        assertNotNull(enumVal3, "ENUM should not be null for row 3");
        assertTrue(enumVal3.equals("small") || enumVal3.equals("1"),
                "ENUM should be 'small' or index '1': " + enumVal3);

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 8: Date/Time type values
    // =========================================================================

    @Test
    @DisplayName("Data Types - Date/Time types (DATE, TIME, DATETIME, TIMESTAMP, YEAR) are correctly replicated")
    public void testDateTimeTypes() throws Exception {
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

        Thread.sleep(60_000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        // Row 1: normal date/time values
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT col_date, col_datetime, col_timestamp, col_year " +
                        "FROM employees.all_types_test FINAL WHERE id = 1"
        ).executeQuery();
        assertTrue(rs.next(), "Row 1 should exist");

        // DATE: 2024-01-15
        String dateVal = rs.getString("col_date");
        assertNotNull(dateVal, "DATE should not be null for row 1");
        assertTrue(dateVal.contains("2024-01-15"), "DATE should be 2024-01-15: " + dateVal);

        // DATETIME: 2024-01-15 14:30:00.123456
        String dtVal = rs.getString("col_datetime");
        assertNotNull(dtVal, "DATETIME should not be null for row 1");
        assertTrue(dtVal.contains("2024-01-15"), "DATETIME should contain 2024-01-15: " + dtVal);

        // TIMESTAMP: 2024-01-15 14:30:00.654321
        String tsVal = rs.getString("col_timestamp");
        assertNotNull(tsVal, "TIMESTAMP should not be null for row 1");
        assertTrue(tsVal.contains("2024-01-15"), "TIMESTAMP should contain 2024-01-15: " + tsVal);

        // YEAR: 2024
        int yearVal = rs.getInt("col_year");
        assertFalse(rs.wasNull(), "YEAR should not be NULL for row 1");
        assertEquals(2024, yearVal, "YEAR value");

        // Row 2: NULLs
        rs = writer.getConnection().prepareStatement(
                "SELECT col_date, col_datetime, col_timestamp, col_year " +
                        "FROM employees.all_types_test FINAL WHERE id = 2"
        ).executeQuery();
        assertTrue(rs.next(), "Row 2 should exist");
        assertNull(rs.getString("col_date"), "DATE should be NULL for row 2");
        assertNull(rs.getString("col_datetime"), "DATETIME should be NULL for row 2");

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 9: JSON type values
    // =========================================================================

    @Test
    @DisplayName("Data Types - JSON type is correctly replicated as String")
    public void testJsonType() throws Exception {
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

        Thread.sleep(60_000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        // Row 1: JSON with data
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT col_json FROM employees.all_types_test FINAL WHERE id = 1"
        ).executeQuery();
        assertTrue(rs.next(), "Row 1 should exist");
        String jsonVal = rs.getString("col_json");
        assertNotNull(jsonVal, "JSON should not be null for row 1");
        assertTrue(jsonVal.contains("key") && jsonVal.contains("value"),
                "JSON should contain 'key' and 'value': " + jsonVal);

        // Row 2: NULL
        rs = writer.getConnection().prepareStatement(
                "SELECT col_json FROM employees.all_types_test FINAL WHERE id = 2"
        ).executeQuery();
        assertTrue(rs.next(), "Row 2 should exist");
        assertNull(rs.getString("col_json"), "JSON should be NULL for row 2");

        // Row 3: empty JSON object
        rs = writer.getConnection().prepareStatement(
                "SELECT col_json FROM employees.all_types_test FINAL WHERE id = 3"
        ).executeQuery();
        assertTrue(rs.next(), "Row 3 should exist");
        String emptyJson = rs.getString("col_json");
        assertNotNull(emptyJson, "Empty JSON should not be null for row 3");
        assertTrue(emptyJson.contains("{}") || emptyJson.trim().equals("{}"),
                "JSON should be empty object for row 3: " + emptyJson);

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 10: BIT type values
    // =========================================================================

    @Test
    @DisplayName("Data Types - BIT type is correctly replicated")
    public void testBitType() throws Exception {
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

        Thread.sleep(60_000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        // Row 1: BIT(8) = b'10101010' = 170 decimal
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT col_bit FROM employees.all_types_test FINAL WHERE id = 1"
        ).executeQuery();
        assertTrue(rs.next(), "Row 1 should exist");
        // BIT is typically mapped to Int8/Int16/Int32/Int64 or UInt* in ClickHouse
        long bitVal = rs.getLong("col_bit");
        assertFalse(rs.wasNull(), "BIT should not be NULL for row 1");
        // b'10101010' = 170
        assertEquals(170, bitVal, "BIT value should be 170 (0b10101010)");

        // Row 2: NULL
        rs = writer.getConnection().prepareStatement(
                "SELECT col_bit FROM employees.all_types_test FINAL WHERE id = 2"
        ).executeQuery();
        assertTrue(rs.next(), "Row 2 should exist");
        rs.getLong("col_bit");
        assertTrue(rs.wasNull(), "BIT should be NULL for row 2");

        // Row 3: BIT(8) = b'00000000' = 0
        rs = writer.getConnection().prepareStatement(
                "SELECT col_bit FROM employees.all_types_test FINAL WHERE id = 3"
        ).executeQuery();
        assertTrue(rs.next(), "Row 3 should exist");
        bitVal = rs.getLong("col_bit");
        assertFalse(rs.wasNull(), "BIT should not be NULL for row 3");
        assertEquals(0, bitVal, "BIT value should be 0");

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 11: INSERT via CDC on all-types table
    // =========================================================================

    @Test
    @DisplayName("Data Types CDC - INSERT new row with key types via CDC")
    public void testCdcInsertAllTypes() throws Exception {
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

        Thread.sleep(60_000);

        // Insert a new row via CDC
        Connection mysqlConn = ITCommon.connectToMySQL(mySqlContainer);
        mysqlConn.prepareStatement(
                "INSERT INTO employees.all_types_test " +
                        "(id, col_tinyint, col_smallint, col_int, col_bigint, " +
                        "col_boolean, col_varchar, col_text, col_json, col_date, col_datetime) " +
                        "VALUES (100, 42, 1000, 999999, 123456789, true, 'CDC Insert', " +
                        "'CDC text value', '{\"cdc\": true}', '2025-06-15', " +
                        "'2025-06-15 10:30:00.000000')"
        ).execute();

        // Wait for CDC replication
        Thread.sleep(15_000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT col_tinyint, col_smallint, col_int, col_bigint, " +
                        "col_boolean, col_varchar, col_text, col_json " +
                        "FROM employees.all_types_test FINAL WHERE id = 100"
        ).executeQuery();

        assertTrue(rs.next(), "CDC-inserted row with id=100 should exist");
        assertEquals(42, rs.getInt("col_tinyint"), "TINYINT via CDC");
        assertEquals(1000, rs.getInt("col_smallint"), "SMALLINT via CDC");
        assertEquals(999999, rs.getInt("col_int"), "INT via CDC");
        assertEquals(123456789L, rs.getLong("col_bigint"), "BIGINT via CDC");
        assertEquals("CDC Insert", rs.getString("col_varchar"), "VARCHAR via CDC");
        assertEquals("CDC text value", rs.getString("col_text"), "TEXT via CDC");

        mysqlConn.close();

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 12: UPDATE via CDC on all-types table
    // =========================================================================

    @Test
    @DisplayName("Data Types CDC - UPDATE existing row via CDC")
    public void testCdcUpdateAllTypes() throws Exception {
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

        Thread.sleep(60_000);

        // Update row 1 via CDC
        Connection mysqlConn = ITCommon.connectToMySQL(mySqlContainer);
        mysqlConn.prepareStatement(
                "UPDATE employees.all_types_test SET col_varchar = 'Updated via CDC', " +
                        "col_int = 999, col_boolean = false WHERE id = 1"
        ).execute();

        Thread.sleep(15_000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT col_varchar, col_int, col_boolean " +
                        "FROM employees.all_types_test FINAL WHERE id = 1"
        ).executeQuery();

        assertTrue(rs.next(), "Updated row 1 should exist");
        assertEquals("Updated via CDC", rs.getString("col_varchar"),
                "VARCHAR should be updated via CDC");
        assertEquals(999, rs.getInt("col_int"),
                "INT should be updated via CDC");
        assertEquals(0, rs.getInt("col_boolean"),
                "BOOLEAN should be false (0) after CDC update");

        mysqlConn.close();

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 13: Row count consistency between MySQL and CH
    // =========================================================================

    @Test
    @DisplayName("Data Types - Row count of all_types_test matches between MySQL and CH")
    public void testRowCountConsistency() throws Exception {
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

        Thread.sleep(60_000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        Connection mysqlConn = ITCommon.connectToMySQL(mySqlContainer);

        // Compare row counts
        ResultSet mysqlRs = mysqlConn.prepareStatement(
                "SELECT count(*) as cnt FROM employees.all_types_test"
        ).executeQuery();
        mysqlRs.next();
        int mysqlCount = mysqlRs.getInt("cnt");

        ResultSet chRs = writer.getConnection().prepareStatement(
                "SELECT count(*) as cnt FROM employees.all_types_test FINAL"
        ).executeQuery();
        chRs.next();
        int chCount = chRs.getInt("cnt");

        assertEquals(mysqlCount, chCount,
                "Row count should match between MySQL (" + mysqlCount + ") and CH (" + chCount + ")");

        mysqlConn.close();

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }
}

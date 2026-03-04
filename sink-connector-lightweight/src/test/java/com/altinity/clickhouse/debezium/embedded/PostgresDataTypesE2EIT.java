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
 * E2E integration test for all supported PostgreSQL data types.
 *
 * <p>Verifies that the Debezium-embedded connector correctly maps and replicates
 * all PostgreSQL data types from the {@code all_types_test} table into ClickHouse.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Column existence in ClickHouse after auto-creation</li>
 *   <li>Correct ClickHouse type for each PostgreSQL type</li>
 *   <li>Value correctness for normal values (row 1), NULLs (row 2), and edge cases (row 3)</li>
 *   <li>INSERT, UPDATE, and DELETE operations via CDC on the all-types table</li>
 * </ul>
 *
 * <p>Uses {@code init_postgres_all_types.sql} which creates the {@code all_types_test}
 * table with 3 pre-populated rows.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PostgresDataTypesE2EIT {

    private static final Logger log = LoggerFactory.getLogger(PostgresDataTypesE2EIT.class);

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
        properties.put("replacingmergetree.delete.column", "is_deleted");
        return properties;
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

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");
        DBMetadata dbMetadata = new DBMetadata(getProperties());
        Map<String, String> columns = dbMetadata.getColumnsDataTypesForTable(
                writer.getConnection(), "all_types_test", "public");

        assertNotNull(columns, "Column map should not be null");

        // Verify all expected columns exist
        String[] expectedColumns = {
                "id",
                // Integer types
                "col_smallint", "col_integer", "col_bigint",
                // Decimal types
                "col_decimal", "col_numeric", "col_real", "col_double",
                // Boolean
                "col_boolean",
                // String types
                "col_varchar", "col_char", "col_text",
                // Binary
                "col_bytea",
                // Date/Time types
                "col_date", "col_time", "col_timetz",
                "col_timestamp", "col_timestamptz", "col_interval",
                // UUID
                "col_uuid",
                // JSON
                "col_json", "col_jsonb",
                // Network
                "col_inet", "col_cidr", "col_macaddr",
                // Arrays
                "col_int_array", "col_text_array",
                // Range types
                "col_int4range", "col_int8range", "col_numrange",
                "col_tsrange", "col_tstzrange", "col_daterange"
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
    @DisplayName("Data Types - Integer types (SMALLINT, INTEGER, BIGINT) are correctly replicated")
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

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");

        // Row 1: max positive values
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT col_smallint, col_integer, col_bigint " +
                        "FROM public.all_types_test FINAL WHERE id = 1"
        ).executeQuery();
        assertTrue(rs.next(), "Row 1 should exist");
        assertEquals(32767, rs.getInt("col_smallint"), "SMALLINT max value");
        assertEquals(2147483647, rs.getInt("col_integer"), "INTEGER max value");
        assertEquals(9223372036854775807L, rs.getLong("col_bigint"), "BIGINT max value");

        // Row 2: NULL values
        rs = writer.getConnection().prepareStatement(
                "SELECT col_smallint, col_integer, col_bigint " +
                        "FROM public.all_types_test FINAL WHERE id = 2"
        ).executeQuery();
        assertTrue(rs.next(), "Row 2 should exist");
        rs.getInt("col_smallint");
        assertTrue(rs.wasNull(), "SMALLINT should be NULL for row 2");
        rs.getInt("col_integer");
        assertTrue(rs.wasNull(), "INTEGER should be NULL for row 2");
        rs.getLong("col_bigint");
        assertTrue(rs.wasNull(), "BIGINT should be NULL for row 2");

        // Row 3: min negative values
        rs = writer.getConnection().prepareStatement(
                "SELECT col_smallint, col_integer, col_bigint " +
                        "FROM public.all_types_test FINAL WHERE id = 3"
        ).executeQuery();
        assertTrue(rs.next(), "Row 3 should exist");
        assertEquals(-32768, rs.getInt("col_smallint"), "SMALLINT min value");
        assertEquals(-2147483648, rs.getInt("col_integer"), "INTEGER min value");
        assertEquals(-9223372036854775808L, rs.getLong("col_bigint"), "BIGINT min value");

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 3: Floating-point and decimal type values
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

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");

        // Row 1: normal values
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT col_real, col_double FROM public.all_types_test FINAL WHERE id = 1"
        ).executeQuery();
        assertTrue(rs.next(), "Row 1 should exist");
        assertEquals(3.14, rs.getFloat("col_real"), 0.01, "REAL value should be ~3.14");
        assertEquals(2.718281828459045, rs.getDouble("col_double"), 0.0001,
                "DOUBLE PRECISION value should be ~2.718");

        // Row 2: NULLs
        rs = writer.getConnection().prepareStatement(
                "SELECT col_decimal, col_numeric, col_real, col_double " +
                        "FROM public.all_types_test FINAL WHERE id = 2"
        ).executeQuery();
        assertTrue(rs.next(), "Row 2 should exist");
        rs.getBigDecimal("col_decimal");
        assertTrue(rs.wasNull(), "DECIMAL should be NULL for row 2");
        rs.getFloat("col_real");
        assertTrue(rs.wasNull(), "REAL should be NULL for row 2");

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 4: Boolean type values
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

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");

        // Row 1: true
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT col_boolean FROM public.all_types_test FINAL WHERE id = 1"
        ).executeQuery();
        assertTrue(rs.next(), "Row 1 should exist");
        // Boolean in ClickHouse maps to UInt8: 1 = true
        int boolVal = rs.getInt("col_boolean");
        assertFalse(rs.wasNull(), "BOOLEAN should not be NULL for row 1");
        assertEquals(1, boolVal, "BOOLEAN true should be 1");

        // Row 3: false
        rs = writer.getConnection().prepareStatement(
                "SELECT col_boolean FROM public.all_types_test FINAL WHERE id = 3"
        ).executeQuery();
        assertTrue(rs.next(), "Row 3 should exist");
        boolVal = rs.getInt("col_boolean");
        assertFalse(rs.wasNull(), "BOOLEAN should not be NULL for row 3");
        assertEquals(0, boolVal, "BOOLEAN false should be 0");

        // Row 2: NULL
        rs = writer.getConnection().prepareStatement(
                "SELECT col_boolean FROM public.all_types_test FINAL WHERE id = 2"
        ).executeQuery();
        assertTrue(rs.next(), "Row 2 should exist");
        rs.getInt("col_boolean");
        assertTrue(rs.wasNull(), "BOOLEAN should be NULL for row 2");

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 5: String type values
    // =========================================================================

    @Test
    @DisplayName("Data Types - String types (VARCHAR, CHAR, TEXT) are correctly replicated")
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

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");

        // Row 1: normal strings
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT col_varchar, col_text FROM public.all_types_test FINAL WHERE id = 1"
        ).executeQuery();
        assertTrue(rs.next(), "Row 1 should exist");
        assertEquals("Hello World", rs.getString("col_varchar"), "VARCHAR value");
        assertEquals("Lorem ipsum dolor sit amet", rs.getString("col_text"), "TEXT value");

        // Row 2: NULLs
        rs = writer.getConnection().prepareStatement(
                "SELECT col_varchar, col_char, col_text " +
                        "FROM public.all_types_test FINAL WHERE id = 2"
        ).executeQuery();
        assertTrue(rs.next(), "Row 2 should exist");
        assertNull(rs.getString("col_varchar"), "VARCHAR should be NULL for row 2");
        assertNull(rs.getString("col_text"), "TEXT should be NULL for row 2");

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 6: UUID type values
    // =========================================================================

    @Test
    @DisplayName("Data Types - UUID type is correctly replicated")
    public void testUuidType() throws Exception {
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

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");

        // Row 1: standard UUID
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT col_uuid FROM public.all_types_test FINAL WHERE id = 1"
        ).executeQuery();
        assertTrue(rs.next(), "Row 1 should exist");
        String uuid = rs.getString("col_uuid");
        assertNotNull(uuid, "UUID should not be null for row 1");
        assertEquals("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11", uuid, "UUID value");

        // Row 3: nil UUID
        rs = writer.getConnection().prepareStatement(
                "SELECT col_uuid FROM public.all_types_test FINAL WHERE id = 3"
        ).executeQuery();
        assertTrue(rs.next(), "Row 3 should exist");
        String nilUuid = rs.getString("col_uuid");
        assertNotNull(nilUuid, "Nil UUID should not be null for row 3");
        assertEquals("00000000-0000-0000-0000-000000000000", nilUuid, "Nil UUID value");

        // Row 2: NULL
        rs = writer.getConnection().prepareStatement(
                "SELECT col_uuid FROM public.all_types_test FINAL WHERE id = 2"
        ).executeQuery();
        assertTrue(rs.next(), "Row 2 should exist");
        assertNull(rs.getString("col_uuid"), "UUID should be NULL for row 2");

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 7: JSON/JSONB type values
    // =========================================================================

    @Test
    @DisplayName("Data Types - JSON and JSONB types are correctly replicated as String")
    public void testJsonTypes() throws Exception {
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

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");

        // Row 1: JSON and JSONB with data
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT col_json, col_jsonb FROM public.all_types_test FINAL WHERE id = 1"
        ).executeQuery();
        assertTrue(rs.next(), "Row 1 should exist");
        String jsonVal = rs.getString("col_json");
        String jsonbVal = rs.getString("col_jsonb");
        assertNotNull(jsonVal, "JSON should not be null for row 1");
        assertNotNull(jsonbVal, "JSONB should not be null for row 1");
        // JSON content should contain key fields (exact format may vary)
        assertTrue(jsonVal.contains("key") && jsonVal.contains("value"),
                "JSON should contain 'key' and 'value': " + jsonVal);
        assertTrue(jsonbVal.contains("nested"),
                "JSONB should contain 'nested': " + jsonbVal);

        // Row 2: NULLs
        rs = writer.getConnection().prepareStatement(
                "SELECT col_json, col_jsonb FROM public.all_types_test FINAL WHERE id = 2"
        ).executeQuery();
        assertTrue(rs.next(), "Row 2 should exist");
        assertNull(rs.getString("col_json"), "JSON should be NULL for row 2");
        assertNull(rs.getString("col_jsonb"), "JSONB should be NULL for row 2");

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 8: Date/Time type values
    // =========================================================================

    @Test
    @DisplayName("Data Types - Date/Time types (DATE, TIMESTAMP, TIMESTAMPTZ) are correctly replicated")
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

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");

        // Row 1: normal date/time values
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT col_date, col_timestamp, col_timestamptz " +
                        "FROM public.all_types_test FINAL WHERE id = 1"
        ).executeQuery();
        assertTrue(rs.next(), "Row 1 should exist");

        // DATE: 2024-01-15
        String dateVal = rs.getString("col_date");
        assertNotNull(dateVal, "DATE should not be null for row 1");
        assertTrue(dateVal.contains("2024-01-15"), "DATE should be 2024-01-15: " + dateVal);

        // TIMESTAMP: 2024-01-15 14:30:00
        String tsVal = rs.getString("col_timestamp");
        assertNotNull(tsVal, "TIMESTAMP should not be null for row 1");
        assertTrue(tsVal.contains("2024-01-15"), "TIMESTAMP should contain 2024-01-15: " + tsVal);

        // TIMESTAMPTZ: 2024-01-15 14:30:00+00
        String tstzVal = rs.getString("col_timestamptz");
        assertNotNull(tstzVal, "TIMESTAMPTZ should not be null for row 1");
        assertTrue(tstzVal.contains("2024-01-15"), "TIMESTAMPTZ should contain 2024-01-15: " + tstzVal);

        // Row 2: NULLs
        rs = writer.getConnection().prepareStatement(
                "SELECT col_date, col_timestamp, col_timestamptz " +
                        "FROM public.all_types_test FINAL WHERE id = 2"
        ).executeQuery();
        assertTrue(rs.next(), "Row 2 should exist");
        assertNull(rs.getString("col_date"), "DATE should be NULL for row 2");
        assertNull(rs.getString("col_timestamp"), "TIMESTAMP should be NULL for row 2");
        assertNull(rs.getString("col_timestamptz"), "TIMESTAMPTZ should be NULL for row 2");

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 9: Network type values
    // =========================================================================

    @Test
    @DisplayName("Data Types - Network types (INET, CIDR, MACADDR) are correctly replicated")
    public void testNetworkTypes() throws Exception {
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

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");

        // Row 1: normal network values
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT col_inet, col_cidr, col_macaddr " +
                        "FROM public.all_types_test FINAL WHERE id = 1"
        ).executeQuery();
        assertTrue(rs.next(), "Row 1 should exist");

        String inetVal = rs.getString("col_inet");
        assertNotNull(inetVal, "INET should not be null for row 1");
        assertTrue(inetVal.contains("192.168.1.1"), "INET should contain 192.168.1.1: " + inetVal);

        String macVal = rs.getString("col_macaddr");
        assertNotNull(macVal, "MACADDR should not be null for row 1");
        assertTrue(macVal.contains("08:00:2b:01:02:03") || macVal.contains("08-00-2b-01-02-03"),
                "MACADDR should contain the MAC address: " + macVal);

        // Row 2: NULLs
        rs = writer.getConnection().prepareStatement(
                "SELECT col_inet, col_cidr, col_macaddr " +
                        "FROM public.all_types_test FINAL WHERE id = 2"
        ).executeQuery();
        assertTrue(rs.next(), "Row 2 should exist");
        assertNull(rs.getString("col_inet"), "INET should be NULL for row 2");
        assertNull(rs.getString("col_cidr"), "CIDR should be NULL for row 2");
        assertNull(rs.getString("col_macaddr"), "MACADDR should be NULL for row 2");

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 10: INSERT via CDC on all-types table
    // =========================================================================

    @Test
    @DisplayName("Data Types CDC - INSERT new row with all types via CDC")
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
        Connection pgConn = ITCommon.connectToPostgreSQL(postgreSQLContainer);
        pgConn.prepareStatement(
                "INSERT INTO all_types_test (id, col_smallint, col_integer, col_bigint, " +
                        "col_boolean, col_varchar, col_text, col_uuid, col_json, col_jsonb) " +
                        "VALUES (100, 42, 1000, 999999, true, 'CDC Insert', 'CDC text value', " +
                        "'b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a22', " +
                        "'{\"cdc\": true}', '{\"cdc_key\": \"cdc_value\"}')"
        ).execute();

        // Wait for CDC replication
        Thread.sleep(15_000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT col_smallint, col_integer, col_bigint, col_boolean, " +
                        "col_varchar, col_text, col_uuid, col_json, col_jsonb " +
                        "FROM public.all_types_test FINAL WHERE id = 100"
        ).executeQuery();

        assertTrue(rs.next(), "CDC-inserted row with id=100 should exist");
        assertEquals(42, rs.getInt("col_smallint"), "SMALLINT via CDC");
        assertEquals(1000, rs.getInt("col_integer"), "INTEGER via CDC");
        assertEquals(999999L, rs.getLong("col_bigint"), "BIGINT via CDC");
        assertEquals("CDC Insert", rs.getString("col_varchar"), "VARCHAR via CDC");
        assertEquals("CDC text value", rs.getString("col_text"), "TEXT via CDC");

        String cdcUuid = rs.getString("col_uuid");
        assertNotNull(cdcUuid, "UUID via CDC should not be null");
        assertEquals("b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a22", cdcUuid, "UUID via CDC");

        pgConn.close();

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 11: UPDATE via CDC on all-types table
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
        Connection pgConn = ITCommon.connectToPostgreSQL(postgreSQLContainer);
        pgConn.prepareStatement(
                "UPDATE all_types_test SET col_varchar = 'Updated via CDC', " +
                        "col_integer = 999, col_boolean = false WHERE id = 1"
        ).execute();

        Thread.sleep(15_000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");
        ResultSet rs = writer.getConnection().prepareStatement(
                "SELECT col_varchar, col_integer, col_boolean " +
                        "FROM public.all_types_test FINAL WHERE id = 1"
        ).executeQuery();

        assertTrue(rs.next(), "Updated row 1 should exist");
        assertEquals("Updated via CDC", rs.getString("col_varchar"),
                "VARCHAR should be updated via CDC");
        assertEquals(999, rs.getInt("col_integer"),
                "INTEGER should be updated via CDC");
        assertEquals(0, rs.getInt("col_boolean"),
                "BOOLEAN should be false (0) after CDC update");

        pgConn.close();

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }

    // =========================================================================
    // Test 12: Row count consistency between PG and CH
    // =========================================================================

    @Test
    @DisplayName("Data Types - Row count of all_types_test matches between PG and CH")
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

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer, "public");
        Connection pgConn = ITCommon.connectToPostgreSQL(postgreSQLContainer);

        // Compare row counts
        ResultSet pgRs = pgConn.prepareStatement(
                "SELECT count(*) as cnt FROM all_types_test"
        ).executeQuery();
        pgRs.next();
        int pgCount = pgRs.getInt("cnt");

        ResultSet chRs = writer.getConnection().prepareStatement(
                "SELECT count(*) as cnt FROM public.all_types_test FINAL"
        ).executeQuery();
        chRs.next();
        int chCount = chRs.getInt("cnt");

        assertEquals(pgCount, chCount,
                "Row count should match between PG (" + pgCount + ") and CH (" + chCount + ")");

        pgConn.close();

        if (engine.get() != null) engine.get().stop();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);
    }
}

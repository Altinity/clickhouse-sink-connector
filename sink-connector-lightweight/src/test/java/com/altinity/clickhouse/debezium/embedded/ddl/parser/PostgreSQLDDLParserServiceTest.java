package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PostgreSQLDDLParserService}.
 *
 * <p>These tests validate the DDL translation logic directly without any
 * Docker containers or external services. Each test exercises a specific
 * translation scenario and asserts the expected ClickHouse DDL output.</p>
 */
@DisplayName("PostgreSQL DDL Parser - Unit Tests")
class PostgreSQLDDLParserServiceTest {

    // -----------------------------------------------------------------------
    // Helper factory – constructs the service with a fixed database name
    // -----------------------------------------------------------------------

    private PostgreSQLDDLParserService parser(String db) {
        return new PostgreSQLDDLParserService(null, null, db);
    }

    private String translate(PostgreSQLDDLParserService svc, String sql) {
        StringBuffer buf = new StringBuffer();
        svc.parseSql(sql, null, buf);
        return buf.toString();
    }

    // -----------------------------------------------------------------------
    // CREATE TABLE tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("CREATE TABLE - basic single-column table translates correctly")
    void testCreateTableBasic() {
        PostgreSQLDDLParserService svc = parser("testdb");
        String pg = "CREATE TABLE users (id INTEGER, name TEXT)";
        String ch = translate(svc, pg);

        assertFalse(ch.isEmpty(), "Output should not be empty");
        assertTrue(ch.contains("CREATE TABLE IF NOT EXISTS"), "Should use CREATE TABLE IF NOT EXISTS");
        assertTrue(ch.contains("`testdb`.`users`"), "Should qualify table name with database");
        assertTrue(ch.contains("ENGINE = ReplacingMergeTree"), "Should use ReplacingMergeTree engine");
        assertTrue(ch.contains("`_version` Nullable(UInt64)"), "Should include CDC _version column");
        assertTrue(ch.contains("`is_deleted` UInt8"), "Should include CDC is_deleted column");
    }

    @Test
    @DisplayName("CREATE TABLE - with inline PRIMARY KEY uses non-nullable PK column")
    void testCreateTableWithPrimaryKey() {
        PostgreSQLDDLParserService svc = parser("mydb");
        String pg = "CREATE TABLE orders (id BIGINT PRIMARY KEY, amount NUMERIC(10,2))";
        String ch = translate(svc, pg);

        // PK column must NOT be wrapped in Nullable()
        assertTrue(ch.contains("`id` Int64"), "PK column should map to Int64 without Nullable");
        assertFalse(ch.contains("Nullable(Int64)"), "PK column must not be Nullable");

        // Non-PK column must be wrapped in Nullable()
        assertTrue(ch.contains("Nullable(Decimal(10, 2))"), "Non-PK column should be Nullable");

        // Order-by clause (the listener emits ORDER BY only, not a separate PRIMARY KEY clause)
        assertTrue(ch.contains("ORDER BY"), "Should contain ORDER BY clause");
        assertTrue(ch.contains("`id`"), "ORDER BY clause should reference the id column");
    }

    @Test
    @DisplayName("CREATE TABLE - with table-level CONSTRAINT PRIMARY KEY uses correct columns")
    void testCreateTableWithCompositePrimaryKey() {
        PostgreSQLDDLParserService svc = parser("mydb");
        String pg = "CREATE TABLE order_items (\n"
                + "  order_id INTEGER,\n"
                + "  item_id  INTEGER,\n"
                + "  qty      INTEGER,\n"
                + "  CONSTRAINT pk_order_items PRIMARY KEY (order_id, item_id)\n"
                + ")";
        String ch = translate(svc, pg);

        // Both PK columns must be non-nullable
        assertTrue(ch.contains("`order_id` Int32"), "order_id (PK) should be non-nullable Int32");
        assertTrue(ch.contains("`item_id` Int32"), "item_id (PK) should be non-nullable Int32");

        // Non-PK column must be nullable
        assertTrue(ch.contains("Nullable(Int32)"), "qty (non-PK) should be Nullable(Int32)");

        // Order-by list must include both PK columns
        assertTrue(ch.contains("`order_id`"), "ORDER BY should reference order_id");
        assertTrue(ch.contains("`item_id`"), "ORDER BY should reference item_id");
    }

    @Test
    @DisplayName("CREATE TABLE - numeric types map correctly")
    void testCreateTableWithAllNumericTypes() {
        PostgreSQLDDLParserService svc = parser("db");
        String pg = "CREATE TABLE nums ("
                + "a SMALLINT, b INTEGER, c BIGINT, "
                + "d SMALLSERIAL, e SERIAL, f BIGSERIAL, "
                + "g REAL, h DOUBLE PRECISION, "
                + "i NUMERIC(10,2), j DECIMAL(5,3), k NUMERIC)";
        String ch = translate(svc, pg);

        assertTrue(ch.contains("Int16"), "SMALLINT should map to Int16");
        assertTrue(ch.contains("Int32"), "INTEGER should map to Int32");
        assertTrue(ch.contains("Int64"), "BIGINT/SERIAL should map to Int64");
        assertTrue(ch.contains("Float32"), "REAL should map to Float32");
        assertTrue(ch.contains("Float64"), "DOUBLE PRECISION should map to Float64");
        assertTrue(ch.contains("Decimal(10, 2)"), "NUMERIC(10,2) should map to Decimal(10, 2)");
        assertTrue(ch.contains("Decimal(5, 3)"), "DECIMAL(5,3) should map to Decimal(5, 3)");
        assertTrue(ch.contains("Decimal(38, 9)"), "Bare NUMERIC should map to Decimal(38, 9)");
    }

    @Test
    @DisplayName("CREATE TABLE - string types all map to String")
    void testCreateTableWithStringTypes() {
        PostgreSQLDDLParserService svc = parser("db");
        String pg = "CREATE TABLE strs ("
                + "a TEXT, b VARCHAR(255), c CHARACTER VARYING(100), "
                + "d CHAR(10), e CHARACTER(5), f NAME)";
        String ch = translate(svc, pg);

        // All string types become String (possibly wrapped Nullable)
        long stringCount = ch.lines()
                .filter(l -> l.contains("String"))
                .count();
        assertTrue(stringCount >= 6, "All six string-type columns should map to String");
    }

    @Test
    @DisplayName("CREATE TABLE - temporal types map correctly")
    void testCreateTableWithTemporalTypes() {
        PostgreSQLDDLParserService svc = parser("db");
        String pg = "CREATE TABLE times ("
                + "a TIMESTAMP, b TIMESTAMPTZ, "
                + "c TIMESTAMP WITHOUT TIME ZONE, d TIMESTAMP WITH TIME ZONE, "
                + "e DATE, f TIME, g INTERVAL)";
        String ch = translate(svc, pg);

        assertTrue(ch.contains("DateTime64(6)"), "TIMESTAMP should map to DateTime64(6)");
        assertTrue(ch.contains("DateTime64(6, 'UTC')"), "TIMESTAMPTZ should map to DateTime64(6, 'UTC')");
        assertTrue(ch.contains("Date32"), "DATE should map to Date32");
        // TIME and INTERVAL map to String
        assertTrue(ch.contains("String"), "TIME/INTERVAL should map to String");
    }

    @Test
    @DisplayName("CREATE TABLE - BOOLEAN maps to UInt8")
    void testCreateTableWithBooleanType() {
        PostgreSQLDDLParserService svc = parser("db");
        String pg = "CREATE TABLE flags (active BOOLEAN, enabled BOOL)";
        String ch = translate(svc, pg);

        assertTrue(ch.contains("UInt8"), "BOOLEAN should map to UInt8");
    }

    @Test
    @DisplayName("CREATE TABLE - UUID maps to UUID")
    void testCreateTableWithUUID() {
        PostgreSQLDDLParserService svc = parser("db");
        String pg = "CREATE TABLE entities (id UUID PRIMARY KEY, name TEXT)";
        String ch = translate(svc, pg);

        // PK UUID is non-nullable
        assertTrue(ch.contains("`id` UUID"), "UUID PK should be non-nullable UUID");
        assertFalse(ch.contains("Nullable(UUID)"), "PK UUID must not be Nullable");
    }

    @Test
    @DisplayName("CREATE TABLE - JSONB maps to String")
    void testCreateTableWithJsonb() {
        PostgreSQLDDLParserService svc = parser("db");
        String pg = "CREATE TABLE docs (id INTEGER, payload JSONB, meta JSON)";
        String ch = translate(svc, pg);

        // payload and meta should both be Nullable(String)
        long nullableStringCount = ch.lines()
                .filter(l -> l.contains("Nullable(String)"))
                .count();
        assertTrue(nullableStringCount >= 2, "JSONB and JSON should map to Nullable(String)");
    }

    @Test
    @DisplayName("CREATE TABLE - SERIAL auto-increment maps to Int64")
    void testCreateTableWithSerial() {
        PostgreSQLDDLParserService svc = parser("db");
        String pg = "CREATE TABLE seq_table (id SERIAL PRIMARY KEY, val TEXT)";
        String ch = translate(svc, pg);

        // SERIAL PK → non-nullable Int64
        assertTrue(ch.contains("`id` Int64"), "SERIAL PK should map to non-nullable Int64");
    }

    @Test
    @DisplayName("CREATE TABLE - table with no PRIMARY KEY uses ORDER BY tuple()")
    void testCreateTableNoPrimaryKey() {
        PostgreSQLDDLParserService svc = parser("db");
        String pg = "CREATE TABLE no_pk (col1 TEXT, col2 INTEGER)";
        String ch = translate(svc, pg);

        assertTrue(ch.contains("ORDER BY tuple()"), "Table with no PK should use ORDER BY tuple()");
    }

    @Test
    @DisplayName("CREATE TABLE - schema-qualified table strips 'public.' prefix")
    void testCreateTablePublicSchemaStripped() {
        PostgreSQLDDLParserService svc = parser("mydb");
        String pg = "CREATE TABLE public.events (id INTEGER PRIMARY KEY, data TEXT)";
        String ch = translate(svc, pg);

        assertTrue(ch.contains("`mydb`.`events`"), "public. prefix should be replaced with database name");
        assertFalse(ch.contains("public"), "public schema prefix should be removed");
    }

    @Test
    @DisplayName("CREATE TABLE - IF NOT EXISTS variant is also handled")
    void testCreateTableIfNotExists() {
        PostgreSQLDDLParserService svc = parser("db");
        String pg = "CREATE TABLE IF NOT EXISTS my_table (id INTEGER PRIMARY KEY)";
        String ch = translate(svc, pg);

        assertTrue(ch.contains("CREATE TABLE IF NOT EXISTS"), "Output should contain IF NOT EXISTS");
        assertTrue(ch.contains("`db`.`my_table`"), "Table name should be correct");
    }

    // -----------------------------------------------------------------------
    // ALTER TABLE tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("ALTER TABLE ADD COLUMN - translates with IF NOT EXISTS and Nullable type")
    void testAlterTableAddColumn() {
        PostgreSQLDDLParserService svc = parser("mydb");
        String pg = "ALTER TABLE employees ADD COLUMN salary NUMERIC(12,2)";
        String ch = translate(svc, pg);

        assertTrue(ch.contains("ALTER TABLE"), "Should contain ALTER TABLE");
        assertTrue(ch.contains("`mydb`.`employees`"), "Should qualify table name");
        assertTrue(ch.contains("ADD COLUMN IF NOT EXISTS"), "Should use ADD COLUMN IF NOT EXISTS");
        assertTrue(ch.contains("`salary`"), "Should include column name");
        assertTrue(ch.contains("Nullable(Decimal(12, 2))"), "Added column should be Nullable");
    }

    @Test
    @DisplayName("ALTER TABLE ADD COLUMN - NOT NULL column uses bare type (not Nullable)")
    void testAlterTableAddColumnNotNull() {
        PostgreSQLDDLParserService svc = parser("db");
        String pg = "ALTER TABLE t ADD COLUMN status TEXT NOT NULL DEFAULT 'active'";
        String ch = translate(svc, pg);

        assertTrue(ch.contains("ADD COLUMN IF NOT EXISTS"), "Should use ADD COLUMN IF NOT EXISTS");
        assertTrue(ch.contains("`status`"), "Should include column name");
        assertTrue(ch.contains("`status` String"), "NOT NULL column should use bare type String");
        assertFalse(ch.contains("Nullable(String)"), "NOT NULL column must not be Nullable");
        assertFalse(ch.contains("DEFAULT"), "DEFAULT clause should be stripped");
    }

    @Test
    @DisplayName("ALTER TABLE ADD COLUMN - ONLY keyword is handled")
    void testAlterTableAddColumnOnly() {
        PostgreSQLDDLParserService svc = parser("db");
        String pg = "ALTER TABLE ONLY public.records ADD COLUMN notes TEXT";
        String ch = translate(svc, pg);

        assertTrue(ch.contains("ADD COLUMN IF NOT EXISTS"), "Should translate ONLY variant");
        assertTrue(ch.contains("`db`.`records`"), "Table should be qualified");
    }

    @Test
    @DisplayName("ALTER TABLE DROP COLUMN - translates with IF EXISTS")
    void testAlterTableDropColumn() {
        PostgreSQLDDLParserService svc = parser("mydb");
        String pg = "ALTER TABLE products DROP COLUMN discontinued";
        String ch = translate(svc, pg);

        assertTrue(ch.contains("ALTER TABLE"), "Should contain ALTER TABLE");
        assertTrue(ch.contains("`mydb`.`products`"), "Should qualify table name");
        assertTrue(ch.contains("DROP COLUMN IF EXISTS"), "Should use DROP COLUMN IF EXISTS");
        assertTrue(ch.contains("`discontinued`"), "Should include column name");
    }

    @Test
    @DisplayName("ALTER TABLE DROP COLUMN - IF EXISTS variant and CASCADE are handled")
    void testAlterTableDropColumnIfExistsCascade() {
        PostgreSQLDDLParserService svc = parser("db");
        String pg = "ALTER TABLE t DROP COLUMN IF EXISTS old_col CASCADE";
        String ch = translate(svc, pg);

        assertTrue(ch.contains("DROP COLUMN IF EXISTS"), "Should use DROP COLUMN IF EXISTS");
        assertTrue(ch.contains("`old_col`"), "Column name should be present");
        assertFalse(ch.contains("CASCADE"), "CASCADE should not be in ClickHouse output");
    }

    @Test
    @DisplayName("ALTER TABLE RENAME COLUMN - translates correctly")
    void testAlterTableRenameColumn() {
        PostgreSQLDDLParserService svc = parser("mydb");
        String pg = "ALTER TABLE customers RENAME COLUMN old_name TO new_name";
        String ch = translate(svc, pg);

        assertTrue(ch.contains("ALTER TABLE"), "Should contain ALTER TABLE");
        assertTrue(ch.contains("`mydb`.`customers`"), "Should qualify table name");
        assertTrue(ch.contains("RENAME COLUMN"), "Should contain RENAME COLUMN");
        assertTrue(ch.contains("`old_name`"), "Should include old column name");
        assertTrue(ch.contains("`new_name`"), "Should include new column name");
        assertTrue(ch.contains("TO"), "Should contain TO keyword");
    }

    // -----------------------------------------------------------------------
    // DROP TABLE tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("DROP TABLE - translates to DROP TABLE IF EXISTS")
    void testDropTable() {
        PostgreSQLDDLParserService svc = parser("mydb");
        String pg = "DROP TABLE old_table";
        String ch = translate(svc, pg);

        assertTrue(ch.contains("DROP TABLE IF EXISTS"), "Should use DROP TABLE IF EXISTS");
        assertTrue(ch.contains("`mydb`.`old_table`"), "Should qualify table name");
    }

    @Test
    @DisplayName("DROP TABLE IF EXISTS - passes through correctly")
    void testDropTableIfExists() {
        PostgreSQLDDLParserService svc = parser("mydb");
        String pg = "DROP TABLE IF EXISTS stale_data";
        String ch = translate(svc, pg);

        assertTrue(ch.contains("DROP TABLE IF EXISTS"), "Should always use DROP TABLE IF EXISTS");
        assertTrue(ch.contains("`mydb`.`stale_data`"), "Should qualify table name");
    }

    @Test
    @DisplayName("DROP TABLE CASCADE - CASCADE keyword is stripped")
    void testDropTableCascade() {
        PostgreSQLDDLParserService svc = parser("db");
        String pg = "DROP TABLE IF EXISTS old_table CASCADE";
        String ch = translate(svc, pg);

        assertTrue(ch.contains("DROP TABLE IF EXISTS"), "Should use DROP TABLE IF EXISTS");
        assertFalse(ch.contains("CASCADE"), "CASCADE should not appear in output");
    }

    // -----------------------------------------------------------------------
    // TRUNCATE tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("TRUNCATE TABLE - translates to TRUNCATE TABLE IF EXISTS")
    void testTruncateTable() {
        PostgreSQLDDLParserService svc = parser("mydb");
        String pg = "TRUNCATE TABLE logs";
        String ch = translate(svc, pg);

        assertTrue(ch.contains("TRUNCATE TABLE IF EXISTS"), "Should use TRUNCATE TABLE IF EXISTS");
        assertTrue(ch.contains("`mydb`.`logs`"), "Should qualify table name");
    }

    @Test
    @DisplayName("TRUNCATE without TABLE keyword - still translates")
    void testTruncateWithoutTableKeyword() {
        PostgreSQLDDLParserService svc = parser("db");
        String pg = "TRUNCATE sessions";
        String ch = translate(svc, pg);

        assertTrue(ch.contains("TRUNCATE TABLE IF EXISTS"), "Should use TRUNCATE TABLE IF EXISTS");
        assertTrue(ch.contains("`db`.`sessions`"), "Should qualify table name");
    }

    // -----------------------------------------------------------------------
    // AtomicBoolean isDropOrTruncate flag tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("parseSql with AtomicBoolean - sets true for DROP TABLE")
    void testDropSetsIsDropOrTruncateFlag() {
        PostgreSQLDDLParserService svc = parser("db");
        AtomicBoolean flag = new AtomicBoolean(false);
        StringBuffer buf = new StringBuffer();
        svc.parseSql("DROP TABLE foo", "foo", buf, flag);

        assertTrue(flag.get(), "isDropOrTruncate should be true for DROP TABLE");
    }

    @Test
    @DisplayName("parseSql with AtomicBoolean - sets true for TRUNCATE")
    void testTruncateSetsIsDropOrTruncateFlag() {
        PostgreSQLDDLParserService svc = parser("db");
        AtomicBoolean flag = new AtomicBoolean(false);
        StringBuffer buf = new StringBuffer();
        svc.parseSql("TRUNCATE TABLE bar", "bar", buf, flag);

        assertTrue(flag.get(), "isDropOrTruncate should be true for TRUNCATE");
    }

    @Test
    @DisplayName("parseSql with AtomicBoolean - remains false for CREATE TABLE")
    void testCreateDoesNotSetIsDropOrTruncateFlag() {
        PostgreSQLDDLParserService svc = parser("db");
        AtomicBoolean flag = new AtomicBoolean(false);
        StringBuffer buf = new StringBuffer();
        svc.parseSql("CREATE TABLE foo (id INTEGER PRIMARY KEY)", "foo", buf, flag);

        assertFalse(flag.get(), "isDropOrTruncate should remain false for CREATE TABLE");
    }

    // -----------------------------------------------------------------------
    // Unsupported DDL graceful skip tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Unsupported DDL (CREATE INDEX) - returns empty output gracefully")
    void testUnsupportedCreateIndexIsSkipped() {
        PostgreSQLDDLParserService svc = parser("db");
        String ch = translate(svc, "CREATE INDEX idx_name ON my_table(col1)");

        assertTrue(ch.isEmpty(), "Unsupported DDL should produce empty output");
    }

    @Test
    @DisplayName("Unsupported DDL (CREATE SEQUENCE) - returns empty output gracefully")
    void testUnsupportedCreateSequenceIsSkipped() {
        PostgreSQLDDLParserService svc = parser("db");
        String ch = translate(svc, "CREATE SEQUENCE my_seq START WITH 1");

        assertTrue(ch.isEmpty(), "CREATE SEQUENCE should produce empty output");
    }

    @Test
    @DisplayName("Unsupported DDL (ALTER TABLE SET DEFAULT) - returns empty output gracefully")
    void testUnsupportedAlterTableSetDefaultIsSkipped() {
        PostgreSQLDDLParserService svc = parser("db");
        String ch = translate(svc, "ALTER TABLE t ALTER COLUMN c SET DEFAULT 0");

        assertTrue(ch.isEmpty(), "Unsupported ALTER TABLE variant should produce empty output");
    }

    @Test
    @DisplayName("Null SQL input - parseSql handles gracefully without exception")
    void testNullSqlInput() {
        PostgreSQLDDLParserService svc = parser("db");
        assertDoesNotThrow(() -> {
            StringBuffer buf = new StringBuffer();
            svc.parseSql(null, "table1", buf);
            assertTrue(buf.toString().isEmpty(), "Null SQL should produce empty output");
        });
    }

    @Test
    @DisplayName("Empty SQL input - parseSql handles gracefully without exception")
    void testEmptySqlInput() {
        PostgreSQLDDLParserService svc = parser("db");
        assertDoesNotThrow(() -> {
            StringBuffer buf = new StringBuffer();
            svc.parseSql("   ", "table1", buf);
            assertTrue(buf.toString().isEmpty(), "Blank SQL should produce empty output");
        });
    }

    // -----------------------------------------------------------------------
    // Type mapping unit tests (static method)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("mapPostgresTypeToClickHouse - comprehensive type mapping verification")
    void testTypeMapping() {
        // Integer types
        assertEquals("Int16", PostgreSQLDDLParserService.mapPostgresTypeToClickHouse("SMALLINT"));
        assertEquals("Int32", PostgreSQLDDLParserService.mapPostgresTypeToClickHouse("INTEGER"));
        assertEquals("Int32", PostgreSQLDDLParserService.mapPostgresTypeToClickHouse("INT"));
        assertEquals("Int64", PostgreSQLDDLParserService.mapPostgresTypeToClickHouse("BIGINT"));
        assertEquals("Int64", PostgreSQLDDLParserService.mapPostgresTypeToClickHouse("SERIAL"));
        assertEquals("Int64", PostgreSQLDDLParserService.mapPostgresTypeToClickHouse("BIGSERIAL"));
        assertEquals("Int16", PostgreSQLDDLParserService.mapPostgresTypeToClickHouse("SMALLSERIAL"));

        // Boolean
        assertEquals("UInt8", PostgreSQLDDLParserService.mapPostgresTypeToClickHouse("BOOLEAN"));
        assertEquals("UInt8", PostgreSQLDDLParserService.mapPostgresTypeToClickHouse("BOOL"));

        // Float
        assertEquals("Float32", PostgreSQLDDLParserService.mapPostgresTypeToClickHouse("REAL"));
        assertEquals("Float64", PostgreSQLDDLParserService.mapPostgresTypeToClickHouse("DOUBLE PRECISION"));

        // Decimal
        assertEquals("Decimal(10, 2)", PostgreSQLDDLParserService.mapPostgresTypeToClickHouse("NUMERIC(10,2)"));
        assertEquals("Decimal(5, 3)", PostgreSQLDDLParserService.mapPostgresTypeToClickHouse("DECIMAL(5,3)"));
        assertEquals("Decimal(38, 9)", PostgreSQLDDLParserService.mapPostgresTypeToClickHouse("NUMERIC"));

        // String types
        assertEquals("String", PostgreSQLDDLParserService.mapPostgresTypeToClickHouse("TEXT"));
        assertEquals("String", PostgreSQLDDLParserService.mapPostgresTypeToClickHouse("VARCHAR"));
        assertEquals("String", PostgreSQLDDLParserService.mapPostgresTypeToClickHouse("CHAR"));
        assertEquals("String", PostgreSQLDDLParserService.mapPostgresTypeToClickHouse("JSONB"));
        assertEquals("String", PostgreSQLDDLParserService.mapPostgresTypeToClickHouse("JSON"));
        assertEquals("String", PostgreSQLDDLParserService.mapPostgresTypeToClickHouse("BYTEA"));
        assertEquals("String", PostgreSQLDDLParserService.mapPostgresTypeToClickHouse("INET"));
        assertEquals("String", PostgreSQLDDLParserService.mapPostgresTypeToClickHouse("XML"));

        // UUID
        assertEquals("UUID", PostgreSQLDDLParserService.mapPostgresTypeToClickHouse("UUID"));

        // Date/Time
        assertEquals("Date32", PostgreSQLDDLParserService.mapPostgresTypeToClickHouse("DATE"));
        assertEquals("DateTime64(6)", PostgreSQLDDLParserService.mapPostgresTypeToClickHouse("TIMESTAMP"));
        assertEquals("DateTime64(6, 'UTC')", PostgreSQLDDLParserService.mapPostgresTypeToClickHouse("TIMESTAMPTZ"));

        // Money
        assertEquals("Decimal(19, 4)", PostgreSQLDDLParserService.mapPostgresTypeToClickHouse("MONEY"));

        // OID
        assertEquals("UInt32", PostgreSQLDDLParserService.mapPostgresTypeToClickHouse("OID"));

        // Unknown type falls back to String
        assertEquals("String", PostgreSQLDDLParserService.mapPostgresTypeToClickHouse("UNKNOWN_TYPE_XYZ"));
    }

    @Test
    @DisplayName("mapPostgresTypeToClickHouse - null input returns String")
    void testTypeMappingNull() {
        assertEquals("String", PostgreSQLDDLParserService.mapPostgresTypeToClickHouse(null));
    }

    // -----------------------------------------------------------------------
    // Database name qualification tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("parseSql - no database name produces backtick-quoted table only")
    void testNoDatabaseName() {
        PostgreSQLDDLParserService svc = parser(null);
        String ch = translate(svc, "DROP TABLE mytable");

        assertTrue(ch.contains("`mytable`"), "Table name should be backtick-quoted");
    }

    @Test
    @DisplayName("parseSql - empty database name produces backtick-quoted table only")
    void testEmptyDatabaseName() {
        PostgreSQLDDLParserService svc = parser("");
        String ch = translate(svc, "DROP TABLE mytable");

        assertTrue(ch.contains("`mytable`"), "Table name should be backtick-quoted");
    }
}

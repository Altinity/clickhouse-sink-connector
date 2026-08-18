package com.altinity.clickhouse.sink.connector.db;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DDLSchemaChangeWaiter}.
 * Tests the DDL parsing and column name extraction logic.
 */
class DDLSchemaChangeWaiterTest {

    @Test
    void testExtractAddColumnNames() {
        String ddl = "ALTER TABLE `test_db`.`test_table` ADD COLUMN `new_col1` String, ADD COLUMN `new_col2` Int32";
        List<String> columns = DDLSchemaChangeWaiter.extractColumnNames(ddl,
                Pattern.compile("ADD\\s+COLUMN\\s+`?([^`\\s]+)`?", Pattern.CASE_INSENSITIVE));
        assertEquals(2, columns.size());
        assertTrue(columns.contains("new_col1"));
        assertTrue(columns.contains("new_col2"));
    }

    @Test
    // DESTRUCTIVE: inert DDL string fed to a regex extractor. There is no
    // database connection in this test and no statement is executed.
    void testExtractDropColumnNames() {
        String ddl = "ALTER TABLE `test_db`.`test_table` DROP COLUMN `old_col`";
        List<String> columns = DDLSchemaChangeWaiter.extractColumnNames(ddl,
                Pattern.compile("DROP\\s+COLUMN\\s+`?([^`\\s]+)`?", Pattern.CASE_INSENSITIVE));
        assertEquals(1, columns.size());
        assertEquals("old_col", columns.get(0));
    }

    @Test
    void testExtractUnquotedColumnNames() {
        String ddl = "ALTER TABLE test_db.test_table ADD COLUMN new_col String";
        List<String> columns = DDLSchemaChangeWaiter.extractColumnNames(ddl,
                Pattern.compile("ADD\\s+COLUMN\\s+`?([^`\\s]+)`?", Pattern.CASE_INSENSITIVE));
        assertEquals(1, columns.size());
        assertEquals("new_col", columns.get(0));
    }

    @Test
    void testExtractNoMatchReturnsEmpty() {
        String ddl = "ALTER TABLE test_db.test_table MODIFY COLUMN col1 String";
        List<String> columns = DDLSchemaChangeWaiter.extractColumnNames(ddl,
                Pattern.compile("ADD\\s+COLUMN\\s+`?([^`\\s]+)`?", Pattern.CASE_INSENSITIVE));
        assertTrue(columns.isEmpty());
    }

    @Test
    void testExtractMultipleAddColumns() {
        String ddl = "ALTER TABLE `db`.`tbl` ADD COLUMN `a` Int8, ADD COLUMN `b` String, ADD COLUMN `c` Float64";
        List<String> columns = DDLSchemaChangeWaiter.extractColumnNames(ddl,
                Pattern.compile("ADD\\s+COLUMN\\s+`?([^`\\s]+)`?", Pattern.CASE_INSENSITIVE));
        assertEquals(3, columns.size());
        assertEquals("a", columns.get(0));
        assertEquals("b", columns.get(1));
        assertEquals("c", columns.get(2));
    }

    @Test
    void testWaitForSchemaVisibilityNullInputs() {
        DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(100, 10);
        // Should not throw on null connection or DDL
        assertDoesNotThrow(() -> waiter.waitForSchemaVisibility(null, "ALTER TABLE db.tbl ADD COLUMN x Int32"));
        assertDoesNotThrow(() -> waiter.waitForSchemaVisibility(null, null));
        assertDoesNotThrow(() -> waiter.waitForSchemaVisibility(null, ""));
    }

    @Test
    void testWaitForSchemaVisibilityNonAlterTable() {
        DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(100, 10);
        // Non-ALTER TABLE DDL should return immediately (no table matcher match)
        assertDoesNotThrow(() -> waiter.waitForSchemaVisibility(null, "CREATE TABLE db.tbl (id Int32) ENGINE = MergeTree()"));
    }

    @Test
    void testConstructorDefaults() {
        DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter();
        assertEquals(30_000, DDLSchemaChangeWaiter.DEFAULT_TIMEOUT_MS);
        assertEquals(100, DDLSchemaChangeWaiter.DEFAULT_POLL_INTERVAL_MS);
    }

    @Test
    void testCaseInsensitiveExtraction() {
        String ddl = "alter table `DB`.`TBL` add column `MixedCase` String";
        List<String> columns = DDLSchemaChangeWaiter.extractColumnNames(ddl,
                Pattern.compile("ADD\\s+COLUMN\\s+`?([^`\\s]+)`?", Pattern.CASE_INSENSITIVE));
        assertEquals(1, columns.size());
        assertEquals("MixedCase", columns.get(0));
    }
}

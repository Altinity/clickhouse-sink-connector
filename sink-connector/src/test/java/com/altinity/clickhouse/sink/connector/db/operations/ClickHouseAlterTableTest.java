package com.altinity.clickhouse.sink.connector.db.operations;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ClickHouseAlterTable} SQL syntax generation.
 */
public class ClickHouseAlterTableTest {

    @Test
    @DisplayName("Empty column map returns empty string")
    public void testEmptyColumnMapReturnsEmpty() {
        ClickHouseAlterTable alterTable = new ClickHouseAlterTable();
        String result = alterTable.createAlterTableSyntax(
                "test_db", "test_table",
                Collections.emptyMap(),
                ClickHouseAlterTable.ALTER_TABLE_OPERATION.ADD);
        assertEquals("", result, "Empty column map should return empty string");
    }

    @Test
    @DisplayName("Null column map returns empty string")
    public void testNullColumnMapReturnsEmpty() {
        ClickHouseAlterTable alterTable = new ClickHouseAlterTable();
        String result = alterTable.createAlterTableSyntax(
                "test_db", "test_table",
                null,
                ClickHouseAlterTable.ALTER_TABLE_OPERATION.ADD);
        assertEquals("", result, "Null column map should return empty string");
    }

    @Test
    @DisplayName("Single column ADD produces valid ALTER TABLE SQL")
    public void testSingleColumnAdd() {
        ClickHouseAlterTable alterTable = new ClickHouseAlterTable();
        Map<String, String> cols = new LinkedHashMap<>();
        cols.put("new_col", "String");
        String result = alterTable.createAlterTableSyntax(
                "test_table",
                cols,
                ClickHouseAlterTable.ALTER_TABLE_OPERATION.ADD);
        assertTrue(result.contains("ALTER TABLE"), "Should contain ALTER TABLE");
        assertTrue(result.contains("`new_col`"), "Should backtick-escape column name");
        assertTrue(result.contains("add column"), "Should contain add column");
    }

    @Test
    @DisplayName("Database-prefixed ALTER TABLE includes backtick-escaped database.table")
    public void testDatabasePrefixedAlterTable() {
        ClickHouseAlterTable alterTable = new ClickHouseAlterTable();
        Map<String, String> cols = new LinkedHashMap<>();
        cols.put("col1", "Int32");
        String result = alterTable.createAlterTableSyntax(
                "my_db", "my_table",
                cols,
                ClickHouseAlterTable.ALTER_TABLE_OPERATION.ADD);
        assertTrue(result.contains("`my_db`.`my_table`"),
                "Should contain backtick-escaped database.table: " + result);
    }

    @Test
    @DisplayName("Multiple columns produce comma-separated ADD COLUMN clauses")
    public void testMultipleColumnsAdd() {
        ClickHouseAlterTable alterTable = new ClickHouseAlterTable();
        Map<String, String> cols = new LinkedHashMap<>();
        cols.put("col_a", "String");
        cols.put("col_b", "Int64");
        String result = alterTable.createAlterTableSyntax(
                "test_table",
                cols,
                ClickHouseAlterTable.ALTER_TABLE_OPERATION.ADD);
        assertTrue(result.contains("`col_a`"), "Should contain col_a");
        assertTrue(result.contains("`col_b`"), "Should contain col_b");
        // Should not end with comma
        assertFalse(result.trim().endsWith(","), "Should not end with trailing comma");
    }
}

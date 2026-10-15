package com.altinity.clickhouse.debezium.embedded.ddl;

import io.debezium.relational.RelationalDatabaseConnectorConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DDL for tables outside the connector's capture filters is not replicated
 * (spec 06.08 §3.3).
 *
 * <p><b>The gap.</b> Debezium emits schema-change events for every table of
 * the captured databases unless
 * {@code schema.history.internal.store.only.captured.tables.ddl=true} is set,
 * and the sink DDL path applied every one of them: a {@code CREATE TABLE} for
 * a table outside {@code table.include.list} created a spurious table in
 * ClickHouse; an {@code ALTER TABLE} on such a table failed on the missing
 * target ({@code Code: 60}) and, since a failed DDL is terminal, halted the
 * whole pipeline for a table nobody asked to replicate.</p>
 *
 * <p>Matching follows Debezium: entries are comma-separated regular
 * expressions, matched in full and case-insensitively against
 * {@code db.table} (table lists) or {@code db} (database lists); when an
 * include list is set the corresponding exclude list is ignored.</p>
 */
public class DdlCaptureFilterTest {

    private static Properties props(String... keyValues) {
        Properties p = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) {
            p.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return p;
    }

    @Test
    @DisplayName("With no filters configured every table is captured")
    public void noFiltersCapturesEverything() {
        assertTrue(DdlCaptureFilter.isCaptured("db1", "orders", new Properties()));
        assertTrue(DdlCaptureFilter.isCaptured(null, null, new Properties()));
    }

    @Test
    @DisplayName("table.include.list: only the listed db.table patterns are captured")
    public void tableIncludeList() {
        Properties p = props("table.include.list", "db1\\.orders, db1\\.order_.*");
        assertTrue(DdlCaptureFilter.isCaptured("db1", "orders", p));
        assertTrue(DdlCaptureFilter.isCaptured("db1", "order_lines", p));
        assertFalse(DdlCaptureFilter.isCaptured("db1", "audit", p),
                "a table outside table.include.list is not replicated, so its DDL must not be");
        assertFalse(DdlCaptureFilter.isCaptured("db2", "orders", p), "the database is part of the match");
    }

    @Test
    @DisplayName("table.exclude.list: listed patterns are dropped, everything else captured")
    public void tableExcludeList() {
        Properties p = props("table.exclude.list", "db1\\.audit.*");
        assertTrue(DdlCaptureFilter.isCaptured("db1", "orders", p));
        assertFalse(DdlCaptureFilter.isCaptured("db1", "audit_log", p));
    }

    @Test
    @DisplayName("An include list takes precedence over an exclude list, as in Debezium")
    public void includeWinsOverExclude() {
        Properties p = props("table.include.list", "db1\\.orders", "table.exclude.list", "db1\\.orders");
        assertTrue(DdlCaptureFilter.isCaptured("db1", "orders", p));
    }

    @Test
    @DisplayName("Matching is a full match and case-insensitive, as in Debezium")
    public void fullMatchCaseInsensitive() {
        Properties p = props("table.include.list", "db1\\.orders");
        assertFalse(DdlCaptureFilter.isCaptured("db1", "orders_archive", p), "prefix must not match");
        assertTrue(DdlCaptureFilter.isCaptured("DB1", "ORDERS", p));
    }

    @Test
    @DisplayName("database.include.list / database.exclude.list apply too")
    public void databaseLists() {
        assertFalse(DdlCaptureFilter.isCaptured("db2", "orders", props("database.include.list", "db1")));
        assertTrue(DdlCaptureFilter.isCaptured("db1", "orders", props("database.include.list", "db1")));
        assertFalse(DdlCaptureFilter.isCaptured("scratch", "t", props("database.exclude.list", "scratch")));
        // A database-only decision when the table is unknown (CREATE DATABASE, etc.).
        assertFalse(DdlCaptureFilter.isCaptured("db2", null, props("database.include.list", "db1")));
        assertTrue(DdlCaptureFilter.isCaptured("db1", null, props("table.include.list", "db1\\.orders")),
                "a table-less DDL in a captured database cannot be judged by the table list");
    }

    @Test
    @DisplayName("An unknown database is never filtered out (conservative: never drop a DDL by guessing)")
    public void unknownDatabaseIsKept() {
        assertTrue(DdlCaptureFilter.isCaptured(null, "orders", props("table.include.list", "db1\\.orders")));
    }

    @Test
    @DisplayName("An uncompilable pattern is ignored rather than treated as a match")
    public void badPatternIsIgnored() {
        assertTrue(DdlCaptureFilter.isCaptured("db1", "orders", props("table.exclude.list", "db1\\.(")));
        assertFalse(DdlCaptureFilter.isCaptured("db1", "orders", props("table.include.list", "db1\\.(")),
                "an include list that matches nothing captures nothing, the same as Debezium");
    }

    @Test
    @DisplayName("The list property names are the ones Debezium defines, not restated literals")
    public void propertyNamesComeFromDebezium() {
        assertEquals(RelationalDatabaseConnectorConfig.DATABASE_INCLUDE_LIST.name(),
                DdlCaptureFilter.DATABASE_INCLUDE_LIST);
        assertEquals(RelationalDatabaseConnectorConfig.DATABASE_EXCLUDE_LIST.name(),
                DdlCaptureFilter.DATABASE_EXCLUDE_LIST);
        assertEquals(RelationalDatabaseConnectorConfig.TABLE_INCLUDE_LIST.name(),
                DdlCaptureFilter.TABLE_INCLUDE_LIST);
        assertEquals(RelationalDatabaseConnectorConfig.TABLE_EXCLUDE_LIST.name(),
                DdlCaptureFilter.TABLE_EXCLUDE_LIST);
        // And the filter really reads through them: a list set under Debezium's
        // own field name is honoured.
        assertFalse(DdlCaptureFilter.isCaptured("db1", "audit",
                props(RelationalDatabaseConnectorConfig.TABLE_INCLUDE_LIST.name(), "db1\\.orders")));
        assertFalse(DdlCaptureFilter.isCaptured("scratch", "t",
                props(RelationalDatabaseConnectorConfig.DATABASE_EXCLUDE_LIST.name(), "scratch")));
    }
}

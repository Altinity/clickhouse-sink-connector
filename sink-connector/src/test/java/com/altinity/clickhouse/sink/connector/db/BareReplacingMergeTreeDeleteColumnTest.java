package com.altinity.clickhouse.sink.connector.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The delete column of a ReplacingMergeTree is
 * the second engine argument of the live engine clause, read from
 * system.tables engine_full. A bare {@code ReplacingMergeTree(_version)} has
 * no second argument, so its delete column is absent. The configured
 * {@code replacingmergetree.delete.column} is a default for tables the
 * connector creates; it must never name the delete column of a table whose
 * engine has none, because the engine never reads that column.
 */
public class BareReplacingMergeTreeDeleteColumnTest {

    @Test
    @DisplayName("a bare engine has no delete column, whatever the configuration names")
    public void bareEngineHasNoDeleteColumn() {
        // The configured default (replacingmergetree.delete.column=is_deleted) is not an input:
        // the resolver reads the engine clause only, so a bare engine resolves to no delete column.
        assertNull(DbWriter.resolveReplacingMergeTreeDeleteColumn("_version"));
        assertNull(DbWriter.resolveReplacingMergeTreeDeleteColumn("_version,"));
        assertNull(DbWriter.resolveReplacingMergeTreeDeleteColumn(null));
    }

    @Test
    @DisplayName("a two-argument engine names its delete column, whatever the table's column is called")
    public void twoArgumentEngineNamesItsDeleteColumn() {
        assertEquals("is_deleted", DbWriter.resolveReplacingMergeTreeDeleteColumn("_version, is_deleted"));
        assertEquals("_sign", DbWriter.resolveReplacingMergeTreeDeleteColumn("_version,_sign"));
        assertEquals("_is_deleted", DbWriter.resolveReplacingMergeTreeDeleteColumn("ver,_is_deleted"));
    }
}

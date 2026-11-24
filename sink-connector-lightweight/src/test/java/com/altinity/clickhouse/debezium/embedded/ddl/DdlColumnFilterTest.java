package com.altinity.clickhouse.debezium.embedded.ddl;

import io.debezium.relational.RelationalDatabaseConnectorConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The column lists decide which columns the DDL path creates, not only which
 * values the record path sends.
 *
 * <p><b>The gap.</b> Debezium applies {@code column.include.list} /
 * {@code column.exclude.list} to the record schema, so an excluded column's
 * value is never sent. The schema-DDL path translated the raw source
 * {@code CREATE TABLE} text instead and applied no filter, so an excluded
 * column was still created in ClickHouse — empty, but present. The lists exist
 * to keep a column such as a password out of the analytics database, so an
 * empty column defeats the intent and leaves the ClickHouse schema depending
 * on which creation path happened to run.</p>
 *
 * <p>The matching is Debezium's own: comma-separated regular expressions,
 * matched in full and case-insensitively against {@code db.table.column},
 * lower-cased literals being the entries with no pattern metacharacter. The
 * probe that pinned the semantics against the bundled Debezium is
 * {@code ColumnFilterProbe} in the bug report.</p>
 */
public class DdlColumnFilterTest {

    /** The configuration from the report. */
    private static final String REPORTED_LIST = "app.users.(id|name),app.orders.(id|user_id)";

    private static Properties props(String... keyValues) {
        Properties p = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) {
            p.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return p;
    }

    @Test
    @DisplayName("With no column list configured every column is kept")
    public void noListKeepsEverything() {
        DdlColumnFilter none = DdlColumnFilter.from(new Properties());
        assertTrue(none.isDisabled());
        assertTrue(none.keeps("app", "users", "password"));
        assertTrue(DdlColumnFilter.from(null).keeps("app", "users", "password"));
        assertTrue(DdlColumnFilter.NONE.keeps("app", "users", "password"));
    }

    @Test
    @DisplayName("column.include.list: only the listed columns are created")
    public void includeListKeepsOnlyListedColumns() {
        DdlColumnFilter filter = DdlColumnFilter.from(props("column.include.list", REPORTED_LIST));
        assertFalse(filter.isDisabled());

        assertTrue(filter.keeps("app", "users", "id"));
        assertTrue(filter.keeps("app", "users", "name"));
        assertFalse(filter.keeps("app", "users", "password"),
                "users.password is not in the list, so it must not be created");
        assertTrue(filter.keeps("app", "orders", "user_id"));
        assertFalse(filter.keeps("app", "orders", "total"));
        assertFalse(filter.keeps("other", "users", "id"),
                "the database is part of the match, and other.users is not in the list");
    }

    @Test
    @DisplayName("column.exclude.list: the listed columns are the only ones dropped")
    public void excludeListDropsOnlyListedColumns() {
        DdlColumnFilter filter = DdlColumnFilter.from(props("column.exclude.list", "app.users.password"));
        assertTrue(filter.keeps("app", "users", "id"));
        assertFalse(filter.keeps("app", "users", "password"));
        assertTrue(filter.keeps("app", "orders", "password"),
                "the entry names app.users, so another table's password column stays");
    }

    @Test
    @DisplayName("An include list takes precedence over an exclude list, as in Debezium")
    public void includeWinsOverExclude() {
        DdlColumnFilter filter = DdlColumnFilter.from(props(
                "column.include.list", "app.users.(id|name)",
                "column.exclude.list", "app.users.name"));
        assertTrue(filter.keeps("app", "users", "name"));
        assertFalse(filter.keeps("app", "users", "password"));
    }

    @Test
    @DisplayName("Matching is a full match and case-insensitive, as in Debezium")
    public void fullMatchCaseInsensitive() {
        DdlColumnFilter filter = DdlColumnFilter.from(props("column.include.list", "app.users.id"));
        assertTrue(filter.keeps("APP", "USERS", "ID"));
        assertFalse(filter.keeps("app", "users", "id2"), "the pattern is anchored, so a prefix must not match");
    }

    @Test
    @DisplayName("An unknown database or column is never filtered out")
    public void unknownIdentifiersAreKept() {
        DdlColumnFilter filter = DdlColumnFilter.from(props("column.include.list", REPORTED_LIST));
        // Debezium's matcher answers "no" for a null catalog, which would drop
        // an entire table; the filter must not let that happen.
        assertTrue(filter.keeps(null, "users", "id"));
        assertTrue(filter.keeps("", "users", "id"));
        assertTrue(filter.keeps("app", "users", null));
        assertTrue(filter.keeps("app", "users", ""));
    }

    @Test
    @DisplayName("The list property names are the ones Debezium defines, not restated literals")
    public void propertyNamesComeFromDebezium() {
        assertEquals(RelationalDatabaseConnectorConfig.COLUMN_INCLUDE_LIST.name(),
                DdlColumnFilter.COLUMN_INCLUDE_LIST);
        assertEquals(RelationalDatabaseConnectorConfig.COLUMN_EXCLUDE_LIST.name(),
                DdlColumnFilter.COLUMN_EXCLUDE_LIST);
        // And the filter really reads through them: a list set under Debezium's
        // own field name is honoured.
        assertFalse(DdlColumnFilter.from(props(RelationalDatabaseConnectorConfig.COLUMN_INCLUDE_LIST.name(),
                "app.users.id")).keeps("app", "users", "password"));
    }

    @Test
    @DisplayName("A blank list is no list")
    public void blankListKeepsEverything() {
        assertTrue(DdlColumnFilter.from(props("column.include.list", "   ")).isDisabled());
        assertTrue(DdlColumnFilter.from(props("column.include.list", "")).isDisabled());
    }
}

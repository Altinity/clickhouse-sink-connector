package com.altinity.clickhouse.sink.connector.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link DbWriter#databaseOfOffsetTable(String)} parses the qualified offset
 * table name from the configuration.
 *
 * <p>Regression cover for #1379. The property is a {@code database.table}
 * pair -- exactly two parts -- but the arity guard rejected anything with
 * {@code length <= 2}, so the correctly configured
 * {@code altinity_sink_connector.replica_source_info} that every sample
 * config in this repository ships was refused and the method always returned
 * {@code null}. The accompanying WARN then claimed the "query was not
 * provided in configuration", which sent the #1379 reporter looking for a
 * schema-history misconfiguration that does not exist on the PostgreSQL path
 * at all.</p>
 */
public class OffsetStorageDatabaseNameTest {

    /** The offset table name every sample config in this repository ships. */
    private static final String SHIPPED_DEFAULT =
            "altinity_sink_connector.replica_source_info";

    @Test
    @DisplayName("the shipped database.table default resolves to its database")
    public void testShippedDefaultResolves() {
        // Fails before the fix: this value splits into exactly 2 parts, the
        // '<= 2' guard rejected it, and the method returned null.
        assertEquals("altinity_sink_connector",
                DbWriter.databaseOfOffsetTable(SHIPPED_DEFAULT),
                "a two-part database.table name must resolve to its database");
    }

    @Test
    @DisplayName("a three-part name still resolves to its first segment")
    public void testThreePartNameResolves() {
        assertEquals("db", DbWriter.databaseOfOffsetTable("db.schema.table"));
    }

    @Test
    @DisplayName("an unqualified name is rejected -- there is no database in it")
    public void testUnqualifiedNameRejected() {
        assertNull(DbWriter.databaseOfOffsetTable("replica_source_info"),
                "a bare table name carries no database and must not be accepted");
    }

    @Test
    @DisplayName("an unset property is rejected")
    public void testUnsetPropertyRejected() {
        assertNull(DbWriter.databaseOfOffsetTable(null));
    }

    @Test
    @DisplayName("an empty property is rejected")
    public void testEmptyPropertyRejected() {
        assertNull(DbWriter.databaseOfOffsetTable(""));
    }
}

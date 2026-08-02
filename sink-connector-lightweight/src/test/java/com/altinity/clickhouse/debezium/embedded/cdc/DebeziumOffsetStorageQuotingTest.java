package com.altinity.clickhouse.debezium.embedded.cdc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link DebeziumOffsetStorage#quoteQualifiedName(String)}.
 *
 * <p>The configured offset/schema-history table name is routinely
 * database-qualified (the shipped default is
 * {@code altinity_sink_connector.replica_source_info}). Wrapping the whole
 * dotted name in one pair of backticks made ClickHouse treat the qualifier
 * as part of the identifier and resolve it against the CURRENT database:
 * {@code Table system.`altinity_sink_connector.replica_source_info` doesn't
 * exist (UNKNOWN_TABLE)} — which aborted connector startup, was classified
 * FATAL, and cascaded across the java-lightweight IT suite.</p>
 */
class DebeziumOffsetStorageQuotingTest {

    @Test
    @DisplayName("Database-qualified name is quoted per component")
    void qualifiedName() {
        assertEquals("`altinity_sink_connector`.`replica_source_info`",
                DebeziumOffsetStorage.quoteQualifiedName(
                        "altinity_sink_connector.replica_source_info"));
    }

    @Test
    @DisplayName("Unqualified name is quoted as a single identifier")
    void unqualifiedName() {
        assertEquals("`replica_source_info`",
                DebeziumOffsetStorage.quoteQualifiedName("replica_source_info"));
    }

    @Test
    @DisplayName("Backticks inside a component are doubled (no escape)")
    void backtickEscaping() {
        assertEquals("`db`.`ta``ble`",
                DebeziumOffsetStorage.quoteQualifiedName("db.ta`ble"));
    }

    @Test
    @DisplayName("The shipped default 'default.replica_source_info' also works")
    void shippedDefault() {
        assertEquals("`default`.`replica_source_info`",
                DebeziumOffsetStorage.quoteQualifiedName(
                        "default.replica_source_info"));
    }
}

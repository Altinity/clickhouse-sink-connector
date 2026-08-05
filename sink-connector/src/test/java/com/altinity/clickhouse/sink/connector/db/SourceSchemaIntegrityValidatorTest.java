package com.altinity.clickhouse.sink.connector.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SourceSchemaIntegrityValidator}, the data-loss guard that
 * detects source-event columns missing from the destination cache.
 *
 * <p>This is the check that would have caught the {@code price_usd} incident:
 * the column existed in the MySQL source event but not in the (stale)
 * destination cache, so it was silently dropped on insert.</p>
 */
class SourceSchemaIntegrityValidatorTest {

    @Test
    @DisplayName("All source columns present in destination -> consistent")
    void allPresent() {
        List<String> source = Arrays.asList("id", "price", "price_usd");
        Set<String> dest = new LinkedHashSet<>(Arrays.asList(
                "id", "price", "price_usd", "_version", "is_deleted"));
        SourceSchemaIntegrityValidator.Result r =
                SourceSchemaIntegrityValidator.check(source, dest);
        assertTrue(r.isConsistent());
        assertTrue(r.getMissingInDestination().isEmpty());
    }

    @Test
    @DisplayName("Source column missing from destination -> inconsistent (the price_usd case)")
    void missingNewColumnIsDetected() {
        // Destination cache is stale: it predates the ADD COLUMN price_usd.
        List<String> source = Arrays.asList("id", "price", "price_usd");
        Set<String> dest = new LinkedHashSet<>(Arrays.asList("id", "price"));
        SourceSchemaIntegrityValidator.Result r =
                SourceSchemaIntegrityValidator.check(source, dest);
        assertFalse(r.isConsistent());
        assertEquals(List.of("price_usd"), r.getMissingInDestination());
    }

    @Test
    @DisplayName("Destination-only columns (version/sign/metadata) do not trigger false positives")
    void destinationOnlyColumnsIgnored() {
        List<String> source = Arrays.asList("id", "price");
        Set<String> dest = new LinkedHashSet<>(Arrays.asList(
                "id", "price", "_version", "_sign", "is_deleted",
                "_topic", "_offset", "_partition"));
        SourceSchemaIntegrityValidator.Result r =
                SourceSchemaIntegrityValidator.check(source, dest);
        assertTrue(r.isConsistent(),
                "Destination-only bookkeeping columns must not be flagged");
    }

    @Test
    @DisplayName("Case-insensitive matching avoids false data-loss alarms")
    void caseInsensitiveMatch() {
        List<String> source = Arrays.asList("ID", "Price_USD");
        Set<String> dest = new LinkedHashSet<>(Arrays.asList("id", "price_usd"));
        SourceSchemaIntegrityValidator.Result r =
                SourceSchemaIntegrityValidator.check(source, dest);
        assertTrue(r.isConsistent());
    }

    @Test
    @DisplayName("Multiple missing columns are all reported")
    void multipleMissingReported() {
        List<String> source = Arrays.asList("id", "a", "b", "c");
        Set<String> dest = new LinkedHashSet<>(Arrays.asList("id"));
        SourceSchemaIntegrityValidator.Result r =
                SourceSchemaIntegrityValidator.check(source, dest);
        assertFalse(r.isConsistent());
        assertEquals(3, r.getMissingInDestination().size());
        assertTrue(r.getMissingInDestination().containsAll(Arrays.asList("a", "b", "c")));
    }

    @Test
    @DisplayName("Null / empty inputs are handled safely")
    void nullSafety() {
        assertTrue(SourceSchemaIntegrityValidator.check(null, null).isConsistent());
        assertTrue(SourceSchemaIntegrityValidator.check(
                Collections.emptyList(), Collections.emptySet()).isConsistent());
        // Source present but destination empty -> all source columns missing.
        assertFalse(SourceSchemaIntegrityValidator.check(
                List.of("x"), Collections.emptySet()).isConsistent());
    }

    @Test
    @DisplayName("validateOrThrow throws with the missing columns on violation")
    void validateOrThrowThrows() {
        SourceSchemaIntegrityValidator.SchemaIntegrityException ex =
                assertThrows(SourceSchemaIntegrityValidator.SchemaIntegrityException.class,
                        () -> SourceSchemaIntegrityValidator.validateOrThrow(
                                "db.t",
                                List.of("id", "price_usd"),
                                Set.of("id")));
        assertEquals(List.of("price_usd"), ex.getMissingColumns());
    }

    @Test
    @DisplayName("validateOrThrow passes silently when consistent")
    void validateOrThrowPasses() throws Exception {
        SourceSchemaIntegrityValidator.validateOrThrow(
                "db.t", List.of("id", "price"), Set.of("id", "price", "_version"));
    }

    // ------------------------------------------------------------------
    // excludeGeneratedColumns — the MATERIALIZED-column livelock guard.
    //
    // MySQL generated columns replicate as MATERIALIZED columns on the
    // destination. They are deliberately absent from the insertable cache
    // (ClickHouse computes them; inserting into them is rejected), so they
    // appear "missing" to check() on EVERY batch. Without this filter the
    // integrity gate invalidated and rebuilt the writer forever — 8,208
    // cycles observed in one CI run — stalling replication for any table
    // with a generated column.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Generated (MATERIALIZED/ALIAS) destination columns are not 'missing'")
    void generatedColumnsAreExcluded() {
        // calculated-columns scenario: sum_col..div_col are MySQL generated
        // columns, MATERIALIZED on the destination.
        List<String> missing = List.of("sum_col", "diff_col", "prod_col", "div_col");
        Set<String> generated = Set.of("sum_col", "diff_col", "prod_col", "div_col");
        assertTrue(SourceSchemaIntegrityValidator
                .excludeGeneratedColumns(missing, generated).isEmpty());
    }

    @Test
    @DisplayName("Genuinely missing columns survive the generated-column filter")
    void genuineMissingSurvives() {
        List<String> missing = List.of("sum_col", "price_usd");
        Set<String> generated = Set.of("sum_col");
        assertEquals(List.of("price_usd"),
                SourceSchemaIntegrityValidator
                        .excludeGeneratedColumns(missing, generated));
    }

    @Test
    @DisplayName("Generated-column match is case-insensitive")
    void generatedMatchIsCaseInsensitive() {
        assertTrue(SourceSchemaIntegrityValidator
                .excludeGeneratedColumns(List.of("Sum_Col"), Set.of("sum_col"))
                .isEmpty());
    }

    @Test
    @DisplayName("excludeGeneratedColumns handles null inputs")
    void excludeGeneratedNullSafety() {
        assertTrue(SourceSchemaIntegrityValidator
                .excludeGeneratedColumns(null, Set.of("a")).isEmpty());
        assertEquals(List.of("a"),
                SourceSchemaIntegrityValidator
                        .excludeGeneratedColumns(List.of("a"), null));
    }

    // ------------------------------------------------------------------
    // isReplicationHistoryTable — the history-table exemption.
    //
    // The replication-history table has its own fixed audit schema
    // (gtid/ddl/database/table/before/after/...); source-row columns are
    // serialized INTO its payload columns, not mapped one-to-one. Running
    // the integrity gate against it flags every source column "missing",
    // blocks each batch for the full visibility-wait timeout polling
    // system.columns for columns that can never appear, skips the history
    // write, and starves the shared connection pool for the process.
    // ------------------------------------------------------------------

    private static com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig
            historyConfig(boolean enabled) {
        java.util.Map<String, String> props = new java.util.HashMap<>();
        props.put("replication.history.enable", String.valueOf(enabled));
        props.put("replication.history.database.name", "replication_history_db");
        props.put("replication.history.table.name", "replication_history");
        return new com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig(props);
    }

    @Test
    @DisplayName("History table is exempt from the integrity gate when history is enabled")
    void historyTableIsExempt() {
        assertTrue(SourceSchemaIntegrityValidator.isReplicationHistoryTable(
                historyConfig(true), "replication_history_db.replication_history"));
    }

    @Test
    @DisplayName("History-table match is case-insensitive")
    void historyTableMatchIsCaseInsensitive() {
        assertTrue(SourceSchemaIntegrityValidator.isReplicationHistoryTable(
                historyConfig(true), "Replication_History_DB.Replication_History"));
    }

    @Test
    @DisplayName("Ordinary data tables are NOT exempt")
    void ordinaryTableIsNotExempt() {
        assertFalse(SourceSchemaIntegrityValidator.isReplicationHistoryTable(
                historyConfig(true), "employees.employees"));
    }

    @Test
    @DisplayName("No exemption when replication history is disabled")
    void noExemptionWhenHistoryDisabled() {
        assertFalse(SourceSchemaIntegrityValidator.isReplicationHistoryTable(
                historyConfig(false), "replication_history_db.replication_history"));
    }

    @Test
    @DisplayName("isReplicationHistoryTable handles null inputs")
    void historyExemptionNullSafety() {
        assertFalse(SourceSchemaIntegrityValidator.isReplicationHistoryTable(
                null, "db.t"));
        assertFalse(SourceSchemaIntegrityValidator.isReplicationHistoryTable(
                historyConfig(true), null));
    }
}

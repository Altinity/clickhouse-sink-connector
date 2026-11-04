package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.db.ClickHouseZoneCrossCheck.ColumnZone;
import com.altinity.clickhouse.sink.connector.db.ClickHouseZoneCrossCheck.ZoneMetadataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 07.03 section 3.1.6 (B5): the configured ClickHouse zone is cross-checked against
 * {@code SELECT timezone()} and existing DateTime column zones. Each mismatch is one WARN per column; an unreadable
 * server zone is also a WARN. Nothing refuses startup.
 */
public class ClickHouseZoneCrossCheckTest {

    /** A fake metadata source. A non-null failure makes the server read fail. */
    private static ZoneMetadataSource source(String serverZone, List<ColumnZone> columns, SQLException failure) {
        return new ZoneMetadataSource() {
            @Override
            public String serverTimezone() throws SQLException {
                if (failure != null) {
                    throw failure;
                }
                return serverZone;
            }

            @Override
            public List<ColumnZone> dateTimeColumnZones() {
                return columns;
            }
        };
    }

    private static ColumnZone column(String table, String zone) {
        return new ColumnZone("db", table, "ts", zone, "MergeTree");
    }

    @Test
    @DisplayName("zoneOfType reads the explicit zone of DateTime and DateTime64, including wrapped types")
    void zoneOfTypeReadsExplicitZones() {
        assertEquals("UTC", ClickHouseZoneCrossCheck.zoneOfType("DateTime('UTC')"));
        assertEquals("America/Chicago", ClickHouseZoneCrossCheck.zoneOfType("DateTime64(3, 'America/Chicago')"));
        assertEquals("UTC", ClickHouseZoneCrossCheck.zoneOfType("Nullable(DateTime64(6, 'UTC'))"));
        assertEquals("UTC", ClickHouseZoneCrossCheck.zoneOfType("LowCardinality(Nullable(DateTime('UTC')))"));
        assertNull(ClickHouseZoneCrossCheck.zoneOfType("DateTime"));
        assertNull(ClickHouseZoneCrossCheck.zoneOfType("DateTime64(3)"));
        assertNull(ClickHouseZoneCrossCheck.zoneOfType("String"));
        assertNull(ClickHouseZoneCrossCheck.zoneOfType(null));
    }

    @Test
    @DisplayName("configured zone matching the server and every column produces no findings")
    void agreementHasNoFindings() throws SQLException {
        var findings = ClickHouseZoneCrossCheck.findMismatches("America/Chicago",
                source("America/Chicago", List.of(column("t1", "America/Chicago")), null));
        assertTrue(findings.isEmpty(), findings.toString());
    }

    @Test
    @DisplayName("an equivalent zone name (US/Central) is not a column mismatch")
    void equivalentZoneNameIsNotReported() throws SQLException {
        var findings = ClickHouseZoneCrossCheck.findMismatches("America/Chicago",
                source("America/Chicago", List.of(column("legacy", "US/Central")), null));
        assertTrue(findings.isEmpty(), findings.toString());
    }

    @Test
    @DisplayName("configured zone that differs from timezone() is one finding naming both zones")
    void serverMismatchIsReported() throws SQLException {
        var findings = ClickHouseZoneCrossCheck.findMismatches("America/Chicago",
                source("UTC", List.of(), null));
        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.get(0).contains("'America/Chicago'"), findings.get(0));
        assertTrue(findings.get(0).contains("'UTC'"), findings.get(0));
    }

    @Test
    @DisplayName("a column whose explicit zone differs from the configured zone is one WARN naming table.column, declared and configured zone")
    void columnMismatchIsReportedByName() throws SQLException {
        var findings = ClickHouseZoneCrossCheck.findMismatches("America/Chicago",
                source("America/Chicago", List.of(column("orders", "UTC")), null));
        assertEquals(1, findings.size(), findings.toString());
        String finding = findings.get(0);
        assertTrue(finding.contains("db.orders.ts"), finding);
        assertTrue(finding.contains("declares zone 'UTC'"), finding);
        assertTrue(finding.contains("clickhouse.datetime.timezone is 'America/Chicago'"), finding);
    }

    @Test
    @DisplayName("each mismatched column is its own finding, so the log has one WARN per column")
    void eachColumnIsItsOwnFinding() throws SQLException {
        var findings = ClickHouseZoneCrossCheck.findMismatches("America/Chicago",
                source("America/Chicago", List.of(column("orders", "UTC"), column("items", "UTC")), null));
        assertEquals(2, findings.size(), findings.toString());
    }

    @Test
    @DisplayName("An unset configured zone is refused; the server zone is never the reference")
    void unsetConfiguredZoneIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ClickHouseZoneCrossCheck.findMismatches("",
                source("Europe/Berlin", List.of(column("a", "Europe/Berlin")), null)));
        assertThrows(IllegalArgumentException.class, () -> ClickHouseZoneCrossCheck.findMismatches(null,
                source("UTC", List.of(), null)));
    }

    @Test
    @DisplayName("A view's declared zone is its output type, not a stored value, so it is not reported")
    void viewColumnsAreNotReported() throws SQLException {
        var findings = ClickHouseZoneCrossCheck.findMismatches("America/Chicago",
                source("America/Chicago", List.of(new ColumnZone("db", "v", "utc_time", "UTC", "View")), null));
        assertTrue(findings.isEmpty(), findings.toString());
    }

    @Test
    @DisplayName("The sink's own metadata database, including its show_replica_status view, is exempt by schema name")
    void metadataDatabaseIsExemptBySchemaName() throws SQLException {
        var findings = ClickHouseZoneCrossCheck.findMismatches("America/Chicago",
                source("America/Chicago", List.of(
                        new ColumnZone("altinity_sink_connector", "show_replica_status", "utc_time", "UTC", "View"),
                        new ColumnZone("altinity_sink_connector", "metadata", "utc_time", "UTC", "MergeTree")), null));
        assertTrue(findings.isEmpty(), findings.toString());
    }

    @Test
    @DisplayName("A base table column in another database is still reported")
    void baseTableColumnMismatchIsStillReported() throws SQLException {
        var findings = ClickHouseZoneCrossCheck.findMismatches("America/Chicago",
                source("America/Chicago", List.of(new ColumnZone("db", "t", "ts", "UTC", "MergeTree")), null));
        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.get(0).contains("db.t.ts"), findings.get(0));
    }

    @Test
    @DisplayName("an unreadable server zone throws so the caller can WARN; it is not a finding")
    void unreadableServerZoneThrows() {
        assertThrows(SQLException.class, () -> ClickHouseZoneCrossCheck.findMismatches("America/Chicago",
                source(null, List.of(), new SQLException("connection refused"))));
    }

    @Test
    @DisplayName("an empty SELECT timezone() result throws rather than being compared")
    void emptyServerZoneThrows() {
        assertThrows(SQLException.class, () -> ClickHouseZoneCrossCheck.findMismatches("America/Chicago",
                source("", List.of(), null)));
    }

    @Test
    @DisplayName("report returns the same findings it logs; a clean run returns an empty list")
    void reportReturnsFindings() throws SQLException {
        var findings = ClickHouseZoneCrossCheck.report("America/Chicago",
                source("America/Chicago", List.of(column("orders", "UTC"), column("ok", "America/Chicago")), null));
        assertEquals(1, findings.size(), findings.toString());
        assertTrue(ClickHouseZoneCrossCheck.report("America/Chicago",
                source("America/Chicago", List.of(), null)).isEmpty());
    }

    @Test
    @DisplayName("a mismatched existing column never throws from the startup check, with or without a connection")
    void startupCheckNeverRefuses() {
        assertDoesNotThrow(() -> ClickHouseZoneCrossCheck.checkAtStartup("America/Chicago", () -> null));
        assertDoesNotThrow(() -> ClickHouseZoneCrossCheck.checkAtStartup("America/Chicago", () -> {
            throw new IllegalStateException("no ClickHouse");
        }));
    }
}

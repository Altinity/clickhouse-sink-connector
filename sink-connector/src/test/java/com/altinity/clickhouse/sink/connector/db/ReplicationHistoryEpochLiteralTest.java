package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.metadata.DataTypeRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 12.04 section 3.5 and Spec 07.03 section 3.1.11: every replication-history
 * instant is a unix epoch in the SQL, so no zone can move it. Golden values are
 * the ClickHouse 24.8.8.17 and 25.8.16 results of the same literals.
 */
public class ReplicationHistoryEpochLiteralTest {

    /** 2100-01-01T00:00:00Z, the open-row sentinel (DataTypeRange.DATETIME32_MAX_TTL). */
    private static final long SENTINEL = 4102444800L;
    /** 2026-11-01 01:30 America/Chicago, first pass (CDT, UTC-5) = 06:30Z. */
    private static final long OVERLAP_FIRST_PASS = 1793514600L;
    /** The same wall clock, second pass (CST, UTC-6) = 07:30Z. */
    private static final long OVERLAP_SECOND_PASS = 1793518200L;

    private static String bulkClose(String validToMax, String binlogTs, String zone) {
        return new QueryFormatter().getInsertQueryForBulkClose("h.items", true, validToMax, binlogTs, 7L, "T", zone);
    }

    @Test
    @DisplayName("The sentinel is the epoch 4102444800, i.e. 2100-01-01T00:00:00Z")
    public void sentinelIsTheEpochOfTheOpenRowInstant() {
        assertEquals(SENTINEL, DataTypeRange.DATETIME32_MAX_TTL);
        assertEquals(Instant.parse("2100-01-01T00:00:00Z"), Instant.ofEpochSecond(DataTypeRange.DATETIME32_MAX_TTL));
    }

    @Test
    @DisplayName("The overlap golden instants are the two real occurrences of 01:30 America/Chicago on 2026-11-01")
    public void overlapGoldenInstants() {
        assertEquals(OVERLAP_FIRST_PASS, Instant.parse("2026-11-01T06:30:00Z").getEpochSecond());
        assertEquals(OVERLAP_SECOND_PASS, Instant.parse("2026-11-01T07:30:00Z").getEpochSecond());
        // Wall-clock resolution picks the first pass only: this is why the text form lost the second pass.
        assertEquals(OVERLAP_FIRST_PASS, ZonedDateTime.of(LocalDateTime.of(2026, 11, 1, 1, 30),
                ZoneId.of("America/Chicago")).toEpochSecond());
    }

    @Test
    @DisplayName("The second pass of a DST overlap keeps its own instant in the bulk-close SQL (old text form: first pass)")
    public void overlapSecondPassIsNotCollapsedIntoFirstPass() {
        String first = bulkClose(String.valueOf(SENTINEL), String.valueOf(OVERLAP_FIRST_PASS), "America/Chicago");
        String second = bulkClose(String.valueOf(SENTINEL), String.valueOf(OVERLAP_SECOND_PASS), "America/Chicago");

        assertTrue(first.contains("toDateTime(" + OVERLAP_FIRST_PASS + ")"), first);
        assertTrue(second.contains("toDateTime(" + OVERLAP_SECOND_PASS + ")"), second);
        assertFalse(second.contains("2026-11-01"), "no wall-clock text may reach the SQL: " + second);
        assertFalse(second.equals(first), "the two passes must not render the same statement");
    }

    @Test
    @DisplayName("History literals are epoch-exact: the statement is identical whatever zone the caller names")
    public void historyLiteralsDoNotDependOnZone() {
        String utc = bulkClose(String.valueOf(SENTINEL), String.valueOf(OVERLAP_SECOND_PASS), "UTC");
        String chicago = bulkClose(String.valueOf(SENTINEL), String.valueOf(OVERLAP_SECOND_PASS), "America/Chicago");
        String apia = bulkClose(String.valueOf(SENTINEL), String.valueOf(OVERLAP_SECOND_PASS), "Pacific/Apia");
        assertEquals(utc, chicago);
        assertEquals(utc, apia);
        assertTrue(utc.contains("toDateTime(" + SENTINEL + ")"), utc);
    }

    @Test
    @DisplayName("The open-row predicate uses the epoch literal, with no quoted text and no zone argument")
    public void openRowPredicateIsEpochLiteral() {
        String sql = new QueryFormatter().getInsertQueryForDelete("h.items",
                java.util.Collections.singletonMap("item_id", "Int32"), "item_id", 99,
                String.valueOf(SENTINEL), String.valueOf(OVERLAP_SECOND_PASS), 7L, "America/Chicago").getLeft();
        assertTrue(sql.contains("`_valid_to` = toDateTime(" + SENTINEL + ")"), sql);
        assertFalse(sql.contains("'2100-01-01"), sql);
        assertFalse(sql.contains("'America/Chicago'"), sql);
    }

    @Test
    @DisplayName("The DDL DEFAULT for deleted_time is the epoch, not a quoted string read in the column zone")
    public void deletedTimeDefaultIsEpochExact() {
        assertEquals("DateTime DEFAULT 4102444800",
                com.altinity.clickhouse.sink.connector.db.ClickHouseDbConstants.DELETED_TIME_COLUMN_DATA_TYPE);
    }
}

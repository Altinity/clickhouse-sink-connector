package com.altinity.clickhouse.sink.connector.metadata;

import org.junit.Assert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.*;

/**
 * Tests for DataTypeRange — Phase 10 edge case coverage.
 * <p>
 * Validates:
 * - DateTime64 MIN/MAX Instant calculations are correct
 * - DateTime32 range boundaries
 * - Date32 boundaries are set
 * - epochSecondsToDateString conversion
 * </p>
 */
public class DataTypeRangeTest {

    @Nested
    @DisplayName("DateTime64 boundaries")
    class DateTime64Tests {

        @Test
        @DisplayName("MIN_DATETIME64 should correspond to 1900-01-01T00:00:00Z")
        public void testMinDateTime64() {
            Instant expected = LocalDateTime
                    .of(LocalDate.of(1900, 1, 1), LocalTime.MIN)
                    .toInstant(ZoneOffset.UTC);
            Assert.assertEquals("MIN_DATETIME64 should be 1900-01-01T00:00:00Z",
                    expected, DataTypeRange.CLICKHOUSE_MIN_SUPPORTED_DATETIME64);
        }

        @Test
        @DisplayName("MAX_DATETIME64 should correspond to 2299-12-31T23:59:59.999999999Z")
        public void testMaxDateTime64() {
            Instant expected = LocalDateTime
                    .of(LocalDate.of(2299, 12, 31), LocalTime.MAX)
                    .toInstant(ZoneOffset.UTC);
            // Both should represent the same date (2299-12-31) in UTC
            Assert.assertEquals("MAX_DATETIME64 year should be 2299",
                    expected.atZone(ZoneOffset.UTC).getYear(),
                    DataTypeRange.CLICKHOUSE_MAX_SUPPORTED_DATETIME64
                            .atZone(ZoneOffset.UTC).getYear());
            Assert.assertEquals("MAX_DATETIME64 month should be 12",
                    expected.atZone(ZoneOffset.UTC).getMonthValue(),
                    DataTypeRange.CLICKHOUSE_MAX_SUPPORTED_DATETIME64
                            .atZone(ZoneOffset.UTC).getMonthValue());
            Assert.assertEquals("MAX_DATETIME64 day should be 31",
                    expected.atZone(ZoneOffset.UTC).getDayOfMonth(),
                    DataTypeRange.CLICKHOUSE_MAX_SUPPORTED_DATETIME64
                            .atZone(ZoneOffset.UTC).getDayOfMonth());
        }

        @Test
        @DisplayName("MIN_DATETIME64 should be before MAX_DATETIME64")
        public void testMinBeforeMax() {
            Assert.assertTrue("MIN should be before MAX",
                    DataTypeRange.CLICKHOUSE_MIN_SUPPORTED_DATETIME64
                            .isBefore(DataTypeRange.CLICKHOUSE_MAX_SUPPORTED_DATETIME64));
        }

        @Test
        @DisplayName("DATETIME64_MIN epoch seconds should be negative (before 1970)")
        public void testMinEpochSeconds() {
            Assert.assertTrue("DATETIME64_MIN should be negative (before epoch)",
                    DataTypeRange.DATETIME64_MIN < 0);
        }

        @Test
        @DisplayName("DATETIME64_MAX epoch seconds should be positive (after 1970)")
        public void testMaxEpochSeconds() {
            Assert.assertTrue("DATETIME64_MAX should be positive (after epoch)",
                    DataTypeRange.DATETIME64_MAX > 0);
        }
    }

    @Nested
    @DisplayName("DateTime32 boundaries")
    class DateTime32Tests {

        @Test
        @DisplayName("DATETIME32_MIN should be 0 (Unix epoch)")
        public void testDateTime32Min() {
            Assert.assertEquals(0L, DataTypeRange.DATETIME32_MIN);
        }

        @Test
        @DisplayName("DATETIME32_MAX should be in year 2106")
        public void testDateTime32Max() {
            ZonedDateTime dt = Instant.ofEpochSecond(DataTypeRange.DATETIME32_MAX)
                    .atZone(ZoneOffset.UTC);
            Assert.assertEquals("DATETIME32_MAX year should be 2106",
                    2106, dt.getYear());
        }

        @Test
        @DisplayName("DATETIME32_MAX_TTL should be less than DATETIME32_MAX")
        public void testDateTime32MaxTtl() {
            Assert.assertTrue("TTL max should be less than absolute max",
                    DataTypeRange.DATETIME32_MAX_TTL < DataTypeRange.DATETIME32_MAX);
        }
    }

    @Nested
    @DisplayName("Date32 boundaries")
    class Date32Tests {

        @Test
        @DisplayName("Date32 boundaries should be set (non-zero)")
        public void testDate32BoundariesSet() {
            Assert.assertNotNull(DataTypeRange.CLICKHOUSE_MIN_SUPPORTED_DATE32);
            Assert.assertNotNull(DataTypeRange.CLICKHOUSE_MAX_SUPPORTED_DATE32);
            Assert.assertTrue("MIN_DATE32 should be less than MAX_DATE32",
                    DataTypeRange.CLICKHOUSE_MIN_SUPPORTED_DATE32
                            < DataTypeRange.CLICKHOUSE_MAX_SUPPORTED_DATE32);
        }
    }

    @Nested
    @DisplayName("epochSecondsToDateString")
    class EpochConversionTests {

        @Test
        @DisplayName("Unix epoch should format as 1970-01-01 00:00:00")
        public void testEpochZero() {
            Assert.assertEquals("1970-01-01 00:00:00",
                    DataTypeRange.epochSecondsToDateString(0));
        }

        @Test
        @DisplayName("Known timestamp should format correctly")
        public void testKnownTimestamp() {
            // 2024-01-15 12:30:45 UTC = 1705321845 (the previously asserted
            // 1705318245 is 11:30:45 UTC — the epoch constant was off by 3600s).
            Assert.assertEquals("2024-01-15 12:30:45",
                    DataTypeRange.epochSecondsToDateString(1705321845L));
        }
    }
}

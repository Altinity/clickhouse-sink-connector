package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.clickhouse.data.ClickHouseDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 07.03 sections 3.1.1, 3.1.9 and 3.3: a value stored as something other
 * than the source held is counted and logged (default), or refused (strict).
 * Each test fails on the code before the reporter existed.
 */
class LossyConversionTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final ZoneOffset UTC = ZoneOffset.UTC;

    /** Chicago spring-forward day: 02:30 does not exist; CDT starts at 02:00 CST. */
    private static final LocalDateTime GAP = LocalDateTime.of(2026, 3, 8, 2, 30);
    /** Chicago fall-back day: 01:30 occurs twice (CDT, then CST). */
    private static final LocalDateTime OVERLAP = LocalDateTime.of(2026, 11, 1, 1, 30);
    private static final LocalDateTime UNAMBIGUOUS = LocalDateTime.of(2026, 3, 8, 4, 30);

    @BeforeEach
    void resetCounts() {
        LossyConversionReporter.resetForTests();
    }

    private static ClickHouseSinkConnectorConfig config(boolean clamp) {
        Map<String, String> props = new HashMap<>();
        props.put("clamp.out.of.range", Boolean.toString(clamp));
        props.put("database.connectionTimeZone", "UTC");
        props.put("clickhouse.datetime.timezone", "UTC");
        return new ClickHouseSinkConnectorConfig(props);
    }

    private static long millis(LocalDateTime wall) {
        return wall.toInstant(UTC).toEpochMilli();
    }

    private static long micros(LocalDateTime wall) {
        return wall.toInstant(UTC).getEpochSecond() * 1_000_000L + wall.getNano() / 1_000L;
    }

    /** Epoch text of the instant of a Chicago wall time, for the expected stored value. */
    private static String epochTextOf(Instant instant) {
        return DebeziumConverter.epochText(instant);
    }

    @Test
    @DisplayName("DATETIME gap in the column zone binds the offset-before instant (03:30 CDT), reported DST_GAP")
    void sameZoneGapBindsOffsetBeforeInstant() {
        DebeziumConverter.RangePolicy policy = DebeziumConverter.RangePolicy.of(config(true), "lossy.gap.dt0");
        String stored = DebeziumConverter.TimestampConverter.convert(millis(GAP), ClickHouseDataType.DateTime64,
                CHICAGO, CHICAGO, CHICAGO, policy);

        Instant chicago0330 = LocalDateTime.of(2026, 3, 8, 3, 30).atZone(CHICAGO).toInstant();
        assertEquals(epochTextOf(chicago0330), stored);
        assertEquals("1772958600.000000", stored);
        assertEquals(1L, LossyConversionReporter.count(LossyConversionReporter.Kind.DST_GAP));
    }

    @Test
    @DisplayName("DATETIME(6) gap into a DateTime column renders the column-zone wall time 03:30:00")
    void sameZoneMicroGapIntoDateTimeRendersColumnWall() {
        DebeziumConverter.RangePolicy policy = DebeziumConverter.RangePolicy.of(config(true), "lossy.gap.dt6");
        String stored = DebeziumConverter.MicroTimestampConverter.convert(micros(GAP), CHICAGO, CHICAGO,
                ClickHouseDataType.DateTime, CHICAGO, policy);

        assertEquals("2026-03-08 03:30:00", stored);
        assertEquals(1L, LossyConversionReporter.count(LossyConversionReporter.Kind.DST_GAP));
    }

    @Test
    @DisplayName("DATETIME overlap in the column zone binds the earlier occurrence, reported DST_OVERLAP")
    void sameZoneOverlapBindsEarlierOccurrence() {
        DebeziumConverter.RangePolicy policy = DebeziumConverter.RangePolicy.of(config(true), "lossy.ov.dt0");
        String stored = DebeziumConverter.TimestampConverter.convert(millis(OVERLAP), ClickHouseDataType.DateTime64,
                CHICAGO, CHICAGO, CHICAGO, policy);

        assertEquals("1793514600.000000", stored);
        assertEquals(1L, LossyConversionReporter.count(LossyConversionReporter.Kind.DST_OVERLAP));
    }

    @Test
    @DisplayName("an unambiguous same-zone wall time is bound exactly as before (digits, no report)")
    void unambiguousSameZoneIsUnchanged() {
        DebeziumConverter.RangePolicy policy = DebeziumConverter.RangePolicy.of(config(true), "lossy.ok.dt0");
        String stored = DebeziumConverter.TimestampConverter.convert(millis(UNAMBIGUOUS), ClickHouseDataType.DateTime64,
                CHICAGO, CHICAGO, CHICAGO, policy);

        assertEquals("2026-03-08 04:30:00.000", stored);
        assertEquals(0L, LossyConversionReporter.count(LossyConversionReporter.Kind.DST_GAP));
        assertEquals(0L, LossyConversionReporter.count(LossyConversionReporter.Kind.DST_OVERLAP));
    }

    @Test
    @DisplayName("a gap in the source zone (different from the server zone) binds the offset-before instant")
    void differentZoneGapBindsOffsetBeforeInstant() {
        DebeziumConverter.RangePolicy policy = DebeziumConverter.RangePolicy.of(config(true), "lossy.diff.dt0");
        String stored = DebeziumConverter.TimestampConverter.convert(millis(GAP), ClickHouseDataType.DateTime64,
                CHICAGO, UTC, UTC, policy);

        assertEquals("1772958600.000000", stored);
        assertEquals(1L, LossyConversionReporter.count(LossyConversionReporter.Kind.DST_GAP));
    }

    @Test
    @DisplayName("a value that is the second occurrence of its digits in the column zone is reported DST_OVERLAP")
    void secondOccurrenceInColumnZoneIsReported() {
        // Source UTC, column America/Chicago: the UTC wall time 07:30 on the fall-back day is
        // 01:30 CST, the second occurrence of 01:30 in Chicago. Digits store the first occurrence.
        LocalDateTime second = LocalDateTime.of(2026, 11, 1, 7, 30);
        DebeziumConverter.RangePolicy policy = DebeziumConverter.RangePolicy.of(config(true), "lossy.f02.dt");
        String stored = DebeziumConverter.TimestampConverter.convert(millis(second), ClickHouseDataType.DateTime,
                UTC, CHICAGO, CHICAGO, policy);

        assertEquals("2026-11-01 01:30:00", stored);
        assertEquals(1L, LossyConversionReporter.count(LossyConversionReporter.Kind.DST_OVERLAP));
    }

    @Test
    @DisplayName("strict mode refuses a DST gap and names the column, the kind and the setting")
    void strictRefusesDstGap() {
        DebeziumConverter.RangePolicy policy = DebeziumConverter.RangePolicy.of(config(false), "lossy.gap.strict");
        DebeziumConverter.ValueOutOfRangeException refused = assertThrows(
                DebeziumConverter.ValueOutOfRangeException.class,
                () -> DebeziumConverter.TimestampConverter.convert(millis(GAP), ClickHouseDataType.DateTime64,
                        CHICAGO, CHICAGO, CHICAGO, policy));

        assertTrue(refused.getMessage().contains("lossy.gap.strict"), refused.getMessage());
        assertTrue(refused.getMessage().contains("dst_gap"), refused.getMessage());
        assertTrue(refused.getMessage().contains("clamp.out.of.range=true"), refused.getMessage());
        assertEquals(0L, LossyConversionReporter.count(LossyConversionReporter.Kind.DST_GAP));
    }

    @Test
    @DisplayName("default clamp stores and counts every saturation, WARNs once per column, summarises every 5 minutes")
    void clampWarnsOncePerColumnAndCountsEveryValue() {
        DebeziumConverter.RangePolicy policy = DebeziumConverter.RangePolicy.of(config(true), "lossy.clamp.a");
        LocalDateTime far = LocalDateTime.of(9999, 12, 31, 23, 59, 59);
        for (int i = 0; i < 3; i++) {
            DebeziumConverter.TimestampConverter.convert(millis(far), ClickHouseDataType.DateTime64,
                    UTC, UTC, UTC, policy);
        }
        assertEquals(3L, LossyConversionReporter.count(LossyConversionReporter.Kind.RANGE_CLAMP));
        assertEquals(1L, LossyConversionReporter.warnings(LossyConversionReporter.Kind.RANGE_CLAMP));

        DebeziumConverter.RangePolicy other = DebeziumConverter.RangePolicy.of(config(true), "lossy.clamp.b");
        DebeziumConverter.TimestampConverter.convert(millis(far), ClickHouseDataType.DateTime64,
                UTC, UTC, UTC, other);
        assertEquals(2L, LossyConversionReporter.warnings(LossyConversionReporter.Kind.RANGE_CLAMP),
                "a second column gets its own first WARN");
    }

    @Test
    @DisplayName("the summary WARN for a kind is written at most once per five minutes")
    void summaryIsRateLimited() {
        long t0 = 1_000_000L;
        LossyConversionReporter.report(LossyConversionReporter.Kind.DST_GAP, "lossy.r.a", "s", "t", t0);
        LossyConversionReporter.report(LossyConversionReporter.Kind.DST_GAP, "lossy.r.a", "s", "t", t0 + 1);
        LossyConversionReporter.report(LossyConversionReporter.Kind.DST_GAP, "lossy.r.a", "s", "t", t0 + 2);
        assertEquals(1L, LossyConversionReporter.warnings(LossyConversionReporter.Kind.DST_GAP),
                "repeats inside the first window are counted, not logged");

        LossyConversionReporter.report(LossyConversionReporter.Kind.DST_GAP, "lossy.r.a", "s", "t",
                t0 + LossyConversionReporter.SUMMARY_INTERVAL_MILLIS);
        assertEquals(2L, LossyConversionReporter.warnings(LossyConversionReporter.Kind.DST_GAP),
                "one summary after the interval");
        assertEquals(4L, LossyConversionReporter.count(LossyConversionReporter.Kind.DST_GAP));
    }

    @Test
    @DisplayName("Bool collapse counts a value other than 0 or 1 and not 0 or 1")
    void boolCollapseIsCounted() {
        DebeziumConverter.RangePolicy policy = DebeziumConverter.RangePolicy.of(config(true), "lossy.bool.a");
        ClickHouseDataTypeMapper.reportIntegerNarrowing(0L, ClickHouseDataType.Bool, policy);
        ClickHouseDataTypeMapper.reportIntegerNarrowing(1L, ClickHouseDataType.Bool, policy);
        assertEquals(0L, LossyConversionReporter.count(LossyConversionReporter.Kind.BOOL_COLLAPSE));

        ClickHouseDataTypeMapper.reportIntegerNarrowing(2L, ClickHouseDataType.Bool, policy);
        ClickHouseDataTypeMapper.reportIntegerNarrowing(-1L, ClickHouseDataType.Bool, policy);
        assertEquals(2L, LossyConversionReporter.count(LossyConversionReporter.Kind.BOOL_COLLAPSE));
    }

    @Test
    @DisplayName("strict mode refuses a Bool collapse")
    void strictRefusesBoolCollapse() {
        DebeziumConverter.RangePolicy policy = DebeziumConverter.RangePolicy.of(config(false), "lossy.bool.s");
        assertThrows(DebeziumConverter.ValueOutOfRangeException.class,
                () -> ClickHouseDataTypeMapper.reportIntegerNarrowing(2L, ClickHouseDataType.Bool, policy));
    }

    @Test
    @DisplayName("an integer wrapping into a narrower column is counted with the value it stores")
    void intWrapIsCountedWithStoredValue() {
        assertEquals("44", ClickHouseDataTypeMapper.wrappedText(300L, ClickHouseDataType.Int8));
        assertEquals("127", ClickHouseDataTypeMapper.wrappedText(-129L, ClickHouseDataType.Int8));
        assertNull(ClickHouseDataTypeMapper.wrappedText(127L, ClickHouseDataType.Int8));
        assertEquals("0", ClickHouseDataTypeMapper.wrappedText(256L, ClickHouseDataType.UInt8));
        assertEquals("255", ClickHouseDataTypeMapper.wrappedText(-1L, ClickHouseDataType.UInt8));
        assertEquals("4464", ClickHouseDataTypeMapper.wrappedText(70000L, ClickHouseDataType.Int16));
        assertNull(ClickHouseDataTypeMapper.wrappedText(Long.MAX_VALUE, ClickHouseDataType.Int64));

        DebeziumConverter.RangePolicy policy = DebeziumConverter.RangePolicy.of(config(true), "lossy.wrap.a");
        ClickHouseDataTypeMapper.reportIntegerNarrowing(300L, ClickHouseDataType.Int8, policy);
        ClickHouseDataTypeMapper.reportIntegerNarrowing(5L, ClickHouseDataType.Int8, policy);
        assertEquals(1L, LossyConversionReporter.count(LossyConversionReporter.Kind.INT_WRAP));
    }
}

package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.clickhouse.data.ClickHouseColumn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Spec 07.10 section 5: a DATETIME fraction with more digits than the bound
 * DateTime, DateTime32 or DateTime64 scale is counted and logged by default,
 * and refused under strict mode.
 */
class FractionTruncationReportTest {

    private static final String COLUMN = "shop.orders.placed_at";
    private static final String MICRO = "io.debezium.time.MicroTimestamp";
    private static final String MILLI = "io.debezium.time.Timestamp";
    // 2023-11-14 22:13:20 UTC, in whole seconds since the epoch.
    private static final long SECOND = 1_700_000_000L;

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

    private static DebeziumConverter.RangePolicy policy(boolean clamp) {
        return DebeziumConverter.RangePolicy.of(config(clamp), COLUMN);
    }

    @Test
    void microFractionBeyondDateTime64ScaleIsCounted() {
        ClickHouseColumn column = ClickHouseColumn.of("placed_at", "Nullable(DateTime64(3))");

        String stored = ClickHouseDataTypeMapper.reportFractionTruncation(
                SECOND * 1_000_000L + 123_456L, MICRO, column, policy(true));

        assertEquals("123", stored);
        assertEquals(1, LossyConversionReporter.count(LossyConversionReporter.Kind.FRACTION_TRUNCATE));
    }

    @Test
    void microFractionWithNoDigitsBeyondScaleIsNotCounted() {
        ClickHouseColumn column = ClickHouseColumn.of("placed_at", "Nullable(DateTime64(3))");

        assertNull(ClickHouseDataTypeMapper.reportFractionTruncation(
                SECOND * 1_000_000L + 123_000L, MICRO, column, policy(true)));
        assertEquals(0, LossyConversionReporter.count(LossyConversionReporter.Kind.FRACTION_TRUNCATE));
    }

    @Test
    void sameScaleIsNotCounted() {
        ClickHouseColumn column = ClickHouseColumn.of("placed_at", "DateTime64(6)");

        assertNull(ClickHouseDataTypeMapper.reportFractionTruncation(
                SECOND * 1_000_000L + 123_456L, MICRO, column, policy(true)));
        assertEquals(0, LossyConversionReporter.count(LossyConversionReporter.Kind.FRACTION_TRUNCATE));
    }

    @Test
    void wholeSecondIntoDateTimeIsNotCounted() {
        ClickHouseColumn column = ClickHouseColumn.of("placed_at", "DateTime");

        assertNull(ClickHouseDataTypeMapper.reportFractionTruncation(
                SECOND * 1_000_000L, MICRO, column, policy(true)));
        assertEquals(0, LossyConversionReporter.count(LossyConversionReporter.Kind.FRACTION_TRUNCATE));
    }

    @Test
    void fractionIntoDateTimeIsCountedAndDropped() {
        ClickHouseColumn column = ClickHouseColumn.of("placed_at", "DateTime");

        String stored = ClickHouseDataTypeMapper.reportFractionTruncation(
                SECOND * 1_000_000L + 500_000L, MICRO, column, policy(true));

        assertEquals("", stored);
        assertEquals(1, LossyConversionReporter.count(LossyConversionReporter.Kind.FRACTION_TRUNCATE));
    }

    @Test
    void millisFractionBeyondDateTime64ScaleIsCounted() {
        ClickHouseColumn column = ClickHouseColumn.of("placed_at", "DateTime64(2)");

        String stored = ClickHouseDataTypeMapper.reportFractionTruncation(
                SECOND * 1_000L + 123L, MILLI, column, policy(true));

        assertEquals("12", stored);
        assertEquals(1, LossyConversionReporter.count(LossyConversionReporter.Kind.FRACTION_TRUNCATE));
    }

    @Test
    void millisFractionWithinScaleIsNotCounted() {
        ClickHouseColumn column = ClickHouseColumn.of("placed_at", "DateTime64(3)");

        assertNull(ClickHouseDataTypeMapper.reportFractionTruncation(
                SECOND * 1_000L + 123L, MILLI, column, policy(true)));
        assertEquals(0, LossyConversionReporter.count(LossyConversionReporter.Kind.FRACTION_TRUNCATE));
    }

    @Test
    void preEpochFractionUsesTheFractionOfTheSecond() {
        ClickHouseColumn column = ClickHouseColumn.of("placed_at", "DateTime64(3)");

        // -1 microsecond is 0.999999 of the second before the epoch.
        String stored = ClickHouseDataTypeMapper.reportFractionTruncation(-1L, MICRO, column, policy(true));

        assertEquals("999", stored);
        assertEquals(1, LossyConversionReporter.count(LossyConversionReporter.Kind.FRACTION_TRUNCATE));
    }

    @Test
    void nonLongValuesNonTemporalTargetsAndOtherSchemasAreNotCounted() {
        ClickHouseColumn dt64 = ClickHouseColumn.of("placed_at", "DateTime64(3)");

        assertNull(ClickHouseDataTypeMapper.reportFractionTruncation(null, MICRO, dt64, policy(true)));
        assertNull(ClickHouseDataTypeMapper.reportFractionTruncation(
                "2023-11-14 22:13:20.123456", MICRO, dt64, policy(true)));
        assertNull(ClickHouseDataTypeMapper.reportFractionTruncation(
                SECOND * 1_000_000L + 123_456L, MICRO, ClickHouseColumn.of("n", "Int64"), policy(true)));
        assertNull(ClickHouseDataTypeMapper.reportFractionTruncation(
                SECOND, "io.debezium.time.Date", dt64, policy(true)));
        assertEquals(0, LossyConversionReporter.count(LossyConversionReporter.Kind.FRACTION_TRUNCATE));
    }

    @Test
    void strictModeRefusesBeforeBind() {
        ClickHouseColumn column = ClickHouseColumn.of("placed_at", "DateTime64(3)");

        assertThrows(DebeziumConverter.ValueOutOfRangeException.class,
                () -> ClickHouseDataTypeMapper.reportFractionTruncation(
                        SECOND * 1_000_000L + 123_456L, MICRO, column, policy(false)));
    }
}

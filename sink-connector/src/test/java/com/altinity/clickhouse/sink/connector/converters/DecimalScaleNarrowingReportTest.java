package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.clickhouse.data.ClickHouseColumn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Spec 07.09 section 5: a decimal with more fractional digits than the bound
 * column scale is counted and logged by default, and refused under strict mode.
 */
class DecimalScaleNarrowingReportTest {

    private static final String COLUMN = "shop.orders.amount";

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

    @Test
    void fractionBeyondColumnScaleIsCounted() {
        ClickHouseColumn column = ClickHouseColumn.of("amount", "Decimal(10, 2)");
        DebeziumConverter.RangePolicy policy = DebeziumConverter.RangePolicy.of(config(true), COLUMN);

        BigDecimal stored = ClickHouseDataTypeMapper.reportDecimalScaleNarrowing(
                new BigDecimal("-1234.5651"), column, policy);

        assertEquals(new BigDecimal("-1234.56"), stored);
        assertEquals(1, LossyConversionReporter.count(LossyConversionReporter.Kind.DECIMAL_TRUNCATE));
    }

    @Test
    void sameScaleAndTrailingZerosAreNotCounted() {
        ClickHouseColumn column = ClickHouseColumn.of("amount", "Decimal(10, 2)");
        DebeziumConverter.RangePolicy policy = DebeziumConverter.RangePolicy.of(config(true), COLUMN);

        assertNull(ClickHouseDataTypeMapper.reportDecimalScaleNarrowing(new BigDecimal("12.34"), column, policy));
        assertNull(ClickHouseDataTypeMapper.reportDecimalScaleNarrowing(new BigDecimal("12.3400"), column, policy));
        assertEquals(0, LossyConversionReporter.count(LossyConversionReporter.Kind.DECIMAL_TRUNCATE));
    }

    @Test
    void zeroTruncatesTowardZero() {
        ClickHouseColumn column = ClickHouseColumn.of("amount", "Decimal(10, 2)");
        DebeziumConverter.RangePolicy policy = DebeziumConverter.RangePolicy.of(config(true), COLUMN);

        BigDecimal stored = ClickHouseDataTypeMapper.reportDecimalScaleNarrowing(
                new BigDecimal("-0.0049"), column, policy);

        assertEquals(new BigDecimal("0.00"), stored);
        assertEquals(1, LossyConversionReporter.count(LossyConversionReporter.Kind.DECIMAL_TRUNCATE));
    }

    @Test
    void nullableDecimalUsesInnerScale() {
        ClickHouseColumn column = ClickHouseColumn.of("amount", "Nullable(Decimal(18, 4))");
        DebeziumConverter.RangePolicy policy = DebeziumConverter.RangePolicy.of(config(true), COLUMN);

        BigDecimal stored = ClickHouseDataTypeMapper.reportDecimalScaleNarrowing(
                new BigDecimal("1.23456"), column, policy);

        assertEquals(new BigDecimal("1.2345"), stored);
        assertEquals(1, LossyConversionReporter.count(LossyConversionReporter.Kind.DECIMAL_TRUNCATE));
    }

    @Test
    void nonDecimalTargetAndNullAreNotCounted() {
        DebeziumConverter.RangePolicy policy = DebeziumConverter.RangePolicy.of(config(true), COLUMN);

        assertNull(ClickHouseDataTypeMapper.reportDecimalScaleNarrowing(
                new BigDecimal("1.5"), ClickHouseColumn.of("amount", "Int64"), policy));
        assertNull(ClickHouseDataTypeMapper.reportDecimalScaleNarrowing(
                null, ClickHouseColumn.of("amount", "Decimal(10, 2)"), policy));
        assertEquals(0, LossyConversionReporter.count(LossyConversionReporter.Kind.DECIMAL_TRUNCATE));
    }

    @Test
    void strictModeRefusesBeforeBind() {
        ClickHouseColumn column = ClickHouseColumn.of("amount", "Decimal(10, 2)");
        DebeziumConverter.RangePolicy policy = DebeziumConverter.RangePolicy.of(config(false), COLUMN);

        assertThrows(DebeziumConverter.ValueOutOfRangeException.class,
                () -> ClickHouseDataTypeMapper.reportDecimalScaleNarrowing(
                        new BigDecimal("1.005"), column, policy));
    }
}

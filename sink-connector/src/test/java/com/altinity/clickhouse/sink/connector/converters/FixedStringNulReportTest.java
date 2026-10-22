package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.clickhouse.data.ClickHouseColumn;
import com.clickhouse.data.ClickHouseDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 07.12: a text value whose last byte is NUL, bound into a FixedString(N) column, is reported once per value
 * (counted and logged by default, refused under strict mode). ClickHouse reads a FixedString back without its NUL
 * padding, so the source's trailing NUL is lost. Padding alone is the FixedString contract and is not reported.
 * Without ClickHouseDataTypeMapper.reportFixedStringNul the counts below stay at zero, so every positive test fails.
 */
class FixedStringNulReportTest {

    private static final String COLUMN = "shop.codes.code4";

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

    private static long count() {
        return LossyConversionReporter.count(LossyConversionReporter.Kind.FIXEDSTRING_NUL);
    }

    @Test
    void trailingNulIntoFixedStringIsCountedAndReadBackWithoutIt() {
        ClickHouseColumn column = ClickHouseColumn.of("code4", "FixedString(4)");
        assertEquals(ClickHouseDataType.FixedString, column.getDataType());

        String stored = ClickHouseDataTypeMapper.reportFixedStringNul("ab\0", column, policy(true));

        assertEquals("ab", stored);
        assertEquals(1L, count());
    }

    @Test
    void everyNulEndingValueIsCountedOnce() {
        ClickHouseColumn column = ClickHouseColumn.of("code4", "FixedString(4)");

        assertEquals("ab", ClickHouseDataTypeMapper.reportFixedStringNul("ab\0", column, policy(true)));
        // Every trailing NUL is padding to ClickHouse, so all of them are gone when the value is read back.
        assertEquals("x", ClickHouseDataTypeMapper.reportFixedStringNul("x\0\0", column, policy(true)));

        assertEquals(2L, count());
    }

    @Test
    void nullableFixedStringIsCounted() {
        ClickHouseColumn column = ClickHouseColumn.of("code4", "Nullable(FixedString(4))");

        assertEquals("ab", ClickHouseDataTypeMapper.reportFixedStringNul("ab\0", column, policy(true)));
        assertEquals(1L, count());

        // The width of the Nullable form is N too (getEstimatedLength() is not N for it): "e-acute NUL" is three bytes.
        ClickHouseColumn two = ClickHouseColumn.of("code2", "Nullable(FixedString(2))");
        assertNull(ClickHouseDataTypeMapper.reportFixedStringNul("é\0", two, policy(true)));
        assertEquals(1L, count());
    }

    @Test
    void paddingOnlyIsNotCounted() {
        ClickHouseColumn column = ClickHouseColumn.of("code4", "FixedString(4)");

        assertNull(ClickHouseDataTypeMapper.reportFixedStringNul("abc", column, policy(true)));
        assertNull(ClickHouseDataTypeMapper.reportFixedStringNul("a", column, policy(true)));
        assertNull(ClickHouseDataTypeMapper.reportFixedStringNul("", column, policy(true)));
        assertEquals(0L, count());
    }

    @Test
    void nulInTheMiddleIsNotCounted() {
        ClickHouseColumn column = ClickHouseColumn.of("code4", "FixedString(4)");

        assertNull(ClickHouseDataTypeMapper.reportFixedStringNul("a\0b", column, policy(true)));
        assertEquals(0L, count());
    }

    @Test
    void valueLongerThanTheWidthIsLeftToClickHouse() {
        ClickHouseColumn column = ClickHouseColumn.of("code4", "FixedString(4)");

        assertNull(ClickHouseDataTypeMapper.reportFixedStringNul("abcd\0", column, policy(true)));
        assertEquals(0L, count());
    }

    @Test
    void widthIsCountedInUtf8Bytes() {
        ClickHouseColumn three = ClickHouseColumn.of("code3", "FixedString(3)");
        ClickHouseColumn two = ClickHouseColumn.of("code2", "FixedString(2)");

        // U+00E9 is two UTF-8 bytes, so "e-acute NUL" is three bytes: it fits FixedString(3) and not FixedString(2).
        assertEquals("é", ClickHouseDataTypeMapper.reportFixedStringNul("é\0", three, policy(true)));
        assertNull(ClickHouseDataTypeMapper.reportFixedStringNul("é\0", two, policy(true)));
        assertEquals(1L, count());
    }

    @Test
    void otherColumnTypesAndValuesAreNotCounted() {
        ClickHouseColumn text = ClickHouseColumn.of("name", "String");
        ClickHouseColumn fixed = ClickHouseColumn.of("code4", "FixedString(4)");

        // A Decimal has a positive precision (10) that a short value would fit under, so the answer here comes from
        // the type check and not from the width check.
        ClickHouseColumn decimal = ClickHouseColumn.of("amount", "Decimal(10, 2)");

        assertNull(ClickHouseDataTypeMapper.reportFixedStringNul("ab\0", text, policy(true)));
        assertNull(ClickHouseDataTypeMapper.reportFixedStringNul("1\0", decimal, policy(true)));
        assertNull(ClickHouseDataTypeMapper.reportFixedStringNul(null, fixed, policy(true)));
        assertNull(ClickHouseDataTypeMapper.reportFixedStringNul(new byte[] {'a', 0}, fixed, policy(true)));
        assertNull(ClickHouseDataTypeMapper.reportFixedStringNul("ab\0", null, policy(true)));
        assertEquals(0L, count());
    }

    @Test
    void strictModeRefusesTheValue() {
        ClickHouseColumn column = ClickHouseColumn.of("code4", "FixedString(4)");

        assertThrows(DebeziumConverter.ValueOutOfRangeException.class,
                () -> ClickHouseDataTypeMapper.reportFixedStringNul("ab\0", column, policy(false)));
        assertTrue(count() == 0L, "a refused value is not counted as a stored conversion");
    }
}

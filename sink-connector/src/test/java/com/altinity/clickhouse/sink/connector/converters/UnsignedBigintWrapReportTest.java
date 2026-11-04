package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.clickhouse.data.ClickHouseDataType;
import org.apache.kafka.connect.data.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 07.11 section 5: a BIGINT UNSIGNED value at or above 2^63 bound into a
 * column that cannot hold it is counted and logged by default, and refused under
 * strict mode. The report needs the propagated source type of the field, and it
 * fires once per value.
 */
class UnsignedBigintWrapReportTest {

    private static final String COLUMN = "shop.orders.total_units";
    private static final String UNSIGNED_BIGINT = "BIGINT UNSIGNED";
    private static final String SIGNED_BIGINT = "BIGINT";
    private static final String UNSIGNED_INT = "INT UNSIGNED";

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

    private static Map<String, String> sourceType(String mysqlType) {
        Map<String, String> params = new HashMap<>();
        params.put(ClickHouseDataTypeMapper.DEBEZIUM_SOURCE_COLUMN_TYPE_PARAM, mysqlType);
        return params;
    }

    private static long count(LossyConversionReporter.Kind kind) {
        return LossyConversionReporter.count(kind);
    }

    /** Records the object handed to setObject. */
    private static PreparedStatement recordingStatement(AtomicReference<Object> bound) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "setObject":
                    bound.set(args[1]);
                    return null;
                case "toString":
                    return "RecordingPreparedStatement";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return null;
            }
        };
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, h);
    }

    /** Binds an INT64 field through the field-level convert and returns the bound object. */
    private static Object bind(Object value, ClickHouseDataType target, boolean clamp,
                               Map<String, String> schemaParameters) throws Exception {
        AtomicReference<Object> bound = new AtomicReference<>();
        assertTrue(ClickHouseDataTypeMapper.convert(Schema.Type.INT64, null, value, 1,
                recordingStatement(bound), config(clamp), target, ZoneId.of("UTC"), null,
                policy(clamp), schemaParameters), "INT64 must be a handled type");
        return bound.get();
    }

    @Test
    void maximumUnsignedBigintIntoInt64IsCounted() {
        // 18446744073709551615 (2^64 - 1) arrives as the long -1.
        String stored = ClickHouseDataTypeMapper.reportUnsignedBigintWrap(
                -1L, sourceType(UNSIGNED_BIGINT), ClickHouseDataType.Int64, policy(true));

        assertEquals("-1", stored);
        assertEquals(1, count(LossyConversionReporter.Kind.INT_WRAP));
    }

    @Test
    void lowestUnsignedBigintAboveTwoTo63IntoInt64IsCounted() {
        // 9223372036854775808 (2^63) arrives as Long.MIN_VALUE.
        String stored = ClickHouseDataTypeMapper.reportUnsignedBigintWrap(
                Long.MIN_VALUE, sourceType(UNSIGNED_BIGINT), ClickHouseDataType.Int64, policy(true));

        assertEquals("-9223372036854775808", stored);
        assertEquals(1, count(LossyConversionReporter.Kind.INT_WRAP));
    }

    @Test
    void everyWrappedValueIsCounted() {
        for (long value : new long[] {-1L, -2L, Long.MIN_VALUE}) {
            ClickHouseDataTypeMapper.reportUnsignedBigintWrap(
                    value, sourceType(UNSIGNED_BIGINT), ClickHouseDataType.Int64, policy(true));
        }

        assertEquals(3, count(LossyConversionReporter.Kind.INT_WRAP));
    }

    @Test
    void zerofillDisplayWidthSpellingIsCounted() {
        String stored = ClickHouseDataTypeMapper.reportUnsignedBigintWrap(
                -1L, sourceType("bigint(20) unsigned zerofill"), ClickHouseDataType.Int64, policy(true));

        assertEquals("-1", stored);
        assertEquals(1, count(LossyConversionReporter.Kind.INT_WRAP));
    }

    @Test
    void narrowSignedTargetsAreCountedOnce() {
        // -1 fits each signed width, so the stored text is -1 (the low bits of 2^64 - 1).
        assertEquals("-1", ClickHouseDataTypeMapper.reportUnsignedBigintWrap(
                -1L, sourceType(UNSIGNED_BIGINT), ClickHouseDataType.Int32, policy(true)));
        assertEquals("-1", ClickHouseDataTypeMapper.reportUnsignedBigintWrap(
                -1L, sourceType(UNSIGNED_BIGINT), ClickHouseDataType.Int16, policy(true)));
        assertEquals("-1", ClickHouseDataTypeMapper.reportUnsignedBigintWrap(
                -1L, sourceType(UNSIGNED_BIGINT), ClickHouseDataType.Int8, policy(true)));

        assertEquals(3, count(LossyConversionReporter.Kind.INT_WRAP));
    }

    @Test
    void lowestUnsignedBigintIntoInt32StoresTheLowBits() {
        // Long.MIN_VALUE has zero low 32 bits.
        assertEquals("0", ClickHouseDataTypeMapper.reportUnsignedBigintWrap(
                Long.MIN_VALUE, sourceType(UNSIGNED_BIGINT), ClickHouseDataType.Int32, policy(true)));

        assertEquals(1, count(LossyConversionReporter.Kind.INT_WRAP));
    }

    @Test
    void unsignedTargetsAreCountedWithTheLowBits() {
        assertEquals("4294967295", ClickHouseDataTypeMapper.reportUnsignedBigintWrap(
                -1L, sourceType(UNSIGNED_BIGINT), ClickHouseDataType.UInt32, policy(true)));
        assertEquals("65535", ClickHouseDataTypeMapper.reportUnsignedBigintWrap(
                -1L, sourceType(UNSIGNED_BIGINT), ClickHouseDataType.UInt16, policy(true)));
        assertEquals("255", ClickHouseDataTypeMapper.reportUnsignedBigintWrap(
                -1L, sourceType(UNSIGNED_BIGINT), ClickHouseDataType.UInt8, policy(true)));

        assertEquals(3, count(LossyConversionReporter.Kind.INT_WRAP));
    }

    @Test
    void boolTargetIsCountedAsBoolCollapse() {
        assertEquals("true", ClickHouseDataTypeMapper.reportUnsignedBigintWrap(
                -1L, sourceType(UNSIGNED_BIGINT), ClickHouseDataType.Bool, policy(true)));

        assertEquals(1, count(LossyConversionReporter.Kind.BOOL_COLLAPSE));
        assertEquals(0, count(LossyConversionReporter.Kind.INT_WRAP));
    }

    @Test
    void stringAndDecimalTargetsAreNotCounted() {
        // Open (spec 07.11 FM-07.11-4): no report kind describes a text or decimal target.
        assertNull(ClickHouseDataTypeMapper.reportUnsignedBigintWrap(
                -1L, sourceType(UNSIGNED_BIGINT), ClickHouseDataType.String, policy(true)));
        assertNull(ClickHouseDataTypeMapper.reportUnsignedBigintWrap(
                -1L, sourceType(UNSIGNED_BIGINT), ClickHouseDataType.Decimal, policy(true)));

        assertEquals(0, count(LossyConversionReporter.Kind.INT_WRAP));
        assertEquals(0, count(LossyConversionReporter.Kind.BOOL_COLLAPSE));
    }

    @Test
    void unsignedBigintBelowTwoTo63IsNotCounted() {
        assertNull(ClickHouseDataTypeMapper.reportUnsignedBigintWrap(
                Long.MAX_VALUE, sourceType(UNSIGNED_BIGINT), ClickHouseDataType.Int64, policy(true)));
        assertNull(ClickHouseDataTypeMapper.reportUnsignedBigintWrap(
                0L, sourceType(UNSIGNED_BIGINT), ClickHouseDataType.Int64, policy(true)));
        assertEquals(0, count(LossyConversionReporter.Kind.INT_WRAP));
    }

    @Test
    void negativeSignedBigintIsNotCounted() {
        // A genuine negative BIGINT is stored exactly; only the unsigned source type can wrap.
        assertNull(ClickHouseDataTypeMapper.reportUnsignedBigintWrap(
                -1L, sourceType(SIGNED_BIGINT), ClickHouseDataType.Int64, policy(true)));
        assertEquals(0, count(LossyConversionReporter.Kind.INT_WRAP));
    }

    @Test
    void withoutPropagatedSourceTypeIsNotCounted() {
        // Without column.propagate.source.type the signed and unsigned BIGINT cannot be told apart.
        assertNull(ClickHouseDataTypeMapper.reportUnsignedBigintWrap(
                -1L, null, ClickHouseDataType.Int64, policy(true)));
        assertNull(ClickHouseDataTypeMapper.reportUnsignedBigintWrap(
                -1L, new HashMap<>(), ClickHouseDataType.Int64, policy(true)));
        assertEquals(0, count(LossyConversionReporter.Kind.INT_WRAP));
    }

    @Test
    void narrowerUnsignedIntegerSourcesAreNotCounted() {
        assertNull(ClickHouseDataTypeMapper.reportUnsignedBigintWrap(
                -1L, sourceType(UNSIGNED_INT), ClickHouseDataType.Int64, policy(true)));
        assertNull(ClickHouseDataTypeMapper.reportUnsignedBigintWrap(
                -1L, sourceType("SMALLINT UNSIGNED"), ClickHouseDataType.Int64, policy(true)));
        assertEquals(0, count(LossyConversionReporter.Kind.INT_WRAP));
    }

    @Test
    void unsignedBigintIntoUInt64IsNotCounted() {
        // A UInt64 target restores the unsigned magnitude when it is bound; nothing is stored differently.
        assertNull(ClickHouseDataTypeMapper.reportUnsignedBigintWrap(
                -1L, sourceType(UNSIGNED_BIGINT), ClickHouseDataType.UInt64, policy(true)));
        assertEquals(0, count(LossyConversionReporter.Kind.INT_WRAP));
    }

    @Test
    void nullAndNonLongValuesAreNotCounted() {
        assertNull(ClickHouseDataTypeMapper.reportUnsignedBigintWrap(
                null, sourceType(UNSIGNED_BIGINT), ClickHouseDataType.Int64, policy(true)));
        assertNull(ClickHouseDataTypeMapper.reportUnsignedBigintWrap(
                "-1", sourceType(UNSIGNED_BIGINT), ClickHouseDataType.Int64, policy(true)));
        assertNull(ClickHouseDataTypeMapper.reportUnsignedBigintWrap(
                Integer.valueOf(-1), sourceType(UNSIGNED_BIGINT), ClickHouseDataType.Int64, policy(true)));
        assertEquals(0, count(LossyConversionReporter.Kind.INT_WRAP));
    }

    @Test
    void strictModeRefusesBeforeBind() {
        assertThrows(DebeziumConverter.ValueOutOfRangeException.class,
                () -> ClickHouseDataTypeMapper.reportUnsignedBigintWrap(
                        -1L, sourceType(UNSIGNED_BIGINT), ClickHouseDataType.Int64, policy(false)));
    }

    @Test
    void convertReportsOnceAndBindsTheSignedLongForNarrowTargets() throws Exception {
        // The narrowing report must not add a second report for the same value.
        assertEquals(Long.MIN_VALUE, bind(Long.MIN_VALUE, ClickHouseDataType.Int32, true, sourceType(UNSIGNED_BIGINT)));
        assertEquals(1, count(LossyConversionReporter.Kind.INT_WRAP));
    }

    @Test
    void convertRefusesUnderStrictModeNamingTheUnsignedSource() {
        DebeziumConverter.ValueOutOfRangeException refused = assertThrows(
                DebeziumConverter.ValueOutOfRangeException.class,
                () -> bind(Long.MIN_VALUE, ClickHouseDataType.Int32, false, sourceType(UNSIGNED_BIGINT)));
        // The source is the unsigned magnitude; the signed text "-9223372036854775808" also contains the digits.
        assertTrue(refused.getMessage().startsWith("Value 9223372036854775808 for column "), refused.getMessage());
    }

    @Test
    void convertBindIsUnchangedForEveryIntegerTarget() throws Exception {
        assertEquals(-1L, bind(-1L, ClickHouseDataType.Int64, true, sourceType(UNSIGNED_BIGINT)));
        assertEquals(-1L, bind(-1L, ClickHouseDataType.Int32, true, sourceType(UNSIGNED_BIGINT)));
        assertEquals(-1L, bind(-1L, ClickHouseDataType.UInt32, true, sourceType(UNSIGNED_BIGINT)));
        assertEquals(new BigInteger("18446744073709551615"),
                bind(-1L, ClickHouseDataType.UInt64, true, sourceType(UNSIGNED_BIGINT)));
    }

    @Test
    void convertDoesNotReportASignedNegativeIntoANarrowTarget() throws Exception {
        assertEquals(-1L, bind(-1L, ClickHouseDataType.Int32, true, sourceType(SIGNED_BIGINT)));
        assertEquals(0, count(LossyConversionReporter.Kind.INT_WRAP));
    }

    @Test
    void convertStillReportsAnUnsignedValueBelowTwoTo63Once() throws Exception {
        // 4294967296 does not fit Int32: the existing narrowing report counts it, once.
        assertEquals(4294967296L, bind(4294967296L, ClickHouseDataType.Int32, true, sourceType(UNSIGNED_BIGINT)));
        assertEquals(1, count(LossyConversionReporter.Kind.INT_WRAP));
    }
}

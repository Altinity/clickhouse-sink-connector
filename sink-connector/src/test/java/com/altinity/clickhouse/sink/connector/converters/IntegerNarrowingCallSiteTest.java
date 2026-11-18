package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.clickhouse.data.ClickHouseDataType;
import org.apache.kafka.connect.data.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A signed integer that wraps into a narrower ClickHouse column is reported as a
 * LOSSY_CONVERSION of kind int_wrap at the convert() call site, not only by the
 * helper reportIntegerNarrowing. Two call sites matter: the INT32/INT8 branch
 * (a MySQL INT or TINYINT value into an Int8 column) and the BIGINT branch (a
 * signed MySQL BIGINT into an Int32 column). Under clamp.out.of.range=false both
 * refuse the value instead of storing the wrapped one (Spec 07.11 section 5).
 *
 * <p>Before the reporter existed these values wrapped silently; these tests pin
 * the behaviour that restored the report. Removing either call site makes the
 * counts below zero and the strict cases stop refusing.
 */
class IntegerNarrowingCallSiteTest {

    private static final String COLUMN = "shop.orders.qty";

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

    /** A PreparedStatement that accepts every bind and does nothing with it. */
    private static PreparedStatement discardingStatement() {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "toString":
                    return "DiscardingPreparedStatement";
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

    /** Field-level convert of one value into {@code target}; returns convert's result. */
    private static boolean convert(Schema.Type type, Object value, ClickHouseDataType target,
                                   boolean clamp) throws Exception {
        return ClickHouseDataTypeMapper.convert(type, null, value, 1, discardingStatement(),
                config(clamp), target, ZoneId.of("UTC"), null, policy(clamp), new HashMap<>());
    }

    private static long intWraps() {
        return LossyConversionReporter.count(LossyConversionReporter.Kind.INT_WRAP);
    }

    @Test
    void int8TargetFromInt32SourceIsReportedAtTheCallSite() throws Exception {
        // MySQL INT 300 into an operator-created Int8 column is stored as 44.
        assertTrue(convert(Schema.Type.INT32, 300, ClickHouseDataType.Int8, true));
        assertEquals(1L, intWraps());
    }

    @Test
    void int32TargetFromBigintSourceIsReportedAtTheCallSite() throws Exception {
        // Signed MySQL BIGINT 2^31 into an operator-created Int32 column is stored as -2^31.
        assertTrue(convert(Schema.Type.INT64, 2147483648L, ClickHouseDataType.Int32, true));
        assertEquals(1L, intWraps());
    }

    @Test
    void inRangeValuesAreNotReported() throws Exception {
        assertTrue(convert(Schema.Type.INT32, 127, ClickHouseDataType.Int8, true));
        assertTrue(convert(Schema.Type.INT32, -128, ClickHouseDataType.Int8, true));
        assertTrue(convert(Schema.Type.INT64, 2147483647L, ClickHouseDataType.Int32, true));
        assertTrue(convert(Schema.Type.INT64, -2147483648L, ClickHouseDataType.Int32, true));
        assertEquals(0L, intWraps());
    }

    @Test
    void strictRefusesInt8WrapAtTheCallSite() {
        assertThrows(DebeziumConverter.ValueOutOfRangeException.class,
                () -> convert(Schema.Type.INT32, 300, ClickHouseDataType.Int8, false));
    }

    @Test
    void strictRefusesBigintIntoInt32AtTheCallSite() {
        assertThrows(DebeziumConverter.ValueOutOfRangeException.class,
                () -> convert(Schema.Type.INT64, 2147483648L, ClickHouseDataType.Int32, false));
    }
}

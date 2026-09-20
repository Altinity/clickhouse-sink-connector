package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.clickhouse.data.ClickHouseDataType;
import org.apache.kafka.connect.data.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 07.01 section 3.1: {@code BIGINT UNSIGNED} under Debezium's default
 * {@code bigint.unsigned.handling.mode=long}.
 *
 * <p>Debezium emits INT64, so a MySQL value in [2^63, 2^64) arrives as a
 * negative {@code long} (two's-complement wrap). Binding that long into a
 * {@code UInt64} column either fails the batch or stores a different number;
 * the mapper must restore the unsigned magnitude so the MySQL value
 * round-trips.</p>
 */
public class ClickHouseDataTypeMapperUInt64Test {

    /** Records the object handed to setObject. */
    private static PreparedStatement recordingStatement(AtomicReference<Object> bound) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "setObject":
                    bound.set(args[1]);
                    return null;
                case "setLong":
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

    private static Object bind(Object value, ClickHouseDataType target) throws Exception {
        AtomicReference<Object> bound = new AtomicReference<>();
        boolean handled = ClickHouseDataTypeMapper.convert(Schema.Type.INT64, null, value, 1,
                recordingStatement(bound), new ClickHouseSinkConnectorConfig(new HashMap<>()),
                target, ZoneId.of("UTC"));
        assertTrue(handled, "INT64 must be a handled type");
        return bound.get();
    }

    @Test
    @DisplayName("A wrapped BIGINT UNSIGNED is restored to its unsigned magnitude for a UInt64 target")
    public void testWrappedUnsignedBigintIsRestoredForUInt64Target() throws Exception {
        Object max = bind(-1L, ClickHouseDataType.UInt64);
        assertTrue(max instanceof BigInteger,
                "18446744073709551615 arrives as -1L and must be bound as its unsigned value, got: "
                        + max + " (" + (max == null ? "null" : max.getClass().getName()) + ")");
        assertEquals(new BigInteger("18446744073709551615"), max);

        Object twoPow63 = bind(Long.MIN_VALUE, ClickHouseDataType.UInt64);
        assertEquals(new BigInteger("9223372036854775808"), twoPow63);

        // In range: unchanged.
        assertEquals(42L, bind(42L, ClickHouseDataType.UInt64));
    }

    @Test
    @DisplayName("A genuine negative BIGINT for a signed Int64 target is left alone")
    public void testNegativeLongForSignedInt64TargetIsUnchanged() throws Exception {
        assertEquals(-5L, bind(-5L, ClickHouseDataType.Int64));
        assertEquals(-5L, bind(-5L, null));
    }
}

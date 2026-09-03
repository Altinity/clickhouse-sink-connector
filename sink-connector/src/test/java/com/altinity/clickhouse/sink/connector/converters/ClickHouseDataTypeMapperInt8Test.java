package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.clickhouse.data.ClickHouseDataType;
import org.apache.kafka.connect.data.Schema;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.HashMap;

/**
 * Regression tests for issue #385: an INT8 value hard-cast to
 * {@link Integer} in {@link ClickHouseDataTypeMapper#convert}.
 *
 * <p>{@code convert} groups INT8 and INT32 into one branch
 * ({@code isFieldTypeInt}) whose terminal case was
 * {@code ps.setInt(index, (Integer) value)}. An INT8 schema delivers a
 * {@link Byte}, never an {@link Integer}, so that cast threw
 *
 * <pre>ClassCastException: class java.lang.Byte cannot be cast to class java.lang.Integer</pre>
 *
 * which is the error in the report -- and it failed the whole batch, so the
 * connector could not replicate any table holding a MySQL TINYINT.
 *
 * <p>The {@code isWiderIntegerTarget} escape hatch just above the cast does not
 * help here: it only fires for Int32/UInt32/Int64/UInt64 ClickHouse targets,
 * and an INT8 source column maps to ClickHouse Int8 (or UInt8 when unsigned),
 * neither of which is in that set. Those two targets are exactly the ones
 * pinned below.
 *
 * <p>The recording {@link PreparedStatement} is a {@link Proxy}, matching the
 * JDBC test-double idiom already used by
 * {@code ClickHouseDataTypeMapperBitBytesTest} and
 * {@code SystemConnectionRefreshTest}. This module has no mocking framework on
 * its test classpath.
 */
public class ClickHouseDataTypeMapperInt8Test {

    /** Records the single binding call {@code convert()} makes for an int value. */
    private static final class RecordingStatement {
        private Integer intValue;
        private Object objectValue;
        private int callCount;

        private final PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                handler());

        private InvocationHandler handler() {
            return (proxy, method, args) -> {
                switch (method.getName()) {
                    case "setInt":
                        callCount++;
                        intValue = (Integer) args[1];
                        return null;
                    case "setObject":
                        callCount++;
                        objectValue = args[1];
                        return null;
                    case "toString":
                        return "RecordingStatement";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        // Nothing else is expected on the integer path. Failing
                        // loudly keeps an unnoticed behaviour change from
                        // silently satisfying these assertions.
                        throw new AssertionError(
                                "unexpected PreparedStatement call: " + method.getName());
                }
            };
        }
    }

    private static ClickHouseSinkConnectorConfig config() {
        return new ClickHouseSinkConnectorConfig(new HashMap<>());
    }

    private static RecordingStatement convert(Schema.Type type, Object value,
                                              ClickHouseDataType target) throws SQLException {
        RecordingStatement recorder = new RecordingStatement();
        boolean handled = ClickHouseDataTypeMapper.convert(
                type, null, value, 1, recorder.statement, config(), target, ZoneId.of("UTC"));
        Assertions.assertTrue(handled, "the integer branch must report the value as handled");
        Assertions.assertEquals(1, recorder.callCount,
                "expected exactly one binding call for an integer value");
        return recorder;
    }

    /**
     * The reported case: MySQL TINYINT -&gt; Debezium INT8 -&gt; ClickHouse Int8.
     * Before the fix this threw
     * "class java.lang.Byte cannot be cast to class java.lang.Integer".
     */
    @Test
    public void int8ByteIsBoundToInt8Column() throws SQLException {
        Assertions.assertEquals(Integer.valueOf(42),
                convert(Schema.Type.INT8, (byte) 42, ClickHouseDataType.Int8).intValue);
    }

    /** Unsigned TINYINT maps to ClickHouse UInt8, also outside the escape hatch. */
    @Test
    public void int8ByteIsBoundToUInt8Column() throws SQLException {
        Assertions.assertEquals(Integer.valueOf(7),
                convert(Schema.Type.INT8, (byte) 7, ClickHouseDataType.UInt8).intValue);
    }

    /** A negative TINYINT must keep its sign, not be reinterpreted as unsigned. */
    @Test
    public void negativeInt8KeepsItsSign() throws SQLException {
        Assertions.assertEquals(Integer.valueOf(-1),
                convert(Schema.Type.INT8, (byte) -1, ClickHouseDataType.Int8).intValue);
        Assertions.assertEquals(Integer.valueOf(-128),
                convert(Schema.Type.INT8, Byte.MIN_VALUE, ClickHouseDataType.Int8).intValue);
        Assertions.assertEquals(Integer.valueOf(127),
                convert(Schema.Type.INT8, Byte.MAX_VALUE, ClickHouseDataType.Int8).intValue);
    }

    /**
     * The INT32 case that works today must be untouched: still setInt, still
     * the same value. Int16 is used as the target because it is outside
     * isWiderIntegerTarget, so this exercises the very line being changed.
     */
    @Test
    public void int32BehaviourIsUnchanged() throws SQLException {
        Assertions.assertEquals(Integer.valueOf(70000),
                convert(Schema.Type.INT32, 70000, ClickHouseDataType.Int16).intValue);
        Assertions.assertEquals(Integer.valueOf(-70000),
                convert(Schema.Type.INT32, -70000, ClickHouseDataType.Int16).intValue);
    }

    /**
     * The isWiderIntegerTarget escape hatch must keep routing to setObject
     * untouched -- the fix below it must not steal those values.
     */
    @Test
    public void widerIntegerTargetStillUsesSetObject() throws SQLException {
        RecordingStatement recorder =
                convert(Schema.Type.INT32, 12345, ClickHouseDataType.UInt64);

        Assertions.assertNull(recorder.intValue, "wider targets must not use setInt");
        Assertions.assertEquals(12345, recorder.objectValue);
    }
}

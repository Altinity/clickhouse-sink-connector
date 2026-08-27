package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.clickhouse.data.ClickHouseDataType;
import io.debezium.time.MicroTime;
import io.debezium.time.Time;
import org.apache.kafka.connect.data.Schema;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.HashMap;

/**
 * Regression tests for the MySQL TIME binding path in
 * {@link ClickHouseDataTypeMapper#convert} (issue #1215, and the TIME half of
 * issue #8).
 *
 * <p>Debezium encodes a MySQL TIME column in one of two ways, chosen by
 * {@code time.precision.mode}:
 *
 * <ul>
 *   <li>ADAPTIVE (the default): TIME(0)..TIME(3) become
 *       {@code io.debezium.time.Time} -- an INT32 count of milliseconds past
 *       midnight; TIME(4)..TIME(6) become {@code io.debezium.time.MicroTime},
 *       an INT64 count of microseconds.</li>
 *   <li>ADAPTIVE_TIME_MICROSECONDS and MICROSECONDS: always MicroTime.</li>
 * </ul>
 *
 * <p>The type map registers both encodings (INT32+Time and INT64+MicroTime
 * both target ClickHouse String), but the binding logic only recognised the
 * INT64 one: {@code isFieldTime} was set solely for INT64 + MicroTime. An
 * INT32 {@code Time} value therefore matched no time branch, fell through to
 * the generic integer branch, and was bound with
 * {@code ps.setInt(index, ((Number) value).intValue())} -- writing the raw
 * millisecond count 57685000 into a String column instead of 16:01:25.
 *
 * <p>The recording {@link PreparedStatement} is a {@link Proxy}, matching the
 * JDBC test-double idiom already used by {@code ClickHouseDataTypeMapperInt8Test}
 * and {@code ClickHouseDataTypeMapperBitBytesTest}. This module has no mocking
 * framework on its test classpath.
 */
public class ClickHouseDataTypeMapperTimeTest {

    /** Records the single binding call convert() makes for a value. */
    private static final class RecordingStatement {
        private String stringValue;
        private Integer intValue;
        private String boundMethod;
        private int callCount;

        private final PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class[]{PreparedStatement.class},
                handler());

        private InvocationHandler handler() {
            return (proxy, method, args) -> {
                switch (method.getName()) {
                    case "setString":
                        callCount++;
                        boundMethod = "setString";
                        stringValue = (String) args[1];
                        return null;
                    case "setInt":
                        callCount++;
                        boundMethod = "setInt";
                        intValue = (Integer) args[1];
                        return null;
                    case "setObject":
                        callCount++;
                        boundMethod = "setObject";
                        return null;
                    case "toString":
                        return "RecordingStatement";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        throw new AssertionError(
                                "unexpected PreparedStatement call: " + method.getName());
                }
            };
        }
    }

    private static ClickHouseSinkConnectorConfig config() {
        return new ClickHouseSinkConnectorConfig(new HashMap());
    }

    private static RecordingStatement bind(Schema.Type type, String schemaName,
                                           Object value) throws SQLException {
        RecordingStatement recorder = new RecordingStatement();
        boolean handled = ClickHouseDataTypeMapper.convert(
                type, schemaName, value, 1, recorder.statement, config(),
                ClickHouseDataType.String, ZoneId.of("UTC"));
        Assertions.assertTrue(handled, "the time value must be reported as handled");
        Assertions.assertEquals(1, recorder.callCount,
                "expected exactly one binding call for a time value");
        return recorder;
    }

    /**
     * The reported case, ADAPTIVE mode, MySQL TIME(0) holding 16:01:25 --
     * 57685000 milliseconds past midnight.
     *
     * <p>Before the fix this bound the integer 57685000 with setInt.
     */
    @Test
    @DisplayName("Issue #1215: INT32 Debezium Time binds a time string, not raw millis")
    public void int32TimeIsBoundAsTimeString() throws SQLException {
        RecordingStatement recorder = bind(Schema.Type.INT32, Time.SCHEMA_NAME, 57_685_000);

        Assertions.assertEquals("setString", recorder.boundMethod,
                "a TIME value must be bound as a string, not as an integer; "
                        + "setInt bound the raw millisecond-of-day count "
                        + recorder.intValue);
        Assertions.assertEquals("16:01:25", recorder.stringValue);
    }

    /** ADAPTIVE mode, MySQL TIME(3): the milliseconds must survive. */
    @Test
    @DisplayName("Issue #8: INT32 Debezium Time keeps its milliseconds")
    public void int32TimeKeepsMilliseconds() throws SQLException {
        RecordingStatement recorder = bind(Schema.Type.INT32, Time.SCHEMA_NAME, 64_264_777);

        Assertions.assertEquals("setString", recorder.boundMethod);
        Assertions.assertEquals("17:51:04.777", recorder.stringValue);
    }

    /** Midnight is the boundary value: zero must not be mistaken for absent. */
    @Test
    @DisplayName("INT32 Debezium Time renders midnight")
    public void int32TimeRendersMidnight() throws SQLException {
        RecordingStatement recorder = bind(Schema.Type.INT32, Time.SCHEMA_NAME, 0);

        Assertions.assertEquals("setString", recorder.boundMethod);
        Assertions.assertEquals("00:00:00", recorder.stringValue);
    }

    /**
     * The INT64 MicroTime path that already worked must keep working, now
     * without the six-digit zero padding of issue #1215.
     */
    @Test
    @DisplayName("INT64 MicroTime still binds a time string")
    public void int64MicroTimeIsBoundAsTimeString() throws SQLException {
        RecordingStatement recorder = bind(
                Schema.Type.INT64, MicroTime.SCHEMA_NAME, 57_685_000_000L);

        Assertions.assertEquals("setString", recorder.boundMethod);
        Assertions.assertEquals("16:01:25", recorder.stringValue);
    }

    /**
     * Control: an INT32 with no logical name is an ordinary integer and must
     * still take the integer branch. The time fix must not capture it.
     */
    @Test
    @DisplayName("Control: a plain INT32 still binds as an integer")
    public void plainInt32StillBindsAsInteger() throws SQLException {
        RecordingStatement recorder = new RecordingStatement();
        boolean handled = ClickHouseDataTypeMapper.convert(
                Schema.Type.INT32, null, 57_685_000, 1, recorder.statement, config(),
                ClickHouseDataType.Int16, ZoneId.of("UTC"));

        Assertions.assertTrue(handled);
        Assertions.assertEquals("setInt", recorder.boundMethod);
        Assertions.assertEquals(Integer.valueOf(57_685_000), recorder.intValue);
    }
}

package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.clickhouse.data.ClickHouseDataType;
import org.apache.kafka.connect.data.Schema;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

/**
 * Regression tests for issue #749: the ARRAY branch of
 * {@link ClickHouseDataTypeMapper#convert} hard-cast the incoming value to
 * {@link ArrayList}.
 *
 * <p>The Kafka Connect contract for an array field is {@link java.util.List},
 * not {@code ArrayList}. Debezium and the Connect converters are free to hand
 * over any List implementation, and an empty array in particular arrives as
 * {@code Collections.emptyList()} -- which is
 * {@code java.util.Collections$EmptyList}, not an {@code ArrayList}. The cast
 * therefore threw {@code ClassCastException: class
 * java.util.Collections$EmptyList cannot be cast to class java.util.ArrayList},
 * killing the whole batch. That is the exact frame in the reporter stack trace
 * ({@code ClickHouseDataTypeMapper.convert}, called from
 * {@code PreparedStatementExecutor.insertPreparedStatement}).
 *
 * <p>Binding via {@link java.util.Collection} covers every List the Connect
 * runtime can produce while keeping the ArrayList case byte-for-byte identical.
 *
 * <p>The recording {@link PreparedStatement} below is a {@link Proxy}, matching
 * the JDBC test-double idiom already used by
 * {@code ClickHouseDataTypeMapperBitBytesTest} and
 * {@code SystemConnectionRefreshTest}. This module has no mocking framework on
 * its test classpath, and one is not needed to observe a single setter.
 */
public class ClickHouseDataTypeMapperArrayTest {

    /**
     * Records the {@code createArrayOf} type name and elements, plus the
     * {@link Array} bound to the statement, so the test can assert on exactly
     * what reaches JDBC.
     */
    private static final class RecordingStatement {
        private String arrayTypeName;
        private Object[] arrayElements;
        private int setArrayCallCount;

        private final Array array = (Array) Proxy.newProxyInstance(
                Array.class.getClassLoader(),
                new Class<?>[]{Array.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "toString":
                            return "RecordingArray";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            throw new AssertionError(
                                    "unexpected Array call: " + method.getName());
                    }
                });

        private final Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "createArrayOf":
                            arrayTypeName = (String) args[0];
                            arrayElements = (Object[]) args[1];
                            return array;
                        case "toString":
                            return "RecordingConnection";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            throw new AssertionError(
                                    "unexpected Connection call: " + method.getName());
                    }
                });

        private final PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                handler());

        private InvocationHandler handler() {
            return (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getConnection":
                        return connection;
                    case "setArray":
                        setArrayCallCount++;
                        Assertions.assertSame(array, args[1],
                                "the Array from createArrayOf must be the one bound");
                        return null;
                    case "toString":
                        return "RecordingStatement";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        // Nothing else is expected on the ARRAY path. Failing
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

    /**
     * Drives the ARRAY branch exactly as {@code PreparedStatementFieldMapper}
     * does: for an ARRAY field it passes the VALUE schema type name as the
     * schemaName, hence {@code Schema.Type.STRING.name()} here.
     */
    private static RecordingStatement convertArray(Object value) throws SQLException {
        RecordingStatement recorder = new RecordingStatement();
        boolean handled = ClickHouseDataTypeMapper.convert(
                Schema.Type.ARRAY, Schema.Type.STRING.name(), value, 1, recorder.statement,
                config(), ClickHouseDataType.Array, ZoneId.of("UTC"));
        Assertions.assertTrue(handled, "the ARRAY branch must report the value as handled");
        Assertions.assertEquals(1, recorder.setArrayCallCount,
                "expected exactly one setArray call for an ARRAY value");
        return recorder;
    }

    /**
     * The reporter case: an empty array field. Debezium delivers
     * {@code Collections.emptyList()}, which before the fix threw
     * {@code ClassCastException: class java.util.Collections$EmptyList cannot
     * be cast to class java.util.ArrayList}.
     */
    @Test
    public void emptyListDoesNotThrowClassCastException() throws SQLException {
        RecordingStatement recorder = convertArray(Collections.emptyList());

        Assertions.assertEquals("String", recorder.arrayTypeName);
        Assertions.assertArrayEquals(new Object[0], recorder.arrayElements);
    }

    /** An immutable List.of(...) is a ListN, also not an ArrayList. */
    @Test
    public void immutableListIsBound() throws SQLException {
        RecordingStatement recorder = convertArray(List.of("a", "b", "c"));

        Assertions.assertEquals("String", recorder.arrayTypeName);
        Assertions.assertArrayEquals(new Object[] {"a", "b", "c"}, recorder.arrayElements);
    }

    /** Arrays.asList produces a java.util.Arrays$ArrayList -- a different class. */
    @Test
    public void arraysAsListIsBound() throws SQLException {
        RecordingStatement recorder = convertArray(Arrays.asList("x", "y"));

        Assertions.assertArrayEquals(new Object[] {"x", "y"}, recorder.arrayElements);
    }

    /** Any other List implementation must work too -- the contract is List. */
    @Test
    public void linkedListIsBound() throws SQLException {
        RecordingStatement recorder = convertArray(new LinkedList<>(List.of("p", "q")));

        Assertions.assertArrayEquals(new Object[] {"p", "q"}, recorder.arrayElements);
    }

    /**
     * The case that already worked must keep working, byte for byte: an
     * ArrayList must still produce the same type name and the same elements.
     */
    @Test
    public void arrayListBehaviourIsUnchanged() throws SQLException {
        RecordingStatement recorder = convertArray(new ArrayList<>(List.of("one", "two")));

        Assertions.assertEquals("String", recorder.arrayTypeName);
        Assertions.assertArrayEquals(new Object[] {"one", "two"}, recorder.arrayElements);
    }
}

package com.altinity.clickhouse.sink.connector.db.batch;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A recording stand-in for the JDBC surface {@link PreparedStatementExecutor}
 * touches, so the ORDER of what it does to ClickHouse can be asserted without
 * a server: which statements are prepared, what is bound at each
 * {@code addBatch()}, when a batch is executed, and when a statement is
 * executed directly.
 *
 * <p>JDK proxies are used because this module has no mocking framework on its
 * test classpath (see {@code GroupInsertQueryWithBatchRecordsTest}).</p>
 */
final class RecordingJdbc {

    /** One observable JDBC call. */
    static final class Event {
        final String kind;
        final String sql;
        /** Parameters bound at the time of an {@code ADD_BATCH}; empty for other kinds. */
        final Map<Integer, Object> params;

        Event(String kind, String sql, Map<Integer, Object> params) {
            this.kind = kind;
            this.sql = sql;
            this.params = params;
        }

        @Override
        public String toString() {
            return kind + " " + sql + (params.isEmpty() ? "" : " " + params);
        }
    }

    static final String PREPARE = "PREPARE";
    static final String ADD_BATCH = "ADD_BATCH";
    static final String EXECUTE_BATCH = "EXECUTE_BATCH";
    static final String EXECUTE = "EXECUTE";

    /** Every call, in the order it happened. */
    final List<Event> events = new ArrayList<>();

    /**
     * When non-null, {@code prepareStatement(sql)} throws {@link SQLException}
     * for every SQL text containing this marker (simulates ClickHouse
     * rejecting the statement).
     */
    String failPrepareContaining = null;

    /** How many times {@code prepareStatement} was refused. */
    int prepareFailures = 0;

    List<Event> ofKind(String kind) {
        List<Event> out = new ArrayList<>();
        for (Event e : events) {
            if (e.kind.equals(kind)) {
                out.add(e);
            }
        }
        return out;
    }

    /** Kinds in order, e.g. {@code [PREPARE, ADD_BATCH, EXECUTE_BATCH]}. */
    List<String> kinds() {
        List<String> out = new ArrayList<>();
        for (Event e : events) {
            out.add(e.kind);
        }
        return out;
    }

    private static Object defaultFor(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        return type == boolean.class ? Boolean.FALSE : 0;
    }

    private PreparedStatement statement(final String sql) {
        final Map<Integer, Object> params = new LinkedHashMap<>();
        InvocationHandler h = (proxy, method, args) -> {
            String name = method.getName();
            switch (name) {
                case "addBatch":
                    events.add(new Event(ADD_BATCH, sql, new LinkedHashMap<>(params)));
                    params.clear();
                    return null;
                case "executeBatch":
                    events.add(new Event(EXECUTE_BATCH, sql, new LinkedHashMap<>()));
                    return new int[0];
                case "execute":
                    events.add(new Event(EXECUTE, sql, new LinkedHashMap<>()));
                    return false;
                case "executeUpdate":
                    events.add(new Event(EXECUTE, sql, new LinkedHashMap<>()));
                    return 0;
                case "setNull":
                    params.put((Integer) args[0], null);
                    return null;
                case "close":
                    return null;
                case "toString":
                    return "RecordingPreparedStatement[" + sql + "]";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    if (name.startsWith("set") && args != null && args.length >= 2
                            && args[0] instanceof Integer) {
                        params.put((Integer) args[0], args[1]);
                        return null;
                    }
                    return defaultFor(method.getReturnType());
            }
        };
        return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, h);
    }

    Connection connection() {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "prepareStatement": {
                    String sql = (String) args[0];
                    if (failPrepareContaining != null && sql.contains(failPrepareContaining)) {
                        prepareFailures++;
                        throw new SQLException("simulated: ClickHouse refused to prepare " + sql);
                    }
                    events.add(new Event(PREPARE, sql, new LinkedHashMap<>()));
                    return statement(sql);
                }
                case "isClosed":
                    return false;
                case "toString":
                    return "RecordingConnection";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultFor(method.getReturnType());
            }
        };
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, h);
    }
}

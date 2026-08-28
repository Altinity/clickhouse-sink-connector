package com.altinity.clickhouse.debezium.embedded.cdc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Captures every statement prepared on a Connection, in order, along with the
 * values bound to each.
 *
 * <p>The offset-storage defects are defects in the SQL that reaches the JDBC
 * driver -- what the text is, what is bound to it, and what order the
 * statements are issued in. Recording at that boundary lets those properties
 * be asserted without a live ClickHouse.
 *
 * <p>Implemented with a dynamic proxy rather than a hand-written stub:
 * java.sql.Connection and PreparedStatement declare well over a hundred
 * methods between them, and the module has no mocking framework on the test
 * classpath. The proxy answers only what the code under test actually calls
 * and returns type-appropriate defaults elsewhere.
 */
final class OffsetStatementRecorder implements InvocationHandler {

    /** Statements in the order they were prepared. */
    final List<Recorded> statements = new ArrayList<>();

    /** A Connection that records rather than connects. */
    Connection connection() {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, this);
    }

    /**
     * Position of the first statement beginning with the given keyword, or -1.
     *
     * @param prefix lower-case statement prefix, e.g. {@code "delete from"}.
     * @return the index in {@link #statements}, or -1 if none matched.
     */
    int indexOf(String prefix) {
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i).sql.toLowerCase(Locale.ROOT).trim()
                    .startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The first statement beginning with the given keyword.
     *
     * @param prefix lower-case statement prefix.
     * @return the matching statement.
     * @throws AssertionError if no statement matched.
     */
    Recorded first(String prefix) {
        int at = indexOf(prefix);
        if (at < 0) {
            throw new AssertionError("no statement starting with '" + prefix
                    + "', saw: " + summary());
        }
        return statements.get(at);
    }

    /** The statement sequence, trimmed and shortened, for failure messages. */
    String summary() {
        List<String> heads = new ArrayList<>();
        for (Recorded stmt : statements) {
            String sql = stmt.sql.trim().replaceAll("\\s+", " ");
            heads.add(sql.length() > 60 ? sql.substring(0, 60) + "..." : sql);
        }
        return heads.toString();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        if ("prepareStatement".equals(method.getName())) {
            Recorded recorded = new Recorded((String) args[0]);
            statements.add(recorded);
            return statementProxy(recorded);
        }
        return defaultValue(method.getReturnType());
    }

    private PreparedStatement statementProxy(Recorded recorded) {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    if ("setString".equals(method.getName())) {
                        int index = (Integer) args[0];
                        while (recorded.boundValues.size() < index) {
                            recorded.boundValues.add(null);
                        }
                        recorded.boundValues.set(index - 1, (String) args[1]);
                        return null;
                    }
                    if ("executeUpdate".equals(method.getName())) {
                        return 1;
                    }
                    // execute() returning false means "no result set", which
                    // is what a DELETE produces.
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == void.class) {
            return null;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == double.class) {
            return 0d;
        }
        if (returnType == float.class) {
            return 0f;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == char.class) {
            return (char) 0;
        }
        return 0;
    }

    /** One captured prepared statement: its text and the values bound to it. */
    static final class Recorded {
        final String sql;
        final List<String> boundValues = new ArrayList<>();

        Recorded(String sql) {
            this.sql = sql;
        }
    }
}

package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.config.ColumnTypeOverrideConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 08.05 section 3.3.1: the reconciler reads a table's columns from
 * {@code system.columns} with the database and table names BOUND as
 * parameters. Interpolating a replicated identifier into a quoted literal
 * makes a name containing a quote either malformed SQL or a different
 * predicate.
 */
public class ColumnTypeOverrideReconcilerTest {

    /** Everything the fake connection was asked to do, in order. */
    private static final class Recorder {
        final List<String> preparedSql = new ArrayList<>();
        final List<Object> boundValues = new ArrayList<>();
        final List<String> interpolatedSql = new ArrayList<>();
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    /** A result set with no rows: the table has no columns, so nothing else runs. */
    private static ResultSet emptyResultSet() {
        return proxy(ResultSet.class, (p, method, args) -> defaultValue(method.getReturnType()));
    }

    private static Connection recordingConnection(Recorder recorder) {
        return proxy(Connection.class, (p, method, args) -> {
            switch (method.getName()) {
                case "prepareStatement":
                    recorder.preparedSql.add((String) args[0]);
                    return proxy(PreparedStatement.class, (ps, m, a) -> {
                        if (m.getName().startsWith("set") && a != null && a.length == 2) {
                            recorder.boundValues.add(a[1]);
                            return null;
                        }
                        if (m.getName().equals("executeQuery")) {
                            return emptyResultSet();
                        }
                        return defaultValue(m.getReturnType());
                    });
                case "createStatement":
                    return proxy(Statement.class, (st, m, a) -> {
                        if (m.getName().startsWith("execute") && a != null && a.length > 0) {
                            recorder.interpolatedSql.add((String) a[0]);
                            return m.getName().equals("executeQuery") ? emptyResultSet()
                                    : defaultValue(m.getReturnType());
                        }
                        return defaultValue(m.getReturnType());
                    });
                case "isClosed":
                    return false;
                default:
                    return defaultValue(method.getReturnType());
            }
        });
    }

    private static ColumnTypeOverrideConfig oneDirectOverride() {
        Map<String, String> props = Collections.singletonMap(
                ColumnTypeOverrideConfig.DIRECT_PREFIX + "*.*.*.status_code", "String");
        ColumnTypeOverrideConfig config = ColumnTypeOverrideConfig.fromProperties(props);
        assertTrue(config.hasOverrides(), "fixture must configure an override");
        return config;
    }

    @Test
    @DisplayName("08.05 s3.3.1: database and table names are bound, never interpolated")
    public void columnMetadataQueryBindsTheNames() throws Exception {
        Recorder recorder = new Recorder();
        String table = "o'brien_accounts";

        new ColumnTypeOverrideReconciler().reconcile(
                recordingConnection(recorder), "sales", table, "public", oneDirectOverride());

        assertTrue(recorder.interpolatedSql.isEmpty(),
                "the column query must not be built by interpolation: " + recorder.interpolatedSql);
        assertEquals(Collections.singletonList(ColumnTypeOverrideReconciler.EXISTING_COLUMNS_QUERY),
                recorder.preparedSql);
        assertFalse(recorder.preparedSql.get(0).contains(table));
        assertEquals(List.of("sales", table), recorder.boundValues);
    }
}

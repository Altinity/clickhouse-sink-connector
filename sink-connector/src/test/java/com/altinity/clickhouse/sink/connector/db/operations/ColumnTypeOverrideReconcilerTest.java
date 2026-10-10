package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.config.ColumnTypeOverrideConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Spec 08.05 section 3.3.1: the reconciler reads a table's columns from
 * {@code system.columns} with the database and table names BOUND as
 * parameters. Interpolating a replicated identifier into a quoted literal
 * makes a name containing a quote either malformed SQL or a different
 * predicate.
 *
 * <p>Spec 08.05 section 3.3.2: a failed ALIAS override reconciliation halts
 * the connector through {@link ColumnTypeOverrideMismatchException}, the
 * same type a direct-override mismatch uses, and a configured override whose
 * source column is not a column of the table is never inspected or altered.
 */
public class ColumnTypeOverrideReconcilerTest {

    /** Everything the fake connection was asked to do, in order. */
    private static final class Recorder {
        final List<String> preparedSql = new ArrayList<>();
        final List<Object> boundValues = new ArrayList<>();
        final List<String> interpolatedSql = new ArrayList<>();
        final List<String> executedDdl = new ArrayList<>();
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

    /**
     * A result set backed by a fixed list of rows, each a column-name to
     * value map keyed the way {@code EXISTING_COLUMNS_QUERY} projects them
     * ({@code name}, {@code type}, {@code default_kind}, {@code default_expression}).
     */
    private static ResultSet rowsResultSet(List<Map<String, String>> rows) {
        int[] cursor = {-1};
        return proxy(ResultSet.class, (p, method, args) -> {
            switch (method.getName()) {
                case "next":
                    cursor[0]++;
                    return cursor[0] < rows.size();
                case "getString":
                    return rows.get(cursor[0]).get((String) args[0]);
                default:
                    return defaultValue(method.getReturnType());
            }
        });
    }

    private static Map<String, String> column(String name, String type,
                                               String defaultKind, String defaultExpression) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("type", type);
        row.put("default_kind", defaultKind);
        row.put("default_expression", defaultExpression);
        return row;
    }

    /** A statement whose every {@code execute} call records the SQL and succeeds. */
    private static Statement recordingStatement(Recorder recorder) {
        return proxy(Statement.class, (p, method, args) -> {
            if (method.getName().startsWith("execute") && args != null && args.length > 0) {
                recorder.executedDdl.add((String) args[0]);
                return method.getName().equals("executeQuery") ? emptyResultSet()
                        : defaultValue(method.getReturnType());
            }
            return defaultValue(method.getReturnType());
        });
    }

    /** A statement that fails any {@code execute} call, simulating a rejected DDL. */
    private static Statement statementThatRejectsDdl(String reason) {
        return proxy(Statement.class, (p, method, args) -> {
            if (method.getName().equals("execute")) {
                throw new SQLException(reason);
            }
            return defaultValue(method.getReturnType());
        });
    }

    /** A statement that fails the test if asked to execute anything at all. */
    private static Statement statementThatMustNotBeUsed() {
        return proxy(Statement.class, (p, method, args) -> {
            if (method.getName().equals("execute")) {
                fail("no DDL should have been attempted, but got: " + args[0]);
            }
            return defaultValue(method.getReturnType());
        });
    }

    /**
     * A connection whose {@code system.columns} read returns {@code columnsResult}
     * and whose {@code createStatement()} returns {@code statement} for any ALTER
     * DDL the reconciler issues.
     */
    private static Connection connectionWith(Recorder recorder, ResultSet columnsResult,
                                              Statement statement) {
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
                            return columnsResult;
                        }
                        return defaultValue(m.getReturnType());
                    });
                case "createStatement":
                    return statement;
                case "isClosed":
                    return false;
                default:
                    return defaultValue(method.getReturnType());
            }
        });
    }

    /** A connection whose {@code prepareStatement} itself fails, simulating an unreadable system.columns. */
    private static Connection connectionWhoseMetadataReadFails(String reason) {
        return proxy(Connection.class, (p, method, args) -> {
            switch (method.getName()) {
                case "prepareStatement":
                    return proxy(PreparedStatement.class, (ps, m, a) -> {
                        if (m.getName().startsWith("set")) {
                            return null;
                        }
                        if (m.getName().equals("executeQuery")) {
                            throw new SQLException(reason);
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

    /** One ALIAS override: source column {@code amount}, type {@code String}, expression {@code amount}. */
    private static ColumnTypeOverrideConfig oneAliasOverride() {
        Map<String, String> props = Collections.singletonMap(
                ColumnTypeOverrideConfig.ALIAS_PREFIX + "*.*.*.amount", "String|amount");
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

    @Test
    @DisplayName("08.05 s3.3.2: a rejected ADD COLUMN ALIAS DDL halts like a direct-override mismatch")
    public void addColumnDdlFailureHalts() {
        Recorder recorder = new Recorder();
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(column("amount", "Int32", "", null));
        Connection conn = connectionWith(recorder, rowsResultSet(rows),
                statementThatRejectsDdl("Code: 44. DB::Exception: Cannot add column"));

        ColumnTypeOverrideMismatchException ex = assertThrows(
                ColumnTypeOverrideMismatchException.class,
                () -> new ColumnTypeOverrideReconciler().reconcile(
                        conn, "db", "accounts", "public", oneAliasOverride()));

        assertTrue(ex.getMessage().contains("amount"), "message should name the source column: " + ex.getMessage());
        assertTrue(ex.getCause() instanceof SQLException, "the SQLException must be preserved as the cause");
    }

    @Test
    @DisplayName("08.05 s3.3.2: a rejected MODIFY COLUMN ALIAS DDL halts like a direct-override mismatch")
    public void modifyColumnDdlFailureHalts() {
        Recorder recorder = new Recorder();
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(column("amount", "Int32", "", null));
        // The ALIAS column exists but with the wrong type, so a MODIFY is needed.
        rows.add(column("amount_string_", "Int32", "ALIAS", "amount"));
        Connection conn = connectionWith(recorder, rowsResultSet(rows),
                statementThatRejectsDdl("Code: 524. DB::Exception: Cannot modify column"));

        assertThrows(ColumnTypeOverrideMismatchException.class,
                () -> new ColumnTypeOverrideReconciler().reconcile(
                        conn, "db", "accounts", "public", oneAliasOverride()));
    }

    @Test
    @DisplayName("08.05 s3.3.2: an ALIAS name already taken by a non-ALIAS column halts instead of being silently skipped")
    public void nonAliasColumnCollisionHalts() {
        Recorder recorder = new Recorder();
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(column("amount", "Int32", "", null));
        // "amount_string_" already exists as an ordinary column, not ALIAS.
        rows.add(column("amount_string_", "String", "", null));
        Connection conn = connectionWith(recorder, rowsResultSet(rows), statementThatMustNotBeUsed());

        ColumnTypeOverrideMismatchException ex = assertThrows(
                ColumnTypeOverrideMismatchException.class,
                () -> new ColumnTypeOverrideReconciler().reconcile(
                        conn, "db", "accounts", "public", oneAliasOverride()));

        assertTrue(ex.getMessage().contains("amount_string_"),
                "message should name the colliding column: " + ex.getMessage());
    }

    @Test
    @DisplayName("08.05 s3.3.2: an unreadable system.columns halts instead of silently skipping both checks")
    public void existingColumnsReadFailureHalts() {
        Connection conn = connectionWhoseMetadataReadFails("Code: 210. DB::NetException: Connection reset by peer");

        assertThrows(ColumnTypeOverrideMismatchException.class,
                () -> new ColumnTypeOverrideReconciler().reconcile(
                        conn, "db", "accounts", "public", oneAliasOverride()));
    }

    @Test
    @DisplayName("08.05 s3.3.2: an override whose source column is absent from the table is skipped, not halted")
    public void wildcardOverrideForAbsentSourceColumnIsIgnored() {
        Recorder recorder = new Recorder();
        List<Map<String, String>> rows = new ArrayList<>();
        // The table has no "amount" column at all -- the override does not apply here.
        rows.add(column("id", "Int64", "", null));
        Connection conn = connectionWith(recorder, rowsResultSet(rows), statementThatMustNotBeUsed());

        new ColumnTypeOverrideReconciler().reconcile(
                conn, "db", "unrelated_table", "public", oneAliasOverride());
        // No exception: statementThatMustNotBeUsed() would have failed the test if any DDL were attempted.
    }

    @Test
    @DisplayName("08.05 s3.3.2: a ClickHouse-only column the source does not have is never altered or halted on, even an ALIAS one")
    public void clickHouseOnlyAliasColumnForAbsentSourceColumnIsNeverTouched() {
        Recorder recorder = new Recorder();
        List<Map<String, String>> rows = new ArrayList<>();
        // No "amount" source column, but a user added an ALIAS column that happens
        // to carry the exact name the "amount" override would generate, with a
        // definition that does not match the override's type/expression.
        rows.add(column("id", "Int64", "", null));
        rows.add(column("amount_string_", "UInt8", "ALIAS", "1"));
        Connection conn = connectionWith(recorder, rowsResultSet(rows), statementThatMustNotBeUsed());

        new ColumnTypeOverrideReconciler().reconcile(
                conn, "db", "unrelated_table", "public", oneAliasOverride());
        // No exception and no MODIFY attempted: statementThatMustNotBeUsed() enforces the latter.
    }

    @Test
    @DisplayName("08.05 s3.3.2: a missing ALIAS column for a source-matching column is still added automatically")
    public void addColumnDdlSucceedsForSourceMatchingColumn() {
        Recorder recorder = new Recorder();
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(column("amount", "Int32", "", null));
        Connection conn = connectionWith(recorder, rowsResultSet(rows), recordingStatement(recorder));

        new ColumnTypeOverrideReconciler().reconcile(
                conn, "db", "accounts", "public", oneAliasOverride());

        assertEquals(1, recorder.executedDdl.size(), "exactly one ALTER should have been issued");
        assertTrue(recorder.executedDdl.get(0).contains("ADD COLUMN `amount_string_`"),
                "unexpected DDL: " + recorder.executedDdl.get(0));
    }
}

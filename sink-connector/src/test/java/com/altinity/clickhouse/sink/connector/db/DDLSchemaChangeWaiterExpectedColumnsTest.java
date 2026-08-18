package com.altinity.clickhouse.sink.connector.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DDLSchemaChangeWaiter#waitForExpectedColumns}, the
 * generalized visibility gate that waits until the destination contains every
 * column the source expects — covering rename/modify, not just add/drop.
 *
 * <p>Uses lightweight JDBC stubs (no Mockito, consistent with the rest of the
 * module). The stub's column list is supplied dynamically so it can "appear"
 * mid-poll, modelling {@code system.columns} propagation.</p>
 */
class DDLSchemaChangeWaiterExpectedColumnsTest {

    @Test
    @DisplayName("Returns empty (all visible) when destination already has the columns")
    void allColumnsAlreadyVisible() {
        Connection conn = stubConnection(() -> Arrays.asList("id", "price", "price_usd"));
        DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(1000, 20);
        Set<String> missing = waiter.waitForExpectedColumns(
                conn, "db", "t", Arrays.asList("id", "price_usd"));
        assertTrue(missing.isEmpty(), "Nothing should be missing");
    }

    @Test
    @DisplayName("Blocks until a propagating column appears, then returns empty")
    void waitsForColumnToAppear() {
        AtomicInteger polls = new AtomicInteger(0);
        // First two polls: column absent. Third+: present (propagation completes).
        Supplier<List<String>> cols = () -> {
            if (polls.incrementAndGet() < 3) {
                return Arrays.asList("id", "price");
            }
            return Arrays.asList("id", "price", "price_usd");
        };
        Connection conn = stubConnection(cols);
        DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(2000, 20);
        Set<String> missing = waiter.waitForExpectedColumns(
                conn, "db", "t", Arrays.asList("id", "price_usd"));
        assertTrue(missing.isEmpty(),
                "Column should become visible after propagation; missing=" + missing);
        assertTrue(polls.get() >= 3, "Should have polled until the column appeared");
    }

    @Test
    @DisplayName("Case-insensitive: SOURCE column case differs from destination metadata")
    void caseInsensitiveVisibility() {
        Connection conn = stubConnection(() -> Arrays.asList("id", "price_usd"));
        DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(1000, 20);
        Set<String> missing = waiter.waitForExpectedColumns(
                conn, "db", "t", Arrays.asList("ID", "Price_USD"));
        assertTrue(missing.isEmpty(), "Case should not cause a false 'missing'");
    }

    @Test
    @DisplayName("Times out and reports the still-missing columns (inserts must not resume)")
    void timesOutReportsMissing() {
        // Column never appears.
        Connection conn = stubConnection(() -> Arrays.asList("id", "price"));
        DDLSchemaChangeWaiter waiter = new DDLSchemaChangeWaiter(150, 20);
        Set<String> missing = waiter.waitForExpectedColumns(
                conn, "db", "t", Arrays.asList("id", "price_usd"));
        assertTrue(missing.contains("price_usd"),
                "Timed-out column must be reported so the caller blocks inserts");
    }

    // ------------------------------------------------------------------
    // Minimal JDBC stubs (only the methods the waiter touches).
    // ------------------------------------------------------------------

    private static Connection stubConnection(Supplier<List<String>> columnSupplier) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                DDLSchemaChangeWaiterExpectedColumnsTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("createStatement".equals(method.getName())) {
                        return stubStatement(columnSupplier);
                    }
                    // close(), isClosed(), etc. — no-op / sane defaults.
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    return null;
                });
    }

    private static Statement stubStatement(Supplier<List<String>> columnSupplier) {
        return (Statement) java.lang.reflect.Proxy.newProxyInstance(
                DDLSchemaChangeWaiterExpectedColumnsTest.class.getClassLoader(),
                new Class<?>[]{Statement.class},
                (proxy, method, args) -> {
                    if ("executeQuery".equals(method.getName())) {
                        return stubResultSet(new ArrayList<>(columnSupplier.get()));
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    return null;
                });
    }

    private static ResultSet stubResultSet(List<String> names) {
        final int[] idx = {-1};
        return (ResultSet) java.lang.reflect.Proxy.newProxyInstance(
                DDLSchemaChangeWaiterExpectedColumnsTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "next":
                            idx[0]++;
                            return idx[0] < names.size();
                        case "getString":
                            return names.get(idx[0]);
                        case "close":
                            return null;
                        default:
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) {
                                return false;
                            }
                            if (rt == int.class) {
                                return 0;
                            }
                            return null;
                    }
                });
    }

    // Guard against unused-import warnings for SQLException in some toolchains.
    @SuppressWarnings("unused")
    private static void referencesSqlException() throws SQLException {
        throw new SQLException();
    }
}

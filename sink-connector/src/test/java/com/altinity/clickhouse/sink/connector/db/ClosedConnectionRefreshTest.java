package com.altinity.clickhouse.sink.connector.db;

import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Tests for {@link BaseDbWriter#isUnusable(Connection)}, the predicate behind
 * {@code getConnection()} deciding whether the cached handle must be replaced.
 * <p>
 * Previously {@code getConnection()} only replaced a NULL connection. A pooled
 * connection that has been closed — returned to or evicted by the pool, or
 * reaped after an idle period — is a non-null object whose every subsequent
 * use throws {@code SQLException: Connection is closed}, so the stale handle
 * was handed straight back to the caller.
 * <p>
 * That is the failure seen in CI as
 * {@code ClickHouseCreateDatabaseTest.testCreateNewDatabase ... SQLException:
 * Connection is closed}, which reproduces on this branch AND on the branch
 * head that predates any of these changes (job 89834564706 vs 93037145547 —
 * byte-identical stack), i.e. it is a long-standing defect rather than a
 * regression.
 */
public class ClosedConnectionRefreshTest {

    private static Connection connection(final Boolean closed, final boolean throwOnCheck) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "isClosed":
                    if (throwOnCheck) {
                        throw new SQLException("driver refuses to answer");
                    }
                    return closed;
                case "toString":
                    return "StubConnection(closed=" + closed + ")";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return null;
            }
        };
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, h);
    }

    @Test
    public void testNullConnectionIsUnusable() {
        Assert.assertTrue(BaseDbWriter.isUnusable(null));
    }

    @Test
    public void testClosedConnectionIsUnusable() {
        // The case the old null-only check missed.
        Assert.assertTrue(BaseDbWriter.isUnusable(connection(true, false)));
    }

    @Test
    public void testOpenConnectionIsUsable() {
        // Must NOT churn a perfectly good connection.
        Assert.assertFalse(BaseDbWriter.isUnusable(connection(false, false)));
    }

    @Test
    public void testConnectionThatCannotReportItsStateIsUnusable() {
        // Fail safe: if the driver throws while answering isClosed(), nothing
        // useful can be done with the handle either.
        Assert.assertTrue(BaseDbWriter.isUnusable(connection(null, true)));
    }
}

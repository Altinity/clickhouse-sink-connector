package com.altinity.clickhouse.debezium.embedded.cdc;

import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for the startup retry around the system-database connection.
 * <p>
 * Context: the system-database connection is created exactly ONCE at startup
 * and then reused for the Debezium storage-database creation and the version
 * lookup. With {@code connection.pool.disable=true} (the setting used by the
 * docker-compose stacks) there is no pool to obtain a replacement from later,
 * so if that single connect attempt returns null the connector is wedged for
 * its whole lifetime: every subsequent query fails with "ClickHouse connection
 * is not available" and the destination tables are never created.
 * <p>
 * That is reachable on a cold start, because compose's healthcheck can pass
 * moments before ClickHouse is actually serving. Verified against
 * clickhouse-jdbc 0.9.8: the V2 driver returns a connection lazily even when
 * nothing is listening, whereas the V1 driver throws "Connection refused",
 * which BaseDbWriter.createConnection turns into a null.
 * <p>
 * Reverting the retry (calling the supplier once) makes
 * {@code testRetriesUntilConnectionAvailable} fail with null.
 */
public class SystemDbConnectRetryTest {

    @Test
    public void testReturnsImmediatelyWhenFirstAttemptSucceeds() {
        Connection stub = StubConnection.create();
        AtomicInteger calls = new AtomicInteger();
        Connection result = DebeziumChangeEventCapture.connectWithRetry(() -> {
            calls.incrementAndGet();
            return stub;
        }, 1L);
        Assert.assertSame(stub, result);
        Assert.assertEquals(1, calls.get());
    }

    @Test
    public void testRetriesUntilConnectionAvailable() {
        Connection stub = StubConnection.create();
        AtomicInteger calls = new AtomicInteger();
        // Unavailable for the first two attempts, exactly like a ClickHouse
        // container that is still coming up.
        Connection result = DebeziumChangeEventCapture.connectWithRetry(
                () -> calls.incrementAndGet() < 3 ? null : stub, 1L);
        Assert.assertSame(stub, result);
        Assert.assertEquals(3, calls.get());
    }

    @Test
    public void testGivesUpAfterConfiguredAttempts() {
        AtomicInteger calls = new AtomicInteger();
        Connection result = DebeziumChangeEventCapture.connectWithRetry(() -> {
            calls.incrementAndGet();
            return null;
        }, 1L);
        Assert.assertNull(result);
        Assert.assertEquals(
                DebeziumChangeEventCapture.SYSTEM_DB_CONNECT_ATTEMPTS, calls.get());
    }

    @Test
    public void testRetryBudgetCoversARealisticColdStart() {
        // 30 attempts x 2s must exceed the ClickHouse healthcheck start_period
        // (30s) used by the docker-compose stacks, with margin.
        long budgetMs = (long) DebeziumChangeEventCapture.SYSTEM_DB_CONNECT_ATTEMPTS
                * DebeziumChangeEventCapture.SYSTEM_DB_CONNECT_RETRY_MS;
        Assert.assertTrue("retry budget " + budgetMs + "ms is too small for a cold start",
                budgetMs >= 30_000L);
    }

    /** Minimal Connection stub; only identity is asserted. */
    private static final class StubConnection {
        static Connection create() {
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if ("toString".equals(method.getName())) {
                            return "StubConnection";
                        }
                        if ("hashCode".equals(method.getName())) {
                            return System.identityHashCode(proxy);
                        }
                        if ("equals".equals(method.getName())) {
                            return proxy == args[0];
                        }
                        return null;
                    });
        }
    }
}

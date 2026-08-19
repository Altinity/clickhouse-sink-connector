package com.altinity.clickhouse.sink.connector.db;

import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Guards the refresh behaviour behind the {@code systemConnection()} accessors
 * in ClickHouseBatchRunnable / ClickHouseBatchWriter.
 * <p>
 * Those executors open one connection to the system database in their
 * constructor and keep it for the lifetime of the task. A pooled connection
 * does not survive that long — it is returned, evicted, or idle-reaped — and
 * every later use of the stale handle throws
 * {@code SQLException: Connection is closed}. The only caller runs on every
 * batch, so one closed handle produced a full stack trace per batch for the
 * remainder of the run (1.17M lines in CI) while the server timezone silently
 * fell back to the default.
 * <p>
 * The executors themselves need a live ClickHouse to construct, so this test
 * exercises the exact predicate they now gate on
 * ({@link BaseDbWriter#isUnusable}) plus the reopen-once-then-cache loop built
 * on top of it. Removing the {@code isUnusable} guard from either executor —
 * i.e. going back to using the cached field directly — makes
 * {@link #closedConnectionIsReplaced()} fail, because the stale handle is
 * handed back instead of a fresh one.
 */
public class SystemConnectionRefreshTest {

    /** Builds a Connection whose isClosed() answers as instructed. */
    private static Connection connection(final boolean closed) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("isClosed".equals(method.getName())) {
                return closed;
            }
            if ("toString".equals(method.getName())) {
                return closed ? "closed-connection" : "open-connection";
            }
            if ("hashCode".equals(method.getName())) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(method.getName())) {
                return proxy == args[0];
            }
            return null;
        };
        return (Connection) Proxy.newProxyInstance(
                SystemConnectionRefreshTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, handler);
    }

    /**
     * Mirrors the executors' accessor: reopen when unusable, otherwise reuse.
     * The supplier stands in for createConnection(SYSTEM_DB).
     */
    private static final class Holder {
        private Connection current;
        private final AtomicInteger opens = new AtomicInteger();

        Holder(Connection initial) {
            this.current = initial;
        }

        Connection systemConnection() {
            if (BaseDbWriter.isUnusable(this.current)) {
                this.opens.incrementAndGet();
                this.current = connection(false);
            }
            return this.current;
        }
    }

    /**
     * The regression: a closed cached handle must not be handed back.
     */
    @Test
    public void closedConnectionIsReplaced() {
        Connection stale = connection(true);
        Holder holder = new Holder(stale);

        Connection got = holder.systemConnection();

        Assert.assertNotSame(
                "a closed system connection must be replaced, not reused",
                stale, got);
        Assert.assertFalse("replacement must be open",
                BaseDbWriter.isUnusable(got));
        Assert.assertEquals("exactly one reopen", 1, holder.opens.get());
    }

    /**
     * An open handle is reused — the fix must not churn connections on every
     * batch.
     */
    @Test
    public void openConnectionIsReused() {
        Connection live = connection(false);
        Holder holder = new Holder(live);

        Assert.assertSame(live, holder.systemConnection());
        Assert.assertSame(live, holder.systemConnection());
        Assert.assertEquals("no reopen while the handle is usable",
                0, holder.opens.get());
    }

    /**
     * A null handle (initial connect failed) is also replaced rather than
     * propagated to DBMetadata.
     */
    @Test
    public void nullConnectionIsReplaced() {
        Holder holder = new Holder(null);

        Connection got = holder.systemConnection();

        Assert.assertNotNull("a null system connection must be replaced", got);
        Assert.assertEquals(1, holder.opens.get());
    }

    /**
     * Once replaced, the new handle is cached — the next batch does not reopen.
     */
    @Test
    public void replacementIsCached() {
        Holder holder = new Holder(connection(true));

        Connection first = holder.systemConnection();
        Connection second = holder.systemConnection();

        Assert.assertSame("replacement must be cached", first, second);
        Assert.assertEquals("only the first call reopens", 1, holder.opens.get());
    }
}

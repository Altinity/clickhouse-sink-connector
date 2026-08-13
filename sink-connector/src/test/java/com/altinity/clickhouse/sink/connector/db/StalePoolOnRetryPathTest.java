package com.altinity.clickhouse.sink.connector.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;

/**
 * Tests that the RETRY path refuses a pool whose target server has been
 * superseded.
 * <p>
 * {@link HikariDbSource#getInstance} compares the requested server against the
 * cached pool, but {@link HikariDbSource#initiateNewConnectionIfClosed} is
 * handed nothing but a database name and used to check only
 * {@code isClosed()}. An OPEN pool built for a server that is no longer being
 * addressed — a restarted container on a new port, a failover, a reconfigured
 * endpoint — passes that check while every connection it produces targets the
 * dead endpoint.
 * <p>
 * That matters because this is precisely the path
 * {@code DBMetadata.executeSystemQuery} and
 * {@code DBMetadata.getColumnsDataTypesForTable} take after a query fails: the
 * retry re-acquired the same dead pool and re-failed identically until the
 * retry budget was exhausted. In CI the observable result was a run of
 * <pre>
 *   HttpHostConnectException: Connect to http://localhost:32849 failed
 * </pre>
 * across every retry of {@code create database if not exists}, the connector
 * never replicating the DDL, and the assertion in the integration test hitting
 * a NullPointerException because the expected column was absent.
 */
public class StalePoolOnRetryPathTest {

    private static final String DB = "system";

    private HikariDataSource pool;

    private static SinkConnectorDataSource dataSource(String url) throws Exception {
        Properties p = new Properties();
        p.setProperty("user", "default");
        return new SinkConnectorDataSource(url, p);
    }

    /**
     * A lazy pool that never opens a socket: nothing here reaches a server, and
     * an unreachable endpoint must not make construction fail.
     */
    private static HikariDataSource poolFor(SinkConnectorDataSource ds) {
        HikariConfig cfg = new HikariConfig();
        cfg.setDataSource(ds);
        cfg.setMinimumIdle(0);
        cfg.setMaximumPoolSize(1);
        cfg.setInitializationFailTimeout(-1);
        return new HikariDataSource(cfg);
    }

    @SuppressWarnings("unchecked")
    private static <T> Map<String, T> staticMap(String name) throws Exception {
        Field f = HikariDbSource.class.getDeclaredField(name);
        f.setAccessible(true);
        return (Map<String, T>) f.get(null);
    }

    /**
     * Seeds the caches the way a live run would: a pool registered for DB, and
     * the endpoint most recently requested for it.
     */
    private void seed(HikariDataSource cached, String wantedEndpoint) throws Exception {
        staticMap("instance").put(DB, cached);
        staticMap("requestedEndpoint").put(DB, wantedEndpoint);
    }

    @BeforeEach
    public void reset() throws Exception {
        staticMap("instance").clear();
        staticMap("requestedEndpoint").clear();
        Field disabled = HikariDbSource.class.getDeclaredField("disabled");
        disabled.setAccessible(true);
        disabled.setBoolean(null, false);
    }

    @AfterEach
    public void cleanup() throws Exception {
        if (pool != null) {
            pool.close();
            pool = null;
        }
        staticMap("instance").clear();
        staticMap("requestedEndpoint").clear();
    }

    /**
     * The bug: an open pool pointing at the previous server was handed straight
     * back to the retry, so the retry re-failed against the dead endpoint.
     */
    @Test
    public void testSupersededPoolIsNotHandedToTheRetry() throws Exception {
        pool = poolFor(dataSource("jdbc:clickhouse://localhost:32849/system"));
        seed(pool, "localhost:32853");

        Assert.assertFalse("precondition: the pool must be open, so isClosed() "
                + "cannot be what rejects it", pool.isClosed());

        SQLException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                SQLException.class,
                () -> HikariDbSource.initiateNewConnectionIfClosed(DB),
                "a pool built for a superseded server must not be reused");
        Assert.assertTrue("the failure must name the superseded server: "
                        + thrown.getMessage(),
                thrown.getMessage().contains("superseded server"));

        Assert.assertFalse("the dead pool must be evicted so the retry rebuilds",
                staticMap("instance").containsKey(DB));
        Assert.assertTrue("the dead pool must be closed", pool.isClosed());
    }

    /**
     * The pool must survive when it still targets the requested server —
     * otherwise the fix would churn a working pool on every retry.
     */
    @Test
    public void testCurrentPoolIsKept() throws Exception {
        pool = poolFor(dataSource("jdbc:clickhouse://localhost:32853/system"));
        seed(pool, "localhost:32853");

        Assert.assertNull("a pool on the requested endpoint is not superseded",
                HikariDbSource.supersededEndpoint(DB, pool));
        Assert.assertSame("the pool must stay cached",
                pool, staticMap("instance").get(DB));
        Assert.assertFalse(pool.isClosed());
    }

    /**
     * Only the server endpoint decides. A different database path on the same
     * server is legitimate — {@code createConnection(".../employees", ...,
     * databaseName="system")} — and must not evict the pool.
     */
    @Test
    public void testDifferentDatabasePathOnSameServerIsKept() throws Exception {
        pool = poolFor(dataSource("jdbc:clickhouse://localhost:32853/employees"));
        seed(pool, "localhost:32853");

        Assert.assertNull("the database path must not count as a server change",
                HikariDbSource.supersededEndpoint(DB, pool));
    }

    /**
     * An unknown state must never discard a working pool: with no recorded
     * endpoint, or an unparseable pool URL, the pool is assumed current. This
     * mirrors {@code servesSameServer}'s "assume a match" stance.
     */
    @Test
    public void testUnknownEndpointAssumesCurrent() throws Exception {
        pool = poolFor(dataSource("jdbc:clickhouse://localhost:32853/system"));
        staticMap("instance").put(DB, pool);

        Assert.assertNull("no recorded endpoint means nothing to compare against",
                HikariDbSource.supersededEndpoint(DB, pool));
        Assert.assertNull("an unknown pool URL must not evict the pool",
                HikariDbSource.supersededEndpoint(DB, null));
    }

    /**
     * close() must forget the recorded endpoints too, or a rebuilt pool would
     * look superseded on its first use.
     */
    @Test
    public void testCloseClearsRecordedEndpoints() throws Exception {
        pool = poolFor(dataSource("jdbc:clickhouse://localhost:32853/system"));
        seed(pool, "localhost:32853");

        HikariDbSource.close();

        Assert.assertTrue("pools must be cleared",
                staticMap("instance").isEmpty());
        Assert.assertTrue("recorded endpoints must be cleared",
                staticMap("requestedEndpoint").isEmpty());
    }
}

package com.altinity.clickhouse.sink.connector.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.util.Properties;

/**
 * Tests that a cached connection pool is only reused when it targets the SAME
 * ClickHouse server.
 * <p>
 * Pools are cached in a static map keyed by database name alone. The same
 * database name can legitimately be requested against different servers within
 * one JVM — throwaway test containers, a reconfigured endpoint, a failover — so
 * keying on the name alone hands back a pool pointing at the wrong (often
 * already stopped) server. The observed symptom is a caller that supplied a
 * perfectly good URL getting:
 * <pre>
 *   HttpHostConnectException: Connect to http://localhost:8123 failed:
 *                             Connection refused
 * </pre>
 * which is exactly how {@code ClickHouseCreateDatabaseTest.testCreateNewDatabase}
 * failed in CI (job 93040996571) right after the preceding test class stopped
 * its container.
 */
public class PoolServerIdentityTest {

    private static SinkConnectorDataSource dataSource(String url) throws Exception {
        Properties p = new Properties();
        p.setProperty("user", "default");
        return new SinkConnectorDataSource(url, p);
    }

    private static HikariDataSource poolFor(SinkConnectorDataSource ds) {
        HikariConfig cfg = new HikariConfig();
        cfg.setDataSource(ds);
        // Never actually opens a socket: the pool is lazy and nothing calls
        // getConnection() in this test.
        cfg.setMinimumIdle(0);
        cfg.setMaximumPoolSize(1);
        cfg.setInitializationFailTimeout(-1);
        return new HikariDataSource(cfg);
    }

    @Test
    public void testSameServerPoolIsReused() throws Exception {
        SinkConnectorDataSource ds = dataSource("jdbc:clickhouse://hostA:8123/system");
        HikariDataSource pool = poolFor(ds);
        try {
            Assert.assertTrue(HikariDbSource.servesSameServer(pool, ds));
            // A distinct but equivalent data source must also match.
            Assert.assertTrue(HikariDbSource.servesSameServer(
                    pool, dataSource("jdbc:clickhouse://hostA:8123/system")));
        } finally {
            pool.close();
        }
    }

    @Test
    public void testDifferentHostIsNotReused() throws Exception {
        HikariDataSource pool = poolFor(dataSource("jdbc:clickhouse://hostA:8123/system"));
        try {
            Assert.assertFalse(HikariDbSource.servesSameServer(
                    pool, dataSource("jdbc:clickhouse://hostB:8123/system")));
        } finally {
            pool.close();
        }
    }

    @Test
    public void testDifferentPortIsNotReused() throws Exception {
        // The container case: same host, new random mapped port per container.
        HikariDataSource pool = poolFor(dataSource("jdbc:clickhouse://localhost:32768/system"));
        try {
            Assert.assertFalse(HikariDbSource.servesSameServer(
                    pool, dataSource("jdbc:clickhouse://localhost:32769/system")));
        } finally {
            pool.close();
        }
    }

    @Test
    public void testPoolTargetUrlIsExposed() throws Exception {
        SinkConnectorDataSource ds = dataSource("jdbc:clickhouse://hostA:8123/system");
        HikariDataSource pool = poolFor(ds);
        try {
            Assert.assertEquals(ds.getJdbcUrl(), HikariDbSource.poolTargetUrl(pool));
            Assert.assertTrue(HikariDbSource.poolTargetUrl(pool).contains("hostA:8123"));
        } finally {
            pool.close();
        }
    }

    @Test
    public void testUnknownUrlFallsBackToReuse() {
        // Defensive: an unexpected data-source type must not cause pool churn.
        Assert.assertNull(HikariDbSource.poolTargetUrl(null));
        Assert.assertTrue(HikariDbSource.servesSameServer(null, null));
    }

    /**
     * The pool key is the database NAME, but the URL a caller supplies may point
     * at a DIFFERENT database on the same server. That is a normal pattern here:
     * <pre>
     *   BaseDbWriter.createConnection(".../employees", ..., databaseName="system")
     * </pre>
     * appears in ITCommon.getDBWriter and ClickHouseBatchRunnable. Treating it as
     * a server change closes the live 'system' pool on every call and rebuilds it
     * against the caller's database path, so unqualified lookups registered under
     * 'system' start resolving against 'employees' — the
     * "Code: 81 ... Database employees does not exist" failure that took out
     * DatabaseOverrideInitialIT and ~40 sibling ITs.
     */
    @Test
    public void testSameServerDifferentDatabasePathIsReused() throws Exception {
        HikariDataSource pool = poolFor(dataSource("jdbc:clickhouse://localhost:15547/system"));
        try {
            Assert.assertTrue(
                    "a different database path on the SAME server must reuse the pool",
                    HikariDbSource.servesSameServer(
                            pool, dataSource("jdbc:clickhouse://localhost:15547/employees")));
        } finally {
            pool.close();
        }
    }

    /**
     * The V1/V2 driver toggle appends different query parameters to the URL
     * ({@code clickhouse.jdbc.v1=true} vs
     * {@code jdbc_ignore_unsupported_values=true}), and the query string says
     * nothing about which server is targeted.
     */
    @Test
    public void testQueryStringDifferenceIsReused() throws Exception {
        HikariDataSource pool = poolFor(dataSource("jdbc:clickhouse://localhost:8123/system"));
        try {
            Assert.assertTrue(HikariDbSource.servesSameServer(
                    pool, dataSource("jdbc:clickhouse://localhost:8123/system?extra=1")));
        } finally {
            pool.close();
        }
    }

    /**
     * A different database path must still NOT mask a genuine server change —
     * the container-per-test case this comparison exists for.
     */
    @Test
    public void testDifferentServerStillDetectedAcrossDatabasePaths() throws Exception {
        HikariDataSource pool = poolFor(dataSource("jdbc:clickhouse://localhost:32768/system"));
        try {
            Assert.assertFalse(HikariDbSource.servesSameServer(
                    pool, dataSource("jdbc:clickhouse://localhost:32769/employees")));
        } finally {
            pool.close();
        }
    }

    @Test
    public void testServerEndpointExtraction() {
        Assert.assertEquals("h:8123",
                HikariDbSource.serverEndpoint("jdbc:clickhouse://h:8123/db?a=b"));
        Assert.assertEquals("h:8123",
                HikariDbSource.serverEndpoint("jdbc:clickhouse://h:8123/db"));
        Assert.assertEquals("h:8123",
                HikariDbSource.serverEndpoint("jdbc:clickhouse://h:8123"));
        Assert.assertEquals("h:8123",
                HikariDbSource.serverEndpoint("jdbc:ch://h:8123/db"));
        Assert.assertEquals("h:8123",
                HikariDbSource.serverEndpoint("jdbc:clickhouse-checked://h:8123/db"));
        // Unparseable input must yield null so callers assume a match rather
        // than churning a working pool.
        Assert.assertNull(HikariDbSource.serverEndpoint(null));
        Assert.assertNull(HikariDbSource.serverEndpoint("not-a-url"));
        Assert.assertNull(HikariDbSource.serverEndpoint("jdbc:clickhouse:///db"));
    }
}

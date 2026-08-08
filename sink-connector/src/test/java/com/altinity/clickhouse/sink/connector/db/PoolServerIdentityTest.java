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
}

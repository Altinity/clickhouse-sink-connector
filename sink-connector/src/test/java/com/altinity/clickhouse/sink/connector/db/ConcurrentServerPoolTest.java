package com.altinity.clickhouse.sink.connector.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Tests that pools for the same database name on DIFFERENT servers coexist.
 * <p>
 * Pools used to be cached by database name alone. That cache cannot represent
 * the state that actually occurs — two servers addressed under the same
 * database name at the same time — so the two requests evicted each other in
 * turn, and every eviction closed a pool another live caller was still using.
 * Both sides then failed with
 * <pre>
 *   HttpHostConnectException: Connect to http://localhost:&lt;the other port&gt;
 *                             failed: Connection refused
 * </pre>
 * while both servers were up. The CI signature was a pool ping-pong:
 * <pre>
 *   Replacing pooled connection for 'system' (32841 != 32845)
 *   Replacing pooled connection for 'system' (32845 != 32841)
 *   Replacing pooled connection for 'system' (32841 != 32845/employees)
 * </pre>
 * with 202 replacements in one run, the storage-database setup never
 * succeeding, and the integration assertion reading a column the connector
 * had never created.
 * <p>
 * The server endpoint is now part of the cache key, so each (server, database)
 * pair gets its own slot and no lookup can disturb another server's pool.
 */
public class ConcurrentServerPoolTest {

    private static final String DB = "system";
    private static final String URL_A = "jdbc:clickhouse://localhost:32841/system";
    private static final String URL_B = "jdbc:clickhouse://localhost:32845/system";

    private final List<HikariDataSource> opened = new ArrayList<>();

    private static SinkConnectorDataSource dataSource(String url) throws Exception {
        Properties p = new Properties();
        p.setProperty("user", "default");
        return new SinkConnectorDataSource(url, p);
    }

    /**
     * A lazy pool that never opens a socket: nothing here reaches a server, and
     * an unreachable endpoint must not make construction fail.
     */
    private HikariDataSource poolFor(String url) throws Exception {
        HikariConfig cfg = new HikariConfig();
        cfg.setDataSource(dataSource(url));
        cfg.setMinimumIdle(0);
        cfg.setMaximumPoolSize(1);
        cfg.setInitializationFailTimeout(-1);
        HikariDataSource pool = new HikariDataSource(cfg);
        opened.add(pool);
        return pool;
    }

    @SuppressWarnings("unchecked")
    private static <T> Map<String, T> staticMap(String name) throws Exception {
        Field f = HikariDbSource.class.getDeclaredField(name);
        f.setAccessible(true);
        return (Map<String, T>) f.get(null);
    }

    /**
     * Registers a pool exactly as {@code getInstance} would, so the assertions
     * exercise the real key layout rather than a test-only one.
     */
    private void register(String url, HikariDataSource pool) throws Exception {
        String key = HikariDbSource.poolKey(url, DB);
        staticMap("instance").put(key, pool);
        staticMap("currentKey").put(DB, key);
    }

    @BeforeEach
    public void reset() throws Exception {
        staticMap("instance").clear();
        staticMap("currentKey").clear();
        Field disabled = HikariDbSource.class.getDeclaredField("disabled");
        disabled.setAccessible(true);
        disabled.setBoolean(null, false);
    }

    @AfterEach
    public void cleanup() throws Exception {
        for (HikariDataSource pool : opened) {
            pool.close();
        }
        opened.clear();
        staticMap("instance").clear();
        staticMap("currentKey").clear();
    }

    /**
     * The bug: registering a second server under the same database name must
     * not disturb the first server's pool.
     */
    @Test
    public void testTwoServersUnderOneDatabaseNameCoexist() throws Exception {
        HikariDataSource poolA = poolFor(URL_A);
        register(URL_A, poolA);
        HikariDataSource poolB = poolFor(URL_B);
        register(URL_B, poolB);

        Map<String, HikariDataSource> pools = staticMap("instance");
        Assert.assertEquals("both servers must hold a slot", 2, pools.size());
        Assert.assertSame("the first server's pool must survive",
                poolA, pools.get(HikariDbSource.poolKey(URL_A, DB)));
        Assert.assertSame("the second server's pool must be present",
                poolB, pools.get(HikariDbSource.poolKey(URL_B, DB)));
        Assert.assertFalse("registering another server must not close a live pool",
                poolA.isClosed());
    }

    /**
     * Going back to the first server — the ping-pong the CI log showed — must
     * return the pool that already exists rather than rebuilding it, and must
     * leave the other server's pool alone.
     */
    @Test
    public void testAlternatingBetweenServersReusesBothPools() throws Exception {
        HikariDataSource poolA = poolFor(URL_A);
        register(URL_A, poolA);
        HikariDataSource poolB = poolFor(URL_B);
        register(URL_B, poolB);

        register(URL_A, poolA);
        Assert.assertSame("returning to the first server must reuse its pool",
                poolA, HikariDbSource.getInstance(DB));
        Assert.assertFalse("the other server's pool must stay open", poolB.isClosed());

        register(URL_B, poolB);
        Assert.assertSame("and back again", poolB, HikariDbSource.getInstance(DB));
        Assert.assertFalse(poolA.isClosed());
        Assert.assertEquals(2, staticMap("instance").size());
    }

    /**
     * A database name resolves to the pool of the server most recently
     * requested for it — the retry path is handed nothing else to go on.
     */
    @Test
    public void testLookupByNameFollowsTheCurrentServer() throws Exception {
        HikariDataSource poolA = poolFor(URL_A);
        register(URL_A, poolA);
        Assert.assertSame(poolA, HikariDbSource.getInstance(DB));

        HikariDataSource poolB = poolFor(URL_B);
        register(URL_B, poolB);
        Assert.assertSame("the name must follow the latest server",
                poolB, HikariDbSource.getInstance(DB));
    }

    /**
     * Only the server endpoint distinguishes pools. A different database PATH
     * on the same server is legitimate — {@code createConnection(".../employees",
     * ..., databaseName="system")} — and must map to the same slot, or the
     * connector would hold two pools for one server.
     */
    @Test
    public void testDatabasePathDoesNotSplitTheKey() {
        Assert.assertEquals(
                HikariDbSource.poolKey("jdbc:clickhouse://localhost:32845/system", DB),
                HikariDbSource.poolKey("jdbc:clickhouse://localhost:32845/employees", DB));
        Assert.assertEquals(
                HikariDbSource.poolKey("jdbc:clickhouse://localhost:32845/system", DB),
                HikariDbSource.poolKey("jdbc:clickhouse://localhost:32845/system?x=1", DB));
    }

    /**
     * Different servers, and different databases on one server, must not
     * collide.
     */
    @Test
    public void testKeyDistinguishesServerAndDatabase() {
        Assert.assertNotEquals(
                HikariDbSource.poolKey(URL_A, DB), HikariDbSource.poolKey(URL_B, DB));
        Assert.assertNotEquals(
                HikariDbSource.poolKey(URL_A, "system"),
                HikariDbSource.poolKey(URL_A, "employees"));
    }

    /**
     * An unparseable URL falls back to the database name, preserving the
     * previous behaviour rather than producing an unusable key.
     */
    @Test
    public void testUnparseableUrlFallsBackToTheDatabaseName() {
        Assert.assertEquals(DB, HikariDbSource.poolKey(null, DB));
        Assert.assertEquals(DB, HikariDbSource.poolKey("not-a-url", DB));
    }

    /**
     * close() must forget the name pointers too, or a name would resolve to a
     * key whose pool no longer exists.
     */
    @Test
    public void testCloseClearsBothCaches() throws Exception {
        register(URL_A, poolFor(URL_A));

        HikariDbSource.close();

        Assert.assertTrue("pools must be cleared", staticMap("instance").isEmpty());
        Assert.assertTrue("name pointers must be cleared",
                staticMap("currentKey").isEmpty());
        Assert.assertNull(HikariDbSource.getInstance(DB));
    }
}

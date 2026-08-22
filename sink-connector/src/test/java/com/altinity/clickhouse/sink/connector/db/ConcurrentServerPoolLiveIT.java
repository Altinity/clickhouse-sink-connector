package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * End-to-end check that two LIVE ClickHouse servers addressed under the same
 * database name do not steal each other's connection pool.
 * <p>
 * This reproduces the CI failure against real servers rather than mocks. Two
 * writers are created for database {@code system} on two different servers,
 * exactly as the integration suite does when a previous test class's container
 * is still being addressed while the next one starts. Each writer then runs a
 * query that must land on ITS OWN server.
 * <p>
 * Before the pool was keyed by server, the second writer's pool replaced the
 * first's, and the first writer's next query went to the wrong server —
 * "Connect to http://&lt;other&gt; failed: Connection refused" when that server
 * was gone, or silently the wrong data when it was up. Here both servers stay
 * up, so a cross-over is detected by the server IDENTITY the query returns
 * rather than by a connection error: a wrong-server answer is a silent
 * correctness bug, and asserting on identity catches it either way.
 * <p>
 * Requires two ClickHouse servers and is skipped unless
 * {@code CH_LIVE_A}/{@code CH_LIVE_B} are set to {@code host:port}.
 */
@EnabledIfEnvironmentVariable(named = "CH_LIVE_A", matches = ".+")
public class ConcurrentServerPoolLiveIT {

    private static final String DB = "system";
    private static final String USER =
            System.getenv().getOrDefault("CH_LIVE_USER", "ch_user");
    private static final String PASS =
            System.getenv().getOrDefault("CH_LIVE_PASS", "password");

    private static String hostOf(String env) {
        return System.getenv(env).split(":")[0];
    }

    private static int portOf(String env) {
        return Integer.parseInt(System.getenv(env).split(":")[1]);
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        props.put("connection.pool.disable", "false");
        return new ClickHouseSinkConnectorConfig(props);
    }

    /**
     * Builds a writer the way production does: seed a pooled connection for the
     * target server, then hand it to the writer.
     */
    private static BaseDbWriter writerFor(String env) {
        String host = hostOf(env);
        int port = portOf(env);
        ClickHouseSinkConnectorConfig cfg = config();
        Connection conn = BaseDbWriter.createConnection(
                BaseDbWriter.getConnectionString(host, port, DB),
                BaseDbWriter.DATABASE_CLIENT_NAME, USER, PASS, DB, cfg);
        Assert.assertNotNull("could not connect to " + env + "=" + host + ":" + port,
                conn);
        return new BaseDbWriter(host, port, DB, USER, PASS, cfg, conn);
    }

    @SuppressWarnings("unchecked")
    private static <T> Map<String, T> staticMap(String name) throws Exception {
        Field f = HikariDbSource.class.getDeclaredField(name);
        f.setAccessible(true);
        return (Map<String, T>) f.get(null);
    }

    /**
     * Marks a server so a query can prove which one answered. The marker is a
     * database name, visible in system.databases on that server only.
     */
    private static void mark(Connection conn, String marker) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("create database if not exists " + marker);
        }
    }

    private static boolean sees(Connection conn, String marker) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "select count() from system.databases where name = '"
                             + marker + "'")) {
            return rs.next() && rs.getInt(1) == 1;
        }
    }

    @BeforeEach
    public void reset() throws Exception {
        HikariDbSource.close();
        staticMap("instance").clear();
        staticMap("currentKey").clear();
    }

    @AfterEach
    public void cleanup() {
        HikariDbSource.close();
    }

    /**
     * The regression: a writer must keep talking to its own server after a
     * second writer registers the same database name on a different server.
     */
    @Test
    public void testWriterKeepsItsOwnServerAfterAnotherIsRegistered()
            throws Exception {
        BaseDbWriter writerA = writerFor("CH_LIVE_A");
        mark(writerA.getConnection(), "marker_a");

        // A second engine starts against a DIFFERENT server, same database name.
        BaseDbWriter writerB = writerFor("CH_LIVE_B");
        mark(writerB.getConnection(), "marker_b");

        // Each writer must still be talking to the server it was built for.
        Assert.assertTrue("writer A must still see its own server",
                sees(writerA.getConnection(), "marker_a"));
        Assert.assertFalse("writer A must NOT have been switched to server B",
                sees(writerA.getConnection(), "marker_b"));
        Assert.assertTrue("writer B must see its own server",
                sees(writerB.getConnection(), "marker_b"));
        Assert.assertFalse("writer B must not see server A",
                sees(writerB.getConnection(), "marker_a"));
    }

    /**
     * The retry path is what CI actually exercised: a writer whose connection
     * has been closed re-acquires one, and that replacement must come from its
     * own server even though another server was registered more recently.
     */
    @Test
    public void testReacquiredConnectionComesFromTheWritersOwnServer()
            throws Exception {
        BaseDbWriter writerA = writerFor("CH_LIVE_A");
        mark(writerA.getConnection(), "marker_a");

        BaseDbWriter writerB = writerFor("CH_LIVE_B");
        mark(writerB.getConnection(), "marker_b");

        // Force writer A down the replacement path, the way an evicted or
        // reaped pooled connection does in a long-running task.
        writerA.getConnection().close();
        Assert.assertTrue("precondition: the handle must look unusable",
                BaseDbWriter.isUnusable(writerA.conn));

        Connection replacement = writerA.getConnection();
        Assert.assertNotNull("a replacement connection must be produced",
                replacement);
        Assert.assertFalse("the replacement must be usable", replacement.isClosed());
        Assert.assertTrue("the replacement must come from writer A's server",
                sees(replacement, "marker_a"));
        Assert.assertFalse("the replacement must NOT come from server B",
                sees(replacement, "marker_b"));
    }

    /**
     * Repeated alternation must not churn pools: each server keeps one pool and
     * both stay open, which is what stops the replace/rebuild ping-pong.
     */
    @Test
    public void testAlternatingWritersDoNotChurnPools() throws Exception {
        for (int i = 0; i < 3; i++) {
            BaseDbWriter a = writerFor("CH_LIVE_A");
            BaseDbWriter b = writerFor("CH_LIVE_B");
            Assert.assertTrue("round " + i + ": A must see its own server",
                    sees(a.getConnection(), "marker_a"));
            Assert.assertTrue("round " + i + ": B must see its own server",
                    sees(b.getConnection(), "marker_b"));
        }
        Assert.assertEquals("exactly one pool per server, no churn",
                2, staticMap("instance").size());
    }

    /**
     * The termination guarantee: when a server goes away for good, the retry
     * path must stop, not spin.
     * <p>
     * Pinning a caller to its own server is required so it never answers from
     * the wrong one, but on its own it has no end state. An executor whose
     * container has been torn down keeps a pool nothing evicts, and every
     * retry re-acquires a connection to the dead endpoint and fails again. In
     * CI a single dead port absorbed 19,032 connection-refused traces in 19
     * minutes -- about 16 per second -- for the remainder of the run, and the
     * job log grew from 46 MB to 355 MB.
     * <p>
     * Server B is stopped mid-test, so this exercises a real disappearance
     * rather than a simulated one. Requires CH_KILLABLE=&lt;podman container
     * name&gt; naming a container the test may stop.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "CH_KILLABLE", matches = ".+")
    public void testRetriesTerminateWhenAServerDisappears() throws Exception {
        BaseDbWriter writerA = writerFor("CH_LIVE_A");
        mark(writerA.getConnection(), "marker_a");
        BaseDbWriter writerB = writerFor("CH_LIVE_B");
        mark(writerB.getConnection(), "marker_b");

        // Server B goes away, exactly as a torn-down test container does.
        Process kill = new ProcessBuilder(
                "podman", "stop", "-t", "1", System.getenv("CH_KILLABLE"))
                .redirectErrorStream(true).start();
        Assert.assertEquals("could not stop the container", 0, kill.waitFor());

        writerB.conn = null;

        // Retry the way DBMetadata does. Every attempt must fail, and the
        // failures must become terminal rather than continuing to dial out.
        int attempts = 0;
        int refusals = 0;
        boolean terminal = false;
        for (int i = 0; i < 20; i++) {
            attempts++;
            try {
                Connection c = HikariDbSource.initiateNewConnectionIfClosed(
                        DB, BaseDbWriter.getConnectionString(
                                hostOf("CH_LIVE_B"), portOf("CH_LIVE_B"), DB));
                if (c != null && sees(c, "marker_b")) {
                    Assert.fail("the stopped server answered on attempt " + i);
                }
            } catch (Exception e) {
                String msg = String.valueOf(e.getMessage());
                if (msg.contains("no longer reachable")) {
                    terminal = true;
                    break;
                }
                refusals++;
            }
        }
        Assert.assertTrue("the dead server must be retired rather than dialled "
                + "forever; " + attempts + " attempts, " + refusals
                + " reached the network", terminal);
        Assert.assertTrue("retirement must happen promptly, not after a long "
                + "spin; took " + attempts + " attempts", attempts <= 3);

        // The surviving server must be completely unaffected.
        Assert.assertTrue("writer A must still work after B was retired",
                sees(writerA.getConnection(), "marker_a"));
    }
}

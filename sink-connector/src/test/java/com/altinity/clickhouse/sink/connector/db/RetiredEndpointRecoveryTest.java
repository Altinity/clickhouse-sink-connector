package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 03.05 section 6, FM-03.05-2: a ClickHouse endpoint that
 * {@code HikariDbSource.retireIfServerGone} marked dead.
 *
 * <p>Once retired, {@code initiateNewConnectionIfClosed} refuses the endpoint
 * without probing it again ("is no longer reachable; not retrying"). The only
 * thing that clears the mark is a new registration through
 * {@code HikariDbSource.getInstance} -- i.e. some caller opening a brand-new
 * connection through {@code BaseDbWriter.createConnection}. There is no
 * timed re-probe, so how soon a worker holding a broken connection can write
 * again after ClickHouse comes back depends on an incidental re-registration,
 * not on a bound.</p>
 */
public class RetiredEndpointRecoveryTest {

    private static final String DB = "system";

    private ServerSocket listening;
    private HikariDataSource pool;

    @SuppressWarnings("unchecked")
    private static <T> T staticField(String name) throws Exception {
        Field f = HikariDbSource.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(null);
    }

    /** A DataSource that refuses at once: nothing here may open a real connection. */
    private static DataSource refusingDataSource() {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getConnection":
                    throw new SQLException("stub data source: no server in a unit test");
                case "toString":
                    return "RefusingDataSource";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return null;
            }
        };
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, h);
    }

    private String url() {
        return "jdbc:clickhouse://127.0.0.1:" + listening.getLocalPort() + "/" + DB;
    }

    private String endpoint() {
        return "127.0.0.1:" + listening.getLocalPort();
    }

    @BeforeEach
    public void setUp() throws Exception {
        HikariDbSource.close();
        // The endpoint ACCEPTS TCP connections for the whole test: the server is back.
        listening = new ServerSocket(0);
        HikariConfig cfg = new HikariConfig();
        cfg.setDataSource(refusingDataSource());
        cfg.setMinimumIdle(0);
        cfg.setMaximumPoolSize(1);
        cfg.setConnectionTimeout(250L);
        cfg.setInitializationFailTimeout(-1);
        pool = new HikariDataSource(cfg);
        String key = HikariDbSource.poolKey(url(), DB);
        RetiredEndpointRecoveryTest.<Map<String, HikariDataSource>>staticField("instance").put(key, pool);
        RetiredEndpointRecoveryTest.<Map<String, String>>staticField("currentKey").put(DB, key);
        Field disabled = HikariDbSource.class.getDeclaredField("disabled");
        disabled.setAccessible(true);
        disabled.setBoolean(null, false);
        // The state retireIfServerGone leaves behind after a failed probe.
        RetiredEndpointRecoveryTest.<Set<String>>staticField("deadEndpoints").add(endpoint());
    }

    @AfterEach
    public void tearDown() throws Exception {
        HikariDbSource.close();
        if (pool != null) {
            pool.close();
        }
        listening.close();
    }

    @Test
    @DisplayName("A retired endpoint is refused without a probe until it is registered again; registering clears it")
    public void registeringTheServerAgainClearsItsRetirement() throws Exception {
        SQLException refused = assertThrows(SQLException.class,
                () -> HikariDbSource.initiateNewConnectionIfClosed(DB, url()));
        assertTrue(refused.getMessage().contains("no longer reachable"), refused.getMessage());

        Properties p = new Properties();
        p.setProperty("user", "default");
        HikariDataSource returned = HikariDbSource.getInstance(new SinkConnectorDataSource(url(), p), DB,
                new ClickHouseSinkConnectorConfig(new HashMap<>()), "default", "");

        assertSame(pool, returned, "the live cached pool is reused, not rebuilt");
        assertFalse(RetiredEndpointRecoveryTest.<Set<String>>staticField("deadEndpoints").contains(endpoint()),
                "a registration is the only thing that clears the retirement");
        assertTrue(RetiredEndpointRecoveryTest.<Set<String>>staticField("liveEndpoints").contains(endpoint()));
    }

    @Test
    @Disabled("DEFECT FM-03.05-2: a retired endpoint is never re-probed; it is refused until some caller happens to "
            + "register it again through getInstance, so recovery after ClickHouse returns has no time bound")
    @DisplayName("A retired endpoint that accepts connections again is probed again, not refused")
    public void aRetiredEndpointThatAcceptsConnectionsAgainIsReprobed() {
        SQLException e = assertThrows(SQLException.class,
                () -> HikariDbSource.initiateNewConnectionIfClosed(DB, url()),
                "the stub pool itself refuses, so some SQLException is expected -- but not the retirement one");
        assertFalse(e.getMessage().contains("no longer reachable"),
                "the endpoint is accepting TCP connections, yet it was refused without a probe: " + e.getMessage());
    }
}

package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import io.debezium.storage.jdbc.offset.JdbcOffsetBackingStoreConfig;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 10.03 section 6: the operator-facing {@code show_replica_status} view
 * can be missing, stale, or read the wrong offset field -- and the connector
 * does not notice any of the three.
 *
 * <p>The JDBC objects are JDK proxies; the view SQL is never executed.</p>
 */
public class ReplicaStatusViewFailureModesTest {

    private static final String VIEW = "CREATE OR REPLACE VIEW %s.show_replica_status AS SELECT * FROM %s FINAL";

    /** Records every statement; answers the system.tables existence check with {@code existing}. */
    private static final class RecordingConnection {
        final List<String> executed = new ArrayList<>();
        final List<String> queried = new ArrayList<>();
        final int existing;

        RecordingConnection(int existing) {
            this.existing = existing;
        }

        private ResultSet countResult() {
            final boolean[] read = {false};
            InvocationHandler h = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "next":
                        if (read[0]) {
                            return false;
                        }
                        read[0] = true;
                        return true;
                    case "getInt":
                        return existing;
                    default:
                        return defaultFor(method.getReturnType());
                }
            };
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ResultSet.class}, h);
        }

        private static Object defaultFor(Class<?> type) {
            if (!type.isPrimitive() || type == void.class) {
                return null;
            }
            return type == boolean.class ? Boolean.FALSE : 0;
        }

        Connection connection() {
            InvocationHandler connection = (proxy, method, args) -> {
                if ("prepareStatement".equals(method.getName())) {
                    final String sql = (String) args[0];
                    InvocationHandler ps = (p, m, a) -> {
                        if ("executeQuery".equals(m.getName())) {
                            queried.add(sql);
                            return countResult();
                        }
                        if ("execute".equals(m.getName())) {
                            executed.add(sql);
                            return false;
                        }
                        return defaultFor(m.getReturnType());
                    };
                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[]{PreparedStatement.class}, ps);
                }
                if ("isClosed".equals(method.getName())) {
                    return false;
                }
                if ("hashCode".equals(method.getName())) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(method.getName())) {
                    return proxy == args[0];
                }
                return defaultFor(method.getReturnType());
            };
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, connection);
        }
    }

    private static Properties props(String view) {
        Properties props = new Properties();
        props.setProperty(JdbcOffsetBackingStoreConfig.OFFSET_STORAGE_PREFIX
                + JdbcOffsetBackingStoreConfig.PROP_TABLE_NAME.name(), "altinity_sink_connector.replica_source_info");
        props.setProperty(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE.toString(), "true");
        if (view != null) {
            props.setProperty(ClickHouseSinkConnectorConfigVariables.REPLICA_STATUS_VIEW.toString(), view);
        }
        return props;
    }

    private static void create(RecordingConnection ch, Properties props) {
        new DebeziumJdbcStorageOperations().createViewForShowReplicaStatus(ch.connection(),
                new ClickHouseSinkConnectorConfig(new HashMap<>()), props);
    }

    @Test
    @DisplayName("an existing view is never replaced, so a corrected replica.status.view is not applied (pinned)")
    public void existingViewIsNeverReplaced() {
        RecordingConnection ch = new RecordingConnection(1);
        create(ch, props(VIEW));
        assertEquals(1, ch.queried.size(), "one existence check against system.tables");
        assertTrue(ch.queried.get(0).contains("system.tables"), ch.queried.get(0));
        assertTrue(ch.executed.isEmpty(), "the view exists, so the configured CREATE OR REPLACE is skipped: a "
                + "wrong view stays wrong until an operator drops it. Executed: " + ch.executed);
    }

    @Test
    @DisplayName("a missing view is created in the offset database")
    public void missingViewIsCreated() {
        RecordingConnection ch = new RecordingConnection(0);
        create(ch, props(VIEW));
        assertEquals(1, ch.executed.size(), ch.executed.toString());
        assertTrue(ch.executed.get(0).startsWith(
                "CREATE OR REPLACE VIEW altinity_sink_connector.show_replica_status"), ch.executed.get(0));
        assertTrue(ch.executed.get(0).contains("FROM altinity_sink_connector.replica_source_info FINAL"),
                ch.executed.get(0));
    }

    @Test
    @DisplayName("without replica.status.view no view is created and nothing is queried")
    public void unconfiguredViewIsSkipped() {
        RecordingConnection ch = new RecordingConnection(0);
        create(ch, props(null));
        assertTrue(ch.executed.isEmpty());
        assertTrue(ch.queried.isEmpty());
    }

    @Test
    @DisplayName("the ConfigDef default view reads ts_sec (MySQL) and ts_usec (PostgreSQL) offsets")
    public void configDefDefaultReadsBothOffsetShapes() {
        String view = new ClickHouseSinkConnectorConfig(new HashMap<>())
                .getString(ClickHouseSinkConnectorConfigVariables.REPLICA_STATUS_VIEW.toString());
        assertTrue(view.contains("'ts_sec'"), view);
        assertTrue(view.contains("'ts_usec'"), view);
    }

    private static String shipped(String relative) throws IOException {
        for (Path candidate : new Path[]{Paths.get(relative), Paths.get("sink-connector-lightweight", relative)}) {
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("cannot find " + relative + " from " + Paths.get("").toAbsolutePath());
    }

    /** The value of the {@code replica.status.view:} key of a shipped YAML config, up to the next key. */
    private static String viewOf(String yaml) {
        int at = yaml.indexOf("replica.status.view:");
        assertTrue(at >= 0, "the shipped config sets replica.status.view");
        int end = yaml.indexOf("ORDER BY offset_key", at);
        assertTrue(end > at, "the view definition ends with ORDER BY offset_key");
        return yaml.substring(at, end);
    }

    @Test
    @Disabled("DEFECT FM-10.03-3: the shipped PostgreSQL config (docker/config_postgres.yml) defines "
            + "show_replica_status over offset_val.ts_sec, which a PostgreSQL offset does not carry (Debezium "
            + "PostgresOffsetContext stores ts_usec), so seconds_behind_source reads now() - 0, about 1.8e9 s, "
            + "permanently: the lag alert is always firing and a real stall is indistinguishable")
    @DisplayName("the shipped PostgreSQL view derives the lag from ts_usec")
    public void shippedPostgresViewReadsTsUsec() throws IOException {
        String view = viewOf(shipped("docker/config_postgres.yml"));
        assertTrue(view.contains("ts_usec"), "the PostgreSQL view reads only ts_sec: " + view);
    }

    @Test
    @DisplayName("the bundled config.properties view reads ts_sec, which a MySQL offset carries")
    public void bundledMySqlViewReadsTsSec() throws IOException {
        String properties = shipped("src/main/resources/config.properties");
        int at = properties.indexOf("replica.status.view=");
        assertTrue(at >= 0, "the bundled config sets replica.status.view");
        String view = properties.substring(at, properties.indexOf('\n', at));
        assertTrue(view.contains("'ts_sec'"), view);
        assertFalse(view.contains("'ts_usec'"), "pinned: the bundled view is MySQL-only; " + view);
    }
}

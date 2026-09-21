package com.altinity.clickhouse.debezium.embedded.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.common.SnowFlakeId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The durable version high-water mark (spec 02.02 §3.5, 09.03 §3.4): the table
 * next to the offset table, the write-ahead horizon, the startup read, and the
 * domain-aware fallback over the targets' {@code max(_version)}.
 *
 * <p>JDBC is stood in for by JDK proxies that record every statement and answer
 * scripted result sets (Mockito is not on this module's test classpath; the same
 * technique as VersionFallbackWithoutGtidTest). No ClickHouse is required.</p>
 */
public class VersionHighWaterMarkTest {

    private static final long M = 1_000_000L;
    /** The connector clock in every test: 2026-08-25T00:00:00Z. */
    private static final long NOW = 1_787_616_000_000L;
    /** A source millisecond slightly behind the connector clock. */
    private static final long T = NOW - 30_000;

    /** Scripted ClickHouse: records SQL + bound parameters, answers by SQL substring. */
    static final class FakeClickHouse {
        final List<String> executed = new ArrayList<>();
        final List<List<Object>> boundParameters = new ArrayList<>();
        final Map<String, List<Object[]>> rowsBySqlFragment = new LinkedHashMap<>();
        int failingInsertsRemaining = 0;

        void answer(String sqlFragment, Object[]... rows) {
            rowsBySqlFragment.put(sqlFragment, Arrays.asList(rows));
        }

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "prepareStatement":
                                return statement((String) args[0]);
                            case "isClosed":
                                return false;
                            case "close":
                                return null;
                            default:
                                return defaultValue(method);
                        }
                    });
        }

        private PreparedStatement statement(String sql) {
            List<Object> params = new ArrayList<>();
            InvocationHandler handler = (proxy, method, args) -> {
                String name = method.getName();
                if (name.startsWith("set") && args != null && args.length == 2) {
                    params.add(args[1]);
                    return null;
                }
                switch (name) {
                    case "executeQuery":
                        executed.add(sql);
                        boundParameters.add(new ArrayList<>(params));
                        return resultSet(sql);
                    case "execute":
                    case "executeUpdate":
                        executed.add(sql);
                        boundParameters.add(new ArrayList<>(params));
                        if (sql.startsWith("INSERT") && failingInsertsRemaining > 0) {
                            failingInsertsRemaining--;
                            throw new SQLException("Code: 210. Connection refused (simulated)");
                        }
                        return name.equals("execute") ? Boolean.FALSE : Integer.valueOf(1);
                    case "close":
                        return null;
                    default:
                        return defaultValue(method);
                }
            };
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {PreparedStatement.class}, handler);
        }

        private ResultSet resultSet(String sql) {
            List<Object[]> rows = Collections.emptyList();
            for (Map.Entry<String, List<Object[]>> e : rowsBySqlFragment.entrySet()) {
                if (sql.contains(e.getKey())) {
                    rows = e.getValue();
                    break;
                }
            }
            final List<Object[]> data = rows;
            final int[] cursor = {-1};
            final boolean[] wasNull = {false};
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {ResultSet.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "next":
                                cursor[0]++;
                                return cursor[0] < data.size();
                            case "getString": {
                                Object v = data.get(cursor[0])[((Integer) args[0]) - 1];
                                wasNull[0] = v == null;
                                return v == null ? null : String.valueOf(v);
                            }
                            case "getLong": {
                                Object v = data.get(cursor[0])[((Integer) args[0]) - 1];
                                wasNull[0] = v == null;
                                return v == null ? 0L : ((Number) v).longValue();
                            }
                            case "wasNull":
                                return wasNull[0];
                            case "close":
                                return null;
                            default:
                                return defaultValue(method);
                        }
                    });
        }

        private static Object defaultValue(Method method) {
            Class<?> r = method.getReturnType();
            if (r == boolean.class) {
                return false;
            }
            if (r == int.class) {
                return 0;
            }
            if (r == long.class) {
                return 0L;
            }
            return null;
        }

        List<String> statementsContaining(String fragment) {
            List<String> out = new ArrayList<>();
            for (String s : executed) {
                if (s.contains(fragment)) {
                    out.add(s);
                }
            }
            return out;
        }
    }

    private static VersionHighWaterMark mark(FakeClickHouse fake) {
        return new VersionHighWaterMark(fake::connection, "offsets_db.replica_source_info",
                () -> NOW, 2, 0L);
    }

    @Test
    @DisplayName("the high-water table is created in the offset database and keyed by the offset table name")
    public void tableIsCreatedNextToTheOffsetTable() throws Exception {
        FakeClickHouse fake = new FakeClickHouse();
        VersionHighWaterMark mark = mark(fake);

        mark.ensureTable();
        mark.load();

        List<String> ddl = fake.statementsContaining("CREATE TABLE IF NOT EXISTS");
        assertEquals(1, ddl.size());
        assertTrue(ddl.get(0).contains("`offsets_db`.`replica_version_high_water`"),
                "the table lives next to the offset table, in the offset database: " + ddl.get(0));
        assertTrue(ddl.get(0).contains("ENGINE = ReplacingMergeTree(high_water_version)")
                && ddl.get(0).contains("ORDER BY offset_table"), ddl.get(0));

        List<String> reads = fake.statementsContaining("SELECT max(`high_water_version`)");
        assertEquals(1, reads.size());
        assertEquals(Collections.singletonList("offsets_db.replica_source_info"),
                fake.boundParameters.get(fake.executed.indexOf(reads.get(0))),
                "the row is keyed by the fully qualified offset table name");
    }

    @Test
    @DisplayName("the horizon is written before the first covered version and reused until a version exceeds it")
    public void horizonIsWrittenAheadOfHandoffAndReusedUntilExceeded() throws Exception {
        FakeClickHouse fake = new FakeClickHouse();
        VersionHighWaterMark mark = mark(fake);
        long v1 = T * M + 1_000_000_001L;

        mark.cover(v1);
        List<String> inserts = fake.statementsContaining("INSERT INTO `offsets_db`.`replica_version_high_water`");
        assertEquals(1, inserts.size(), "the first covered version moves the horizon and persists it");
        long horizon1 = v1 + VersionHighWaterMark.HORIZON_HEADROOM_MS * M;
        assertEquals(Arrays.asList("offsets_db.replica_source_info", horizon1),
                fake.boundParameters.get(fake.executed.indexOf(inserts.get(0))));
        assertEquals(horizon1, mark.horizon());

        mark.cover(v1 + 100);
        mark.cover(horizon1);
        assertEquals(1, fake.statementsContaining("INSERT INTO").size(),
                "versions at or below the horizon are already covered: nothing is written");

        mark.cover(horizon1 + 1);
        inserts = fake.statementsContaining("INSERT INTO");
        assertEquals(2, inserts.size(), "the first version above the horizon moves it again");
        assertEquals(horizon1 + 1 + VersionHighWaterMark.HORIZON_HEADROOM_MS * M, mark.horizon());
    }

    @Test
    @DisplayName("load returns the highest persisted mark (0 when absent) and primes the horizon")
    public void loadReadsTheHighestPersistedMark() throws Exception {
        FakeClickHouse empty = new FakeClickHouse();
        empty.answer("SELECT max(`high_water_version`)", new Object[] {null});
        assertEquals(0L, mark(empty).load(), "an absent row reads as 0");

        FakeClickHouse fake = new FakeClickHouse();
        long persisted = T * M + 1_000_000_000L;
        fake.answer("SELECT max(`high_water_version`)", new Object[] {String.valueOf(persisted)});
        VersionHighWaterMark mark = mark(fake);
        assertEquals(persisted, mark.load());
        assertEquals(persisted, mark.horizon(), "the persisted mark is the horizon in force");

        mark.cover(persisted - 1);
        assertTrue(fake.statementsContaining("INSERT INTO").isEmpty(),
                "a version already below the persisted horizon needs no write");
    }

    @Test
    @DisplayName("a horizon that cannot be persisted fails loudly and leaves the horizon unmoved")
    public void horizonWriteFailureIsLoud() {
        FakeClickHouse fake = new FakeClickHouse();
        fake.failingInsertsRemaining = Integer.MAX_VALUE;
        VersionHighWaterMark mark = mark(fake);
        long v1 = T * M + 1_000_000_001L;

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> mark.cover(v1));
        assertTrue(failure.getMessage().contains("replica_version_high_water"), failure.getMessage());
        assertEquals(2, fake.statementsContaining("INSERT INTO").size(), "every configured attempt was made");
        assertEquals(0L, mark.horizon(), "an unpersisted horizon is not in force");
    }

    @Test
    @DisplayName("a sequence-domain version decodes to floorDiv(v, 1e6) + 1")
    public void decodesSequenceDomainVersions() {
        long v = T * M + 1_000_000_000L + 5;
        assertEquals(T + 1001, VersionHighWaterMark.decodePlausibleFloor(v, NOW));
    }

    @Test
    @DisplayName("a snowflake-domain version decodes to its timestamp field + 1")
    public void decodesSnowflakeDomainVersions() {
        long v = SnowFlakeId.generate(T, 42L, false);
        assertEquals(T + 1, VersionHighWaterMark.decodePlausibleFloor(v, NOW));
    }

    @Test
    @DisplayName("versions with no plausible timestamp decoding are rejected")
    public void rejectsImplausibleVersions() {
        assertEquals(0L, VersionHighWaterMark.decodePlausibleFloor(Long.MAX_VALUE, NOW),
                "a UInt64-max sentinel row decodes to nothing");
        assertEquals(0L, VersionHighWaterMark.decodePlausibleFloor(12_345L, NOW),
                "a raw GTID transaction number is not timestamp-anchored");
        assertEquals(0L, VersionHighWaterMark.decodePlausibleFloor(1_000_000_000_000L, NOW),
                "a PostgreSQL LSN is not timestamp-anchored");
        assertEquals(0L, VersionHighWaterMark.decodePlausibleFloor(0L, NOW));
        assertEquals(0L, VersionHighWaterMark.decodePlausibleFloor(-1L, NOW));
        long tenYearsAhead = (NOW + 10L * 365 * 24 * 3_600_000L) * M;
        assertTrue(VersionHighWaterMark.decodePlausibleFloor(tenYearsAhead, NOW) <= NOW,
                "a value a decade in the future is never taken as a sequence-domain floor");
    }

    @Test
    @DisplayName("the target scan seeds from the highest plausible max(_version) across the targets, in either domain")
    public void scanSeedsFromTheHighestPlausibleTargetVersion() throws Exception {
        FakeClickHouse fake = new FakeClickHouse();
        fake.answer("system.columns", new Object[] {"target_db1", "orders"}, new Object[] {"target_db1", "events"},
                new Object[] {"target_db2", "broken"});
        fake.answer("FROM `target_db1`.`orders`", new Object[] {String.valueOf(T * M + 1_000_000_000L)});
        fake.answer("FROM `target_db1`.`events`", new Object[] {String.valueOf(SnowFlakeId.generate(T + 60_000, 7L, false))});
        fake.answer("FROM `target_db2`.`broken`", new Object[] {"18446744073709551615"});
        VersionHighWaterMark mark = mark(fake);

        long floor = mark.scanTargets(Arrays.asList("target_db1", "target_db2"), "_version");

        assertEquals(T + 60_001, floor, "the snowflake-versioned table carries the newest timestamp");
        List<String> discovery = fake.statementsContaining("system.columns");
        assertEquals(1, discovery.size());
        assertTrue(discovery.get(0).contains("ReplacingMergeTree"), discovery.get(0));
        assertEquals(Arrays.asList("_version", "target_db1", "target_db2"),
                fake.boundParameters.get(fake.executed.indexOf(discovery.get(0))));
        assertEquals(3, fake.statementsContaining("SELECT max(`_version`)").size(),
                "every discovered table is asked once");

        assertEquals(0L, mark.scanTargets(Collections.emptyList(), "_version"),
                "no target databases: nothing to scan");
    }

    @Test
    @DisplayName("target databases come from the literal database.include.list entries mapped through the override map and prefix")
    public void targetDatabasesFollowTheIncludeListAndOverrides() throws Exception {
        Properties props = new Properties();
        props.setProperty("database.include.list", "db1, db2");
        Map<String, String> configMap = new HashMap<>();
        ClickHouseSinkConnectorConfig.setDefaultValues(configMap);
        configMap.put(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATABASE_OVERRIDE_MAP.toString(), "db1:target_db1");
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(configMap);

        assertEquals(Arrays.asList("target_db1", "db2"), VersionHighWaterMark.targetDatabases(props, config));

        Properties pattern = new Properties();
        pattern.setProperty("database.include.list", "db.*");
        assertTrue(VersionHighWaterMark.targetDatabases(pattern, config).isEmpty(),
                "a pattern entry cannot be resolved to ClickHouse databases without guessing");

        assertTrue(VersionHighWaterMark.targetDatabases(new Properties(), config).isEmpty());
    }
}

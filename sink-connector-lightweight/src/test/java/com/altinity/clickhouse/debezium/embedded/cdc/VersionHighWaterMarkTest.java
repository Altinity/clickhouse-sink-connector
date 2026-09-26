package com.altinity.clickhouse.debezium.embedded.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;

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
 * clock seed of a start that has no mark -- which must never read a target table
 * (Invariant I14, spec 10.06).
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
    @DisplayName("a persisted mark seeds floorDiv(v, 1e6) + 1 and the seed touches only the mark table")
    public void seedFloorUsesThePersistedMark() throws Exception {
        FakeClickHouse fake = new FakeClickHouse();
        fake.answer("SELECT max(`high_water_version`)", new Object[] {String.valueOf(T * M + 1_000_000_000L + 5)});
        VersionHighWaterMark mark = mark(fake);

        VersionHighWaterMark.Seed seed = mark.seedFloor();

        assertTrue(seed.fromMark, seed.source);
        assertEquals(T + 1001, seed.floorMs, "floorDiv(v, 1e6) + 1: the slot strictly above the mark");
        assertOnlyTheMarkTableWasTouched(fake);
    }

    /**
     * Invariant I14 (spec 10.06): a start without a mark row is seeded from the
     * connector clock, and the seed NEVER reads a target table. The previous
     * design ran {@code SELECT max(_version)} over every replicated table here --
     * thousands of full-column scans per hour on a large replica, re-run by every
     * engine retry on the event thread. This test pins the statement set of the
     * seed: it goes red the moment any discovery or table read is added back.
     */
    @Test
    @DisplayName("without a mark the floor is the connector clock plus head-room, and no target table is read")
    public void seedFloorWithoutAMarkUsesTheClockAndReadsNoTargetTable() throws Exception {
        FakeClickHouse fake = new FakeClickHouse();
        VersionHighWaterMark mark = mark(fake);

        VersionHighWaterMark.Seed seed = mark.seedFloor();

        assertTrue(!seed.fromMark, seed.source);
        assertEquals(NOW + VersionHighWaterMark.CLOCK_SEED_HEADROOM_MS, seed.floorMs,
                "the clock plus head-room; the floor is only ever raised, so higher is the safe side");
        assertTrue(seed.floorMs * M + 1 > T * M + 1_000_000_000L + 5,
                "a first delivery at floor * 1e6 + 1 out-ranks a version the previous run wrote at T");
        assertOnlyTheMarkTableWasTouched(fake);
    }

    @Test
    @DisplayName("an implausible persisted mark is ignored and the clock seed is used instead")
    public void implausiblePersistedMarkFallsBackToTheClock() throws Exception {
        FakeClickHouse fake = new FakeClickHouse();
        fake.answer("SELECT max(`high_water_version`)", new Object[] {"18446744073709551615"});
        VersionHighWaterMark mark = mark(fake);

        VersionHighWaterMark.Seed seed = mark.seedFloor();

        assertTrue(!seed.fromMark, seed.source);
        assertEquals(NOW + VersionHighWaterMark.CLOCK_SEED_HEADROOM_MS, seed.floorMs);
        assertOnlyTheMarkTableWasTouched(fake);
    }

    /** The whole statement set of a seed: CREATE TABLE IF NOT EXISTS + one read, both on the mark table. */
    private static void assertOnlyTheMarkTableWasTouched(FakeClickHouse fake) {
        assertEquals(2, fake.executed.size(), "exactly two statements: " + fake.executed);
        for (String sql : fake.executed) {
            assertTrue(sql.contains("`offsets_db`.`replica_version_high_water`"),
                    "every statement of the seed names the mark table: " + sql);
            assertTrue(!sql.contains("system.columns") && !sql.contains("system.tables"),
                    "the seed does not discover target tables: " + sql);
            assertTrue(!sql.contains("SELECT max(`_version`)") && !sql.contains("max_execution_time"),
                    "the seed does not scan a target table: " + sql);
        }
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

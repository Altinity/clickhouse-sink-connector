package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.config.SinkConnectorLightWeightConfig;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.PrimaryKeyRebuildPlan;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.PrimaryKeyRebuildPlan.Provenance;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 06.09: the primary-key rebuild at the DDL barrier, driven against a
 * scripted, recording stand-in for the ClickHouse (and, where needed, the
 * MySQL) connection. JDK proxies, because this module has no mocking
 * framework on its test classpath. Every SQL text the rebuild sends is
 * recorded in order; the metadata queries are answered from a script.
 */
public class PrimaryKeyRebuildTest {

    private static final long EPOCH = 1700000000000L;
    private static final String S = "t__pk_rebuild_" + EPOCH;
    private static final String K = "t__pk_rebuild_keys_" + EPOCH;

    private static final String ENGINE_FULL =
            "ReplacingMergeTree(_version, is_deleted) ORDER BY id SETTINGS index_granularity = 8192";

    private static final String SHOW_CREATE = "CREATE TABLE employees.t\n"
            + "(\n"
            + "    `id` Int32,\n"
            + "    `tenant` Int32,\n"
            + "    `name` Nullable(String),\n"
            + "    `_version` UInt64,\n"
            + "    `is_deleted` UInt8\n"
            + ")\n"
            + "ENGINE = ReplacingMergeTree(_version, is_deleted)\n"
            + "ORDER BY id\n"
            + "SETTINGS index_granularity = 8192";

    @BeforeAll
    static void engine() {
        DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine = true;
    }

    // ------------------------------------------------------------------
    // Recording fakes
    // ------------------------------------------------------------------

    /** A scripted, recording JDBC connection. */
    static final class FakeDb {
        /** Every SQL text sent, in order (Statement.execute/executeQuery and prepareStatement). */
        final List<String> executed = new ArrayList<>();
        /** Rows bound through PreparedStatement.addBatch, in order. */
        final List<List<Object>> batched = new ArrayList<>();
        final AtomicInteger executeBatchCalls = new AtomicInteger();
        private final List<Map.Entry<Predicate<String>, List<Object[]>>> answers = new ArrayList<>();
        private int[] metaTypes = new int[0];

        /** Answers queries containing {@code fragment} with {@code rows} (first script wins). */
        FakeDb answer(String fragment, Object[]... rows) {
            answers.add(new java.util.AbstractMap.SimpleEntry<>(sql -> sql.contains(fragment), Arrays.asList(rows)));
            return this;
        }

        /** JDBC types reported by ResultSetMetaData for every result (the source side). */
        FakeDb metaTypes(int... types) {
            this.metaTypes = types;
            return this;
        }

        private List<Object[]> rowsFor(String sql) {
            for (Map.Entry<Predicate<String>, List<Object[]>> a : answers) {
                if (a.getKey().test(sql)) {
                    return a.getValue();
                }
            }
            return Collections.emptyList();
        }

        private static Object defaultFor(Class<?> type) {
            if (!type.isPrimitive() || type == void.class) {
                return null;
            }
            return type == boolean.class ? Boolean.FALSE : 0;
        }

        private Object plain(Object proxy, java.lang.reflect.Method method, Object[] args, String name) {
            switch (method.getName()) {
                case "toString":
                    return name;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultFor(method.getReturnType());
            }
        }

        Connection connection() {
            InvocationHandler h = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "createStatement":
                        return statement();
                    case "prepareStatement":
                        executed.add((String) args[0]);
                        return prepared();
                    case "isClosed":
                    case "isReadOnly":
                        return false;
                    default:
                        return plain(proxy, method, args, "FakeConnection");
                }
            };
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, h);
        }

        private Statement statement() {
            InvocationHandler h = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "execute":
                        executed.add((String) args[0]);
                        return false;
                    case "executeQuery":
                        executed.add((String) args[0]);
                        return resultSet(rowsFor((String) args[0]));
                    default:
                        return plain(proxy, method, args, "FakeStatement");
                }
            };
            return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Statement.class}, h);
        }

        private PreparedStatement prepared() {
            final Map<Integer, Object> params = new LinkedHashMap<>();
            InvocationHandler h = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "setObject":
                        params.put((Integer) args[0], args[1]);
                        return null;
                    case "addBatch":
                        batched.add(new ArrayList<>(params.values()));
                        params.clear();
                        return null;
                    case "executeBatch":
                        executeBatchCalls.incrementAndGet();
                        return new int[0];
                    default:
                        return plain(proxy, method, args, "FakePreparedStatement");
                }
            };
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, h);
        }

        private ResultSet resultSet(final List<Object[]> rows) {
            final int[] cursor = {-1};
            InvocationHandler h = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "next":
                        cursor[0]++;
                        return cursor[0] < rows.size();
                    case "getString": {
                        Object v = rows.get(cursor[0])[(Integer) args[0] - 1];
                        return v == null ? null : String.valueOf(v);
                    }
                    case "getLong":
                        return ((Number) rows.get(cursor[0])[(Integer) args[0] - 1]).longValue();
                    case "getObject":
                        return rows.get(cursor[0])[(Integer) args[0] - 1];
                    case "getMetaData":
                        return metaData();
                    default:
                        return plain(proxy, method, args, "FakeResultSet");
                }
            };
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ResultSet.class}, h);
        }

        private ResultSetMetaData metaData() {
            InvocationHandler h = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getColumnCount":
                        return metaTypes.length;
                    case "getColumnType":
                        return metaTypes[(Integer) args[0] - 1];
                    case "getColumnTypeName":
                        return "type" + args[0];
                    default:
                        return plain(proxy, method, args, "FakeMetaData");
                }
            };
            return (ResultSetMetaData) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ResultSetMetaData.class}, h);
        }
    }

    private static Object[] row(Object... values) {
        return values;
    }

    /** The rebuild's own statements: everything but the metadata reads. */
    private static List<String> actions(FakeDb db) {
        List<String> out = new ArrayList<>();
        for (String sql : db.executed) {
            if (sql.startsWith("CREATE") || sql.startsWith("ALTER") || sql.startsWith("INSERT")
                    || sql.startsWith("SELECT count()") || sql.startsWith("EXCHANGE") || sql.startsWith("RENAME")
                    || sql.startsWith("DROP")) {
                out.add(sql);
            }
        }
        return out;
    }

    private static Object[][] tColumns(boolean nullableId, boolean withNewId) {
        List<Object[]> rows = new ArrayList<>();
        rows.add(row("id", nullableId ? "Nullable(Int32)" : "Int32", ""));
        rows.add(row("tenant", "Int32", ""));
        rows.add(row("name", "Nullable(String)", ""));
        if (withNewId) {
            rows.add(row("new_id", "UInt64", ""));
        }
        rows.add(row("_version", "UInt64", ""));
        rows.add(row("is_deleted", "UInt8", ""));
        return rows.toArray(new Object[0][]);
    }

    /** A ClickHouse holding employees.t keyed by id, healthy counts, Atomic database. */
    private static FakeDb clickHouse(String engineFull, String showCreate, long expected, long actual,
                                     boolean nullableId, boolean withNewId) {
        return new FakeDb()
                .answer("SELECT engine_full FROM system.tables", row(engineFull))
                .answer("AND table = 't' ORDER BY position", tColumns(nullableId, withNewId))
                .answer("AND table = '" + S + "' ORDER BY position", tColumns(nullableId, withNewId))
                .answer("SHOW CREATE TABLE", row(showCreate))
                .answer("SELECT count() FROM (", row(expected))
                .answer("SELECT count() FROM `employees`.`" + S + "`", row(actual))
                .answer("SELECT count() FROM `employees`.`t` FINAL", row(expected + 1))
                .answer("SELECT engine FROM system.databases", row("Atomic"));
    }

    private static PrimaryKeyRebuildPlan existingKeyPlan() {
        Map<String, Provenance> provenance = new LinkedHashMap<>();
        provenance.put("tenant", Provenance.EXISTING);
        return new PrimaryKeyRebuildPlan("employees", "t", Collections.singletonList("id"),
                Collections.singletonList("tenant"), false, provenance,
                "ALTER TABLE t DROP PRIMARY KEY, ADD PRIMARY KEY (tenant)");
    }

    private static PrimaryKeyRebuildPlan sourceValuedPlan() {
        Map<String, Provenance> provenance = new LinkedHashMap<>();
        provenance.put("new_id", Provenance.SOURCE_VALUED);
        return new PrimaryKeyRebuildPlan("employees", "t", Collections.singletonList("id"),
                Collections.singletonList("new_id"), false, provenance,
                "ALTER TABLE t DROP PRIMARY KEY, ADD COLUMN new_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT FIRST, "
                        + "ADD PRIMARY KEY (new_id)");
    }

    private static Supplier<Connection> noSource() {
        return () -> {
            throw new AssertionError("the source must not be opened when no column is SOURCE_VALUED");
        };
    }

    private static ClickHouseSinkConnectorConfig config() {
        return new ClickHouseSinkConnectorConfig(new HashMap<>());
    }

    // ------------------------------------------------------------------
    // rewriteCreateStatement (Spec 06.09 §3.3 step 3)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The rebuilt definition renames the table, replaces ORDER BY with the new key and drops PRIMARY KEY")
    public void rewritesOrderByAndTableName() {
        String create = "CREATE TABLE employees.t\n"
                + "(\n"
                + "    `id` Int32,\n"
                + "    `tenant` Int32,\n"
                + "    `_version` UInt64,\n"
                + "    `is_deleted` UInt8\n"
                + ")\n"
                + "ENGINE = ReplacingMergeTree(_version, is_deleted)\n"
                + "PRIMARY KEY id\n"
                + "ORDER BY (id, tenant)\n"
                + "SETTINGS index_granularity = 8192";
        String expected = "CREATE TABLE `employees`.`t__pk_rebuild_1`\n"
                + "(\n"
                + "    `id` Int32,\n"
                + "    `tenant` Int32,\n"
                + "    `_version` UInt64,\n"
                + "    `is_deleted` UInt8\n"
                + ")\n"
                + "ENGINE = ReplacingMergeTree(_version, is_deleted)\n"
                + "ORDER BY (`tenant`)\n"
                + "SETTINGS index_granularity = 8192";
        Map<String, String> types = new LinkedHashMap<>();
        types.put("id", "Int32");
        types.put("tenant", "Int32");
        assertEquals(expected, PrimaryKeyRebuild.rewriteCreateStatement(create, "employees", "t", "t__pk_rebuild_1",
                Collections.singletonList("tenant"), false, types));

        // Backticked header, single-column ORDER BY, composite new key: same shape.
        String quoted = create.replace("CREATE TABLE employees.t", "CREATE TABLE `employees`.`t`")
                .replace("ORDER BY (id, tenant)", "ORDER BY id");
        String rewritten = PrimaryKeyRebuild.rewriteCreateStatement(quoted, "employees", "t", "t__pk_rebuild_1",
                Arrays.asList("tenant", "id"), false, types);
        assertEquals(expected.replace("ORDER BY (`tenant`)", "ORDER BY (`tenant`, `id`)"), rewritten);
        assertFalse(rewritten.contains("PRIMARY KEY"));
    }

    @Test
    @DisplayName("Engine arguments, PARTITION BY, SAMPLE BY, TTL and SETTINGS are kept verbatim")
    public void rewriteKeepsPartitionTtlAndSettings() {
        String create = "CREATE TABLE employees.t\n"
                + "(\n"
                + "    `id` Int32,\n"
                + "    `tenant` UInt32,\n"
                + "    `d` Date,\n"
                + "    `_version` UInt64,\n"
                + "    `is_deleted` UInt8\n"
                + ")\n"
                + "ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{uuid}/{shard}', '{replica}', _version, is_deleted)\n"
                + "PARTITION BY toYYYYMM(d)\n"
                + "ORDER BY id\n"
                + "SAMPLE BY tenant\n"
                + "TTL d + toIntervalDay(30)\n"
                + "SETTINGS index_granularity = 8192, ttl_only_drop_parts = 1\n"
                + "COMMENT 'mirrored'";
        String rewritten = PrimaryKeyRebuild.rewriteCreateStatement(create, "employees", "t", "t__pk_rebuild_2",
                Collections.singletonList("tenant"), false, Collections.emptyMap());
        String expected = create.replace("CREATE TABLE employees.t", "CREATE TABLE `employees`.`t__pk_rebuild_2`")
                .replace("ORDER BY id", "ORDER BY (`tenant`)");
        assertEquals(expected, rewritten);
    }

    @Test
    @DisplayName("New-key columns declared Nullable(X) are re-declared X, other columns untouched")
    public void rewriteMakesKeyColumnsNonNullable() {
        String create = "CREATE TABLE employees.t\n"
                + "(\n"
                + "    `id` Int32,\n"
                + "    `name` Nullable(String),\n"
                + "    `d` Nullable(DateTime64(3, 'UTC')) DEFAULT now64(3),\n"
                + "    `code` LowCardinality(Nullable(String)) CODEC(ZSTD(1)),\n"
                + "    `note` Nullable(String),\n"
                + "    `_version` UInt64,\n"
                + "    `is_deleted` UInt8\n"
                + ")\n"
                + "ENGINE = ReplacingMergeTree(_version, is_deleted)\n"
                + "ORDER BY id\n"
                + "SETTINGS index_granularity = 8192";
        Map<String, String> types = new LinkedHashMap<>();
        types.put("name", "Nullable(String)");
        types.put("d", "Nullable(DateTime64(3, 'UTC'))");
        types.put("code", "LowCardinality(Nullable(String))");
        String rewritten = PrimaryKeyRebuild.rewriteCreateStatement(create, "employees", "t", "t__pk_rebuild_3",
                Arrays.asList("name", "d", "code"), false, types);
        assertTrue(rewritten.contains("    `name` String,\n"), rewritten);
        assertTrue(rewritten.contains("    `d` DateTime64(3, 'UTC') DEFAULT now64(3),\n"), rewritten);
        assertTrue(rewritten.contains("    `code` LowCardinality(String) CODEC(ZSTD(1)),\n"), rewritten);
        assertTrue(rewritten.contains("    `note` Nullable(String),\n"), rewritten);
        assertTrue(rewritten.endsWith("ORDER BY (`name`, `d`, `code`)\nSETTINGS index_granularity = 8192"), rewritten);
        assertFalse(rewritten.contains("allow_nullable_key"), rewritten);
    }

    @Test
    @DisplayName("The keyless all-columns fallback keeps Nullable key columns and adds allow_nullable_key = 1")
    public void keylessFallbackAddsAllowNullableKey() {
        String create = "CREATE TABLE employees.t\n"
                + "(\n"
                + "    `id` Int32,\n"
                + "    `name` Nullable(String),\n"
                + "    `_version` UInt64,\n"
                + "    `is_deleted` UInt8\n"
                + ")\n"
                + "ENGINE = ReplacingMergeTree(_version, is_deleted)\n"
                + "ORDER BY id\n"
                + "SETTINGS index_granularity = 8192";
        Map<String, String> types = new LinkedHashMap<>();
        types.put("id", "Int32");
        types.put("name", "Nullable(String)");
        String rewritten = PrimaryKeyRebuild.rewriteCreateStatement(create, "employees", "t", "t__pk_rebuild_4",
                Arrays.asList("id", "name"), true, types);
        assertTrue(rewritten.contains("    `name` Nullable(String),\n"), rewritten);
        assertTrue(rewritten.endsWith("ORDER BY (`id`, `name`)\n"
                + "SETTINGS index_granularity = 8192, allow_nullable_key = 1"), rewritten);

        // No SETTINGS clause: one is created; a COMMENT stays last.
        String noSettings = create.replace("\nSETTINGS index_granularity = 8192", "\nCOMMENT 'x'");
        String rewrittenNoSettings = PrimaryKeyRebuild.rewriteCreateStatement(noSettings, "employees", "t",
                "t__pk_rebuild_4", Arrays.asList("id", "name"), true, types);
        assertTrue(rewrittenNoSettings.endsWith("ORDER BY (`id`, `name`)\nSETTINGS allow_nullable_key = 1\nCOMMENT 'x'"),
                rewrittenNoSettings);

        // An existing allow_nullable_key = 0 is set to 1, not duplicated.
        String zero = create.replace("index_granularity = 8192", "index_granularity = 8192, allow_nullable_key = 0");
        String rewrittenZero = PrimaryKeyRebuild.rewriteCreateStatement(zero, "employees", "t", "t__pk_rebuild_4",
                Arrays.asList("id", "name"), true, types);
        assertTrue(rewrittenZero.endsWith("SETTINGS index_granularity = 8192, allow_nullable_key = 1"), rewrittenZero);

        // No Nullable key column: nothing added even in the fallback.
        String noNullable = PrimaryKeyRebuild.rewriteCreateStatement(create, "employees", "t", "t__pk_rebuild_4",
                Collections.singletonList("id"), true, types);
        assertFalse(noNullable.contains("allow_nullable_key"), noNullable);
    }

    // ------------------------------------------------------------------
    // The protocol (Spec 06.09 §3.3) against the recording connection
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Local rebuild: CREATE, INSERT...SELECT FINAL WHERE is_deleted = 0, both counts, EXCHANGE, DROP; no source read")
    public void localCopyStatementSequence() {
        FakeDb ch = clickHouse(ENGINE_FULL, SHOW_CREATE, 5, 5, false, false);

        PrimaryKeyRebuild.execute(existingKeyPlan(), ch.connection(), new Properties(), config(), "hr", noSource(),
                EPOCH);

        String select = "SELECT `id`, `tenant`, `name`, `_version`, `is_deleted` FROM `employees`.`t` FINAL "
                + "WHERE `is_deleted` = 0";
        List<String> expected = Arrays.asList(
                "CREATE TABLE `employees`.`" + S + "`\n"
                        + "(\n"
                        + "    `id` Int32,\n"
                        + "    `tenant` Int32,\n"
                        + "    `name` Nullable(String),\n"
                        + "    `_version` UInt64,\n"
                        + "    `is_deleted` UInt8\n"
                        + ")\n"
                        + "ENGINE = ReplacingMergeTree(_version, is_deleted)\n"
                        + "ORDER BY (`tenant`)\n"
                        + "SETTINGS index_granularity = 8192",
                "INSERT INTO `employees`.`" + S + "` (`id`, `tenant`, `name`, `_version`, `is_deleted`) " + select,
                "SELECT count() FROM (" + select + ")",
                "SELECT count() FROM `employees`.`" + S + "`",
                "EXCHANGE TABLES `employees`.`t` AND `employees`.`" + S + "`",
                // DESTRUCTIVE: expected statement text recorded by a fake connection; nothing is executed against any database.
                "DROP TABLE IF EXISTS `employees`.`" + S + "`");
        assertEquals(expected, actions(ch));
        assertTrue(ch.batched.isEmpty(), "no key map is filled without a SOURCE_VALUED column");

        // The live predicate follows the engine's delete flag; version-only engines have none.
        assertEquals("`is_deleted` = 0", PrimaryKeyRebuild.liveRowPredicate(ENGINE_FULL));
        assertEquals("`_is_deleted` = 0", PrimaryKeyRebuild.liveRowPredicate(
                "ReplicatedReplacingMergeTree('/clickhouse/tables/{uuid}/{shard}', '{replica}', _version, _is_deleted) ORDER BY id"));
        assertNull(PrimaryKeyRebuild.liveRowPredicate("ReplacingMergeTree(_version) ORDER BY id"));
    }

    @Test
    @DisplayName("A SOURCE_VALUED column: key-map table, read-only source SELECT, JOIN copy taking the column from the map")
    public void sourceKeyMapJoinSequence() {
        String showCreate = SHOW_CREATE.replace("    `tenant` Int32,\n", "    `tenant` Int32,\n")
                .replace("    `name` Nullable(String),\n", "    `name` Nullable(String),\n    `new_id` UInt64,\n");
        FakeDb ch = clickHouse(ENGINE_FULL, showCreate, 3, 3, false, true);
        FakeDb source = new FakeDb()
                .metaTypes(Types.INTEGER, Types.BIGINT)
                .answer("FROM `hr`.`t`", row(1, 101L), row(2, 102L), row(3, 103L));
        Connection[] opened = new Connection[1];
        Supplier<Connection> supplier = () -> opened[0] = source.connection();

        PrimaryKeyRebuild.execute(sourceValuedPlan(), ch.connection(), new Properties(), config(), "hr", supplier,
                EPOCH);

        // The source: exactly one statement, a SELECT keyed by the old identity.
        assertEquals(Collections.singletonList("SELECT `id`, `new_id` FROM `hr`.`t`"), source.executed);
        assertTrue(opened[0] != null, "the source connection was opened through the supplier");
        // The key map holds every source row, bound in order.
        assertEquals(Arrays.asList(Arrays.asList(1, 101L), Arrays.asList(2, 102L), Arrays.asList(3, 103L)),
                ch.batched);
        assertEquals(1, ch.executeBatchCalls.get());

        String select = "SELECT o.`id`, o.`tenant`, o.`name`, k.`new_id`, o.`_version`, o.`is_deleted` "
                + "FROM `employees`.`t` AS o FINAL INNER JOIN `employees`.`" + K + "` AS k ON o.`id` = k.`id` "
                + "WHERE o.`is_deleted` = 0";
        List<String> expected = Arrays.asList(
                "CREATE TABLE `employees`.`" + S + "`\n"
                        + "(\n"
                        + "    `id` Int32,\n"
                        + "    `tenant` Int32,\n"
                        + "    `name` Nullable(String),\n"
                        + "    `new_id` UInt64,\n"
                        + "    `_version` UInt64,\n"
                        + "    `is_deleted` UInt8\n"
                        + ")\n"
                        + "ENGINE = ReplacingMergeTree(_version, is_deleted)\n"
                        + "ORDER BY (`new_id`)\n"
                        + "SETTINGS index_granularity = 8192",
                "CREATE TABLE `employees`.`" + K + "` (`id` Int32, `new_id` UInt64) ENGINE = MergeTree ORDER BY (`id`)",
                "INSERT INTO `employees`.`" + K + "` (`id`, `new_id`) VALUES (?, ?)",
                "INSERT INTO `employees`.`" + S + "` (`id`, `tenant`, `name`, `new_id`, `_version`, `is_deleted`) "
                        + select,
                "SELECT count() FROM (" + select + ")",
                "SELECT count() FROM `employees`.`" + S + "`",
                "SELECT count() FROM `employees`.`t` FINAL WHERE `is_deleted` = 0",
                "EXCHANGE TABLES `employees`.`t` AND `employees`.`" + S + "`",
                // DESTRUCTIVE: expected statement text recorded by a fake connection; nothing is executed against any database.
                "DROP TABLE IF EXISTS `employees`.`" + S + "`",
                "DROP TABLE IF EXISTS `employees`.`" + K + "`");
        assertEquals(expected, actions(ch));
    }

    @Test
    @DisplayName("A count mismatch is loud and no EXCHANGE, RENAME or DROP is issued")
    public void countMismatchAbortsBeforeSwap() {
        FakeDb ch = clickHouse(ENGINE_FULL, SHOW_CREATE, 5, 4, false, false);

        DDLReplicationException loud = assertThrows(DDLReplicationException.class,
                () -> PrimaryKeyRebuild.execute(existingKeyPlan(), ch.connection(), new Properties(), config(), "hr",
                        noSource(), EPOCH));

        assertTrue(loud.getMessage().contains("5") && loud.getMessage().contains("4"), loud.getMessage());
        assertTrue(loud.getMessage().contains(S), loud.getMessage());
        for (String sql : ch.executed) {
            assertFalse(sql.startsWith("EXCHANGE") || sql.startsWith("RENAME") || sql.startsWith("DROP"),
                    "must not swap or drop after a mismatch: " + sql);
        }
        List<String> actions = actions(ch);
        assertEquals("SELECT count() FROM `employees`.`" + S + "`", actions.get(actions.size() - 1));
    }

    @Test
    @DisplayName("disable.drop.truncate=true: the swap happens, the retired copy is kept")
    public void retiredCopyKeptWhenDropTruncateDisabled() {
        FakeDb ch = clickHouse(ENGINE_FULL, SHOW_CREATE, 5, 5, false, false);
        Properties props = new Properties();
        props.setProperty(SinkConnectorLightWeightConfig.DISABLE_DROP_TRUNCATE, "true");

        PrimaryKeyRebuild.execute(existingKeyPlan(), ch.connection(), props, config(), "hr", noSource(), EPOCH);

        List<String> actions = actions(ch);
        assertEquals("EXCHANGE TABLES `employees`.`t` AND `employees`.`" + S + "`", actions.get(actions.size() - 1));
        for (String sql : ch.executed) {
            assertFalse(sql.startsWith("DROP"), "nothing may be dropped: " + sql);
        }

        // A non-Atomic database swaps by RENAME instead.
        FakeDb ordinary = clickHouse(ENGINE_FULL, SHOW_CREATE, 5, 5, false, false);
        ordinary.answers.add(0, new java.util.AbstractMap.SimpleEntry<>(
                sql -> sql.startsWith("SELECT engine FROM system.databases"),
                Collections.singletonList(row("Ordinary"))));
        PrimaryKeyRebuild.execute(existingKeyPlan(), ordinary.connection(), props, config(), "hr", noSource(), EPOCH);
        List<String> ordinaryActions = actions(ordinary);
        assertEquals("RENAME TABLE `employees`.`t` TO `employees`.`t__pk_retired_" + EPOCH + "`, `employees`.`" + S
                + "` TO `employees`.`t`", ordinaryActions.get(ordinaryActions.size() - 1));
    }

    // ------------------------------------------------------------------
    // Deferred clauses on old-key columns (Spec 06.09 §3.1.1, §3.3 step 3b)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A deferred DROP is applied to the empty rebuilt table before the copy, which excludes the column")
    public void deferredClausesApplyToRebuiltTableBeforeCopy() {
        // The GIPK promotion: my_row_id was the key, id becomes it, my_row_id is
        // dropped -- on the rebuilt table, never on the existing one (Code: 524).
        String engineFull = "ReplacingMergeTree(_version, is_deleted) ORDER BY my_row_id SETTINGS index_granularity = 8192";
        String showCreate = "CREATE TABLE employees.t\n"
                + "(\n"
                + "    `my_row_id` UInt64,\n"
                + "    `id` Int32,\n"
                + "    `v` String,\n"
                + "    `_version` UInt64,\n"
                + "    `is_deleted` UInt8\n"
                + ")\n"
                + "ENGINE = ReplacingMergeTree(_version, is_deleted)\n"
                + "ORDER BY my_row_id\n"
                + "SETTINGS index_granularity = 8192";
        FakeDb ch = new FakeDb()
                .answer("SELECT engine_full FROM system.tables", row(engineFull))
                .answer("AND table = 't' ORDER BY position", row("my_row_id", "UInt64", ""), row("id", "Int32", ""),
                        row("v", "String", ""), row("_version", "UInt64", ""), row("is_deleted", "UInt8", ""))
                // The rebuilt table's columns AFTER step 3b: my_row_id is gone.
                .answer("AND table = '" + S + "' ORDER BY position", row("id", "Int32", ""), row("v", "String", ""),
                        row("_version", "UInt64", ""), row("is_deleted", "UInt8", ""))
                .answer("SHOW CREATE TABLE", row(showCreate))
                .answer("SELECT count() FROM (", row(3L))
                .answer("SELECT count() FROM `employees`.`" + S + "`", row(3L))
                .answer("SELECT engine FROM system.databases", row("Atomic"));
        Map<String, Provenance> provenance = new LinkedHashMap<>();
        provenance.put("id", Provenance.EXISTING);
        PrimaryKeyRebuildPlan plan = new PrimaryKeyRebuildPlan("employees", "t",
                Collections.singletonList("my_row_id"), Collections.singletonList("id"), false, provenance,
                // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                "ALTER TABLE t DROP PRIMARY KEY, DROP COLUMN my_row_id, ADD PRIMARY KEY (id)",
                Collections.singletonList("DROP COLUMN IF EXISTS my_row_id"), Collections.emptyMap(),
                Collections.emptyMap());

        PrimaryKeyRebuild.execute(plan, ch.connection(), new Properties(), config(), "hr", noSource(), EPOCH);

        String select = "SELECT `id`, `v`, `_version`, `is_deleted` FROM `employees`.`t` FINAL WHERE `is_deleted` = 0";
        List<String> expected = Arrays.asList(
                "CREATE TABLE `employees`.`" + S + "`\n"
                        + "(\n"
                        + "    `my_row_id` UInt64,\n"
                        + "    `id` Int32,\n"
                        + "    `v` String,\n"
                        + "    `_version` UInt64,\n"
                        + "    `is_deleted` UInt8\n"
                        + ")\n"
                        + "ENGINE = ReplacingMergeTree(_version, is_deleted)\n"
                        + "ORDER BY (`id`)\n"
                        + "SETTINGS index_granularity = 8192",
                // DESTRUCTIVE: expected statement text recorded by a fake connection; nothing is executed against any database.
                "ALTER TABLE `employees`.`" + S + "` DROP COLUMN IF EXISTS my_row_id",
                "INSERT INTO `employees`.`" + S + "` (`id`, `v`, `_version`, `is_deleted`) " + select,
                "SELECT count() FROM (" + select + ")",
                "SELECT count() FROM `employees`.`" + S + "`",
                "EXCHANGE TABLES `employees`.`t` AND `employees`.`" + S + "`",
                // DESTRUCTIVE: expected statement text recorded by a fake connection; nothing is executed against any database.
                "DROP TABLE IF EXISTS `employees`.`" + S + "`");
        assertEquals(expected, actions(ch));
        // The deferred DROP never targets the existing table.
        for (String sql : ch.executed) {
            assertFalse(sql.startsWith("ALTER TABLE `employees`.`t`"), "must not ALTER the existing table: " + sql);
        }
    }

    @Test
    @DisplayName("A renamed key column is declared under its new name on the rebuilt table and copied as o.old AS new")
    public void renamedKeyColumnIsCopiedUnderNewName() {
        // CHANGE id ref_id INT: ClickHouse rejects RENAME COLUMN of a key column
        // even on an empty table (Code: 524, measured on 24.8.14), so the
        // rebuilt table is created keyed by `ref_id` directly and the copy
        // reads o.`id` AS `ref_id`; no ALTER RENAME is issued anywhere.
        FakeDb ch = new FakeDb()
                .answer("SELECT engine_full FROM system.tables", row(ENGINE_FULL))
                .answer("AND table = 't' ORDER BY position", tColumns(false, false))
                .answer("AND table = '" + S + "' ORDER BY position", row("ref_id", "Int32", ""),
                        row("tenant", "Int32", ""), row("name", "Nullable(String)", ""), row("_version", "UInt64", ""),
                        row("is_deleted", "UInt8", ""))
                .answer("SHOW CREATE TABLE", row(SHOW_CREATE))
                .answer("SELECT count() FROM (", row(5L))
                .answer("SELECT count() FROM `employees`.`" + S + "`", row(5L))
                .answer("SELECT engine FROM system.databases", row("Atomic"));
        Map<String, Provenance> provenance = new LinkedHashMap<>();
        provenance.put("ref_id", Provenance.EXISTING);
        PrimaryKeyRebuildPlan plan = new PrimaryKeyRebuildPlan("employees", "t", Collections.singletonList("id"),
                Collections.singletonList("ref_id"), false, provenance,
                "ALTER TABLE t CHANGE COLUMN id ref_id INT NOT NULL",
                Collections.singletonList("RENAME COLUMN id TO ref_id"), Collections.singletonMap("id", "ref_id"),
                Collections.emptyMap());

        PrimaryKeyRebuild.execute(plan, ch.connection(), new Properties(), config(), "hr", noSource(), EPOCH);

        String select = "SELECT o.`id` AS `ref_id`, o.`tenant`, o.`name`, o.`_version`, o.`is_deleted` "
                + "FROM `employees`.`t` AS o FINAL WHERE o.`is_deleted` = 0";
        List<String> expected = Arrays.asList(
                "CREATE TABLE `employees`.`" + S + "`\n"
                        + "(\n"
                        + "    `ref_id` Int32,\n"
                        + "    `tenant` Int32,\n"
                        + "    `name` Nullable(String),\n"
                        + "    `_version` UInt64,\n"
                        + "    `is_deleted` UInt8\n"
                        + ")\n"
                        + "ENGINE = ReplacingMergeTree(_version, is_deleted)\n"
                        + "ORDER BY (`ref_id`)\n"
                        + "SETTINGS index_granularity = 8192",
                "INSERT INTO `employees`.`" + S + "` (`ref_id`, `tenant`, `name`, `_version`, `is_deleted`) " + select,
                "SELECT count() FROM (" + select + ")",
                "SELECT count() FROM `employees`.`" + S + "`",
                "EXCHANGE TABLES `employees`.`t` AND `employees`.`" + S + "`",
                // DESTRUCTIVE: expected statement text recorded by a fake connection; nothing is executed against any database.
                "DROP TABLE IF EXISTS `employees`.`" + S + "`");
        assertEquals(expected, actions(ch));
        for (String sql : ch.executed) {
            assertFalse(sql.contains("RENAME COLUMN"), "a key column is never renamed by ALTER: " + sql);
        }

        // A rename that also widens, and a widening alone: the rebuilt table
        // declares the translated type; the INSERT ... SELECT converts.
        Map<String, String> types = new LinkedHashMap<>();
        types.put("id", "Int32");
        String widenedAndRenamed = PrimaryKeyRebuild.rewriteCreateStatement(SHOW_CREATE, "employees", "t", S,
                Collections.singletonList("id"), false, types, Collections.singletonMap("id", "ref_id"),
                Collections.singletonMap("ref_id", "Int64"));
        assertTrue(widenedAndRenamed.contains("    `ref_id` Int64,\n"), widenedAndRenamed);
        assertTrue(widenedAndRenamed.endsWith("ORDER BY (`ref_id`)\nSETTINGS index_granularity = 8192"),
                widenedAndRenamed);
        assertFalse(widenedAndRenamed.contains("`id`"), widenedAndRenamed);
        String widened = PrimaryKeyRebuild.rewriteCreateStatement(SHOW_CREATE, "employees", "t", S,
                Collections.singletonList("id"), false, types, Collections.emptyMap(),
                Collections.singletonMap("id", "Int64"));
        assertTrue(widened.contains("    `id` Int64,\n"), widened);
        assertTrue(widened.endsWith("ORDER BY (`id`)\nSETTINGS index_granularity = 8192"), widened);
        // A declaration with arguments, DEFAULT and CODEC keeps everything after the type.
        String decorated = SHOW_CREATE.replace("    `id` Int32,\n", "    `id` Decimal(10, 2) DEFAULT 0 CODEC(ZSTD(1)),\n");
        String retyped = PrimaryKeyRebuild.rewriteCreateStatement(decorated, "employees", "t", S,
                Collections.singletonList("id"), false, Collections.singletonMap("id", "Decimal(10, 2)"),
                Collections.emptyMap(), Collections.singletonMap("id", "Decimal(12, 2)"));
        assertTrue(retyped.contains("    `id` Decimal(12, 2) DEFAULT 0 CODEC(ZSTD(1)),\n"), retyped);
    }

    // ------------------------------------------------------------------
    // Preconditions (Spec 06.09 §3.2)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A Replicated engine with a literal ZooKeeper path is refused before any statement runs")
    public void replicatedLiteralPathIsLoud() {
        String literal = "ReplicatedReplacingMergeTree('/clickhouse/tables/01/employees/t', '{replica}', _version, "
                + "is_deleted) ORDER BY id SETTINGS index_granularity = 8192";
        assertTrue(PrimaryKeyRebuild.isReplicatedWithLiteralPath(literal));
        assertFalse(PrimaryKeyRebuild.isReplicatedWithLiteralPath(
                "ReplicatedReplacingMergeTree('/clickhouse/tables/{uuid}/{shard}', '{replica}', _version, is_deleted) ORDER BY id"));
        assertFalse(PrimaryKeyRebuild.isReplicatedWithLiteralPath(
                "ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/{database}/{table}', '{replica}', _version, is_deleted) ORDER BY id"));
        assertFalse(PrimaryKeyRebuild.isReplicatedWithLiteralPath(ENGINE_FULL));

        FakeDb ch = clickHouse(literal, SHOW_CREATE, 5, 5, false, false);
        DDLReplicationException loud = assertThrows(DDLReplicationException.class,
                () -> PrimaryKeyRebuild.execute(existingKeyPlan(), ch.connection(), new Properties(), config(), "hr",
                        noSource(), EPOCH));
        assertTrue(loud.getMessage().contains("{table}") && loud.getMessage().contains("{uuid}"), loud.getMessage());
        assertTrue(loud.getMessage().toLowerCase().contains("manual rebuild"), loud.getMessage());
        assertTrue(actions(ch).isEmpty(), "nothing may run after a refusal: " + ch.executed);

        // The legacy sign-based engine and a non-Replacing engine are refused too.
        FakeDb collapsing = clickHouse("CollapsingMergeTree(_sign) ORDER BY id", SHOW_CREATE, 5, 5, false, false);
        assertThrows(DDLReplicationException.class,
                () -> PrimaryKeyRebuild.execute(existingKeyPlan(), collapsing.connection(), new Properties(), config(),
                        "hr", noSource(), EPOCH));
        assertTrue(actions(collapsing).isEmpty());
        DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine = false;
        try {
            FakeDb legacy = clickHouse(ENGINE_FULL, SHOW_CREATE, 5, 5, false, false);
            assertThrows(DDLReplicationException.class,
                    () -> PrimaryKeyRebuild.execute(existingKeyPlan(), legacy.connection(), new Properties(), config(),
                            "hr", noSource(), EPOCH));
            assertTrue(legacy.executed.isEmpty(), "refused before any query: " + legacy.executed);
        } finally {
            DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine = true;
        }
    }

    @Test
    @DisplayName("A Nullable old-key column with a source key map is refused before any statement runs")
    public void nullableOldKeyWithSourceMapIsLoud() {
        FakeDb ch = clickHouse(ENGINE_FULL, SHOW_CREATE, 3, 3, true, true);

        DDLReplicationException loud = assertThrows(DDLReplicationException.class,
                () -> PrimaryKeyRebuild.execute(sourceValuedPlan(), ch.connection(), new Properties(), config(), "hr",
                        noSource(), EPOCH));

        assertTrue(loud.getMessage().contains("Nullable(Int32)"), loud.getMessage());
        assertTrue(loud.getMessage().contains("NULL never equals NULL"), loud.getMessage());
        assertTrue(actions(ch).isEmpty(), "nothing may run after a refusal: " + ch.executed);

        // Without a source key map a Nullable old key is no obstacle (no JOIN).
        FakeDb local = clickHouse(ENGINE_FULL, SHOW_CREATE.replace("`id` Int32", "`id` Nullable(Int32)"), 5, 5,
                true, false);
        PrimaryKeyRebuild.execute(existingKeyPlan(), local.connection(), new Properties(), config(), "hr", noSource(),
                EPOCH);
        assertFalse(actions(local).isEmpty());

        // A type the key map cannot bind is refused on either side.
        FakeDb decimalKey = new FakeDb()
                .answer("SELECT engine_full FROM system.tables", row(ENGINE_FULL))
                .answer("ORDER BY position", row("id", "Decimal(10, 2)", ""), row("new_id", "UInt64", ""),
                        row("_version", "UInt64", ""), row("is_deleted", "UInt8", ""));
        DDLReplicationException decimal = assertThrows(DDLReplicationException.class,
                () -> PrimaryKeyRebuild.execute(sourceValuedPlan(), decimalKey.connection(), new Properties(),
                        config(), "hr", noSource(), EPOCH));
        assertTrue(decimal.getMessage().contains("Decimal(10, 2)"), decimal.getMessage());
        assertTrue(actions(decimalKey).isEmpty());

        FakeDb mysqlDecimal = clickHouse(ENGINE_FULL, SHOW_CREATE.replace("    `name` Nullable(String),\n",
                "    `name` Nullable(String),\n    `new_id` UInt64,\n"), 3, 3, false, true);
        FakeDb source = new FakeDb().metaTypes(Types.DECIMAL, Types.BIGINT).answer("FROM `hr`.`t`", row(1, 1L));
        DDLReplicationException sourceType = assertThrows(DDLReplicationException.class,
                () -> PrimaryKeyRebuild.execute(sourceValuedPlan(), mysqlDecimal.connection(), new Properties(),
                        config(), "hr", source::connection, EPOCH));
        assertTrue(sourceType.getMessage().contains("JDBC type " + Types.DECIMAL), sourceType.getMessage());
        assertTrue(mysqlDecimal.batched.isEmpty(), "no row may be bound after the type refusal");
        for (String sql : mysqlDecimal.executed) {
            assertFalse(sql.startsWith("INSERT INTO `employees`.`" + S + "`") || sql.startsWith("EXCHANGE")
                    || sql.startsWith("DROP"), "the copy must not start: " + sql);
        }
    }

    @Test
    @DisplayName("replication.history.enable=true is refused before any query")
    public void historyTablesAreRefused() {
        Map<String, String> history = new HashMap<>();
        history.put("replication.history.enable", "true");
        FakeDb ch = clickHouse(ENGINE_FULL, SHOW_CREATE, 5, 5, false, false);
        DDLReplicationException loud = assertThrows(DDLReplicationException.class,
                () -> PrimaryKeyRebuild.execute(existingKeyPlan(), ch.connection(), new Properties(),
                        new ClickHouseSinkConnectorConfig(history), "hr", noSource(), EPOCH));
        assertTrue(loud.getMessage().contains("replication.history.enable"), loud.getMessage());
        assertTrue(ch.executed.isEmpty(), ch.executed.toString());
    }
}

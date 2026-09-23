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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 06.09: the primary-key rebuild -- the metadata-only swap at the DDL
 * barrier ({@link PrimaryKeyRebuild#swap}) and the online backfill that
 * follows ({@link PrimaryKeyBackfill}) -- driven against scripted, recording
 * stand-ins for the ClickHouse (and, where needed, the MySQL) connection. JDK
 * proxies, because this module has no mocking framework on its test
 * classpath. Every SQL text sent is recorded in order; the metadata queries
 * are answered from a script; the backfill's scheduler and failure reporter
 * are recorders, so no test sleeps.
 */
public class PrimaryKeyRebuildTest {

    private static final long EPOCH = 1700000000000L;
    private static final String S = "t__pk_rebuild_" + EPOCH;
    private static final String K = "t__pk_rebuild_keys_" + EPOCH;
    private static final String COMMENT = "mirrored from hr";

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
        private final List<Map.Entry<String, int[]>> failures = new ArrayList<>();
        private final List<Map.Entry<String, Runnable>> hooks = new ArrayList<>();
        private int[] metaTypes = new int[0];

        /**
         * Runs {@code action} once, right after the first statement containing
         * {@code fragment} has been sent -- the test's way of doing something
         * on another connection while an attempt is between two statements.
         */
        FakeDb onStatement(String fragment, Runnable action) {
            hooks.add(new java.util.AbstractMap.SimpleEntry<>(fragment, action));
            return this;
        }

        private void runHooks(String sql) {
            for (java.util.Iterator<Map.Entry<String, Runnable>> it = hooks.iterator(); it.hasNext(); ) {
                Map.Entry<String, Runnable> hook = it.next();
                if (sql.contains(hook.getKey())) {
                    it.remove();
                    hook.getValue().run();
                }
            }
        }

        /** Answers queries containing {@code fragment} with {@code rows} (first script wins). */
        FakeDb answer(String fragment, Object[]... rows) {
            answers.add(new java.util.AbstractMap.SimpleEntry<>(sql -> sql.contains(fragment), Arrays.asList(rows)));
            return this;
        }

        /** Makes the next {@code times} statements containing {@code fragment} fail ({@code -1}: always). */
        FakeDb failOn(String fragment, int times) {
            failures.add(new java.util.AbstractMap.SimpleEntry<>(fragment, new int[] {times}));
            return this;
        }

        private void maybeFail(String sql) throws SQLException {
            for (Map.Entry<String, int[]> f : failures) {
                if (sql.contains(f.getKey()) && f.getValue()[0] != 0) {
                    if (f.getValue()[0] > 0) {
                        f.getValue()[0]--;
                    }
                    throw new SQLException("injected failure for [" + sql + "]");
                }
            }
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
                        maybeFail((String) args[0]);
                        runHooks((String) args[0]);
                        return false;
                    case "executeQuery":
                        executed.add((String) args[0]);
                        maybeFail((String) args[0]);
                        runHooks((String) args[0]);
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

    /** A recording scheduler: nothing runs until the test calls {@link #runNext()}. */
    static final class FakeScheduler implements PrimaryKeyBackfill.Scheduler {
        final List<Long> delays = new ArrayList<>();
        final List<Runnable> queued = new ArrayList<>();

        @Override
        public void schedule(Runnable action, long delayMs) {
            delays.add(delayMs);
            queued.add(action);
        }

        /** Runs the oldest scheduled attempt on the calling thread. */
        void runNext() {
            assertFalse(queued.isEmpty(), "no attempt is scheduled");
            queued.remove(0).run();
        }
    }

    /** A recording failure reporter (the error-table hook of the connector). */
    static final class FakeReporter implements PrimaryKeyBackfill.FailureReporter {
        final List<String> steps = new ArrayList<>();
        final List<String> statements = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        final List<Connection> connections = new ArrayList<>();

        @Override
        public void failed(Connection ch, PrimaryKeyBackfill.Task task, String step, String statement,
                           Exception cause) {
            connections.add(ch);
            steps.add(step);
            statements.add(statement);
            messages.add(cause.getMessage());
        }
    }

    /** A backfill runner wired to the fakes; attempts run only through {@link FakeScheduler#runNext()}. */
    private static final class Harness {
        final FakeScheduler scheduler = new FakeScheduler();
        final FakeReporter reporter = new FakeReporter();
        final PrimaryKeyBackfill backfill;

        Harness(FakeDb ch, FakeDb source, Properties props) {
            Supplier<Connection> sourceSupplier = source == null ? noSource() : source::connection;
            backfill = new PrimaryKeyBackfill(ch::connection, sourceSupplier, props, reporter, scheduler);
        }

        /** Submits the task and runs its first attempt synchronously. */
        void runOnce(PrimaryKeyBackfill.Task task) {
            assertTrue(backfill.submit(task), "the task must be accepted");
            assertEquals(Collections.singletonList(0L), scheduler.delays, "the first attempt runs without delay");
            scheduler.runNext();
        }
    }

    /** The protocol's own statements: everything but the metadata reads and the backfill marker. */
    private static List<String> actions(FakeDb db) {
        List<String> out = new ArrayList<>();
        for (String sql : db.executed) {
            if (sql.contains(" MODIFY COMMENT ")) {
                continue;
            }
            // DESTRUCTIVE: statement prefixes matched against text a fake connection recorded; nothing is executed against any database.
            if (sql.startsWith("CREATE") || sql.startsWith("ALTER") || sql.startsWith("INSERT")
                    || sql.startsWith("SELECT count()") || sql.startsWith("EXCHANGE") || sql.startsWith("RENAME")
                    || sql.startsWith("DROP") || sql.startsWith("TRUNCATE")) {
                out.add(sql);
            }
        }
        return out;
    }

    /** Sends {@code sql} on a fresh statement of {@code db}'s connection (the DDL path's own statement). */
    private static void execute(FakeDb db, String sql) {
        try (Statement st = db.connection().createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
    }

    /** The {@code MODIFY COMMENT} statements issued, in order. */
    private static List<String> commentStatements(FakeDb db) {
        List<String> out = new ArrayList<>();
        for (String sql : db.executed) {
            if (sql.contains(" MODIFY COMMENT ")) {
                out.add(sql);
            }
        }
        return out;
    }

    private static void assertNothingDropped(FakeDb db) {
        for (String sql : db.executed) {
            assertFalse(sql.startsWith("DROP"), "nothing may be dropped: " + sql);
        }
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

    /** The ClickHouse the SWAP sees: employees.t keyed by id, no leftovers, Atomic database. */
    private static FakeDb clickHouse(String engineFull, String showCreate, boolean nullableId, boolean withNewId) {
        return new FakeDb()
                .answer("SELECT engine_full, comment FROM system.tables", row(engineFull, COMMENT))
                .answer("AND table = 't' ORDER BY position", tColumns(nullableId, withNewId))
                .answer("SHOW CREATE TABLE", row(showCreate))
                .answer("SELECT engine FROM system.databases", row("Atomic"));
    }

    /**
     * The ClickHouse the BACKFILL sees, after the swap: {@code t} is the rebuilt
     * table with {@code rebuiltColumns}, {@code retired} holds the pre-DDL rows
     * with {@code retiredColumns} and the given active partitions; the
     * completeness check reports {@code missingKeys}.
     */
    private static FakeDb afterSwap(Object[][] rebuiltColumns, Object[][] retiredColumns, String retired,
                                    long missingKeys, String... partitions) {
        List<Object[]> parts = new ArrayList<>();
        for (String p : partitions) {
            parts.add(row(p));
        }
        return new FakeDb()
                .answer("AND table = 't' ORDER BY position", rebuiltColumns)
                .answer("AND table = '" + retired + "' ORDER BY position", retiredColumns)
                .answer("SELECT DISTINCT partition_id FROM system.parts", parts.toArray(new Object[0][]))
                // The completeness check reads R as "AS r FINAL" inside its subquery; the copy's row count does not.
                .answer("FROM `employees`.`" + retired + "` AS r FINAL", row(missingKeys))
                .answer("SELECT count() FROM (", row(5L))
                .answer("SELECT comment FROM system.tables", row(COMMENT + "\n" + PrimaryKeyBackfill.MARKER_PREFIX
                        + "{\"v\":1}"));
    }

    /** The task the swap of {@code plan} produces on the given (old) ClickHouse. */
    private static PrimaryKeyBackfill.Task swapped(PrimaryKeyRebuildPlan plan, FakeDb ch) {
        return PrimaryKeyRebuild.swap(plan, ch.connection(), new Properties(), config(), "hr", EPOCH);
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

    @Test
    @DisplayName("A UUID '...' token after the table name (Atomic databases) is stripped; a header without one is unchanged")
    public void rewriteStripsTableUuid() {
        String body = "\n"
                + "(\n"
                + "    `id` Int32,\n"
                + "    `tenant` Int32,\n"
                + "    `_version` UInt64,\n"
                + "    `is_deleted` UInt8\n"
                + ")\n"
                + "ENGINE = ReplacingMergeTree(_version, is_deleted)\n"
                + "ORDER BY id\n"
                + "SETTINGS index_granularity = 8192";
        String expected = "CREATE TABLE `employees`.`t__pk_rebuild_5`" + body.replace("ORDER BY id", "ORDER BY (`tenant`)");
        Map<String, String> types = new LinkedHashMap<>();
        types.put("id", "Int32");
        types.put("tenant", "Int32");

        String bare = "CREATE TABLE employees.t UUID 'a1b2c3d4-e5f6-7890-abcd-ef1234567890'" + body;
        String rewritten = PrimaryKeyRebuild.rewriteCreateStatement(bare, "employees", "t", "t__pk_rebuild_5",
                Collections.singletonList("tenant"), false, types);
        assertEquals(expected, rewritten);
        assertFalse(rewritten.toUpperCase().contains("UUID"), rewritten);

        // Backticked header, lower-case keyword: same result.
        String quoted = "CREATE TABLE `employees`.`t` uuid 'A1B2C3D4-E5F6-7890-ABCD-EF1234567890'" + body;
        assertEquals(expected, PrimaryKeyRebuild.rewriteCreateStatement(quoted, "employees", "t", "t__pk_rebuild_5",
                Collections.singletonList("tenant"), false, types));

        // No UUID token: the header is rewritten as before.
        assertEquals(expected, PrimaryKeyRebuild.rewriteCreateStatement("CREATE TABLE employees.t" + body,
                "employees", "t", "t__pk_rebuild_5", Collections.singletonList("tenant"), false, types));
    }

    @Test
    @DisplayName("A deferred rename of a key column is applied in PARTITION BY, SAMPLE BY and TTL, not inside other identifiers or string literals")
    public void rewriteRenamesKeyColumnInPartitionAndTtl() {
        String create = "CREATE TABLE employees.t\n"
                + "(\n"
                + "    `id` Int32,\n"
                + "    `id_extra` Int32,\n"
                + "    `tag` String DEFAULT 'id',\n"
                + "    `_version` UInt64,\n"
                + "    `is_deleted` UInt8\n"
                + ")\n"
                + "ENGINE = ReplacingMergeTree(_version, is_deleted)\n"
                + "PARTITION BY intDiv(id, 1000)\n"
                + "PRIMARY KEY id\n"
                + "ORDER BY id\n"
                + "SAMPLE BY id\n"
                + "TTL toDateTime(id) + toIntervalDay(30) WHERE tag != 'id' AND id_extra != 0\n"
                + "SETTINGS index_granularity = 8192\n"
                + "COMMENT 'keyed by id'";
        String expected = "CREATE TABLE `employees`.`t__pk_rebuild_6`\n"
                + "(\n"
                + "    `ref_id` Int32,\n"
                + "    `id_extra` Int32,\n"
                + "    `tag` String DEFAULT 'id',\n"
                + "    `_version` UInt64,\n"
                + "    `is_deleted` UInt8\n"
                + ")\n"
                + "ENGINE = ReplacingMergeTree(_version, is_deleted)\n"
                + "PARTITION BY intDiv(`ref_id`, 1000)\n"
                + "ORDER BY (`ref_id`)\n"
                + "SAMPLE BY `ref_id`\n"
                + "TTL toDateTime(`ref_id`) + toIntervalDay(30) WHERE tag != 'id' AND id_extra != 0\n"
                + "SETTINGS index_granularity = 8192\n"
                + "COMMENT 'keyed by id'";
        Map<String, String> types = new LinkedHashMap<>();
        types.put("id", "Int32");
        types.put("id_extra", "Int32");
        Map<String, String> renames = Collections.singletonMap("id", "ref_id");
        assertEquals(expected, PrimaryKeyRebuild.rewriteCreateStatement(create, "employees", "t", "t__pk_rebuild_6",
                Collections.singletonList("id"), false, types, renames, Collections.emptyMap()));

        // Backticked references are renamed the same way.
        String quoted = create.replace("intDiv(id, 1000)", "intDiv(`id`, 1000)").replace("toDateTime(id)", "toDateTime(`id`)")
                .replace("SAMPLE BY id", "SAMPLE BY `id`");
        assertEquals(expected, PrimaryKeyRebuild.rewriteCreateStatement(quoted, "employees", "t", "t__pk_rebuild_6",
                Collections.singletonList("id"), false, types, renames, Collections.emptyMap()));
    }

    // ------------------------------------------------------------------
    // The swap phase (Spec 06.09 §3.3.1) against the recording connection
    // ------------------------------------------------------------------

    private static final String CREATE_S_TENANT = "CREATE TABLE `employees`.`" + S + "`\n"
            + "(\n"
            + "    `id` Int32,\n"
            + "    `tenant` Int32,\n"
            + "    `name` Nullable(String),\n"
            + "    `_version` UInt64,\n"
            + "    `is_deleted` UInt8\n"
            + ")\n"
            + "ENGINE = ReplacingMergeTree(_version, is_deleted)\n"
            + "ORDER BY (`tenant`)\n"
            + "SETTINGS index_granularity = 8192";

    @Test
    @DisplayName("The swap phase: CREATE S, the pending-backfill marker, EXCHANGE; no INSERT, no source, the task returned")
    public void swapPhaseIsMetadataOnly() {
        FakeDb ch = clickHouse(ENGINE_FULL, SHOW_CREATE, false, false);

        PrimaryKeyBackfill.Task task = swapped(existingKeyPlan(), ch);

        assertEquals(Arrays.asList(CREATE_S_TENANT, "EXCHANGE TABLES `employees`.`t` AND `employees`.`" + S + "`"),
                actions(ch));
        for (String sql : ch.executed) {
            assertFalse(sql.startsWith("INSERT") || sql.startsWith("DROP") || sql.contains("FROM `hr`"),
                    "the swap is metadata-only: " + sql);
        }
        assertTrue(ch.batched.isEmpty(), "no key map is filled by the swap");
        // The pending backfill is recorded on the table that becomes R, before
        // the exchange, so a restart can resume it from the table alone.
        List<String> comments = commentStatements(ch);
        assertEquals(1, comments.size(), comments.toString());
        String prefix = "ALTER TABLE `employees`.`t` MODIFY COMMENT '" + COMMENT + "\n" + PrimaryKeyBackfill.MARKER_PREFIX;
        assertTrue(comments.get(0).startsWith(prefix), comments.get(0));
        int at = ch.executed.indexOf(comments.get(0));
        assertTrue(at < ch.executed.indexOf("EXCHANGE TABLES `employees`.`t` AND `employees`.`" + S + "`"),
                "the marker is written before the exchange");
        PrimaryKeyBackfill.Marker marker = PrimaryKeyBackfill.Marker.parse(
                comments.get(0).substring(prefix.length() - PrimaryKeyBackfill.MARKER_PREFIX.length(),
                        comments.get(0).length() - 1));
        assertEquals("t", marker.table);
        assertEquals(Collections.singletonList("id"), marker.oldKey);
        assertEquals(Collections.singletonList("tenant"), marker.newKey);
        assertEquals("hr", marker.sourceDatabase);
        assertEquals(K, marker.keyMap);

        // The returned task describes the backfill instead of running it.
        assertEquals("employees", task.database());
        assertEquals("t", task.table());
        assertEquals(S, task.retired());
        assertEquals(K, task.keyMapTable());
        assertEquals("hr", task.sourceDatabase());
        assertEquals("is_deleted", task.deleteFlag());
        assertEquals(Collections.singletonList("id"), task.oldKey());
        assertEquals(Collections.singletonList("tenant"), task.newKey());
        assertFalse(task.needKeyMap());
        assertEquals(0, task.failures());

        // Step 1: a leftover of an attempt whose swap never completed (no
        // marker, T still keyed by the old identity) is dropped; a retired
        // table whose backfill is pending (marker) and its key map are not.
        String pendingRetired = "t__pk_rebuild_1600000000000";
        String pendingKeys = "t__pk_rebuild_keys_1600000000000";
        String stale = "t__pk_rebuild_1500000000000";
        FakeDb withLeftovers = clickHouse(ENGINE_FULL, SHOW_CREATE, false, false)
                .answer("AND (name LIKE 't", row(stale, ""), row(pendingRetired, "x\n"
                        + PrimaryKeyBackfill.MARKER_PREFIX + task.markerJson()), row(pendingKeys, ""))
                .answer("AND table = 't' AND is_in_sorting_key = 1", row("id"));
        swapped(existingKeyPlan(), withLeftovers);
        List<String> drops = new ArrayList<>();
        for (String sql : withLeftovers.executed) {
            if (sql.startsWith("DROP")) {
                drops.add(sql);
            }
        }
        // DESTRUCTIVE: expected statement text recorded by a fake connection; nothing is executed against any database.
        assertEquals(Collections.singletonList("DROP TABLE IF EXISTS `employees`.`" + stale + "`"), drops);

        // The live predicate follows the engine's delete flag; version-only engines have none.
        assertEquals("`is_deleted` = 0", PrimaryKeyRebuild.liveRowPredicate(ENGINE_FULL));
        assertEquals("`_is_deleted` = 0", PrimaryKeyRebuild.liveRowPredicate(
                "ReplicatedReplacingMergeTree('/clickhouse/tables/{uuid}/{shard}', '{replica}', _version, _is_deleted) ORDER BY id"));
        assertNull(PrimaryKeyRebuild.liveRowPredicate("ReplacingMergeTree(_version) ORDER BY id"));
    }

    // ------------------------------------------------------------------
    // The backfill phase (Spec 06.09 §3.3.2) against the recording connection
    // ------------------------------------------------------------------

    private static String tenantSelect(String partition) {
        return "SELECT `id`, `tenant`, `name`, `_version`, `is_deleted` FROM `employees`.`" + S + "` FINAL "
                + "WHERE `is_deleted` = 0" + (partition == null ? "" : " AND _partition_id = '" + partition + "'");
    }

    /**
     * The completeness check: FINAL confined to the retired table's subquery,
     * the rebuilt table read as its distinct keys (live or tombstoned) -- on
     * 24.8.14 a plain {@code R FINAL LEFT JOIN T} read T FINAL-like and
     * reported every key T held only as a tombstone as missing.
     */
    private static final String TENANT_CHECK = "SELECT count() FROM (SELECT r.`tenant` AS `tenant` FROM `employees`.`" + S
            + "` AS r FINAL WHERE r.`is_deleted` = 0) AS r LEFT JOIN (SELECT DISTINCT `tenant` FROM `employees`.`t`) AS t "
            + "ON t.`tenant` = r.`tenant` WHERE t.`tenant` IS NULL SETTINGS join_use_nulls = 1";

    @Test
    @DisplayName("Local backfill: INSERT...SELECT FROM R FINAL WHERE is_deleted = 0 per partition, completeness check, drop of R; no source read")
    public void localCopyStatementSequence() {
        PrimaryKeyBackfill.Task task = swapped(existingKeyPlan(), clickHouse(ENGINE_FULL, SHOW_CREATE, false, false));
        FakeDb ch = afterSwap(tColumns(false, false), tColumns(false, false), S, 0, "202401", "202402");
        Harness h = new Harness(ch, null, new Properties());

        h.runOnce(task);

        String insertPrefix = "INSERT INTO `employees`.`t` (`id`, `tenant`, `name`, `_version`, `is_deleted`) ";
        List<String> expected = Arrays.asList(
                insertPrefix + tenantSelect("202401"),
                "SELECT count() FROM (" + tenantSelect("202401") + ")",
                insertPrefix + tenantSelect("202402"),
                "SELECT count() FROM (" + tenantSelect("202402") + ")",
                TENANT_CHECK,
                // DESTRUCTIVE: expected statement text recorded by a fake connection; nothing is executed against any database.
                "DROP TABLE IF EXISTS `employees`.`" + S + "`");
        assertEquals(expected, actions(ch));
        assertTrue(ch.batched.isEmpty(), "no key map is filled without a SOURCE_VALUED column");
        assertTrue(h.reporter.steps.isEmpty(), "nothing failed: " + h.reporter.steps);
        assertEquals(Collections.singletonList(0L), h.scheduler.delays, "no retry was scheduled");
        assertEquals(0, h.backfill.pending());

        // An unpartitioned retired table ('all', or no active part): one statement.
        for (String[] parts : new String[][] {{"all"}, {}}) {
            PrimaryKeyBackfill.Task again = swapped(existingKeyPlan(), clickHouse(ENGINE_FULL, SHOW_CREATE, false, false));
            FakeDb one = afterSwap(tColumns(false, false), tColumns(false, false), S, 0, parts);
            new Harness(one, null, new Properties()).runOnce(again);
            assertEquals(Arrays.asList(
                    insertPrefix + tenantSelect(null),
                    "SELECT count() FROM (" + tenantSelect(null) + ")",
                    TENANT_CHECK,
                    // DESTRUCTIVE: expected statement text recorded by a fake connection; nothing is executed against any database.
                    "DROP TABLE IF EXISTS `employees`.`" + S + "`"), actions(one));
        }
    }

    @Test
    @DisplayName("A SOURCE_VALUED column: key-map table, read-only source SELECT, JOIN copy from the map, K dropped after the check")
    public void sourceKeyMapJoinSequence() {
        String showCreate = SHOW_CREATE.replace("    `name` Nullable(String),\n",
                "    `name` Nullable(String),\n    `new_id` UInt64,\n");
        FakeDb swapDb = clickHouse(ENGINE_FULL, showCreate, false, true);
        PrimaryKeyBackfill.Task task = swapped(sourceValuedPlan(), swapDb);
        assertTrue(task.needKeyMap());
        assertEquals(Collections.singletonList("new_id"), task.sourceValued());
        // The swap neither creates nor fills K (its name appears only in the marker).
        assertFalse(actions(swapDb).stream().anyMatch(sql -> sql.contains(K)), "the swap does not touch K");
        assertTrue(swapDb.batched.isEmpty());

        // After the swap BOTH tables carry new_id (the ADD COLUMN ran on T
        // before the exchange); the values must come from the source, never from R.
        FakeDb ch = afterSwap(tColumns(false, true), tColumns(false, true), S, 0, "all");
        FakeDb source = new FakeDb()
                .metaTypes(Types.INTEGER, Types.BIGINT)
                .answer("FROM `hr`.`t`", row(1, 101L), row(2, 102L), row(3, 103L));
        Harness h = new Harness(ch, source, new Properties());

        h.runOnce(task);

        // The source: exactly one statement, a SELECT keyed by the old identity.
        assertEquals(Collections.singletonList("SELECT `id`, `new_id` FROM `hr`.`t`"), source.executed);
        // The key map holds every source row, bound in order.
        assertEquals(Arrays.asList(Arrays.asList(1, 101L), Arrays.asList(2, 102L), Arrays.asList(3, 103L)),
                ch.batched);
        assertEquals(1, ch.executeBatchCalls.get());

        String select = "SELECT o.`id`, o.`tenant`, o.`name`, k.`new_id`, o.`_version`, o.`is_deleted` "
                + "FROM `employees`.`" + S + "` AS o FINAL INNER JOIN `employees`.`" + K + "` AS k ON o.`id` = k.`id` "
                + "WHERE o.`is_deleted` = 0";
        List<String> expected = Arrays.asList(
                "CREATE TABLE `employees`.`" + K + "` (`id` Int32, `new_id` UInt64) ENGINE = MergeTree ORDER BY (`id`)",
                "INSERT INTO `employees`.`" + K + "` (`id`, `new_id`) VALUES (?, ?)",
                "INSERT INTO `employees`.`t` (`id`, `tenant`, `name`, `new_id`, `_version`, `is_deleted`) " + select,
                "SELECT count() FROM (" + select + ")",
                "SELECT count() FROM (SELECT k.`new_id` AS `new_id` FROM `employees`.`" + S + "` AS r FINAL INNER JOIN "
                        + "`employees`.`" + K + "` AS k ON r.`id` = k.`id` WHERE r.`is_deleted` = 0) AS r LEFT JOIN "
                        + "(SELECT DISTINCT `new_id` FROM `employees`.`t`) AS t ON t.`new_id` = r.`new_id` "
                        + "WHERE t.`new_id` IS NULL SETTINGS join_use_nulls = 1",
                // DESTRUCTIVE: expected statement text recorded by a fake connection; nothing is executed against any database.
                "DROP TABLE IF EXISTS `employees`.`" + S + "`",
                "DROP TABLE IF EXISTS `employees`.`" + K + "`");
        assertEquals(expected, actions(ch));
        assertTrue(h.reporter.steps.isEmpty(), h.reporter.steps.toString());
    }

    @Test
    @DisplayName("A non-zero completeness count: no drop of R/K, the failure is reported, the task is re-scheduled")
    public void completenessCheckGuardsDrop() {
        PrimaryKeyBackfill.Task task = swapped(existingKeyPlan(), clickHouse(ENGINE_FULL, SHOW_CREATE, false, false));
        FakeDb ch = afterSwap(tColumns(false, false), tColumns(false, false), S, 2, "all");
        Harness h = new Harness(ch, null, new Properties());

        h.runOnce(task);

        assertNothingDropped(ch);
        assertEquals(TENANT_CHECK, actions(ch).get(actions(ch).size() - 1), "the check is the last statement");
        assertEquals(Collections.singletonList("step 3 (completeness check)"), h.reporter.steps);
        assertEquals(Collections.singletonList(TENANT_CHECK), h.reporter.statements);
        assertTrue(h.reporter.messages.get(0).contains("2 live key(s)"), h.reporter.messages.get(0));
        assertTrue(h.reporter.connections.get(0) != null, "the reporter receives the attempt's connection");
        assertEquals(Arrays.asList(0L, 10_000L), h.scheduler.delays, "re-scheduled with the first backoff");
        assertEquals(1, task.failures());
        assertEquals(1, h.backfill.pending());

        // Once the check passes, the re-scheduled attempt re-issues the copy and drops R.
        ch.answers.add(0, new java.util.AbstractMap.SimpleEntry<>(
                sql -> sql.contains("FROM `employees`.`" + S + "` AS r FINAL"),
                Collections.singletonList(row(0L))));
        h.scheduler.runNext();
        List<String> actions = actions(ch);
        // DESTRUCTIVE: expected statement text recorded by a fake connection; nothing is executed against any database.
        assertEquals("DROP TABLE IF EXISTS `employees`.`" + S + "`", actions.get(actions.size() - 1));
        assertEquals(2, actions.stream().filter(sql -> sql.startsWith("INSERT INTO `employees`.`t`")).count());
        assertEquals(0, h.backfill.pending());
        assertEquals(Arrays.asList(0L, 10_000L), h.scheduler.delays, "nothing more scheduled after success");
    }

    @Test
    @DisplayName("A failing INSERT re-schedules with 10 s, 20 s, ... capped at 5 min; the same statements are re-issued; nothing dropped meanwhile")
    public void backfillRetriesWithBackoff() {
        PrimaryKeyBackfill.Task task = swapped(existingKeyPlan(), clickHouse(ENGINE_FULL, SHOW_CREATE, false, false));
        FakeDb ch = afterSwap(tColumns(false, false), tColumns(false, false), S, 0, "all")
                .failOn("INSERT INTO `employees`.`t`", 7);
        Harness h = new Harness(ch, null, new Properties());
        String insert = "INSERT INTO `employees`.`t` (`id`, `tenant`, `name`, `_version`, `is_deleted`) "
                + tenantSelect(null);

        h.runOnce(task);
        for (int attempt = 2; attempt <= 7; attempt++) {
            assertNothingDropped(ch);
            h.scheduler.runNext();
        }

        assertEquals(Arrays.asList(0L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L),
                h.scheduler.delays);
        assertEquals(7, task.failures());
        assertEquals(7, h.reporter.steps.size());
        assertTrue(h.reporter.steps.stream().allMatch("step 2 (copy the live rows)"::equals), h.reporter.steps.toString());
        assertTrue(h.reporter.statements.stream().allMatch(insert::equals), h.reporter.statements.toString());
        assertNothingDropped(ch);
        List<String> inserts = new ArrayList<>();
        for (String sql : ch.executed) {
            if (sql.startsWith("INSERT")) {
                inserts.add(sql);
            }
        }
        assertEquals(Collections.nCopies(7, insert), inserts, "every attempt re-issues the same copy statement");
        assertEquals(1, h.backfill.pending());

        // The eighth attempt succeeds: the copy, the check, then the drop.
        h.scheduler.runNext();
        List<String> actions = actions(ch);
        // DESTRUCTIVE: expected statement text recorded by a fake connection; nothing is executed against any database.
        assertEquals("DROP TABLE IF EXISTS `employees`.`" + S + "`", actions.get(actions.size() - 1));
        assertEquals(TENANT_CHECK, actions.get(actions.size() - 2));
        assertEquals(8, h.scheduler.delays.size(), "no further attempt after success");
        assertEquals(0, h.backfill.pending());

        // Backoff arithmetic: 10 s doubling, capped at 300 s, never overflowing.
        PrimaryKeyBackfill.Task fresh = swapped(existingKeyPlan(), clickHouse(ENGINE_FULL, SHOW_CREATE, false, false));
        List<Long> delays = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            delays.add(fresh.nextBackoffMs());
        }
        assertEquals(Arrays.asList(10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L), delays.subList(0, 6));
        assertTrue(delays.subList(5, 40).stream().allMatch(d -> d == 300_000L), delays.toString());
    }

    @Test
    @DisplayName("resumePending finds a retired table with the marker whose companion is keyed by the new identity; markerless or old-keyed scratch is not resumed")
    public void restartResumesPendingBackfill() {
        PrimaryKeyBackfill.Task original = swapped(existingKeyPlan(), clickHouse(ENGINE_FULL, SHOW_CREATE, false, false));
        String marker = PrimaryKeyBackfill.MARKER_PREFIX + original.markerJson();
        FakeDb ch = new FakeDb()
                .answer("SELECT name, comment FROM system.tables WHERE database = 'employees' AND (name LIKE '%",
                        // R of a completed swap: resumed.
                        row(S, COMMENT + "\n" + marker),
                        // S of a swap that never completed: no marker, not resumed.
                        row("u__pk_rebuild_1700000000001", ""),
                        // A retired table whose companion is gone: not resumed.
                        row("v__pk_retired_1700000000002", marker.replace("\"table\":\"t\"", "\"table\":\"v\"")))
                .answer("SELECT engine_full FROM system.tables WHERE database = 'employees' AND name = 't'",
                        row(ENGINE_FULL))
                .answer("AND table = 't' AND is_in_sorting_key = 1", row("tenant"));

        List<PrimaryKeyBackfill.Task> tasks = PrimaryKeyBackfill.resumePending(ch.connection(), new Properties(),
                config(), "employees");

        assertEquals(1, tasks.size(), tasks.toString());
        PrimaryKeyBackfill.Task resumed = tasks.get(0);
        assertEquals("t", resumed.table());
        assertEquals(S, resumed.retired());
        assertEquals(K, resumed.keyMapTable());
        assertEquals(Collections.singletonList("id"), resumed.oldKey());
        assertEquals(Collections.singletonList("tenant"), resumed.newKey());
        assertEquals("hr", resumed.sourceDatabase());
        assertEquals("is_deleted", resumed.deleteFlag());
        assertFalse(resumed.needKeyMap());
        assertEquals(original.plan().sourceSql(), resumed.plan().sourceSql());
        for (String sql : ch.executed) {
            assertFalse(sql.startsWith("DROP") || sql.startsWith("INSERT"), "resume only reads: " + sql);
        }

        // Scheduled once by the engine at start; a second scan does not double it.
        FakeDb run = afterSwap(tColumns(false, false), tColumns(false, false), S, 0, "all");
        Harness h = new Harness(run, null, new Properties());
        assertEquals(1, h.backfill.submitAll(tasks));
        assertEquals(0, h.backfill.submitAll(tasks), "already pending");
        h.scheduler.runNext();
        List<String> actions = actions(run);
        // DESTRUCTIVE: expected statement text recorded by a fake connection; nothing is executed against any database.
        assertEquals("DROP TABLE IF EXISTS `employees`.`" + S + "`", actions.get(actions.size() - 1));

        // The companion still keyed by the OLD identity: the swap did not
        // happen as recorded; not resumed (and loud), nothing touched.
        FakeDb oldKeyed = new FakeDb()
                .answer("SELECT name, comment FROM system.tables WHERE database = 'employees' AND (name LIKE '%",
                        row(S, marker))
                .answer("SELECT engine_full FROM system.tables WHERE database = 'employees' AND name = 't'",
                        row(ENGINE_FULL))
                .answer("AND table = 't' AND is_in_sorting_key = 1", row("id"));
        assertTrue(PrimaryKeyBackfill.resumePending(oldKeyed.connection(), new Properties(), config(), "employees")
                .isEmpty());

        // A source-valued rebuild resumes with its key map re-read from the source.
        PrimaryKeyBackfill.Task sourceValued = swapped(sourceValuedPlan(), clickHouse(ENGINE_FULL,
                SHOW_CREATE.replace("    `name` Nullable(String),\n", "    `name` Nullable(String),\n    `new_id` UInt64,\n"),
                false, true));
        FakeDb withKeyMap = new FakeDb()
                .answer("SELECT name, comment FROM system.tables WHERE database = 'employees' AND (name LIKE '%",
                        row(S, PrimaryKeyBackfill.MARKER_PREFIX + sourceValued.markerJson()))
                .answer("SELECT engine_full FROM system.tables WHERE database = 'employees' AND name = 't'",
                        row(ENGINE_FULL))
                .answer("AND table = 't' AND is_in_sorting_key = 1", row("new_id"));
        List<PrimaryKeyBackfill.Task> resumedSourceValued = PrimaryKeyBackfill.resumePending(withKeyMap.connection(),
                new Properties(), config(), "employees");
        assertEquals(1, resumedSourceValued.size());
        assertTrue(resumedSourceValued.get(0).needKeyMap());
        assertEquals(Collections.singletonList("new_id"), resumedSourceValued.get(0).sourceValued());
        assertEquals(K, resumedSourceValued.get(0).keyMapTable());
        // A K left by the interrupted attempt may be partial: it is re-created, not trusted.
        FakeDb rerun = afterSwap(tColumns(false, true), tColumns(false, true), S, 0, "all")
                .answer("AND name = '" + K + "'", row(K));
        FakeDb source = new FakeDb().metaTypes(Types.INTEGER, Types.BIGINT).answer("FROM `hr`.`t`", row(1, 101L));
        new Harness(rerun, source, new Properties()).runOnce(resumedSourceValued.get(0));
        List<String> rerunActions = actions(rerun);
        // DESTRUCTIVE: expected statement text recorded by a fake connection; nothing is executed against any database.
        assertEquals("DROP TABLE IF EXISTS `employees`.`" + K + "`", rerunActions.get(0));
        assertTrue(rerunActions.get(1).startsWith("CREATE TABLE `employees`.`" + K + "`"), rerunActions.get(1));
        assertEquals(Collections.singletonList("SELECT `id`, `new_id` FROM `hr`.`t`"), source.executed);
    }

    @Test
    @DisplayName("disable.drop.truncate=true: the backfill completes, nothing is dropped, the kept copy is marked complete")
    public void retiredCopyKeptWhenDropTruncateDisabled() {
        Properties props = new Properties();
        // DESTRUCTIVE: a setting name only; nothing is executed against any database in this test.
        props.setProperty(SinkConnectorLightWeightConfig.DISABLE_DROP_TRUNCATE, "true");
        FakeDb swapDb = clickHouse(ENGINE_FULL, SHOW_CREATE, false, false);
        PrimaryKeyBackfill.Task task = PrimaryKeyRebuild.swap(existingKeyPlan(), swapDb.connection(), props, config(),
                "hr", EPOCH);
        assertEquals("EXCHANGE TABLES `employees`.`t` AND `employees`.`" + S + "`",
                actions(swapDb).get(actions(swapDb).size() - 1));

        FakeDb ch = afterSwap(tColumns(false, false), tColumns(false, false), S, 0, "all");
        Harness h = new Harness(ch, null, props);
        h.runOnce(task);

        assertNothingDropped(ch);
        assertNothingDropped(swapDb);
        assertEquals(TENANT_CHECK, actions(ch).get(actions(ch).size() - 1), "the check still runs");
        // The marker comes off the kept copy, so the next start does not copy it again.
        assertEquals(Collections.singletonList("ALTER TABLE `employees`.`" + S + "` MODIFY COMMENT '" + COMMENT + "'"),
                commentStatements(ch));
        assertTrue(h.reporter.steps.isEmpty(), h.reporter.steps.toString());
        assertEquals(0, h.backfill.pending());

        // A non-Atomic database swaps by RENAME instead; the retired name follows.
        FakeDb ordinary = clickHouse(ENGINE_FULL, SHOW_CREATE, false, false);
        ordinary.answers.add(0, new java.util.AbstractMap.SimpleEntry<>(
                sql -> sql.startsWith("SELECT engine FROM system.databases"),
                Collections.singletonList(row("Ordinary"))));
        PrimaryKeyBackfill.Task renamed = PrimaryKeyRebuild.swap(existingKeyPlan(), ordinary.connection(), props,
                config(), "hr", EPOCH);
        List<String> ordinaryActions = actions(ordinary);
        assertEquals("RENAME TABLE `employees`.`t` TO `employees`.`t__pk_retired_" + EPOCH + "`, `employees`.`" + S
                + "` TO `employees`.`t`", ordinaryActions.get(ordinaryActions.size() - 1));
        assertEquals("t__pk_retired_" + EPOCH, renamed.retired());
    }

    // ------------------------------------------------------------------
    // Superseding DDL, a second key change, a schema change during the
    // backfill (Spec 06.09 §3.3.2 steps 7-9)
    // ------------------------------------------------------------------

    private static final String SHOW_CREATE_NEW_ID = SHOW_CREATE.replace("    `name` Nullable(String),\n",
            "    `name` Nullable(String),\n    `new_id` UInt64,\n");

    /** The DDL path's (writer's) connection as a cancel sees it: the scan finds the task's retired table with its marker. */
    private static FakeDb writerSeeing(PrimaryKeyBackfill.Task task) {
        return new FakeDb().answer("SELECT name, comment FROM system.tables WHERE database = 'employees' AND (name LIKE 't",
                row(task.retired(), COMMENT + "\n" + PrimaryKeyBackfill.MARKER_PREFIX + task.markerJson()));
    }

    private static List<String> startingWith(List<String> statements, String prefix) {
        return statements.stream().filter(sql -> sql.startsWith(prefix)).collect(Collectors.toList());
    }

    @Test
    // DESTRUCTIVE: statement text recorded by fake connections only; nothing is executed against any database in this test.
    @DisplayName("A TRUNCATE TABLE arriving while the backfill is queued cancels it and drops R and K before the truncate runs; no copy statement is ever issued")
    public void truncateDuringBackfillCancelsAndDropsRetired() {
        // A source-valued rebuild: both the retired copy and the key map are at stake.
        PrimaryKeyBackfill.Task task = swapped(sourceValuedPlan(), clickHouse(ENGINE_FULL, SHOW_CREATE_NEW_ID, false, true));
        FakeDb backfillDb = afterSwap(tColumns(false, true), tColumns(false, true), S, 0, "all");
        Harness h = new Harness(backfillDb, null, new Properties());
        assertTrue(h.backfill.submit(task));
        assertEquals(Collections.singletonList(0L), h.scheduler.delays);
        assertEquals(1, h.backfill.pending());

        // A superseding statement for ANOTHER table leaves the task alone.
        FakeDb unrelated = new FakeDb();
        assertEquals(0, h.backfill.cancelFor(unrelated.connection(), "employees", "other"));
        assertTrue(actions(unrelated).isEmpty(), unrelated.executed.toString());
        assertEquals(1, h.backfill.pending());
        assertFalse(task.cancelled());

        // The DDL path for t: the cancel on the writer's connection, then the statement itself.
        FakeDb writer = writerSeeing(task);
        int cancelled = h.backfill.cancelFor(writer.connection(), "employees", "t");
        // DESTRUCTIVE: the source's own replicated statement, recorded by a fake connection; nothing is executed against any database.
        execute(writer, "TRUNCATE TABLE `employees`.`t`");

        assertEquals(1, cancelled);
        assertTrue(task.cancelled());
        assertEquals(0, h.backfill.pending());
        // DESTRUCTIVE: expected statement text recorded by a fake connection; nothing is executed against any database.
        assertEquals(Arrays.asList(
                "DROP TABLE IF EXISTS `employees`.`" + S + "`",
                "DROP TABLE IF EXISTS `employees`.`" + K + "`",
                "TRUNCATE TABLE `employees`.`t`"), actions(writer), "R and K are dropped BEFORE the truncate runs");

        // The queued attempt is a no-op: no connection opened, no statement, no retry, no failure.
        h.scheduler.runNext();
        assertTrue(backfillDb.executed.isEmpty(), "no copy statement may be issued: " + backfillDb.executed);
        assertEquals(Collections.singletonList(0L), h.scheduler.delays, "nothing re-scheduled");
        assertTrue(h.reporter.steps.isEmpty(), "a cancel is not a failure: " + h.reporter.steps);
        assertEquals(0, h.backfill.pending());

        // DESTRUCTIVE: a setting name only; nothing is executed against any database in this test.
        // disable.drop.truncate=true: the retired copy is dropped all the same --
        // the operator keeps the rows already in T, not the retired ones.
        Properties keep = new Properties();
        // DESTRUCTIVE: a setting name only; nothing is executed against any database in this test.
        keep.setProperty(SinkConnectorLightWeightConfig.DISABLE_DROP_TRUNCATE, "true");
        PrimaryKeyBackfill.Task kept = PrimaryKeyRebuild.swap(existingKeyPlan(),
                clickHouse(ENGINE_FULL, SHOW_CREATE, false, false).connection(), keep, config(), "hr", EPOCH);
        Harness hk = new Harness(afterSwap(tColumns(false, false), tColumns(false, false), S, 0, "all"), null, keep);
        assertTrue(hk.backfill.submit(kept));
        FakeDb writerKeep = writerSeeing(kept);
        assertEquals(1, hk.backfill.cancelFor(writerKeep.connection(), "employees", "t"));
        // DESTRUCTIVE: expected statement text recorded by a fake connection; nothing is executed against any database.
        assertEquals(Collections.singletonList("DROP TABLE IF EXISTS `employees`.`" + S + "`"), actions(writerKeep));
        assertEquals(0, hk.backfill.pending());
    }

    @Test
    // DESTRUCTIVE: statement text recorded by fake connections only; nothing is executed against any database in this test.
    @DisplayName("A DROP TABLE arriving while the backfill is queued cancels it; a pending copy of a previous run is dropped by its marker, a marker-less scratch table is not; DROP DATABASE cancels every table of the database")
    public void dropTableDuringBackfillCancels() {
        PrimaryKeyBackfill.Task task = swapped(existingKeyPlan(), clickHouse(ENGINE_FULL, SHOW_CREATE, false, false));
        FakeDb backfillDb = afterSwap(tColumns(false, false), tColumns(false, false), S, 0, "all");
        Harness h = new Harness(backfillDb, null, new Properties());
        assertTrue(h.backfill.submit(task));

        // The writer's connection also sees, for the same table, a pending copy
        // of a previous run that this process did not resume (marker) with its
        // key map, and a marker-less leftover of a swap that never completed.
        String previous = "t__pk_retired_1600000000000";
        String previousKeys = "t__pk_rebuild_keys_1600000000000";
        String stale = "t__pk_rebuild_1500000000000";
        PrimaryKeyBackfill.Task older = new PrimaryKeyBackfill.Task("employees", "t", previous, previousKeys, null, "hr",
                "is_deleted", Collections.singletonList("id"), Collections.singletonList("new_id"),
                Collections.emptyMap(), Collections.singletonList("new_id"), false, 1600000000000L);
        FakeDb writer = new FakeDb().answer(
                "SELECT name, comment FROM system.tables WHERE database = 'employees' AND (name LIKE 't",
                row(stale, ""),
                row(previous, PrimaryKeyBackfill.MARKER_PREFIX + older.markerJson()),
                row(S, COMMENT + "\n" + PrimaryKeyBackfill.MARKER_PREFIX + task.markerJson()));

        assertEquals(1, h.backfill.cancelFor(writer.connection(), "employees", "t"));
        // DESTRUCTIVE: the source's own replicated statement, recorded by a fake connection; nothing is executed against any database.
        execute(writer, "DROP TABLE IF EXISTS `employees`.`t`");

        // DESTRUCTIVE: expected statement text recorded by a fake connection; nothing is executed against any database.
        assertEquals(Arrays.asList(
                "DROP TABLE IF EXISTS `employees`.`" + S + "`",
                "DROP TABLE IF EXISTS `employees`.`" + previous + "`",
                "DROP TABLE IF EXISTS `employees`.`" + previousKeys + "`",
                "DROP TABLE IF EXISTS `employees`.`t`"), actions(writer),
                "the task's R, the previous run's R and K by its marker, never the marker-less leftover; all before the drop");
        assertTrue(task.cancelled());
        h.scheduler.runNext();
        assertTrue(backfillDb.executed.isEmpty(), "no copy statement may be issued: " + backfillDb.executed);
        assertEquals(0, h.backfill.pending());
        assertEquals(Collections.singletonList(0L), h.scheduler.delays, "nothing re-scheduled");
        assertTrue(h.reporter.steps.isEmpty(), h.reporter.steps.toString());

        // DROP DATABASE: every pending backfill of that database, whatever the
        // table; a backfill in another database is untouched.
        PrimaryKeyBackfill.Task t1 = swapped(existingKeyPlan(), clickHouse(ENGINE_FULL, SHOW_CREATE, false, false));
        PrimaryKeyBackfill.Task u1 = new PrimaryKeyBackfill.Task("employees", "u", "u__pk_rebuild_" + EPOCH,
                "u__pk_rebuild_keys_" + EPOCH, null, "hr", "is_deleted", Collections.singletonList("id"),
                Collections.singletonList("b"), Collections.emptyMap(), Collections.emptyList(), false, EPOCH);
        PrimaryKeyBackfill.Task elsewhere = new PrimaryKeyBackfill.Task("other", "t", S, K, null, "hr", "is_deleted",
                Collections.singletonList("id"), Collections.singletonList("tenant"), Collections.emptyMap(),
                Collections.emptyList(), false, EPOCH);
        Harness all = new Harness(new FakeDb(), null, new Properties());
        assertEquals(3, all.backfill.submitAll(Arrays.asList(t1, u1, elsewhere)));
        FakeDb writerAll = new FakeDb();
        assertEquals(2, all.backfill.cancelAllFor(writerAll.connection(), "employees"));
        // DESTRUCTIVE: expected statement text recorded by a fake connection; nothing is executed against any database.
        assertEquals(Arrays.asList(
                "DROP TABLE IF EXISTS `employees`.`" + S + "`",
                "DROP TABLE IF EXISTS `employees`.`u__pk_rebuild_" + EPOCH + "`"), actions(writerAll));
        assertTrue(writerAll.executed.stream().anyMatch(sql -> sql.contains("name LIKE '%\\_\\_pk\\_rebuild\\_%'")),
                "the scan covers every table of the database: " + writerAll.executed);
        assertTrue(t1.cancelled() && u1.cancelled() && !elsewhere.cancelled());
        assertEquals(1, all.backfill.pending());
    }

    @Test
    // DESTRUCTIVE: statement text recorded by fake connections only; nothing is executed against any database in this test.
    @DisplayName("A running copy cancelled between two statements stops there without a retry; a retired table already gone at run time is terminal; a shutdown stops at the boundary too")
    public void cancelledBackfillStopsWithoutRescheduling() {
        PrimaryKeyBackfill.Task task = swapped(existingKeyPlan(), clickHouse(ENGINE_FULL, SHOW_CREATE, false, false));
        FakeDb backfillDb = afterSwap(tColumns(false, false), tColumns(false, false), S, 0, "all");
        Harness h = new Harness(backfillDb, null, new Properties());
        FakeDb writer = writerSeeing(task);
        // The superseding statement arrives while the copy statement is in flight.
        backfillDb.onStatement("INSERT INTO `employees`.`t`", () -> {
            assertEquals(1, h.backfill.cancelFor(writer.connection(), "employees", "t"));
            // DESTRUCTIVE: the source's own replicated statement, recorded by a fake connection; nothing is executed against any database.
            execute(writer, "TRUNCATE TABLE `employees`.`t`");
        });

        h.runOnce(task);

        String insert = "INSERT INTO `employees`.`t` (`id`, `tenant`, `name`, `_version`, `is_deleted`) "
                + tenantSelect(null);
        assertEquals(Collections.singletonList(insert), actions(backfillDb),
                "the attempt stops at the next statement boundary: no row count, no check, no drop by the task");
        // DESTRUCTIVE: expected statement text recorded by a fake connection; nothing is executed against any database.
        assertEquals(Arrays.asList("DROP TABLE IF EXISTS `employees`.`" + S + "`", "TRUNCATE TABLE `employees`.`t`"),
                actions(writer));
        assertEquals(Collections.singletonList(0L), h.scheduler.delays, "not re-scheduled");
        assertTrue(h.reporter.steps.isEmpty(), "a cancel is not a failure: " + h.reporter.steps);
        assertEquals(0, h.backfill.pending());
        assertEquals(0, task.failures());

        // The retired table vanished before the attempt, with no cancel seen in
        // this process: logged, reported, terminal -- never retried, nothing copied.
        PrimaryKeyBackfill.Task gone = swapped(existingKeyPlan(), clickHouse(ENGINE_FULL, SHOW_CREATE, false, false));
        FakeDb noRetired = new FakeDb().answer("AND table = 't' ORDER BY position", tColumns(false, false));
        Harness hg = new Harness(noRetired, null, new Properties());
        hg.runOnce(gone);
        assertEquals(Collections.singletonList(0L), hg.scheduler.delays, "terminal: not re-scheduled");
        assertEquals(Collections.singletonList("step 0 (columns of the retired table)"), hg.reporter.steps);
        assertTrue(hg.reporter.messages.get(0).contains("no longer exists"), hg.reporter.messages.get(0));
        assertTrue(actions(noRetired).isEmpty(), "no copy, no drop: " + actions(noRetired));
        assertEquals(0, hg.backfill.pending());

        // A shutdown between two statements stops the attempt as well; the
        // marker on the retired table resumes it at the next start.
        PrimaryKeyBackfill.Task interrupted = swapped(existingKeyPlan(), clickHouse(ENGINE_FULL, SHOW_CREATE, false, false));
        FakeDb shut = afterSwap(tColumns(false, false), tColumns(false, false), S, 0, "all");
        Harness hs = new Harness(shut, null, new Properties());
        shut.onStatement("INSERT INTO `employees`.`t`", hs.backfill::shutdown);
        hs.runOnce(interrupted);
        assertEquals(Collections.singletonList(insert), actions(shut), shut.executed.toString());
        assertNothingDropped(shut);
        assertEquals(Collections.singletonList(0L), hs.scheduler.delays);
        assertTrue(hs.reporter.steps.isEmpty(), hs.reporter.steps.toString());
        assertEquals(0, hs.backfill.pending());
    }

    private static PrimaryKeyRebuildPlan backToIdPlan() {
        Map<String, Provenance> provenance = new LinkedHashMap<>();
        provenance.put("id", Provenance.EXISTING);
        return new PrimaryKeyRebuildPlan("employees", "t", Collections.singletonList("tenant"),
                Collections.singletonList("id"), false, provenance,
                "ALTER TABLE t DROP PRIMARY KEY, ADD PRIMARY KEY (id)");
    }

    @Test
    @DisplayName("A second key change while the first backfill is pending: step 1 keeps the marker-bearing retired table, and the two backfills run in submission order")
    public void secondSwapKeepsPendingRetiredTable() {
        // First change: id -> tenant; its backfill is still pending.
        PrimaryKeyBackfill.Task first = swapped(existingKeyPlan(), clickHouse(ENGINE_FULL, SHOW_CREATE, false, false));
        assertEquals(S, first.retired());

        // Second change on the same table, tenant -> id, while R1 (marker) and
        // its key map are still there and T is keyed by tenant.
        String S2 = "t__pk_rebuild_" + (EPOCH + 1);
        FakeDb second = clickHouse(ENGINE_FULL.replace("ORDER BY id", "ORDER BY tenant"),
                SHOW_CREATE.replace("ORDER BY id", "ORDER BY tenant"), false, false)
                .answer("AND (name LIKE 't", row(S, COMMENT + "\n" + PrimaryKeyBackfill.MARKER_PREFIX + first.markerJson()),
                        row(K, ""))
                .answer("AND table = 't' AND is_in_sorting_key = 1", row("tenant"));
        PrimaryKeyBackfill.Task later = PrimaryKeyRebuild.swap(backToIdPlan(), second.connection(), new Properties(),
                config(), "hr", EPOCH + 1);

        assertNothingDropped(second);
        assertEquals(S2, later.retired());
        assertEquals("EXCHANGE TABLES `employees`.`t` AND `employees`.`" + S2 + "`",
                actions(second).get(actions(second).size() - 1));
        assertEquals(Collections.singletonList("tenant"), later.oldKey());
        assertEquals(Collections.singletonList("id"), later.newKey());

        // Both backfills on one runner: T now carries both key columns as
        // ordinary columns, so each copy and check is built against it.
        String copy1 = "INSERT INTO `employees`.`t` (`id`, `tenant`, `name`, `_version`, `is_deleted`) " + tenantSelect(null);
        String copy2 = copy1.replace("`" + S + "`", "`" + S2 + "`");
        FakeDb run = new FakeDb()
                .answer("AND table = 't' ORDER BY position", tColumns(false, false))
                .answer("AND table = '" + S + "' ORDER BY position", tColumns(false, false))
                .answer("AND table = '" + S2 + "' ORDER BY position", tColumns(false, false))
                .answer("SELECT DISTINCT partition_id FROM system.parts", row("all"))
                .answer("FROM `employees`.`" + S + "` AS r FINAL", row(0L))
                .answer("FROM `employees`.`" + S2 + "` AS r FINAL", row(0L))
                .answer("SELECT count() FROM (", row(5L))
                // The first backfill's first copy fails, so it is re-scheduled behind the second one.
                .failOn(copy1, 1);
        Harness h = new Harness(run, null, new Properties());
        assertTrue(h.backfill.submit(first));
        assertTrue(h.backfill.submit(later));
        assertEquals(2, h.backfill.pending());
        assertEquals(Arrays.asList(0L, 0L), h.scheduler.delays);

        h.scheduler.runNext();  // first, attempt 1: the copy fails -> retry in 10 s
        assertEquals(Arrays.asList(0L, 0L, 10_000L), h.scheduler.delays);
        h.scheduler.runNext();  // later: the earlier backfill of the same table is still pending -> waits, issues nothing
        assertEquals(Arrays.asList(0L, 0L, 10_000L, PrimaryKeyBackfill.FIFO_RECHECK_MS), h.scheduler.delays);
        assertFalse(run.executed.stream().anyMatch(sql -> sql.contains("`" + S2 + "`")),
                "the later backfill must not overtake the earlier one: " + run.executed);
        h.scheduler.runNext();  // first, attempt 2: copies R1, drops it
        h.scheduler.runNext();  // later: copies R2, drops it

        List<String> actions = actions(run);
        assertEquals(Arrays.asList(copy1, copy1, copy2), startingWith(actions, "INSERT"));
        // DESTRUCTIVE: expected statement text recorded by a fake connection; nothing is executed against any database.
        assertEquals(Arrays.asList("DROP TABLE IF EXISTS `employees`.`" + S + "`",
                "DROP TABLE IF EXISTS `employees`.`" + S2 + "`"), startingWith(actions, "DROP"));
        assertEquals(Collections.singletonList("step 2 (copy the live rows)"), h.reporter.steps);
        assertEquals(0, h.backfill.pending());
        assertEquals(4, h.scheduler.delays.size(), "nothing more scheduled");
    }

    @Test
    @DisplayName("A column on T that R lacks (added after the swap) is left out of the copy, as are ALIAS/MATERIALIZED columns; the list follows T's current columns")
    public void columnAddedDuringBackfillIsOmittedFromCopy() {
        PrimaryKeyBackfill.Task task = swapped(existingKeyPlan(), clickHouse(ENGINE_FULL, SHOW_CREATE, false, false));
        Object[][] widened = {row("id", "Int32", ""), row("tenant", "Int32", ""), row("name", "Nullable(String)", ""),
                row("extra", "Nullable(Int32)", ""), row("doubled", "Int32", "MATERIALIZED"),
                row("shadow", "Int32", "ALIAS"), row("_version", "UInt64", ""), row("is_deleted", "UInt8", "")};
        FakeDb ch = afterSwap(widened, tColumns(false, false), S, 0, "all");

        new Harness(ch, null, new Properties()).runOnce(task);

        String insert = "INSERT INTO `employees`.`t` (`id`, `tenant`, `name`, `_version`, `is_deleted`) "
                + tenantSelect(null);
        assertEquals(Arrays.asList(
                insert,
                "SELECT count() FROM (" + tenantSelect(null) + ")",
                TENANT_CHECK,
                // DESTRUCTIVE: expected statement text recorded by a fake connection; nothing is executed against any database.
                "DROP TABLE IF EXISTS `employees`.`" + S + "`"), actions(ch));

        // A column removed from T after the swap is simply not in the copied set.
        Object[][] narrower = {row("id", "Int32", ""), row("tenant", "Int32", ""), row("_version", "UInt64", ""),
                row("is_deleted", "UInt8", "")};
        FakeDb removed = afterSwap(narrower, tColumns(false, false), S, 0, "all");
        new Harness(removed, null, new Properties()).runOnce(
                swapped(existingKeyPlan(), clickHouse(ENGINE_FULL, SHOW_CREATE, false, false)));
        String narrowSelect = "SELECT `id`, `tenant`, `_version`, `is_deleted` FROM `employees`.`" + S
                + "` FINAL WHERE `is_deleted` = 0";
        assertEquals("INSERT INTO `employees`.`t` (`id`, `tenant`, `_version`, `is_deleted`) " + narrowSelect,
                actions(removed).get(0));
    }

    // ------------------------------------------------------------------
    // Deferred clauses on old-key columns (Spec 06.09 §3.1.1, §3.3.1 step 3)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A deferred DROP is applied to the empty rebuilt table before the swap; the copy excludes the column")
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
        Object[][] oldColumns = {row("my_row_id", "UInt64", ""), row("id", "Int32", ""), row("v", "String", ""),
                row("_version", "UInt64", ""), row("is_deleted", "UInt8", "")};
        Object[][] newColumns = {row("id", "Int32", ""), row("v", "String", ""), row("_version", "UInt64", ""),
                row("is_deleted", "UInt8", "")};
        FakeDb ch = new FakeDb()
                .answer("SELECT engine_full, comment FROM system.tables", row(engineFull, COMMENT))
                .answer("AND table = 't' ORDER BY position", oldColumns)
                .answer("SHOW CREATE TABLE", row(showCreate))
                .answer("SELECT engine FROM system.databases", row("Atomic"));
        Map<String, Provenance> provenance = new LinkedHashMap<>();
        provenance.put("id", Provenance.EXISTING);
        PrimaryKeyRebuildPlan plan = new PrimaryKeyRebuildPlan("employees", "t",
                Collections.singletonList("my_row_id"), Collections.singletonList("id"), false, provenance,
                // DESTRUCTIVE: statement text used only by this test; nothing outside the test containers/fakes is touched.
                "ALTER TABLE t DROP PRIMARY KEY, DROP COLUMN my_row_id, ADD PRIMARY KEY (id)",
                Collections.singletonList("DROP COLUMN IF EXISTS my_row_id"), Collections.emptyMap(),
                Collections.emptyMap());

        PrimaryKeyBackfill.Task task = swapped(plan, ch);

        List<String> expectedSwap = Arrays.asList(
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
                "EXCHANGE TABLES `employees`.`t` AND `employees`.`" + S + "`");
        assertEquals(expectedSwap, actions(ch));
        // The deferred DROP never targets the existing table (its only ALTER is the marker).
        for (String sql : ch.executed) {
            assertFalse(sql.startsWith("ALTER TABLE `employees`.`t`") && !sql.contains(" MODIFY COMMENT "),
                    "must not ALTER the existing table: " + sql);
        }

        // The copy reads the rebuilt table's columns: my_row_id, gone from T, is not copied.
        FakeDb after = afterSwap(newColumns, oldColumns, S, 0, "all");
        new Harness(after, null, new Properties()).runOnce(task);
        String select = "SELECT `id`, `v`, `_version`, `is_deleted` FROM `employees`.`" + S + "` FINAL WHERE `is_deleted` = 0";
        assertEquals(Arrays.asList(
                "INSERT INTO `employees`.`t` (`id`, `v`, `_version`, `is_deleted`) " + select,
                "SELECT count() FROM (" + select + ")",
                "SELECT count() FROM (SELECT r.`id` AS `id` FROM `employees`.`" + S + "` AS r FINAL WHERE r.`is_deleted` = 0) "
                        + "AS r LEFT JOIN (SELECT DISTINCT `id` FROM `employees`.`t`) AS t ON t.`id` = r.`id` "
                        + "WHERE t.`id` IS NULL SETTINGS join_use_nulls = 1",
                // DESTRUCTIVE: expected statement text recorded by a fake connection; nothing is executed against any database.
                "DROP TABLE IF EXISTS `employees`.`" + S + "`"), actions(after));
    }

    @Test
    @DisplayName("A renamed key column is declared under its new name on the rebuilt table and copied as o.old AS new")
    public void renamedKeyColumnIsCopiedUnderNewName() {
        // CHANGE id ref_id INT: ClickHouse rejects RENAME COLUMN of a key column
        // even on an empty table (Code: 524, measured on 24.8.14), so the
        // rebuilt table is created keyed by `ref_id` directly and the copy
        // reads o.`id` AS `ref_id`; no ALTER RENAME is issued anywhere.
        FakeDb ch = clickHouse(ENGINE_FULL, SHOW_CREATE, false, false);
        Map<String, Provenance> provenance = new LinkedHashMap<>();
        provenance.put("ref_id", Provenance.EXISTING);
        PrimaryKeyRebuildPlan plan = new PrimaryKeyRebuildPlan("employees", "t", Collections.singletonList("id"),
                Collections.singletonList("ref_id"), false, provenance,
                "ALTER TABLE t CHANGE COLUMN id ref_id INT NOT NULL",
                Collections.singletonList("RENAME COLUMN id TO ref_id"), Collections.singletonMap("id", "ref_id"),
                Collections.emptyMap());

        PrimaryKeyBackfill.Task task = swapped(plan, ch);

        assertEquals(Arrays.asList(
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
                "EXCHANGE TABLES `employees`.`t` AND `employees`.`" + S + "`"), actions(ch));
        assertEquals(Collections.singletonMap("id", "ref_id"), task.renames());
        assertEquals(Collections.singletonList("ref_id"), task.newKey());

        Object[][] renamedColumns = {row("ref_id", "Int32", ""), row("tenant", "Int32", ""),
                row("name", "Nullable(String)", ""), row("_version", "UInt64", ""), row("is_deleted", "UInt8", "")};
        FakeDb after = afterSwap(renamedColumns, tColumns(false, false), S, 0, "all");
        new Harness(after, null, new Properties()).runOnce(task);
        String select = "SELECT o.`id` AS `ref_id`, o.`tenant`, o.`name`, o.`_version`, o.`is_deleted` "
                + "FROM `employees`.`" + S + "` AS o FINAL WHERE o.`is_deleted` = 0";
        assertEquals(Arrays.asList(
                "INSERT INTO `employees`.`t` (`ref_id`, `tenant`, `name`, `_version`, `is_deleted`) " + select,
                "SELECT count() FROM (" + select + ")",
                "SELECT count() FROM (SELECT r.`id` AS `ref_id` FROM `employees`.`" + S + "` AS r FINAL WHERE r.`is_deleted` = 0) "
                        + "AS r LEFT JOIN (SELECT DISTINCT `ref_id` FROM `employees`.`t`) AS t ON t.`ref_id` = r.`ref_id` "
                        + "WHERE t.`ref_id` IS NULL SETTINGS join_use_nulls = 1",
                // DESTRUCTIVE: expected statement text recorded by a fake connection; nothing is executed against any database.
                "DROP TABLE IF EXISTS `employees`.`" + S + "`"), actions(after));
        for (String sql : ch.executed) {
            assertFalse(sql.contains("RENAME COLUMN"), "a key column is never renamed by ALTER: " + sql);
        }
        for (String sql : after.executed) {
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

        FakeDb ch = clickHouse(literal, SHOW_CREATE, false, false);
        DDLReplicationException loud = assertThrows(DDLReplicationException.class,
                () -> swapped(existingKeyPlan(), ch));
        assertTrue(loud.getMessage().contains("{table}") && loud.getMessage().contains("{uuid}"), loud.getMessage());
        assertTrue(loud.getMessage().toLowerCase().contains("manual rebuild"), loud.getMessage());
        assertTrue(actions(ch).isEmpty() && commentStatements(ch).isEmpty(),
                "nothing may run after a refusal: " + ch.executed);

        // The legacy sign-based engine and a non-Replacing engine are refused too.
        FakeDb collapsing = clickHouse("CollapsingMergeTree(_sign) ORDER BY id", SHOW_CREATE, false, false);
        assertThrows(DDLReplicationException.class, () -> swapped(existingKeyPlan(), collapsing));
        assertTrue(actions(collapsing).isEmpty());
        DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine = false;
        try {
            FakeDb legacy = clickHouse(ENGINE_FULL, SHOW_CREATE, false, false);
            assertThrows(DDLReplicationException.class, () -> swapped(existingKeyPlan(), legacy));
            assertTrue(legacy.executed.isEmpty(), "refused before any query: " + legacy.executed);
        } finally {
            DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine = true;
        }
    }

    @Test
    @DisplayName("A Nullable old-key column with a source key map is refused before any statement runs")
    public void nullableOldKeyWithSourceMapIsLoud() {
        FakeDb ch = clickHouse(ENGINE_FULL, SHOW_CREATE, true, true);

        DDLReplicationException loud = assertThrows(DDLReplicationException.class,
                () -> swapped(sourceValuedPlan(), ch));

        assertTrue(loud.getMessage().contains("Nullable(Int32)"), loud.getMessage());
        assertTrue(loud.getMessage().contains("NULL never equals NULL"), loud.getMessage());
        assertTrue(actions(ch).isEmpty() && commentStatements(ch).isEmpty(),
                "nothing may run after a refusal: " + ch.executed);

        // Without a source key map a Nullable old key is no obstacle (no JOIN).
        FakeDb local = clickHouse(ENGINE_FULL, SHOW_CREATE.replace("`id` Int32", "`id` Nullable(Int32)"), true, false);
        swapped(existingKeyPlan(), local);
        assertFalse(actions(local).isEmpty());

        // A ClickHouse type the key map cannot bind is refused before the swap.
        FakeDb decimalKey = new FakeDb()
                .answer("SELECT engine_full, comment FROM system.tables", row(ENGINE_FULL, COMMENT))
                .answer("ORDER BY position", row("id", "Decimal(10, 2)", ""), row("new_id", "UInt64", ""),
                        row("_version", "UInt64", ""), row("is_deleted", "UInt8", ""));
        DDLReplicationException decimal = assertThrows(DDLReplicationException.class,
                () -> swapped(sourceValuedPlan(), decimalKey));
        assertTrue(decimal.getMessage().contains("Decimal(10, 2)"), decimal.getMessage());
        assertTrue(actions(decimalKey).isEmpty());

        // The MySQL side can only be judged by the backfill, from the source
        // metadata: the failure is reported and retried, no row is bound, no
        // copy starts and nothing is dropped -- the retired table stays.
        PrimaryKeyBackfill.Task task = swapped(sourceValuedPlan(), clickHouse(ENGINE_FULL,
                SHOW_CREATE.replace("    `name` Nullable(String),\n", "    `name` Nullable(String),\n    `new_id` UInt64,\n"),
                false, true));
        FakeDb mysqlDecimal = afterSwap(tColumns(false, true), tColumns(false, true), S, 0, "all");
        FakeDb source = new FakeDb().metaTypes(Types.DECIMAL, Types.BIGINT).answer("FROM `hr`.`t`", row(1, 1L));
        Harness h = new Harness(mysqlDecimal, source, new Properties());
        h.runOnce(task);
        assertEquals(Collections.singletonList("step 1 (source column types)"), h.reporter.steps);
        assertTrue(h.reporter.messages.get(0).contains("JDBC type " + Types.DECIMAL), h.reporter.messages.get(0));
        assertTrue(mysqlDecimal.batched.isEmpty(), "no row may be bound after the type refusal");
        for (String sql : mysqlDecimal.executed) {
            assertFalse(sql.startsWith("INSERT INTO `employees`.`t`") || sql.startsWith("DROP"),
                    "the copy must not start: " + sql);
        }
        assertEquals(Arrays.asList(0L, 10_000L), h.scheduler.delays);
    }

    @Test
    @DisplayName("replication.history.enable=true is refused before any query")
    public void historyTablesAreRefused() {
        Map<String, String> history = new HashMap<>();
        history.put("replication.history.enable", "true");
        FakeDb ch = clickHouse(ENGINE_FULL, SHOW_CREATE, false, false);
        DDLReplicationException loud = assertThrows(DDLReplicationException.class,
                () -> PrimaryKeyRebuild.swap(existingKeyPlan(), ch.connection(), new Properties(),
                        new ClickHouseSinkConnectorConfig(history), "hr", EPOCH));
        assertTrue(loud.getMessage().contains("replication.history.enable"), loud.getMessage());
        assertTrue(ch.executed.isEmpty(), ch.executed.toString());
    }
}

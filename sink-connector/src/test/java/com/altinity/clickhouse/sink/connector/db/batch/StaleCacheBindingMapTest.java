package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.CacheInvalidationManager;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.model.BlockMetaData;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 04.03 section 3.5 / FM-04.03-2: a segment is bound with the column map
 * its INSERT template was built from, never with the writer's cached map.
 *
 * <p>The grouping re-reads the table when a record carries a column the cached
 * map lacks (a DDL applied out of band, or a MATERIALIZED column converted to
 * DEFAULT by the source-conformance enforcement of spec 08.04) and builds the
 * INSERT from the fresh map. {@code ClickHouseBatchRunnable.flushRecordsToClickHouse}
 * then hands the executor {@code writer.getColumnNameToDataTypeMap()} -- the
 * cached map. Binding walked that stale map, so the new column's placeholder
 * was never set and the driver's {@code addBatch()} threw a
 * {@code NullPointerException}: the first batch after every such re-read
 * failed. Each test here groups with the cached map and executes with the
 * same cached map, exactly as the runnable does.</p>
 *
 * <p>JDBC objects are JDK proxies: an in-memory {@code system.columns} for the
 * metadata reads and DDL, {@link RecordingJdbc} for the INSERT statements
 * (this module has no mocking framework on its test classpath).</p>
 */
public class StaleCacheBindingMapTest {

    private static final String DB = "db1";
    private static final String TABLE = "t1";
    private static final String TOPIC = "srv.db1.t1";

    private static final Schema PRE = SchemaBuilder.struct().name("srv.db1.t1.pre")
            .field("id", Schema.INT32_SCHEMA)
            .build();

    private static final Schema POST = SchemaBuilder.struct().name("srv.db1.t1.post")
            .field("id", Schema.INT32_SCHEMA)
            .field("key_col", Schema.OPTIONAL_STRING_SCHEMA)
            .build();

    /**
     * {@code system.columns} for the table plus DDL execution; INSERT
     * statements are delegated to a {@link RecordingJdbc}.
     */
    private static final class FakeClickHouse {
        /** rows of (name, type, default_kind) */
        final List<String[]> columns = new ArrayList<>();
        final List<String> executed = new ArrayList<>();
        final RecordingJdbc inserts = new RecordingJdbc();
        private final Connection insertConnection = inserts.connection();

        FakeClickHouse() {
            columns.add(new String[]{"id", "Int32", ""});
            columns.add(new String[]{"_version", "UInt64", ""});
            columns.add(new String[]{"is_deleted", "UInt8", ""});
        }

        private String[] find(String name) {
            for (String[] c : columns) {
                if (c[0].equalsIgnoreCase(name)) {
                    return c;
                }
            }
            return null;
        }

        private static String quotedName(String sql) {
            int start = sql.indexOf("lower('") + 7;
            return sql.substring(start, sql.indexOf("')", start));
        }

        private List<String[]> answer(String sql) {
            if (sql.contains("default_kind='ALIAS' or default_kind='MATERIALIZED'")) {
                List<String[]> rows = new ArrayList<>();
                for (String[] c : columns) {
                    if ("ALIAS".equals(c[2]) || "MATERIALIZED".equals(c[2])) {
                        rows.add(new String[]{c[0]});
                    }
                }
                return rows;
            }
            if (sql.startsWith("SELECT name, type, default_kind FROM system.columns")) {
                return new ArrayList<>(columns);
            }
            if (sql.startsWith("SELECT type FROM system.columns")) {
                String[] c = find(quotedName(sql));
                return c == null ? Collections.emptyList() : Collections.singletonList(new String[]{c[1]});
            }
            if (sql.startsWith("SELECT default_expression FROM system.columns")) {
                return Collections.singletonList(new String[]{"concat(toString(id), '-x')"});
            }
            if (sql.startsWith("SELECT default_kind FROM system.columns")) {
                String[] c = find(quotedName(sql));
                return c == null ? Collections.emptyList() : Collections.singletonList(new String[]{c[2]});
            }
            throw new IllegalStateException("unexpected query: " + sql);
        }

        /** {@code MODIFY COLUMN `c` <type> DEFAULT <expr>}: the conversion takes effect. */
        private void execute(String sql) {
            executed.add(sql);
            String upper = sql.toUpperCase();
            if (upper.startsWith("ALTER TABLE") && upper.contains("MODIFY COLUMN `")
                    && upper.contains(" DEFAULT ")) {
                int nameStart = upper.indexOf("MODIFY COLUMN `") + "MODIFY COLUMN `".length();
                String[] c = find(sql.substring(nameStart, sql.indexOf('`', nameStart)));
                if (c != null) {
                    c[2] = "DEFAULT";
                }
            }
        }

        private static Object defaultFor(Class<?> type) {
            if (!type.isPrimitive() || type == void.class) {
                return null;
            }
            return type == boolean.class ? Boolean.FALSE : 0;
        }

        private ResultSet resultSet(List<String[]> rows, String[] header) {
            final int[] cursor = {-1};
            InvocationHandler h = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "next":
                        cursor[0]++;
                        return cursor[0] < rows.size();
                    case "getString":
                        String[] row = rows.get(cursor[0]);
                        if (args[0] instanceof Integer) {
                            return row[(Integer) args[0] - 1];
                        }
                        for (int i = 0; i < header.length; i++) {
                            if (header[i].equals(args[0])) {
                                return row[i];
                            }
                        }
                        return null;
                    default:
                        return defaultFor(method.getReturnType());
                }
            };
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ResultSet.class}, h);
        }

        Connection connection() {
            final String[] header = {"name", "type", "default_kind"};
            InvocationHandler statement = (proxy, method, args) -> {
                if ("executeQuery".equals(method.getName())) {
                    return resultSet(answer((String) args[0]), header);
                }
                return defaultFor(method.getReturnType());
            };
            final Statement stmt = (Statement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Statement.class}, statement);

            InvocationHandler connection = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "createStatement":
                        return stmt;
                    case "prepareStatement": {
                        final String sql = (String) args[0];
                        if (sql.startsWith("INSERT")) {
                            return insertConnection.prepareStatement(sql);
                        }
                        InvocationHandler ps = (p, m, a) -> {
                            if ("execute".equals(m.getName())) {
                                execute(sql);
                                return false;
                            }
                            if ("executeQuery".equals(m.getName())) {
                                return resultSet(answer(sql), header);
                            }
                            return defaultFor(m.getReturnType());
                        };
                        return Proxy.newProxyInstance(getClass().getClassLoader(),
                                new Class<?>[]{PreparedStatement.class}, ps);
                    }
                    case "isClosed":
                        return false;
                    case "toString":
                        return "FakeClickHouseConnection";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        return defaultFor(method.getReturnType());
                }
            };
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, connection);
        }
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.ENABLE_SCHEMA_EVOLUTION.toString(), "false");
        props.put(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE.toString(), "true");
        return new ClickHouseSinkConnectorConfig(props);
    }

    /** The writer's cached map: it predates key_col. */
    private static Map<String, String> cachedWithoutKeyCol() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "Int32");
        m.put("_version", "UInt64");
        m.put("is_deleted", "UInt8");
        return m;
    }

    private static ClickHouseStruct insert(Schema schema, int id) {
        Struct after = new Struct(schema).put("id", id);
        if (schema == POST) {
            after.put("key_col", "k" + id);
        }
        ClickHouseStruct r = new ClickHouseStruct(id, TOPIC, null, 0, System.currentTimeMillis(),
                null, after, null, ClickHouseConverter.CDC_OPERATION.CREATE);
        r.setDatabase(DB);
        r.setTs_ms(1_700_000_000_000L + id);
        r.setFile("binary.000001");
        r.setPos(1000L + id);
        return r;
    }

    private static PreparedStatementExecutor executor() {
        return new PreparedStatementExecutor("is_deleted", true, null, "_version",
                DB, ZoneId.of("UTC"), () -> Arrays.asList("id"));
    }

    /**
     * Groups the batch with the writer's cached map and executes it with the
     * SAME cached map, as {@code ClickHouseBatchRunnable.processBatchRecords}
     * and {@code flushRecordsToClickHouse} do.
     */
    private static void groupAndExecute(FakeClickHouse ch, List<ClickHouseStruct> batch) throws Exception {
        ClickHouseSinkConnectorConfig config = config();
        Connection conn = ch.connection();
        Map<String, String> writerMap = cachedWithoutKeyCol();
        List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> segments = new ArrayList<>();
        new GroupInsertQueryWithBatchRecords("_version", null, "is_deleted").groupQueryWithRecords(
                batch, segments, new HashMap<TopicPartition, Long>(), config, TABLE, DB, conn, writerMap);
        assertTrue(executor().addToPreparedStatementBatch(TOPIC, segments, new BlockMetaData(), config, conn,
                TABLE, writerMap, DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE));
    }

    private static int placeholders(String sql) {
        int n = 0;
        for (char c : sql.toCharArray()) {
            if (c == '?') {
                n++;
            }
        }
        return n;
    }

    /** Every staged row binds every placeholder of its statement; returns the rows. */
    private static List<RecordingJdbc.Event> assertEveryPlaceholderBound(FakeClickHouse ch, int expectedRows) {
        List<RecordingJdbc.Event> rows = ch.inserts.ofKind(RecordingJdbc.ADD_BATCH);
        assertEquals(expectedRows, rows.size(), "every row staged exactly once: " + ch.inserts.events);
        for (RecordingJdbc.Event row : rows) {
            int n = placeholders(row.sql);
            for (int i = 1; i <= n; i++) {
                assertTrue(row.params.containsKey(i), "placeholder " + i + " of " + row.sql
                        + " reached addBatch() unbound (the V2 driver throws NullPointerException on it); "
                        + "the template was built from the re-read map but bound with the writer's "
                        + "cached map. Bound: " + row.params);
            }
        }
        return rows;
    }

    /** key_col is the second column of a template that carries it: id=1, key_col=2. */
    private static void assertKeyColBound(List<RecordingJdbc.Event> rows) {
        int withKeyCol = 0;
        for (RecordingJdbc.Event row : rows) {
            if (row.sql.contains("`key_col`")) {
                withKeyCol++;
                assertEquals("k" + row.params.get(1), row.params.get(2),
                        "the source value of key_col is bound: " + row);
            }
        }
        assertTrue(withKeyCol > 0, "at least one row was written through a template carrying key_col");
    }

    @BeforeEach
    public void setUp() {
        CacheInvalidationManager.getInstance().clearAll();
        DBMetadata.setMaxRetries(1);
    }

    @AfterEach
    public void tearDown() {
        DBMetadata.setMaxRetries(10);
        CacheInvalidationManager.getInstance().clearAll();
    }

    @Test
    @DisplayName("a MATERIALIZED column converted to DEFAULT mid-batch is bound in that batch, not left unbound")
    public void materializedColumnConvertedMidBatchIsBoundInTheSameBatch() throws Exception {
        FakeClickHouse ch = new FakeClickHouse();
        ch.columns.add(1, new String[]{"key_col", "String", "MATERIALIZED"});

        groupAndExecute(ch, new ArrayList<>(Arrays.asList(insert(POST, 1), insert(POST, 2), insert(POST, 3))));

        assertEquals("ALTER TABLE `db1`.`t1` MODIFY COLUMN `key_col` String DEFAULT concat(toString(id), '-x')",
                ch.executed.get(0), "precondition: the source-conformance enforcement ran");
        assertKeyColBound(assertEveryPlaceholderBound(ch, 3));
    }

    @Test
    @DisplayName("a column added out of band and found by the stale-cache re-read is bound in the same batch")
    public void columnFoundByStaleCacheReReadIsBoundInTheSameBatch() throws Exception {
        FakeClickHouse ch = new FakeClickHouse();
        ch.columns.add(1, new String[]{"key_col", "Nullable(String)", ""});

        groupAndExecute(ch, new ArrayList<>(Arrays.asList(insert(POST, 1), insert(POST, 2))));

        assertTrue(ch.executed.isEmpty(), "no DDL: the column already exists, only the cache was stale");
        assertKeyColBound(assertEveryPlaceholderBound(ch, 2));
    }

    @Test
    @DisplayName("templates built before and after a mid-batch re-read are each bound with their own map")
    public void templatesBuiltBeforeAndAfterAReReadAreEachBoundWithTheirOwnMap() throws Exception {
        FakeClickHouse ch = new FakeClickHouse();
        ch.columns.add(1, new String[]{"key_col", "Nullable(String)", ""});

        groupAndExecute(ch, new ArrayList<>(Arrays.asList(
                insert(PRE, 1), insert(POST, 2), insert(PRE, 3), insert(POST, 4))));

        assertEquals(2, ch.inserts.ofKind(RecordingJdbc.PREPARE).size(), "one statement per template");
        assertKeyColBound(assertEveryPlaceholderBound(ch, 4));
    }

    @Test
    @DisplayName("a placeholder the binding map lacks fails the batch naming the column, before addBatch()")
    public void placeholderMissingFromTheBindingMapFailsNamingTheColumn() {
        FakeClickHouse ch = new FakeClickHouse();
        Map<String, Integer> index = new HashMap<>();
        index.put("id", 1);
        index.put("key_col", 2);
        index.put("_version", 3);
        index.put("is_deleted", 4);
        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> segment = new HashMap<>();
        segment.put(new MutablePair<>(
                        "INSERT INTO `t1`(`id`,`key_col`,`_version`,`is_deleted`) VALUES (?,?,?,?)", index),
                new ArrayList<>(Collections.singletonList(insert(POST, 1))));
        List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> segments = new ArrayList<>();
        segments.add(segment);
        Connection conn = ch.connection();

        StaleSchemaCacheException e = assertThrows(StaleSchemaCacheException.class,
                () -> executor().addToPreparedStatementBatch(TOPIC, segments, new BlockMetaData(), config(),
                        conn, TABLE, cachedWithoutKeyCol(), DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE));

        assertTrue(e.getMessage().contains("key_col"), e.getMessage());
        assertTrue(ch.inserts.ofKind(RecordingJdbc.ADD_BATCH).isEmpty(),
                "nothing is staged with an unbound parameter: " + ch.inserts.events);
    }
}

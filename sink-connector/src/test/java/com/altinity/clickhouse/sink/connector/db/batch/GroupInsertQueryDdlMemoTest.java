package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.CacheInvalidationManager;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 04.06 section 3.5: how the per-batch template and staleness memos
 * behave around a DDL -- pre/post-ALTER records in one batch, a stale cache
 * that must be re-read once, a dropped column that must still fail loudly, an
 * ALIAS column proven absent once, and a column map changed in place.
 */
public class GroupInsertQueryDdlMemoTest {

    /** The source row before ADD COLUMN note. */
    private static final Schema PRE = SchemaBuilder.struct().name("srv.db.t.pre")
            .field("id", Schema.INT32_SCHEMA)
            .field("name", Schema.OPTIONAL_STRING_SCHEMA)
            .build();

    /** The source row after ADD COLUMN note. */
    private static final Schema POST = SchemaBuilder.struct().name("srv.db.t.post")
            .field("id", Schema.INT32_SCHEMA)
            .field("name", Schema.OPTIONAL_STRING_SCHEMA)
            .field("note", Schema.OPTIONAL_STRING_SCHEMA)
            .build();

    /**
     * A ClickHouse that answers the three metadata queries the grouping path
     * can issue, and counts them: the column listing (a cache refresh), the
     * ALIAS/MATERIALIZED name listing the refresh runs first, and the
     * default_kind probe for one column.
     */
    private static final class FakeClickHouse {
        /** rows of (name, type, default_kind) */
        final List<String[]> columns = new ArrayList<>();
        int listings = 0;
        int kindProbes = 0;

        FakeClickHouse(boolean withNote, String noteKind) {
            columns.add(new String[]{"id", "Int32", ""});
            columns.add(new String[]{"name", "Nullable(String)", ""});
            if (withNote) {
                columns.add(new String[]{"note", "Nullable(String)", noteKind});
            }
            columns.add(new String[]{"_version", "UInt64", ""});
            columns.add(new String[]{"is_deleted", "UInt8", ""});
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
                listings++;
                return new ArrayList<>(columns);
            }
            if (sql.startsWith("SELECT default_kind FROM system.columns")) {
                kindProbes++;
                int start = sql.indexOf("lower('") + 7;
                String name = sql.substring(start, sql.indexOf("')", start));
                for (String[] c : columns) {
                    if (c[0].equalsIgnoreCase(name)) {
                        return Collections.singletonList(new String[]{c[2]});
                    }
                }
                return Collections.emptyList();
            }
            throw new IllegalStateException("unexpected query: " + sql);
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
                    case "close":
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

    private static Map<String, String> cachedMap(boolean withNote) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "Int32");
        m.put("name", "Nullable(String)");
        if (withNote) {
            m.put("note", "Nullable(String)");
        }
        m.put("_version", "UInt64");
        m.put("is_deleted", "UInt8");
        return m;
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE.toString(), "true");
        return new ClickHouseSinkConnectorConfig(props);
    }

    private static ClickHouseStruct insert(Schema schema, int id) {
        Struct after = new Struct(schema).put("id", id).put("name", "n" + id);
        if (schema == POST) {
            after.put("note", "note" + id);
        }
        ClickHouseStruct r = new ClickHouseStruct(id, "srv.db.t", null, 0, System.currentTimeMillis(),
                null, after, null, ClickHouseConverter.CDC_OPERATION.CREATE);
        r.setDatabase("db");
        return r;
    }

    private static List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> group(
            List<ClickHouseStruct> batch, Map<String, String> cached, FakeClickHouse ch) {
        List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> segments = new ArrayList<>();
        new GroupInsertQueryWithBatchRecords("_version", null, "is_deleted").groupQueryWithRecords(
                batch, segments, new HashMap<TopicPartition, Long>(), config(), "t", "db",
                ch.connection(), cached);
        return segments;
    }

    private static MutablePair<String, Map<String, Integer>> onlyTemplate(
            Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> bucket) {
        assertEquals(1, bucket.size(), "exactly one template expected: " + bucket.keySet());
        return bucket.keySet().iterator().next();
    }

    @BeforeEach
    public void resetProvenAbsent() {
        CacheInvalidationManager.getInstance().clearAll();
    }

    @Test
    @DisplayName("ADD COLUMN: pre- and post-ALTER records interleaved in one batch get their own templates")
    public void preAndPostAlterRecordsInOneBatchGetTheirOwnTemplates() {
        FakeClickHouse ch = new FakeClickHouse(true, "");
        List<ClickHouseStruct> batch = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            batch.add(insert(i % 2 == 0 ? PRE : POST, i));
        }

        List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> segments =
                group(batch, cachedMap(true), ch);

        assertEquals(1, segments.size());
        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> bucket = segments.get(0);
        assertEquals(2, bucket.size(), "one template per record shape");
        int preRows = 0;
        int postRows = 0;
        for (Map.Entry<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> e : bucket.entrySet()) {
            boolean carriesNote = e.getKey().getLeft().contains("`note`");
            for (ClickHouseStruct r : e.getValue()) {
                assertEquals(carriesNote, r.getAfterStruct().schema() == POST,
                        "a record is grouped under the template of its own shape");
            }
            if (carriesNote) {
                postRows += e.getValue().size();
                assertTrue(e.getKey().getRight().containsKey("note"));
            } else {
                preRows += e.getValue().size();
                assertFalse(e.getKey().getRight().containsKey("note"),
                        "a pre-ALTER row must not reserve a placeholder for note (DEFAULT applies)");
            }
        }
        assertEquals(30, preRows);
        assertEquals(30, postRows);
        assertEquals(0, ch.listings, "the cache already knows every column: no metadata read");
        assertEquals(0, ch.kindProbes);
    }

    @Test
    @DisplayName("Stale cache: one metadata re-read, and the fresh map keys every later record")
    public void staleCacheIsRefreshedOnceAndTheFreshMapKeysTheMemo() {
        // The table has note; this writer's cache predates the ADD COLUMN.
        FakeClickHouse ch = new FakeClickHouse(true, "");
        List<ClickHouseStruct> batch = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            batch.add(insert(POST, i));
        }

        List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> segments =
                group(batch, cachedMap(false), ch);

        assertEquals(1, ch.listings, "the staleness is discovered by the first record and re-read once");
        assertEquals(0, ch.kindProbes, "the re-read produced the column; no default_kind probe");
        MutablePair<String, Map<String, Integer>> template = onlyTemplate(segments.get(0));
        assertTrue(template.getLeft().contains("`note`"), "built from the fresh map, not the stale one");
        assertEquals(50, segments.get(0).get(template).size(), "every record, including the first, under it");
    }

    @Test
    @DisplayName("ALIAS column: proven absent once for the whole batch, no query storm")
    public void aliasColumnIsProvenAbsentOnceForTheWholeBatch() {
        // The source has note; ClickHouse defines note as an ALIAS (not stored).
        FakeClickHouse ch = new FakeClickHouse(true, "ALIAS");
        List<ClickHouseStruct> batch = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            batch.add(insert(POST, i));
        }

        List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> segments =
                group(batch, cachedMap(false), ch);

        assertEquals(1, ch.listings, "one column listing for 100 records");
        assertEquals(1, ch.kindProbes, "one default_kind probe for 100 records");
        MutablePair<String, Map<String, Integer>> template = onlyTemplate(segments.get(0));
        assertFalse(template.getLeft().contains("`note`"), "an ALIAS is never in the INSERT");
        assertEquals(100, segments.get(0).get(template).size());
        assertTrue(CacheInvalidationManager.getInstance().isColumnProvenAbsent("db.t", "note"));
    }

    // DESTRUCTIVE: the name below describes a SOURCE-side DDL scenario the
    // grouper must react to; this test destroys nothing -- it only asserts
    // that a record carrying a column the replica no longer has fails loudly.
    @Test
    @DisplayName("DROP COLUMN: a record carrying the dropped column still fails the batch after a clean schema was memoised")
    public void droppedColumnStillFailsLoudlyAfterACleanSchemaWasMemoised() {
        // note was dropped in ClickHouse and the cache knows it; a record read
        // before the DROP still carries it.
        FakeClickHouse ch = new FakeClickHouse(false, "");
        List<ClickHouseStruct> batch = new ArrayList<>();
        batch.add(insert(PRE, 1));
        batch.add(insert(PRE, 2));
        batch.add(insert(POST, 3));

        MissingTargetColumnException e = assertThrows(MissingTargetColumnException.class,
                () -> group(batch, cachedMap(false), ch),
                "the memoised clean verdict for the other schema must not skip this record's check");
        assertTrue(e.getMessage().contains("note"), e.getMessage());
        assertEquals(1, ch.listings, "the re-read happened for the record that carries the column");
    }

    @Test
    @DisplayName("A column map changed in place under the same reference rebuilds the template")
    public void templateIsRebuiltWhenTheColumnMapChangesInPlace() {
        ClickHouseSinkConnectorConfig config = config();
        GroupInsertQueryWithBatchRecords grouper =
                new GroupInsertQueryWithBatchRecords("_version", null, "is_deleted");
        Map<String, String> sameInstance = cachedMap(true);
        ClickHouseStruct r = insert(POST, 1);

        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> first = new HashMap<>();
        grouper.updateQueryToRecordsMap(r, r.getAfterModifiedFields(), first, "t", config, sameInstance);
        assertTrue(onlyTemplate(first).getLeft().contains("`name`"));

        // A DROP COLUMN name applied to the cache in place.
        sameInstance.remove("name");
        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> second = new HashMap<>();
        grouper.updateQueryToRecordsMap(r, r.getAfterModifiedFields(), second, "t", config, sameInstance);
        MutablePair<String, Map<String, Integer>> rebuilt = onlyTemplate(second);
        assertFalse(rebuilt.getLeft().contains("`name`"),
                "an identity-only memo would still emit the pre-DDL template");
        assertNotNull(rebuilt.getRight().get("note"));
    }
}

package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.CacheInvalidationManager;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failure modes of the schema cache on the write path (specs 08.03 and 08.01,
 * section 6 of each): how many catalog queries a stale-cache probe may cost,
 * and what happens when the re-read itself fails.
 *
 * <p>The known incident this guards: a column ClickHouse computes
 * (MATERIALIZED) was taken as proof of a stale cache on every record; the
 * re-read never produced it, the table version was bumped anyway, every worker
 * rebuilt its writer, and the loop ran at record rate -- 9.7 million
 * {@code system.columns} queries in one hour, a table version of 66 million
 * after three hours, and a stalled binlog offset. These tests pin the bound:
 * one probe per column per DDL generation, independent of record and batch
 * count, and only against the system catalog (Invariant I14).</p>
 *
 * <p>JDBC objects are JDK proxies over an in-memory {@code system.columns}
 * (this module has no mocking framework on its test classpath).</p>
 */
public class SchemaCacheFailureModesTest {

    private static final class FakeCatalog {
        /** rows of (name, type, default_kind) */
        final List<String[]> columns = new ArrayList<>();
        final List<String> queries = new ArrayList<>();
        /** when true every column listing throws, as a server refusing metadata reads would */
        boolean listingFails = false;

        FakeCatalog() {
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

        private List<String[]> answer(String sql) throws SQLException {
            queries.add(sql);
            boolean listing = sql.startsWith("SELECT name, type, default_kind FROM system.columns")
                    || sql.contains("default_kind='ALIAS' or default_kind='MATERIALIZED'");
            if (listing && listingFails) {
                throw new SQLException("Code: 202. DB::Exception: Too many simultaneous queries");
            }
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
            if (sql.startsWith("SELECT default_kind FROM system.columns")) {
                int start = sql.indexOf("lower('") + 7;
                String[] c = find(sql.substring(start, sql.indexOf("')", start)));
                return c == null ? Collections.emptyList() : Collections.singletonList(new String[]{c[2]});
            }
            throw new IllegalStateException("unexpected query: " + sql);
        }

        private static Object defaultFor(Class<?> type) {
            if (!type.isPrimitive() || type == void.class) {
                return null;
            }
            return type == boolean.class ? Boolean.FALSE : 0;
        }

        private ResultSet resultSet(List<String[]> rows) {
            final String[] header = {"name", "type", "default_kind"};
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
            InvocationHandler statement = (proxy, method, args) -> {
                if ("executeQuery".equals(method.getName())) {
                    return resultSet(answer((String) args[0]));
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
                        return "FakeCatalogConnection";
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

    /** The writer's cached column map: the three columns the table had when the writer was built. */
    private static Map<String, String> cachedMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "Int32");
        m.put("_version", "UInt64");
        m.put("is_deleted", "UInt8");
        return m;
    }

    private static List<ClickHouseStruct> records(Schema schema, String extraColumn, int count) {
        List<ClickHouseStruct> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Struct after = new Struct(schema).put("id", i).put(extraColumn, "v" + i);
            ClickHouseStruct record = new ClickHouseStruct(
                    i, "topic", null, 0, System.currentTimeMillis(),
                    null, after, null, ClickHouseConverter.CDC_OPERATION.CREATE);
            record.setDatabase("db");
            out.add(record);
        }
        return out;
    }

    /** One batch through a NEW grouper, exactly as ClickHouseBatchRunnable#processBatchRecords does per batch. */
    private static List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> groupBatch(
            FakeCatalog catalog, List<ClickHouseStruct> batch, Map<String, String> cached) {
        List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> segments = new ArrayList<>();
        Map<TopicPartition, Long> offsets = new HashMap<>();
        new GroupInsertQueryWithBatchRecords().groupQueryWithRecords(batch, segments, offsets, config(),
                "t", "db", catalog.connection(), cached);
        return segments;
    }

    @BeforeEach
    public void reset() {
        CacheInvalidationManager.getInstance().clearAll();
        DBMetadata.setMaxRetries(1);
    }

    @AfterEach
    public void restore() {
        DBMetadata.setMaxRetries(10);
        CacheInvalidationManager.getInstance().clearAll();
    }

    @Test
    @DisplayName("an ALIAS column the source also carries costs one probe per DDL generation, not one per record")
    public void aliasColumnIsProbedOncePerDdlGenerationNotPerRecord() {
        FakeCatalog catalog = new FakeCatalog();
        catalog.columns.add(new String[]{"computed", "String", "ALIAS"});
        Schema schema = SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA)
                .field("computed", Schema.OPTIONAL_STRING_SCHEMA)
                .build();
        Map<String, String> cached = cachedMap();
        long versionBefore = CacheInvalidationManager.getInstance().getVersion("db.t");

        // 50 batches of 200 records: 10,000 records carrying a column ClickHouse computes.
        for (int batch = 0; batch < 50; batch++) {
            assertFalse(groupBatch(catalog, records(schema, "computed", 200), cached).isEmpty());
        }

        assertEquals(3, catalog.queries.size(), "one probe for the whole stream: the ALIAS/MATERIALIZED "
                + "list, the column listing and one default_kind lookup; anything proportional to records "
                + "or batches is the metadata storm. Queries: " + catalog.queries);
        for (String sql : catalog.queries) {
            assertTrue(sql.contains("system.columns"), "a stale-cache probe reads only the system "
                    + "catalog, never the replicated table (Invariant I14): " + sql);
        }
        assertEquals(versionBefore, CacheInvalidationManager.getInstance().getVersion("db.t"),
                "an ALIAS miss must not bump the table version: every bump makes every worker rebuild "
                        + "its writer, which is how the storm multiplied by the thread count");

        // A DDL on the table retires the proof: the column is probed exactly once more.
        CacheInvalidationManager.getInstance().invalidateTable("db.t");
        groupBatch(catalog, records(schema, "computed", 200), cached);
        assertEquals(6, catalog.queries.size(), "one more probe after the DDL, then silence");
        groupBatch(catalog, records(schema, "computed", 200), cached);
        assertEquals(6, catalog.queries.size());
    }

    @Test
    @DisplayName("a column the table lacks fails the batch after one probe, however many records carry it")
    public void missingColumnFailsTheBatchAfterOneProbe() {
        FakeCatalog catalog = new FakeCatalog();
        Schema schema = SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA)
                .field("note", Schema.OPTIONAL_STRING_SCHEMA)
                .build();

        assertThrows(MissingTargetColumnException.class,
                () -> groupBatch(catalog, records(schema, "note", 500), cachedMap()));
        assertEquals(3, catalog.queries.size(), "the failing attempt costs one probe, not one per record: "
                + catalog.queries);
        assertFalse(CacheInvalidationManager.getInstance().isColumnProvenAbsent("db.t", "note"),
                "a missing column is never proven absent, so the retry re-probes and a fixed table heals");

        // The operator adds the column in ClickHouse: the retried batch (same records) now passes.
        catalog.columns.add(new String[]{"note", "Nullable(String)", ""});
        List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> segments =
                groupBatch(catalog, records(schema, "note", 500), cachedMap());
        assertFalse(segments.isEmpty());
        for (MutablePair<String, Map<String, Integer>> template : segments.get(0).keySet()) {
            assertTrue(template.getLeft().contains("note"), "the retry binds the added column: "
                    + template.getLeft());
        }
    }

    @Test
    @Disabled("DEFECT FM-08.03-4: when the stale-cache re-read fails (every column listing throws), "
            + "refreshIfRecordHasUnknownColumn logs a WARN and returns null, the grouper memoises the stale map "
            + "as verified, and the INSERT is built without a column ClickHouse has and the record carries; the "
            + "bind-time backstop walks the stale map and cannot see the column, so the value is dropped")
    @DisplayName("a failed stale-cache re-read never yields an INSERT that omits a column the record carries")
    public void failedReReadNeverDropsTheColumn() {
        FakeCatalog catalog = new FakeCatalog();
        // The table HAS the column (a DDL added it); the writer's map predates it.
        catalog.columns.add(new String[]{"note", "Nullable(String)", ""});
        catalog.listingFails = true;
        Schema schema = SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA)
                .field("note", Schema.OPTIONAL_STRING_SCHEMA)
                .build();

        List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> segments;
        try {
            segments = groupBatch(catalog, records(schema, "note", 10), cachedMap());
        } catch (RuntimeException failedLoudly) {
            return; // correct: the batch fails and is retried against a re-read that works
        }
        for (MutablePair<String, Map<String, Integer>> template : segments.get(0).keySet()) {
            assertTrue(template.getLeft().contains("note"), "the INSERT omits `note` although the record "
                    + "carries it and ClickHouse has it: the value is silently dropped and the offset advances "
                    + "past it. INSERT: " + template.getLeft());
        }
    }
}

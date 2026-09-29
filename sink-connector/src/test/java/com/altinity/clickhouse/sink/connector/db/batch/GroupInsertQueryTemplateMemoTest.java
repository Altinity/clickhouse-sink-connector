package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.QueryFormatter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 04.06 section 3.1: within one batch the INSERT template and the
 * cache-staleness check are derived once per Connect schema per column-map
 * instance, and a memo entry never outlives the column map it was built from.
 */
public class GroupInsertQueryTemplateMemoTest {

    private static final Schema ROW_A = SchemaBuilder.struct().name("srv.db.t.A")
            .field("id", Schema.INT32_SCHEMA)
            .field("name", Schema.OPTIONAL_STRING_SCHEMA)
            .build();

    /** The post-ALTER shape: one more column than ROW_A. */
    private static final Schema ROW_B = SchemaBuilder.struct().name("srv.db.t.B")
            .field("id", Schema.INT32_SCHEMA)
            .field("name", Schema.OPTIONAL_STRING_SCHEMA)
            .field("note", Schema.OPTIONAL_STRING_SCHEMA)
            .build();

    /** Counts how often the grouping path walks the map. */
    private static final class CountingColumnMap extends LinkedHashMap<String, String> {
        int entrySetCalls = 0;
        int keySetCalls = 0;

        @Override
        public Set<Map.Entry<String, String>> entrySet() {
            entrySetCalls++;
            return super.entrySet();
        }

        @Override
        public Set<String> keySet() {
            keySetCalls++;
            return super.keySet();
        }
    }

    private static CountingColumnMap columns(boolean withName) {
        CountingColumnMap m = new CountingColumnMap();
        m.put("id", "Int32");
        if (withName) {
            m.put("name", "Nullable(String)");
        }
        m.put("note", "Nullable(String)");
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
        if (schema == ROW_B) {
            after.put("note", "note" + id);
        }
        ClickHouseStruct r = new ClickHouseStruct(id, "srv.db.t", null, 0, System.currentTimeMillis(),
                null, after, null, ClickHouseConverter.CDC_OPERATION.CREATE);
        r.setDatabase("db");
        return r;
    }

    /** What the formatter answers for this record, computed fresh (no memo). */
    private static MutablePair<String, Map<String, Integer>> fresh(ClickHouseStruct r, Map<String, String> columns,
                                                                    ClickHouseSinkConnectorConfig config) {
        return new QueryFormatter().getInsertQueryUsingInputFunction(
                "t", r.getAfterModifiedFields(), columns, false, false,
                config.getString(ClickHouseSinkConnectorConfigVariables.STORE_RAW_DATA_COLUMN.toString()),
                "db", "is_deleted", r.getAfterStruct().schema().fields(), "_version", null);
    }

    @Test
    @DisplayName("Two interleaved schemas: two template builds, two staleness checks, every record bucketed")
    public void templateIsBuiltOncePerSchemaPerBatch() {
        ClickHouseSinkConnectorConfig config = config();
        CountingColumnMap columns = columns(true);
        List<ClickHouseStruct> batch = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            batch.add(insert(i % 3 == 2 ? ROW_B : ROW_A, i));
        }

        List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> segments = new ArrayList<>();
        new GroupInsertQueryWithBatchRecords("_version", null, "is_deleted").groupQueryWithRecords(
                batch, segments, new HashMap<TopicPartition, Long>(), config, "t", "db",
                new RecordingJdbc().connection(), columns);

        assertEquals(1, segments.size(), "no TRUNCATE: one segment");
        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> buckets = segments.get(0);
        assertEquals(2, buckets.size(), "one bucket per distinct template");

        MutablePair<String, Map<String, Integer>> expectedA = fresh(batch.get(0), columns, config);
        MutablePair<String, Map<String, Integer>> expectedB = fresh(batch.get(2), columns, config);
        assertFalse(expectedA.getLeft().contains("`note`"), "an A record does not carry note");
        assertTrue(expectedB.getLeft().contains("`note`"), "a B record carries note");
        List<ClickHouseStruct> bucketA = buckets.get(expectedA);
        List<ClickHouseStruct> bucketB = buckets.get(expectedB);
        assertNotNull(bucketA, "the memoised A template must equal the fresh one");
        assertNotNull(bucketB, "the memoised B template must equal the fresh one");
        assertEquals(200, bucketA.size());
        assertEquals(100, bucketB.size());
        for (ClickHouseStruct r : bucketA) {
            assertTrue(r.getAfterStruct().schema() == ROW_A, "A bucket holds only A records");
        }
        for (ClickHouseStruct r : bucketB) {
            assertTrue(r.getAfterStruct().schema() == ROW_B, "B bucket holds only B records");
        }

        // The memo proof. Without it the formatter walked the map's entries
        // once per RECORD (300) and the staleness check rebuilt the key set
        // once per record (300); the two fresh() calls above walked entries
        // twice more, so subtract them.
        assertEquals(2, columns.entrySetCalls - 2,
                "the INSERT template must be built once per schema per batch, not once per record");
        assertEquals(2, columns.keySetCalls,
                "the staleness check must run once per schema per batch, not once per record");
    }

    @Test
    @DisplayName("A second column-map instance rebuilds the template even for the same schema")
    public void templateIsRebuiltForADifferentColumnMap() {
        ClickHouseSinkConnectorConfig config = config();
        GroupInsertQueryWithBatchRecords grouper =
                new GroupInsertQueryWithBatchRecords("_version", null, "is_deleted");
        ClickHouseStruct r = insert(ROW_A, 1);

        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> first = new HashMap<>();
        grouper.updateQueryToRecordsMap(r, r.getAfterModifiedFields(), first, "t", config, columns(true));
        String firstTemplate = first.keySet().iterator().next().getLeft();
        assertTrue(firstTemplate.contains("`name`"), "the first map declares name");

        // Same grouper, same record schema, a map WITHOUT the name column.
        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> second = new HashMap<>();
        grouper.updateQueryToRecordsMap(r, r.getAfterModifiedFields(), second, "t", config, columns(false));
        String secondTemplate = second.keySet().iterator().next().getLeft();
        assertFalse(secondTemplate.contains("`name`"),
                "a memo keyed by anything but the column map's identity would reuse the first template");
        Collection<Integer> secondIndexes = second.keySet().iterator().next().getRight().values();
        // note is not carried by an A record and name is not in the second
        // map: only id and the engine columns remain.
        assertEquals(3, secondIndexes.size(), "id, _version, is_deleted");
    }
}

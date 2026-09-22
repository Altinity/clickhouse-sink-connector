package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.ClickHouseDbConstants;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.model.BlockMetaData;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Spec 05.04 section 3.1 / 04.04 section 3.1: on a CollapsingMergeTree target
 * an UPDATE stages a {@code -1} cancel row carrying the before image, THEN a
 * {@code +1} live row carrying the after image.
 *
 * <p>Before this change the before image was bound with sign {@code -1} and
 * then overwritten in place by the after image before the only
 * {@code addBatch()}, so no cancel row ever reached ClickHouse; and because
 * the grouping stage appended every UPDATE twice, each UPDATE produced two
 * {@code +1} rows. The table grew by two live rows per update and nothing
 * collapsed.</p>
 */
public class PreparedStatementExecutorCollapsingSignTest {

    private static final String TABLE = "orders";
    private static final String SIGN = ClickHouseDbConstants.SIGN_COLUMN;

    private static final Schema ROW_SCHEMA = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .field("val", Schema.STRING_SCHEMA)
            .build();

    private static Map<String, String> columns() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "Int32");
        m.put("val", "String");
        m.put(SIGN, "Int8");
        return m;
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE.toString(), "true");
        return new ClickHouseSinkConnectorConfig(props);
    }

    private static ClickHouseStruct record(Struct before, Struct after,
                                           ClickHouseConverter.CDC_OPERATION op) {
        ClickHouseStruct record = new ClickHouseStruct(1L, "topic", null, 0,
                System.currentTimeMillis(), before, after, null, op);
        record.setDatabase("db");
        return record;
    }

    private static Struct row(int id, String val) {
        return new Struct(ROW_SCHEMA).put("id", id).put("val", val);
    }

    /** Groups and executes the records; returns the recorded JDBC calls and the sign parameter index. */
    private static MutablePair<RecordingJdbc, Map<String, Integer>> run(List<ClickHouseStruct> records)
            throws Exception {
        ClickHouseSinkConnectorConfig config = config();
        List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> grouped =
                new ArrayList<>();
        new GroupInsertQueryWithBatchRecords().groupQueryWithRecords(
                records, grouped, new HashMap<>(), config, TABLE, "db", null, columns());
        assertEquals(1, grouped.size(), "no TRUNCATE: one segment");
        assertEquals(1, grouped.get(0).size(),
                "all records share one INSERT template: " + grouped.get(0).keySet());
        Map<String, Integer> indexMap = grouped.get(0).keySet().iterator().next().getRight();

        RecordingJdbc jdbc = new RecordingJdbc();
        PreparedStatementExecutor executor = new PreparedStatementExecutor(
                null, false, SIGN, null, "db", ZoneId.of("UTC"));
        executor.addToPreparedStatementBatch("topic", grouped, new BlockMetaData(), config,
                jdbc.connection(), TABLE, columns(), DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE);
        return new MutablePair<>(jdbc, indexMap);
    }

    private static List<Object> stagedValues(RecordingJdbc jdbc, int index) {
        List<Object> out = new ArrayList<>();
        for (RecordingJdbc.Event e : jdbc.ofKind(RecordingJdbc.ADD_BATCH)) {
            out.add(e.params.get(index));
        }
        return out;
    }

    @Test
    @DisplayName("An UPDATE stages [-1 (before image), +1 (after image)]")
    public void testUpdateStagesCancelRowThenLiveRow() throws Exception {
        MutablePair<RecordingJdbc, Map<String, Integer>> r = run(new ArrayList<>(List.of(
                record(row(1, "old"), row(1, "new"), ClickHouseConverter.CDC_OPERATION.UPDATE))));
        RecordingJdbc jdbc = r.getLeft();
        int signIndex = r.getRight().get(SIGN);
        int valIndex = r.getRight().get("val");

        assertEquals(Arrays.asList(-1, 1), stagedValues(jdbc, signIndex),
                "one UPDATE must stage exactly a cancel row then a live row; staged: " + jdbc.events);
        assertEquals(Arrays.asList("old", "new"), stagedValues(jdbc, valIndex),
                "the cancel row carries the before image, the live row the after image");
        assertEquals(1, jdbc.ofKind(RecordingJdbc.EXECUTE_BATCH).size(),
                "both rows go out in the one batch: " + jdbc.kinds());
    }

    @Test
    @DisplayName("An INSERT stages exactly one +1 row")
    public void testInsertStagesOneLiveRow() throws Exception {
        MutablePair<RecordingJdbc, Map<String, Integer>> r = run(new ArrayList<>(List.of(
                record(null, row(1, "v"), ClickHouseConverter.CDC_OPERATION.CREATE))));
        assertEquals(Arrays.asList(1), stagedValues(r.getLeft(), r.getRight().get(SIGN)));
    }

    @Test
    @DisplayName("A DELETE stages exactly one -1 row")
    public void testDeleteStagesOneCancelRow() throws Exception {
        MutablePair<RecordingJdbc, Map<String, Integer>> r = run(new ArrayList<>(List.of(
                record(row(1, "v"), null, ClickHouseConverter.CDC_OPERATION.DELETE))));
        assertEquals(Arrays.asList(-1), stagedValues(r.getLeft(), r.getRight().get(SIGN)));
    }
}

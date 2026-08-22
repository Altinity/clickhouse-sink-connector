package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A single MySQL statement that modifies N rows produces ONE binlog event
 * carrying N row images, which Debezium emits as N change records delivered in
 * one batch.
 *
 * <p>In replication-history mode {@code groupQueryWithRecords()} returned from
 * inside the per-record loop as soon as it saw the first UPDATE, so every
 * remaining record in that batch was discarded -- silently, with no error and
 * no metric, while the offset still advanced past the lost rows. Production
 * symptom: every batch logged {@code Records: 1} regardless of
 * {@code max.batch.size}, and 853 of 853 non-first row images were never
 * applied.</p>
 *
 * <p>Measured end to end on MySQL 8.0.36 ROW/FULL/GTID -> ClickHouse
 * 24.8.14.10547: one UPDATE touching 20 rows applied 1 of 20 before the fix and
 * 20 of 20 after it. DELETEs were unaffected in both cases, because the DELETE
 * path never enters this branch -- which is why bulk DELETEs appeared to work
 * while bulk UPDATEs silently lost data.</p>
 *
 * <p>The standard (non-history) flow never took this branch and was never
 * affected.</p>
 */
public class GroupInsertQueryHistoryMultiRowTest {

    private static final String DB = "repro_db";
    private static final String TABLE = "multirow";

    private static Map<String, String> columns() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "Int32");
        m.put("v", "Nullable(String)");
        m.put("_version", "UInt64");
        m.put("is_deleted", "UInt8");
        return m;
    }

    private static final Schema ROW_SCHEMA = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .field("v", Schema.OPTIONAL_STRING_SCHEMA)
            .build();

    /** One UPDATE row image: both before and after are present. */
    private static ClickHouseStruct updateRecord(int id, long offset) {
        Struct before = new Struct(ROW_SCHEMA).put("id", id).put("v", "old");
        Struct after = new Struct(ROW_SCHEMA).put("id", id).put("v", "new");
        return new ClickHouseStruct(offset, "topic", null, 0, System.currentTimeMillis(),
                before, after, new HashMap<>(),
                ClickHouseConverter.CDC_OPERATION.UPDATE);
    }

    private static ClickHouseSinkConnectorConfig historyConfig() {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString(),
                "true");
        return new ClickHouseSinkConnectorConfig(props);
    }

    private static int groupedRecordCount(
            Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> map) {
        int n = 0;
        for (List<ClickHouseStruct> l : map.values()) {
            n += l.size();
        }
        return n;
    }

    /**
     * The defect itself: 20 UPDATE row images from one statement must all be
     * grouped, not just the first.
     */
    @Test
    public void allRowsOfAMultiRowUpdateAreGroupedInHistoryMode() {
        List<ClickHouseStruct> records = new ArrayList<>();
        for (int id = 1; id <= 20; id++) {
            records.add(updateRecord(id, id));
        }
        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> grouped =
                new HashMap<>();

        new GroupInsertQueryWithBatchRecords().groupQueryWithRecords(
                records, grouped, new HashMap<>(), historyConfig(),
                TABLE, DB, null, columns());

        Assert.assertEquals(
                "every row image of a multi-row UPDATE must be grouped; returning from "
                        + "inside the loop discarded all but the first",
                20, groupedRecordCount(grouped));
    }

    /**
     * A batch that begins with an UPDATE must not swallow the records that
     * follow it -- that ordering is what made the loss total in production.
     */
    @Test
    public void recordsAfterTheFirstUpdateSurviveInHistoryMode() {
        List<ClickHouseStruct> records = new ArrayList<>();
        records.add(updateRecord(1, 1));
        for (int id = 2; id <= 5; id++) {
            records.add(updateRecord(id, id));
        }
        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> grouped =
                new HashMap<>();

        new GroupInsertQueryWithBatchRecords().groupQueryWithRecords(
                records, grouped, new HashMap<>(), historyConfig(),
                TABLE, DB, null, columns());

        Assert.assertEquals("records following the first UPDATE must not be discarded",
                5, groupedRecordCount(grouped));
    }

    /**
     * Regression guard: in history mode an UPDATE must still produce ONE row
     * (the after image), not the two rows the standard flow emits. The fix
     * changes only where control flows, never how many rows an UPDATE yields.
     */
    @Test
    public void historyModeStillEmitsOneRowPerUpdate() {
        List<ClickHouseStruct> records = new ArrayList<>();
        records.add(updateRecord(1, 1));
        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> grouped =
                new HashMap<>();

        new GroupInsertQueryWithBatchRecords().groupQueryWithRecords(
                records, grouped, new HashMap<>(), historyConfig(),
                TABLE, DB, null, columns());

        Assert.assertEquals("history mode must not split an UPDATE into before+after",
                1, groupedRecordCount(grouped));
    }

    /**
     * Regression guard for the standard flow: with history mode DISABLED an
     * UPDATE is still split into before and after, so 20 updates yield 40 rows.
     * This branch was never affected and must stay unchanged.
     */
    @Test
    public void standardModeStillSplitsUpdateIntoBeforeAndAfter() {
        List<ClickHouseStruct> records = new ArrayList<>();
        for (int id = 1; id <= 20; id++) {
            records.add(updateRecord(id, id));
        }
        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> grouped =
                new HashMap<>();

        new GroupInsertQueryWithBatchRecords().groupQueryWithRecords(
                records, grouped, new HashMap<>(),
                new ClickHouseSinkConnectorConfig(new HashMap<>()),
                TABLE, DB, null, columns());

        Assert.assertEquals("standard mode must still emit before+after per UPDATE",
                40, groupedRecordCount(grouped));
    }
}

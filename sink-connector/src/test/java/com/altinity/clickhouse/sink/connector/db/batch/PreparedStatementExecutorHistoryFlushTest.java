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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 12.03 section 3.2, Gap G-12.03-1: in replication-history mode the SCD2
 * UPDATE statement reads the open row it closes straight back out of the
 * target ({@code INSERT ... SELECT ... FROM t FINAL WHERE ...}) and runs
 * INLINE on its own statement, so the rows staged on the shared INSERT
 * statement must be flushed FIRST -- exactly as the DELETE branch already did.
 *
 * <p>Without the flush a row created and updated in one batch had no visible
 * open row when the UPDATE ran: the close row was never written and the
 * pre-update version vanished from the history, while the current-state view
 * stayed correct, so no count or current-value check could detect it.</p>
 *
 * <p>Driven through {@link RecordingJdbc} (JDK proxies; this module has no
 * mocking framework), which records the ORDER of prepare / addBatch /
 * executeBatch calls without a server.</p>
 */
public class PreparedStatementExecutorHistoryFlushTest {

    private static final String TABLE = "employees";

    private static final Schema ROW_SCHEMA = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .field("name", Schema.STRING_SCHEMA)
            .build();

    private static final Schema KEY_SCHEMA = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .build();

    /** An SCD2 table (Spec 12.02): data columns plus the history and engine columns. */
    private static Map<String, String> columns() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "Int32");
        m.put("name", "String");
        m.put(ClickHouseDbConstants.DELETED_FROM_TIME_COLUMN, "DateTime");
        m.put(ClickHouseDbConstants.DELETED_TIME_COLUMN, "DateTime");
        m.put(ClickHouseDbConstants.OPERATION_COLUMN, "String");
        m.put(ClickHouseDbConstants.VERSION_COLUMN, "UInt64");
        m.put(ClickHouseDbConstants.IS_DELETED_COLUMN, "UInt8");
        return m;
    }

    private static ClickHouseSinkConnectorConfig historyConfig() {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE.toString(), "true");
        props.put(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString(), "true");
        return new ClickHouseSinkConnectorConfig(props);
    }

    private static Struct row(int id, String name) {
        return new Struct(ROW_SCHEMA).put("id", id).put("name", name);
    }

    private static ClickHouseStruct record(long offset, int id, Struct before, Struct after,
                                           ClickHouseConverter.CDC_OPERATION op) {
        ClickHouseStruct record = new ClickHouseStruct(offset, "topic", new Struct(KEY_SCHEMA).put("id", id), 0,
                System.currentTimeMillis(), before, after, null, op);
        record.setDatabase("db");
        record.setGtid(100L + offset);
        record.setTs_ms(1709290200000L);
        record.setTsSec(1709290200L);
        return record;
    }

    /** Groups and executes the records in history mode; returns the recorded JDBC calls. */
    private static RecordingJdbc run(List<ClickHouseStruct> records) throws Exception {
        ClickHouseSinkConnectorConfig config = historyConfig();
        List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> grouped = new ArrayList<>();
        new GroupInsertQueryWithBatchRecords().groupQueryWithRecords(
                records, grouped, new HashMap<>(), config, TABLE, "db", null, columns());
        assertEquals(1, grouped.size(), "no TRUNCATE: one segment");
        assertEquals(1, grouped.get(0).size(),
                "the CREATE and the UPDATE share one INSERT template: " + grouped.get(0).keySet());

        RecordingJdbc jdbc = new RecordingJdbc();
        PreparedStatementExecutor executor = new PreparedStatementExecutor(
                ClickHouseDbConstants.IS_DELETED_COLUMN, true, null, ClickHouseDbConstants.VERSION_COLUMN,
                "db", ZoneId.of("UTC"));
        executor.addToPreparedStatementBatch("topic", grouped, new BlockMetaData(), config,
                jdbc.connection(), TABLE, columns(), DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE);
        return jdbc;
    }

    private static boolean isInsertTemplate(RecordingJdbc.Event e) {
        return e.sql.contains(" VALUES (");
    }

    private static boolean isHistoryStatement(RecordingJdbc.Event e) {
        return e.sql.contains("UNION ALL");
    }

    private static int indexOf(RecordingJdbc jdbc, String kind, boolean template, int from) {
        for (int i = from; i < jdbc.events.size(); i++) {
            RecordingJdbc.Event e = jdbc.events.get(i);
            if (e.kind.equals(kind) && (template ? isInsertTemplate(e) : isHistoryStatement(e))) {
                return i;
            }
        }
        return -1;
    }

    @Test
    @DisplayName("CREATE then UPDATE of one row in one batch: the CREATE is flushed BEFORE the history UPDATE is prepared")
    public void createThenUpdateInOneBatchFlushesTheCreateFirst() throws Exception {
        RecordingJdbc jdbc = run(new ArrayList<>(List.of(
                record(1L, 1, null, row(1, "old"), ClickHouseConverter.CDC_OPERATION.CREATE),
                record(2L, 1, row(1, "old"), row(1, "new"), ClickHouseConverter.CDC_OPERATION.UPDATE))));

        int stagedCreate = indexOf(jdbc, RecordingJdbc.ADD_BATCH, true, 0);
        assertTrue(stagedCreate >= 0, "the CREATE is staged on the shared INSERT statement: " + jdbc.events);

        int historyPrepared = indexOf(jdbc, RecordingJdbc.PREPARE, false, 0);
        assertTrue(historyPrepared >= 0, "the UPDATE prepares its own SCD2 statement: " + jdbc.events);
        assertTrue(stagedCreate < historyPrepared, "the CREATE is staged before the UPDATE runs: " + jdbc.events);

        int flushed = indexOf(jdbc, RecordingJdbc.EXECUTE_BATCH, true, stagedCreate);
        assertTrue(flushed >= 0 && flushed < historyPrepared,
                "the shared statement is executed AFTER the CREATE was staged and BEFORE the history UPDATE "
                        + "is prepared, so its SELECT ... FINAL sees the open row it closes (Gap G-12.03-1): "
                        + jdbc.kinds());

        int historyExecuted = indexOf(jdbc, RecordingJdbc.EXECUTE_BATCH, false, historyPrepared);
        assertTrue(historyExecuted > historyPrepared, "the history UPDATE is executed inline: " + jdbc.kinds());
        assertEquals(1, jdbc.ofKind(RecordingJdbc.PREPARE).stream().filter(
                PreparedStatementExecutorHistoryFlushTest::isHistoryStatement).count(),
                "one SCD2 statement for one UPDATE");
    }

    @Test
    @DisplayName("The flushed SCD2 UPDATE closes the before key and binds the after image (regression guard)")
    public void historyUpdateIsTheCorrectedTwoSelectShape() throws Exception {
        RecordingJdbc jdbc = run(new ArrayList<>(List.of(
                record(1L, 1, null, row(1, "old"), ClickHouseConverter.CDC_OPERATION.CREATE),
                record(2L, 1, row(1, "old"), row(1, "new"), ClickHouseConverter.CDC_OPERATION.UPDATE))));

        RecordingJdbc.Event history = jdbc.events.get(indexOf(jdbc, RecordingJdbc.PREPARE, false, 0));
        assertEquals(1, history.sql.split("UNION ALL").length - 1,
                "same-key UPDATE: close row + after row only (Spec 12.03 section 3.2): " + history.sql);
        assertTrue(history.sql.contains("WHERE `id`=1 AND `_valid_to` = toDateTime('2100-01-01 00:00:00', 'UTC') AND `is_deleted` = 0"),
                "the close row selects the before key's open row: " + history.sql);

        RecordingJdbc.Event bound = jdbc.events.get(indexOf(jdbc, RecordingJdbc.ADD_BATCH, false, 0));
        assertTrue(bound.params.containsValue("new"), "the after image is bound as parameters: " + bound.params);
    }
}

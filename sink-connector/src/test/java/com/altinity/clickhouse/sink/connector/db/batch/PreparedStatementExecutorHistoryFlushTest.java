package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ZoneFixtures;
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

import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        return new ClickHouseSinkConnectorConfig(ZoneFixtures.withZones(props));
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

    /** Groups the records exactly as the worker does before a write. */
    private static List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> segments(
            List<ClickHouseStruct> records) {
        List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> grouped = new ArrayList<>();
        new GroupInsertQueryWithBatchRecords().groupQueryWithRecords(
                records, grouped, new HashMap<>(), historyConfig(), TABLE, "db", null, columns());
        assertFalse(grouped.isEmpty(), "the records must be grouped into at least one segment");
        return grouped;
    }

    /** The CREATE-plus-UPDATE shape the flush tests use: one segment, one INSERT template. */
    private static List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> grouped(
            List<ClickHouseStruct> records) {
        List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> grouped = segments(records);
        assertEquals(1, grouped.size(), "no TRUNCATE: one segment");
        assertEquals(1, grouped.get(0).size(),
                "the two operations share one INSERT template: " + grouped.get(0).keySet());
        return grouped;
    }

    private static PreparedStatementExecutor executor() {
        return new PreparedStatementExecutor(
                ClickHouseDbConstants.IS_DELETED_COLUMN, true, null, ClickHouseDbConstants.VERSION_COLUMN,
                "db", ZoneId.of("UTC"));
    }

    /** Executes the records in history mode with a scripted executeBatch() answer. */
    private static RuntimeException executeExpectingFailure(List<ClickHouseStruct> records,
                                                            RecordingJdbc jdbc) {
        ClickHouseSinkConnectorConfig config = historyConfig();
        List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> grouped = grouped(records);
        return assertThrows(RuntimeException.class, () ->
                executor().addToPreparedStatementBatch("topic", grouped, new BlockMetaData(), config,
                        jdbc.connection(), TABLE, columns(), DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE));
    }

    /** Groups and executes the records in history mode; returns the recorded JDBC calls. */
    private static RecordingJdbc run(List<ClickHouseStruct> records) throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        executor().addToPreparedStatementBatch("topic", grouped(records), new BlockMetaData(), historyConfig(),
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
        assertTrue(history.sql.contains("WHERE `id`=1 AND `_valid_to` = toDateTime(4102444800) AND `is_deleted` = 0"),
                "the close row selects the before key's open row: " + history.sql);

        RecordingJdbc.Event bound = jdbc.events.get(indexOf(jdbc, RecordingJdbc.ADD_BATCH, false, 0));
        assertTrue(bound.params.containsValue("new"), "the after image is bound as parameters: " + bound.params);
    }

    @Test
    @DisplayName("A flush that the driver flags EXECUTE_FAILED fails the batch BEFORE the history UPDATE runs")
    public void aFlushFlaggedExecuteFailedFailsTheBatchBeforeTheHistoryUpdateRuns() {
        RecordingJdbc jdbc = new RecordingJdbc();
        // Call 1 is the flush of the staged CREATE; it is the one the driver
        // flags. Anything after it must never run at all.
        jdbc.executeBatchAnswer = call -> new int[]{Statement.EXECUTE_FAILED};
        List<ClickHouseStruct> batch = new ArrayList<>(List.of(
                record(1L, 1, null, row(1, "old"), ClickHouseConverter.CDC_OPERATION.CREATE),
                record(2L, 1, row(1, "old"), row(1, "new"), ClickHouseConverter.CDC_OPERATION.UPDATE)));

        RuntimeException thrown = executeExpectingFailure(batch, jdbc);

        RuntimeException flushFailure = assertInstanceOf(RuntimeException.class, thrown.getCause(),
                "the chunk rethrows the flush failure: " + thrown);
        assertTrue(flushFailure.getMessage().contains(
                        "Failed to flush records staged before a replication-history UPDATE for db.employees"),
                "the failure names the flush and the table: " + flushFailure.getMessage());
        assertInstanceOf(SQLException.class, flushFailure.getCause(),
                "the driver's EXECUTE_FAILED answer is the cause: " + flushFailure);
        assertTrue(flushFailure.getCause().getMessage().contains("EXECUTE_FAILED for db.employees"),
                "the cause names the table: " + flushFailure.getCause().getMessage());

        assertEquals(1, jdbc.executeBatchCalls,
                "the flush failed, so the history UPDATE's own statement was never executed: " + jdbc.kinds());
        assertEquals(-1, indexOf(jdbc, RecordingJdbc.PREPARE, false, 0),
                "the SCD2 statement is not even prepared against a target the flush did not reach: "
                        + jdbc.kinds());
        assertFalse(batch.get(0).isAppliedToClickHouse(),
                "the CREATE is not in ClickHouse, so it is not marked applied: " + thrown);
        assertFalse(batch.get(1).isAppliedToClickHouse(), "...and neither is the UPDATE: " + thrown);
    }

    @Test
    @DisplayName("A flush flagged EXECUTE_FAILED ahead of the history DELETE fails the batch and runs no delete")
    public void aFlushFlaggedExecuteFailedAheadOfTheHistoryDeleteFailsTheBatch() {
        RecordingJdbc jdbc = new RecordingJdbc();
        jdbc.executeBatchAnswer = call -> new int[]{Statement.EXECUTE_FAILED};
        List<ClickHouseStruct> batch = new ArrayList<>(List.of(
                record(1L, 1, null, row(1, "old"), ClickHouseConverter.CDC_OPERATION.CREATE),
                record(2L, 1, row(1, "old"), null, ClickHouseConverter.CDC_OPERATION.DELETE)));

        RuntimeException thrown = executeExpectingFailure(batch, jdbc);

        RuntimeException flushFailure = assertInstanceOf(RuntimeException.class, thrown.getCause(),
                "the chunk rethrows the flush failure: " + thrown);
        assertTrue(flushFailure.getMessage().contains(
                        "Failed to flush records staged before a replication-history DELETE for db.employees"),
                "the DELETE branch has its own flush site and names it: " + flushFailure.getMessage());
        assertEquals(1, jdbc.executeBatchCalls,
                "the delete's SELECT ... FINAL never ran against a row the flush did not write: "
                        + jdbc.kinds());
        assertFalse(batch.get(0).isAppliedToClickHouse(), "not durable, not applied");
        assertFalse(batch.get(1).isAppliedToClickHouse(),
                "a delete with no visible open row writes zero rows and reports success; it must not "
                        + "be marked applied: " + thrown);
    }

    @Test
    @DisplayName("A history UPDATE the driver flags EXECUTE_FAILED keeps the flushed CREATE and fails the batch")
    public void aHistoryStatementFlaggedExecuteFailedFailsTheBatchAfterTheFlushSucceeded() {
        RecordingJdbc jdbc = new RecordingJdbc();
        // Call 1 is the flush (one staged CREATE) and it succeeds; call 2 is
        // the SCD2 statement itself and it comes back flagged. The SCD2
        // statement IS the UPDATE's write, so an unchecked answer would let
        // the caller mark the UPDATE applied over a version that was never
        // written -- and no later flush exists to catch it.
        jdbc.executeBatchAnswer = call -> call == 1 ? new int[]{1} : new int[]{Statement.EXECUTE_FAILED};
        List<ClickHouseStruct> batch = new ArrayList<>(List.of(
                record(1L, 1, null, row(1, "old"), ClickHouseConverter.CDC_OPERATION.CREATE),
                record(2L, 1, row(1, "old"), row(1, "new"), ClickHouseConverter.CDC_OPERATION.UPDATE)));

        RuntimeException thrown = executeExpectingFailure(batch, jdbc);

        assertInstanceOf(SQLException.class, thrown.getCause(),
                "the flagged history statement surfaces as its own SQLException: " + thrown);
        String cause = thrown.getCause().getMessage();
        assertTrue(cause.contains("The driver marked 1 of 1 statement(s) EXECUTE_FAILED for employees"),
                "the history table name is already qualified, so it prints alone: " + cause);
        assertTrue(cause.contains("spec 03.06 FM-03.06-4"), "the contract is named: " + cause);

        assertEquals(2, jdbc.executeBatchCalls,
                "the flush ran and returned success, then the history statement ran and was flagged: "
                        + jdbc.kinds());
        assertTrue(batch.get(0).isAppliedToClickHouse(),
                "the CREATE WAS flushed before the UPDATE: it is durable and stays marked, so the retry "
                        + "does not resend it (spec 03.06 section 3.5)");
        assertFalse(batch.get(1).isAppliedToClickHouse(),
                "the UPDATE's version was never written, so its offset must stay unacknowledged");

        // What the retry does with the same list: only the missing version.
        List<ClickHouseStruct> resent = segments(batch).stream()
                .flatMap(segment -> segment.values().stream())
                .flatMap(List::stream)
                .collect(Collectors.toList());
        assertEquals(1, resent.size(), "the retry carries the UPDATE alone: " + resent);
        assertSame(batch.get(1), resent.get(0), "and it is the version that was not written");
    }
}

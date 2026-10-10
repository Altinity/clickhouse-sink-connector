package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.model.BlockMetaData;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 03.06 section 6: what {@code PreparedStatementExecutor} does when the
 * driver's {@code executeBatch()} fails part-way through a multi-chunk
 * template, and when it reports a statement as {@code EXECUTE_FAILED}
 * without throwing. No server: a scripted JDBC proxy stands in.
 */
public class PreparedStatementExecutorFailureModesTest {

    private static final Schema ROW = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .build();

    private static final String INSERT = "insert into db.t(`id`,`_version`,`is_deleted`) select `id`,`_version`,"
            + "`is_deleted` from input('`id` Int32,`_version` UInt64,`is_deleted` UInt8')";

    /** A statement whose n-th executeBatch() (1-based) answers with {@code script.apply(n)}. */
    private static PreparedStatement scriptedStatement(List<Integer> executed, IntFunction<int[]> script) {
        final int[] calls = {0};
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "executeBatch":
                    calls[0]++;
                    int[] answer = script.apply(calls[0]);
                    executed.add(calls[0]);
                    return answer;
                case "toString":
                    return "ScriptedPreparedStatement";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return null;
            }
        };
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, h);
    }

    private static Connection connectionReturning(PreparedStatement ps) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "prepareStatement":
                    return ps;
                case "isClosed":
                    return false;
                case "toString":
                    return "ScriptedConnection";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return null;
            }
        };
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, h);
    }

    private static ClickHouseStruct insertRecord(int id) {
        ClickHouseStruct record = new ClickHouseStruct(0L, "topic", null, 0, System.currentTimeMillis(),
                null, new Struct(ROW).put("id", id), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        record.setDatabase("db");
        record.setGtid(100L + id);
        record.setTs_ms(1767225600000L);
        return record;
    }

    private static List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> segment(int rows) {
        Map<String, Integer> index = new LinkedHashMap<>();
        index.put("id", 1);
        index.put("_version", 2);
        index.put("is_deleted", 3);
        List<ClickHouseStruct> records = new ArrayList<>();
        for (int i = 1; i <= rows; i++) {
            records.add(insertRecord(i));
        }
        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> map = new HashMap<>();
        map.put(new MutablePair<>(INSERT, index), records);
        return Collections.singletonList(map);
    }

    private static Map<String, String> columns() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "Int32");
        m.put("_version", "UInt64");
        m.put("is_deleted", "UInt8");
        return m;
    }

    private static PreparedStatementExecutor executor() {
        return new PreparedStatementExecutor("is_deleted", true, null, "_version", "db", ZoneId.of("UTC"));
    }

    @Test
    @DisplayName("A chunk that fails after an earlier chunk was sent fails the batch and leaves the earlier chunk written")
    public void aFailedChunkLeavesTheEarlierChunksWrittenAndFailsTheBatch() {
        Map<String, String> props = new HashMap<>();
        props.put("buffer.max.records", "1");   // two rows -> two chunks -> two executeBatch() calls
        List<Integer> executed = new ArrayList<>();
        PreparedStatement ps = scriptedStatement(executed, n -> {
            if (n == 2) {
                throw new RuntimeException(new SQLException(
                        "Code: 252. DB::Exception: Too many parts (3001). (TOO_MANY_PARTS)"));
            }
            return new int[]{1};
        });

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                executor().addToPreparedStatementBatch("topic", segment(2), new BlockMetaData(),
                        new ClickHouseSinkConnectorConfig(props), connectionReturning(ps), "t", columns(),
                        DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE));

        assertTrue(String.valueOf(thrown).contains("Code: 252") || String.valueOf(thrown.getCause()).contains("Code: 252"),
                "the ClickHouse error reaches the worker's classifier: " + thrown);
        // The first chunk's executeBatch() returned: its rows are in ClickHouse and
        // nothing rolls them back. This call alone does not retry -- that is the
        // worker's job (spec 03.03 section 3.1) -- so it only proves chunk 1 ran
        // and chunk 2 did not. What a retry of the SAME record list does with
        // chunk 1 is covered by aChunkThatFailsThenSucceedsOnRetrySendsEarlierChunksExactlyOnce()
        // below: since spec 03.06 section 3.5, chunk 1's row is marked applied and
        // is excluded from the retry's regrouping, so it is sent exactly once
        // (previously it was sent again every retry; idempotent under
        // ReplacingMergeTree, additive otherwise).
        assertEquals(Collections.singletonList(1), executed,
                "chunk 1 was executed (its executeBatch() returned) before chunk 2 failed");
    }

    @Test
    @DisplayName("A chunk that fails on the first attempt and succeeds on retry sends the earlier, already-applied chunks exactly once")
    public void aChunkThatFailsThenSucceedsOnRetrySendsEarlierChunksExactlyOnce() throws Exception {
        // Spec 03.06 section 3.5 / FM-03.06-2: a retry re-groups the SAME record
        // list (spec 09.01 section 3.2) through GroupInsertQueryWithBatchRecords,
        // which must skip a record PreparedStatementExecutor already marked
        // applied, instead of handing it to addToPreparedStatementBatch again.
        Map<String, String> props = new HashMap<>();
        props.put("buffer.max.records", "1"); // two rows -> two chunks -> two executeBatch() calls
        List<ClickHouseStruct> records = new ArrayList<>();
        records.add(insertRecord(1));
        records.add(insertRecord(2));

        // Attempt 1: chunk 1 (record 1) succeeds, chunk 2 (record 2) throws.
        List<Integer> executedAttempt1 = new ArrayList<>();
        PreparedStatement failingPs = scriptedStatement(executedAttempt1, n -> {
            if (n == 2) {
                throw new RuntimeException(new SQLException(
                        "Code: 252. DB::Exception: Too many parts (3001). (TOO_MANY_PARTS)"));
            }
            return new int[]{1};
        });
        List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> attempt1Segments =
                new ArrayList<>();
        new GroupInsertQueryWithBatchRecords().groupQueryWithRecords(records, attempt1Segments,
                new HashMap<>(), new ClickHouseSinkConnectorConfig(props), "t", "db",
                connectionReturning(failingPs), columns());

        assertThrows(RuntimeException.class, () ->
                executor().addToPreparedStatementBatch("topic", attempt1Segments, new BlockMetaData(),
                        new ClickHouseSinkConnectorConfig(props), connectionReturning(failingPs), "t", columns(),
                        DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE));
        assertEquals(Collections.singletonList(1), executedAttempt1, "chunk 1 executed; chunk 2 threw");
        assertTrue(records.get(0).isAppliedToClickHouse(),
                "record 1's chunk returned from executeBatch() without throwing: it is durable");
        assertFalse(records.get(1).isAppliedToClickHouse(),
                "record 2's chunk never returned from executeBatch(): it is not durable");

        // Attempt 2 (the retry): the caller keeps the SAME records list and
        // regroups it again (spec 09.01 section 3.2). Record 1 must be excluded
        // from every segment this time.
        List<Integer> executedAttempt2 = new ArrayList<>();
        PreparedStatement succeedingPs = scriptedStatement(executedAttempt2, n -> new int[]{1});
        List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> attempt2Segments =
                new ArrayList<>();
        new GroupInsertQueryWithBatchRecords().groupQueryWithRecords(records, attempt2Segments,
                new HashMap<>(), new ClickHouseSinkConnectorConfig(props), "t", "db",
                connectionReturning(succeedingPs), columns());

        List<ClickHouseStruct> regrouped = new ArrayList<>();
        for (Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> seg : attempt2Segments) {
            for (List<ClickHouseStruct> recs : seg.values()) {
                regrouped.addAll(recs);
            }
        }
        assertEquals(1, regrouped.size(),
                "record 1 is already applied and must not be regrouped into a fresh segment on retry");
        assertTrue(regrouped.get(0) == records.get(1), "the only record carried into the retry is record 2");

        boolean result = executor().addToPreparedStatementBatch("topic", attempt2Segments, new BlockMetaData(),
                new ClickHouseSinkConnectorConfig(props), connectionReturning(succeedingPs), "t", columns(),
                DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE);
        assertTrue(result, "the retry succeeds");
        assertEquals(Collections.singletonList(1), executedAttempt2,
                "the retry executes exactly one chunk: record 1 (already applied) is never resent");
        assertTrue(records.get(1).isAppliedToClickHouse(), "record 2 is now marked applied too");
    }

    @Test
    @Disabled("DEFECT FM-03.06-4: an int[] entry of Statement.EXECUTE_FAILED is only counted in the INFO line; "
            + "the batch is reported written and its offset acknowledged although the driver says a statement failed")
    @DisplayName("A driver result that marks a statement EXECUTE_FAILED fails the batch")
    public void anExecuteFailedEntryFailsTheBatch() {
        List<Integer> executed = new ArrayList<>();
        PreparedStatement ps = scriptedStatement(executed, n -> new int[]{1, Statement.EXECUTE_FAILED});
        boolean written;
        try {
            written = executor().addToPreparedStatementBatch("topic", segment(2), new BlockMetaData(),
                    new ClickHouseSinkConnectorConfig(new HashMap<>()), connectionReturning(ps), "t", columns(),
                    DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE);
        } catch (Exception e) {
            written = false;
        }
        assertFalse(written, "a statement the driver reports as EXECUTE_FAILED is not in ClickHouse; reporting the "
                + "batch written lets its offset be acknowledged past a lost row");
    }
}

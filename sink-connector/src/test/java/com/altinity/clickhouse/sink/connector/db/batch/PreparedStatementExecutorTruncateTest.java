package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A replicated MySQL TRUNCATE (change event {@code op = t}) grouped with DML
 * in one batch (spec 04.05).
 *
 * <p>The TRUNCATE here is never the connector's own decision: it is MySQL's
 * statement, already executed at the source, arriving as a binlog change
 * event that the replica must follow.</p>
 *
 * <p><b>The ordering defect.</b> The grouping stage put the truncation under
 * its own key in the SAME {@code HashMap} as the INSERT template, and the
 * executor iterated that map. Whether the truncation ran before or after the
 * inserts of the same batch therefore depended on the hash of the table name:
 * for one order {@code [INSERT r1, TRUNCATE, INSERT r2]} resurrected r1, for
 * the other it lost r2. Two TRUNCATEs in one batch collapsed onto one key. And
 * the statement was issued against the SOURCE database name carried by the
 * record, which under {@code clickhouse.database.override.map} is not the
 * table's database at all.</p>
 */
public class PreparedStatementExecutorTruncateTest {

    private static final String SOURCE_DB = "db1";
    private static final String TARGET_DB = "db1_replica";

    private static final Schema ROW_SCHEMA = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .build();

    private static Map<String, String> columns() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "Int32");
        return m;
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE.toString(), "true");
        return new ClickHouseSinkConnectorConfig(props);
    }

    private static ClickHouseStruct insert(int id, long offset) {
        ClickHouseStruct record = new ClickHouseStruct(offset, "topic", null, 0,
                System.currentTimeMillis(), null, new Struct(ROW_SCHEMA).put("id", id), null,
                ClickHouseConverter.CDC_OPERATION.CREATE);
        record.setDatabase(SOURCE_DB);
        return record;
    }

    /**
     * The replicated MySQL TRUNCATE change event: no row images.
     * DESTRUCTIVE: a test fixture record only; it reaches a recording JDBC
     * proxy, so no table is ever truncated.
     */
    private static ClickHouseStruct truncateEvent(long offset) {
        ClickHouseStruct record = new ClickHouseStruct(offset, "topic", null, 0,
                System.currentTimeMillis(), null, null, null,
                ClickHouseConverter.CDC_OPERATION.TRUNCATE);
        record.setDatabase(SOURCE_DB);
        return record;
    }

    /** Groups {@code records} for {@code table} and executes them against {@code jdbc}. */
    private static boolean run(String table, List<ClickHouseStruct> records, RecordingJdbc jdbc)
            throws Exception {
        ClickHouseSinkConnectorConfig config = config();
        List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> segments =
                new ArrayList<>();
        new GroupInsertQueryWithBatchRecords().groupQueryWithRecords(
                records, segments, new HashMap<>(), config, table, TARGET_DB, null, columns());
        PreparedStatementExecutor executor = new PreparedStatementExecutor(
                null, false, null, null, TARGET_DB, ZoneId.of("UTC"));
        return executor.addToPreparedStatementBatch("topic", segments, new BlockMetaData(), config,
                jdbc.connection(), table, columns(), DBMetadata.TABLE_ENGINE.MERGE_TREE);
    }

    /** A compact trace: {@code INSERT(id)} per staged row, {@code FLUSH}, {@code EXECUTE <sql>}. */
    private static List<String> trace(RecordingJdbc jdbc) {
        List<String> out = new ArrayList<>();
        for (RecordingJdbc.Event e : jdbc.events) {
            switch (e.kind) {
                case RecordingJdbc.ADD_BATCH:
                    out.add("INSERT(" + e.params.get(1) + ")");
                    break;
                case RecordingJdbc.EXECUTE_BATCH:
                    out.add("FLUSH");
                    break;
                case RecordingJdbc.EXECUTE:
                    out.add("EXECUTE " + e.sql);
                    break;
                default:
                    break;
            }
        }
        return out;
    }

    /**
     * Reproduces the pre-fix keying -- INSERT template pair and truncation marker
     * pair in one {@code HashMap} -- and reports whether the truncation entry
     * iterated FIRST for this table name. Used to pick two table names that
     * cover both hash orders, so the ordering assertion cannot pass by luck.
     */
    private static boolean truncateIteratedFirstUnderOldKeying(String table) {
        Map<String, Integer> insertIndexes = new HashMap<>();
        insertIndexes.put("id", 1);
        MutablePair<String, Map<String, Integer>> insertKey = new MutablePair<>(
                "INSERT INTO `" + table + "`(`id`) VALUES (?)", insertIndexes);
        // DESTRUCTIVE: marker text only, mirroring the old grouping key; it is
        // used to compute a HashMap iteration order and is never executed.
        MutablePair<String, Map<String, Integer>> truncateKey = new MutablePair<>(
                "TRUNCATE TABLE `" + table + "`", new HashMap<>());
        Map<MutablePair<String, Map<String, Integer>>, String> asTheOldCodeKeyedIt = new HashMap<>();
        asTheOldCodeKeyedIt.put(insertKey, "insert");
        asTheOldCodeKeyedIt.put(truncateKey, "truncate");
        return "truncate".equals(asTheOldCodeKeyedIt.values().iterator().next());
    }

    /** Two table names whose old-keying hash orders differ: [truncate-first, truncate-last]. */
    private static String[] tablesCoveringBothHashOrders() {
        String truncateFirst = null;
        String truncateLast = null;
        for (int i = 0; i < 10_000 && (truncateFirst == null || truncateLast == null); i++) {
            String table = "orders_" + i;
            if (truncateIteratedFirstUnderOldKeying(table)) {
                truncateFirst = truncateFirst == null ? table : truncateFirst;
            } else {
                truncateLast = truncateLast == null ? table : truncateLast;
            }
        }
        assertNotNull(truncateFirst, "precondition: a table name whose TRUNCATE hashed first");
        assertNotNull(truncateLast, "precondition: a table name whose TRUNCATE hashed last");
        return new String[]{truncateFirst, truncateLast};
    }

    // DESTRUCTIVE: the tests below drive replicated TRUNCATE events into a
    // recording JDBC proxy; nothing is executed against any database.
    @Test
    @DisplayName("INSERT(r1) -> TRUNCATE -> INSERT(r2) is applied in binlog order for both hash orders")
    public void truncateIsAppliedAtItsBinlogPositionForBothHashOrders() throws Exception {
        for (String table : tablesCoveringBothHashOrders()) {
            RecordingJdbc jdbc = new RecordingJdbc();

            boolean result = run(table, new ArrayList<>(Arrays.asList(
                    insert(1, 1), truncateEvent(2), insert(2, 3))), jdbc);

            assertTrue(result, "the batch is written");
            // DESTRUCTIVE: expected-trace text; asserts the recorded ORDER of a
            // replicated TRUNCATE relative to the rows around it.
            assertEquals(Arrays.asList(
                            "INSERT(1)", "FLUSH",
                            "EXECUTE TRUNCATE TABLE `" + TARGET_DB + "`.`" + table + "`",
                            "INSERT(2)", "FLUSH"),
                    trace(jdbc),
                    "table " + table + ": r1 must reach ClickHouse before the truncation and r2 after it; "
                            + "events: " + jdbc.events);
        }
    }

    @Test
    @DisplayName("The truncation targets the executor's (resolved target) database, not the source database")
    public void truncateTargetsTheExecutorDatabaseNotTheSourceDatabase() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();

        run("orders", new ArrayList<>(Arrays.asList(insert(1, 1), truncateEvent(2))), jdbc);

        List<RecordingJdbc.Event> executed = jdbc.ofKind(RecordingJdbc.EXECUTE);
        assertEquals(1, executed.size(), "exactly one truncate: " + jdbc.events);
        // DESTRUCTIVE: expected statement text; under a database override map
        // the record's source database is not the table's database at all.
        assertEquals("TRUNCATE TABLE `" + TARGET_DB + "`.`orders`", executed.get(0).sql,
                "the record carries the SOURCE database (" + SOURCE_DB + "); the statement must "
                        + "name the executor's target database");
    }

    @Test
    @DisplayName("Two TRUNCATEs in one batch are two segments; both run, in order")
    public void twoTruncatesInOneBatchAreBothApplied() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();

        run("orders", new ArrayList<>(Arrays.asList(
                insert(1, 1), truncateEvent(2), insert(2, 3), truncateEvent(4), insert(3, 5))), jdbc);

        String truncate = "EXECUTE TRUNCATE TABLE `" + TARGET_DB + "`.`orders`";
        // DESTRUCTIVE: expected-trace text only (recording proxy).
        assertEquals(Arrays.asList(
                        "INSERT(1)", "FLUSH", truncate, "INSERT(2)", "FLUSH", truncate, "INSERT(3)", "FLUSH"),
                trace(jdbc),
                "two equal TRUNCATE keys must not collapse onto one; events: " + jdbc.events);
    }

    /**
     * Spec 04.05 section 3 step 2: a TRUNCATE that ClickHouse keeps refusing
     * fails the batch. Previously {@code truncateTable} swallowed the final
     * failure, the batch continued, {@code addToPreparedStatementBatch}
     * answered {@code true} and the batch was acknowledged -- with the
     * pre-truncate rows still in ClickHouse.
     */
    // DESTRUCTIVE: the test below drives a replicated TRUNCATE event into a
    // recording JDBC proxy that REFUSES it; nothing is executed anywhere.
    @Test
    @DisplayName("A truncation that ClickHouse refuses fails the batch; the result is never true")
    public void truncateRefusedByClickHouseFailsTheBatch() {
        RecordingJdbc jdbc = new RecordingJdbc();
        // Refuse only the qualified TRUNCATE statement itself (marker: the
        // `.`table` part); no data operation is performed by the recorder.
        jdbc.failPrepareContaining = "`.`orders`";

        boolean[] result = {false};
        // DESTRUCTIVE: assertion only -- the recorder throws on prepare, so
        // no table is truncated; we assert the batch FAILS as a consequence.
        RuntimeException e = assertThrows(RuntimeException.class, () -> {
            result[0] = run("orders", new ArrayList<>(Arrays.asList(
                    insert(1, 1), truncateEvent(2), insert(2, 3))), jdbc);
        }, "a refused TRUNCATE must fail the batch, never let it be acknowledged");

        assertTrue(!result[0], "the batch must never be reported as written");
        // DESTRUCTIVE: assertion text only; counts refused prepare attempts.
        assertTrue(jdbc.prepareFailures >= 1, "the TRUNCATE must have been attempted");
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertTrue(String.valueOf(root.getMessage()).contains("refused to prepare"),
                "the ClickHouse refusal must be the root cause; was: " + root);
        assertTrue(jdbc.ofKind(RecordingJdbc.ADD_BATCH).size() == 1,
                "r1 was staged and flushed before the truncate; r2 must NOT have been written: "
                        + jdbc.events);
    }
}

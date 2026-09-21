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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A replicated MySQL TRUNCATE (change event {@code op = t}) grouped with DML
 * in one batch (spec 04.05).
 *
 * <p>The TRUNCATE here is never the connector's own decision: it is MySQL's
 * statement, already executed at the source, arriving as a binlog change
 * event that the replica must follow.</p>
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
        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> grouped = new HashMap<>();
        new GroupInsertQueryWithBatchRecords().groupQueryWithRecords(
                records, grouped, new HashMap<>(), config, table, TARGET_DB, null, columns());
        PreparedStatementExecutor executor = new PreparedStatementExecutor(
                null, false, null, null, TARGET_DB, ZoneId.of("UTC"));
        return executor.addToPreparedStatementBatch("topic", grouped, new BlockMetaData(), config,
                jdbc.connection(), table, columns(), DBMetadata.TABLE_ENGINE.MERGE_TREE);
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
    @DisplayName("A TRUNCATE that ClickHouse refuses fails the batch; the result is never true")
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
    }
}

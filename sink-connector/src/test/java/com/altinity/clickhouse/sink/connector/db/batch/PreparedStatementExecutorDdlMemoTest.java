package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.model.BlockMetaData;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 04.06 section 3.5 item 2, end to end: a batch holding records read
 * before and after an ADD COLUMN, grouped and bound through the memoised
 * path against the recording JDBC surface. Two statements are prepared; a
 * pre-ALTER row carries no parameter for the new column; a post-ALTER row
 * binds it, including an explicit NULL.
 */
public class PreparedStatementExecutorDdlMemoTest {

    private static final Schema PRE = SchemaBuilder.struct().name("srv.db.orders.pre")
            .field("id", Schema.INT32_SCHEMA)
            .field("name", Schema.OPTIONAL_STRING_SCHEMA)
            .build();

    private static final Schema POST = SchemaBuilder.struct().name("srv.db.orders.post")
            .field("id", Schema.INT32_SCHEMA)
            .field("name", Schema.OPTIONAL_STRING_SCHEMA)
            .field("note", Schema.OPTIONAL_STRING_SCHEMA)
            .build();

    private static Map<String, String> columns() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "Int32");
        m.put("name", "Nullable(String)");
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

    private static ClickHouseStruct insert(Schema schema, int id, String name, String note) {
        Struct after = new Struct(schema).put("id", id).put("name", name);
        if (schema == POST) {
            after.put("note", note);
        }
        ClickHouseStruct r = new ClickHouseStruct(id, "srv.db.orders", null, 0, System.currentTimeMillis(),
                null, after, null, ClickHouseConverter.CDC_OPERATION.CREATE);
        r.setDatabase("db");
        r.setTs_ms(1_700_000_000_000L + id);
        r.setFile("binary.000001");
        r.setPos(1000L + id);
        return r;
    }

    @Test
    @DisplayName("Mixed pre/post-ALTER batch: two statements, each row bound through its own template")
    public void mixedPreAndPostAlterBatchBindsEachRowThroughItsOwnTemplate() throws Exception {
        ClickHouseSinkConnectorConfig config = config();
        Map<String, String> columns = columns();
        List<ClickHouseStruct> batch = new ArrayList<>();
        batch.add(insert(PRE, 1, "a", null));
        batch.add(insert(POST, 2, "b", "n2"));
        batch.add(insert(POST, 3, null, null));
        batch.add(insert(PRE, 4, "d", null));
        batch.add(insert(POST, 5, "e", "n5"));

        RecordingJdbc jdbc = new RecordingJdbc();
        Connection conn = jdbc.connection();
        List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> segments = new ArrayList<>();
        new GroupInsertQueryWithBatchRecords("_version", null, "is_deleted").groupQueryWithRecords(
                batch, segments, new HashMap<TopicPartition, Long>(), config, "orders", "db", conn, columns);
        assertEquals(1, segments.size());
        assertEquals(2, segments.get(0).size(), "one template per record shape");

        PreparedStatementExecutor ex = new PreparedStatementExecutor("is_deleted", true, null, "_version",
                "db", ZoneId.of("UTC"), () -> Arrays.asList("id"));
        assertTrue(ex.addToPreparedStatementBatch("srv.db.orders", segments, new BlockMetaData(), config, conn,
                "orders", columns, DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE));

        List<RecordingJdbc.Event> prepares = jdbc.ofKind(RecordingJdbc.PREPARE);
        assertEquals(2, prepares.size(), "one PreparedStatement per template");
        List<RecordingJdbc.Event> rows = jdbc.ofKind(RecordingJdbc.ADD_BATCH);
        assertEquals(5, rows.size(), "every row bound exactly once");
        assertEquals(2, jdbc.ofKind(RecordingJdbc.EXECUTE_BATCH).size());

        int preRows = 0;
        int postRows = 0;
        for (RecordingJdbc.Event row : rows) {
            boolean postTemplate = row.sql.contains("`note`");
            if (postTemplate) {
                postRows++;
                assertEquals(5, row.params.size(), "id, name, note, _version, is_deleted: " + row);
                assertTrue(row.params.containsKey(3), "note has a placeholder on the post-ALTER template");
            } else {
                preRows++;
                assertEquals(4, row.params.size(), "id, name, _version, is_deleted: " + row);
                assertFalse(row.sql.contains("`note`"));
            }
        }
        assertEquals(2, preRows);
        assertEquals(3, postRows);

        // The row with a NULL name and NULL note binds explicit NULLs, never
        // the previous row's values (Spec 07.07 / 04.03).
        RecordingJdbc.Event nullRow = null;
        for (RecordingJdbc.Event row : rows) {
            if (Integer.valueOf(3).equals(row.params.get(1))) {
                nullRow = row;
            }
        }
        assertTrue(nullRow != null, "row id=3 was bound");
        assertTrue(nullRow.params.containsKey(2) && nullRow.params.get(2) == null, "name bound as NULL");
        assertTrue(nullRow.params.containsKey(3) && nullRow.params.get(3) == null, "note bound as NULL");
        for (RecordingJdbc.Event row : rows) {
            if (Integer.valueOf(2).equals(row.params.get(1))) {
                assertEquals("b", row.params.get(2));
                assertEquals("n2", row.params.get(3));
            }
            if (Integer.valueOf(1).equals(row.params.get(1))) {
                assertEquals("a", row.params.get(2));
                // Pre-ALTER template: id=1, name=2, _version=3, is_deleted=4 -- no note index.
                assertEquals(4, row.params.size(), "pre-ALTER row has no note index: " + row);
                assertFalse(row.sql.contains("`note`"));
            }
        }
    }
}

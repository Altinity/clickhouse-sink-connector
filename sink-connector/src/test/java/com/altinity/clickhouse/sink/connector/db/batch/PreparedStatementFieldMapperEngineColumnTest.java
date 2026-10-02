package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 04.02 section 3.1 / 05.04 section 3: an engine column with a
 * non-default name ({@code ReplacingMergeTree(ver)},
 * {@code CollapsingMergeTree(sgn)}) is bound like any other, and a table
 * column the INSERT has no placeholder for is refused rather than skipped.
 *
 * <p>The binder previously bound {@code ver} / {@code sgn} only "if present in
 * the index map" and otherwise moved on at DEBUG. With the query built from
 * the constants alone the column was never in that map, so every row was
 * written with {@code ver = 0} (a redelivered older row wins every merge)
 * and {@code sgn = 0} (no +1/-1 pair ever collapses).</p>
 */
public class PreparedStatementFieldMapperEngineColumnTest {

    private static final Schema ROW_SCHEMA = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .build();

    private static PreparedStatement recordingStatement(Map<Integer, Object> bound) {
        InvocationHandler h = (proxy, method, args) -> {
            String name = method.getName();
            if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer) {
                bound.put((Integer) args[0], "setNull".equals(name) ? null : args[1]);
                return null;
            }
            switch (name) {
                case "toString":
                    return "RecordingPreparedStatement";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return null;
            }
        };
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, h);
    }

    private static ClickHouseStruct insert() {
        ClickHouseStruct record = new ClickHouseStruct(3L, "topic", null, 0, System.currentTimeMillis(),
                null, new Struct(ROW_SCHEMA).put("id", 1), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        record.setDatabase("db1");
        record.setGtid(77L);
        record.setTs_ms(1767225600000L);
        return record;
    }

    private static ClickHouseSinkConnectorConfig config(boolean historyMode) {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString(),
                Boolean.toString(historyMode));
        return new ClickHouseSinkConnectorConfig(props);
    }

    private static Map<String, String> rmtColumns() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "Int32");
        m.put("ver", "UInt64");
        m.put("removed", "UInt8");
        return m;
    }

    private static Map<String, Integer> indexes(String... columns) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String c : columns) {
            m.put(c, m.size() + 1);
        }
        return m;
    }

    @Test
    @DisplayName("ReplacingMergeTree(ver): the version is bound at ver's placeholder with setLong")
    public void testNonStandardVersionColumnIsBound() throws Exception {
        ClickHouseStruct record = insert();
        Map<Integer, Object> bound = new HashMap<>();
        Map<String, Integer> indexMap = indexes("id", "ver", "removed");

        new PreparedStatementFieldMapper("removed", true, null, "ver", "db1", ZoneId.of("UTC"))
                .insertPreparedStatement(indexMap, recordingStatement(bound), ROW_SCHEMA.fields(), record,
                        record.getAfterStruct(), false, config(false), rmtColumns(),
                        DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "orders");

        assertTrue(record.getVersion() > 0, "precondition: a version was derived");
        assertEquals(record.getVersion(), bound.get(indexMap.get("ver")),
                "ver must carry the record's version; bound: " + bound);
        assertEquals(0, bound.get(indexMap.get("removed")), "a live row has removed = 0");
    }

    @Test
    @DisplayName("A version column in the table with no placeholder in the INSERT is refused, not skipped")
    public void testVersionColumnWithoutPlaceholderFailsLoudly() {
        ClickHouseStruct record = insert();
        Map<Integer, Object> bound = new HashMap<>();
        // The INSERT built from the default constants alone: no ver, no removed.
        Map<String, Integer> indexMap = indexes("id");

        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                new PreparedStatementFieldMapper("removed", true, null, "ver", "db1", ZoneId.of("UTC"))
                        .insertPreparedStatement(indexMap, recordingStatement(bound), ROW_SCHEMA.fields(),
                                record, record.getAfterStruct(), false, config(false), rmtColumns(),
                                DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "orders"),
                "writing the row would store ver = 0, and a redelivered older row would win every merge");
        assertTrue(e.getMessage().contains("'ver'") || e.getMessage().contains("'removed'"), e.getMessage());
    }

    @Test
    @DisplayName("A delete column in the table with no placeholder in the INSERT is refused unless ignore_delete")
    public void testDeleteColumnWithoutPlaceholderFailsLoudly() {
        ClickHouseStruct record = insert();
        Map<String, Integer> indexMap = indexes("id", "ver");

        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                new PreparedStatementFieldMapper("removed", true, null, "ver", "db1", ZoneId.of("UTC"))
                        .insertPreparedStatement(indexMap, recordingStatement(new HashMap<>()),
                                ROW_SCHEMA.fields(), record, record.getAfterStruct(), false, config(false),
                                rmtColumns(), DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "orders"));
        assertTrue(e.getMessage().contains("'removed'"), e.getMessage());
    }

    @Test
    @DisplayName("CollapsingMergeTree(sgn): the sign is bound at sgn's placeholder; a missing placeholder is refused")
    public void testNonStandardSignColumnIsBoundOrRefused() throws Exception {
        Map<String, String> cmtColumns = new LinkedHashMap<>();
        cmtColumns.put("id", "Int32");
        cmtColumns.put("sgn", "Int8");
        ClickHouseStruct record = insert();
        Map<Integer, Object> bound = new HashMap<>();
        Map<String, Integer> indexMap = indexes("id", "sgn");

        new PreparedStatementFieldMapper(null, false, "sgn", null, "db1", ZoneId.of("UTC"))
                .insertPreparedStatement(indexMap, recordingStatement(bound), ROW_SCHEMA.fields(), record,
                        record.getAfterStruct(), false, config(false), cmtColumns,
                        DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE, "orders");
        assertEquals(1, bound.get(indexMap.get("sgn")), "an INSERT is a +1 row; bound: " + bound);

        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                new PreparedStatementFieldMapper(null, false, "sgn", null, "db1", ZoneId.of("UTC"))
                        .insertPreparedStatement(indexes("id"), recordingStatement(new HashMap<>()),
                                ROW_SCHEMA.fields(), record, record.getAfterStruct(), false, config(false),
                                cmtColumns, DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE, "orders"),
                "writing the row would store sgn = 0, so no +1/-1 pair ever collapses");
        assertTrue(e.getMessage().contains("'sgn'"), e.getMessage());
    }

    /**
     * Regression guard: in replication-history mode the SCD Type 2 statement
     * hardcodes the engine columns as SQL literals and records no index for
     * them, so their absence from the index map is by design.
     */
    @Test
    @DisplayName("Replication-history mode keeps binding a statement whose engine columns are SQL literals")
    public void testHistoryModeStatementWithLiteralEngineColumnsIsAccepted() throws Exception {
        ClickHouseStruct record = insert();
        Map<String, String> historyColumns = new LinkedHashMap<>();
        historyColumns.put("id", "Int32");
        historyColumns.put("_version", "UInt64");
        historyColumns.put("is_deleted", "UInt8");

        new PreparedStatementFieldMapper("is_deleted", true, null, "_version", "db1", ZoneId.of("UTC"))
                .insertPreparedStatement(indexes("id"), recordingStatement(new HashMap<>()),
                        ROW_SCHEMA.fields(), record, record.getAfterStruct(), false, config(true),
                        historyColumns, DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "orders");
    }

    // ---- Spec 08.01 section 3.2: a table WITHOUT a delete column refuses only its delete markers ----

    /** An old-style {@code ReplacingMergeTree(ver)} target: version column, no delete column at all. */
    private static Map<String, String> oldStyleRmtColumns() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "Int32");
        m.put("ver", "UInt64");
        return m;
    }

    private static ClickHouseStruct delete() {
        ClickHouseStruct record = new ClickHouseStruct(4L, "topic", null, 0, System.currentTimeMillis(),
                new Struct(ROW_SCHEMA).put("id", 1), null, null, ClickHouseConverter.CDC_OPERATION.DELETE);
        record.setDatabase("db1");
        record.setGtid(78L);
        record.setTs_ms(1767225600000L);
        return record;
    }

    private static ClickHouseStruct update() {
        ClickHouseStruct record = new ClickHouseStruct(5L, "topic", null, 0, System.currentTimeMillis(),
                new Struct(ROW_SCHEMA).put("id", 1), new Struct(ROW_SCHEMA).put("id", 2), null,
                ClickHouseConverter.CDC_OPERATION.UPDATE);
        record.setDatabase("db1");
        record.setGtid(79L);
        record.setTs_ms(1767225600000L);
        return record;
    }

    private static ClickHouseSinkConnectorConfig configIgnoringDeletes() {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.IGNORE_DELETE.toString(), "true");
        return new ClickHouseSinkConnectorConfig(props);
    }

    @Test
    @DisplayName("A DELETE for a ReplacingMergeTree table with no delete column is refused")
    public void testDeleteForTableWithoutDeleteColumnIsRefused() {
        ClickHouseStruct record = delete();

        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                new PreparedStatementFieldMapper("removed", false, null, "ver", "db1", ZoneId.of("UTC"))
                        .insertPreparedStatement(indexes("id", "ver"), recordingStatement(new HashMap<>()),
                                ROW_SCHEMA.fields(), record, record.getBeforeStruct(), true, config(false),
                                oldStyleRmtColumns(), DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "orders"),
                "written as is, the before image becomes a LIVE row with a higher version and resurrects the key");
        assertTrue(e.getMessage().startsWith("A DELETE"), e.getMessage());
        assertTrue(e.getMessage().contains("db1.orders"), e.getMessage());
        assertTrue(e.getMessage().contains("'removed'"), e.getMessage());
    }

    @Test
    @DisplayName("An INSERT to the same table is written: only the delete marker is unrepresentable")
    public void testInsertForTableWithoutDeleteColumnIsAccepted() throws Exception {
        ClickHouseStruct record = insert();
        Map<Integer, Object> bound = new HashMap<>();

        new PreparedStatementFieldMapper("removed", false, null, "ver", "db1", ZoneId.of("UTC"))
                .insertPreparedStatement(indexes("id", "ver"), recordingStatement(bound), ROW_SCHEMA.fields(),
                        record, record.getAfterStruct(), false, config(false), oldStyleRmtColumns(),
                        DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "orders");

        assertEquals(1, bound.get(1), "id bound at its placeholder: " + bound);
        assertTrue(bound.containsKey(2), "ver bound at its placeholder: " + bound);
    }

    @Test
    @DisplayName("With ignore_delete=true the DELETE is not refused (deletes are not replicated by choice)")
    public void testDeleteForTableWithoutDeleteColumnIsAcceptedWhenDeletesAreIgnored() throws Exception {
        ClickHouseStruct record = delete();

        new PreparedStatementFieldMapper("removed", false, null, "ver", "db1", ZoneId.of("UTC"))
                .insertPreparedStatement(indexes("id", "ver"), recordingStatement(new HashMap<>()),
                        ROW_SCHEMA.fields(), record, record.getBeforeStruct(), true, configIgnoringDeletes(),
                        oldStyleRmtColumns(), DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "orders");
    }

    @Test
    @DisplayName("The sorting-key relocation tombstone is a delete marker too: refused without a delete column")
    public void testRelocationTombstoneForTableWithoutDeleteColumnIsRefused() {
        ClickHouseStruct record = update();

        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                new PreparedStatementFieldMapper("removed", false, null, "ver", "db1", ZoneId.of("UTC"))
                        .insertTombstonePreparedStatement(indexes("id", "ver"), recordingStatement(new HashMap<>()),
                                ROW_SCHEMA.fields(), record, record.getBeforeStruct(), config(false),
                                oldStyleRmtColumns(), DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "orders"),
                "the tombstone would reach ClickHouse as a LIVE row at the old key");
        assertTrue(e.getMessage().contains("tombstone"), e.getMessage());
        assertTrue(e.getMessage().contains("'removed'"), e.getMessage());
    }
}

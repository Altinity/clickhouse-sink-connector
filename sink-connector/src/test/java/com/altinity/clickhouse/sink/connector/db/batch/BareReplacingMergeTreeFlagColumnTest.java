package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.QueryFormatter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.altinity.clickhouse.sink.connector.common.ClickHouseErrorClassifier;
import com.altinity.clickhouse.sink.connector.common.ClickHouseErrorClassifier.ErrorCategory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A table whose engine clause has no delete
 * argument, {@code ReplacingMergeTree(_version)}, but which has an
 * {@code is_deleted} or {@code _sign} column, was written with sign-style
 * values (+1 live, -1 delete) into the column the configuration NAMED. The
 * engine never reads that column, so every live row read as deleted and
 * nothing was deleted by the engine.
 *
 * <p>The delete semantics come from the live engine clause only (system.tables
 * engine_full). A bare engine has none, so the connector must not write a
 * flag into a name it guessed: live rows are written as plain rows (the
 * column keeps its DEFAULT), and a DELETE is refused loudly with the rebuild a
 * human must run, because ALTER cannot change an engine's arguments.</p>
 */
public class BareReplacingMergeTreeFlagColumnTest {

    private static final Schema ROW_SCHEMA = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .build();

    /** A bare ReplacingMergeTree(_version) target carrying the flag-named columns. */
    private static Map<String, String> bareTableColumns() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "Int32");
        m.put("_version", "UInt64");
        m.put("is_deleted", "UInt8");
        m.put("_sign", "Int8");
        return m;
    }

    private static List<Field> recordFields() {
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("id", 0, Schema.INT32_SCHEMA));
        return fields;
    }

    private static PreparedStatement recordingStatement(Map<Integer, Object> bound) {
        InvocationHandler h = (proxy, method, args) -> {
            String name = method.getName();
            if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer) {
                bound.put((Integer) args[0], args[1]);
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

    private static ClickHouseStruct record(ClickHouseConverter.CDC_OPERATION op) {
        ClickHouseStruct record = new ClickHouseStruct(3L, "topic", null, 0, System.currentTimeMillis(),
                op == ClickHouseConverter.CDC_OPERATION.DELETE ? new Struct(ROW_SCHEMA).put("id", 1) : null,
                new Struct(ROW_SCHEMA).put("id", 1), null, op);
        record.setDatabase("db1");
        record.setGtid(77L);
        record.setTs_ms(1767225600000L);
        return record;
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString(), "false");
        props.put("database.connectionTimeZone", "UTC");
        props.put("clickhouse.datetime.timezone", "UTC");
        return new ClickHouseSinkConnectorConfig(props);
    }

    private static Map<String, Integer> indexes(String... columns) {
        Map<String, Integer> m = new HashMap<>();
        int i = 1;
        for (String c : columns) {
            m.put(c, i++);
        }
        return m;
    }

    @Test
    @DisplayName("an INSERT into a bare engine omits its flag columns so ClickHouse DEFAULT applies")
    public void bareEngineInsertOmitsFlagColumns() {
        MutablePair<String, Map<String, Integer>> response =
                new QueryFormatter().getInsertQueryUsingInputFunction("orders", recordFields(),
                        bareTableColumns(), false, false, null, "db1", null, null, "_version", null, false);

        assertFalse(response.left.contains("is_deleted"),
                "is_deleted must not be written by a bare engine; query: " + response.left);
        assertFalse(response.left.contains("_sign"),
                "_sign must not be written by a bare engine; query: " + response.left);
        assertFalse(response.right.containsKey("is_deleted"), "placeholders: " + response.right);
        assertFalse(response.right.containsKey("_sign"), "placeholders: " + response.right);
    }

    @Test
    @DisplayName("a live row into a bare engine binds no flag value (no 1 into is_deleted)")
    public void bareEngineLiveRowBindsNoFlag() throws Exception {
        Map<Integer, Object> bound = new HashMap<>();
        ClickHouseStruct record = record(ClickHouseConverter.CDC_OPERATION.CREATE);

        new PreparedStatementFieldMapper(null, false, null, "_version", "db1", ZoneId.of("UTC"))
                .insertPreparedStatement(indexes("id", "_version"), recordingStatement(bound), ROW_SCHEMA.fields(),
                        record, record.getAfterStruct(), false, config(), bareTableColumns(),
                        DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "orders");

        assertEquals(2, bound.size(), "only id and _version are bound; bound: " + bound);
    }

    @Test
    @DisplayName("a batched INSERT into a bare engine names no flag column, whatever the configured default")
    public void bareEngineBatchInsertOmitsFlagColumns() {
        // The batch path (the one production uses) must not adopt the configured default
        // replacingmergetree.delete.column (is_deleted in config()) for a bare engine.
        ClickHouseStruct record = record(ClickHouseConverter.CDC_OPERATION.CREATE);
        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> queryToRecords = new LinkedHashMap<>();

        new GroupInsertQueryWithBatchRecords("_version", null, null).updateQueryToRecordsMap(
                record, record.getAfterModifiedFields(), queryToRecords, "orders", config(), bareTableColumns());

        assertEquals(1, queryToRecords.size(), "one template for one table");
        MutablePair<String, Map<String, Integer>> template = queryToRecords.keySet().iterator().next();
        assertFalse(template.left.contains("is_deleted"),
                "is_deleted must not be written by a bare engine; query: " + template.left);
        assertFalse(template.left.contains("_sign"),
                "_sign must not be written by a bare engine; query: " + template.left);
        assertFalse(template.right.containsKey("is_deleted"), "placeholders: " + template.right);
        assertFalse(template.right.containsKey("_sign"), "placeholders: " + template.right);
    }

    @Test
    @DisplayName("a DELETE into a bare engine is refused with the rebuild a human must run")
    public void bareEngineDeleteRefusedWithRebuild() {
        ClickHouseStruct record = record(ClickHouseConverter.CDC_OPERATION.DELETE);

        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                new PreparedStatementFieldMapper(null, false, null, "_version", "db1", ZoneId.of("UTC"))
                        .insertPreparedStatement(indexes("id", "_version"), recordingStatement(new HashMap<>()),
                                ROW_SCHEMA.fields(), record, record.getBeforeStruct(), true, config(),
                                bareTableColumns(), DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "orders"));
        assertTrue(e.getMessage().startsWith("A DELETE"), e.getMessage());
        assertTrue(e.getMessage().contains("db1.orders"), e.getMessage());
        assertTrue(e.getMessage().contains("ENGINE = ReplacingMergeTree(_version, is_deleted)"), e.getMessage());
        assertTrue(e.getMessage().contains("EXCHANGE TABLES"), e.getMessage());
        assertTrue(e.getMessage().contains("cannot change an engine"), e.getMessage());
    }

    @Test
    @DisplayName("the bare-engine refusal is terminal, so it stops the task instead of being retried")
    public void bareEngineRefusalIsTerminal() {
        ClickHouseStruct record = record(ClickHouseConverter.CDC_OPERATION.DELETE);

        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                new PreparedStatementFieldMapper(null, false, null, "_version", "db1", ZoneId.of("UTC"))
                        .insertPreparedStatement(indexes("id", "_version"), recordingStatement(new HashMap<>()),
                                ROW_SCHEMA.fields(), record, record.getBeforeStruct(), true, config(),
                                bareTableColumns(), DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "orders"));

        assertEquals(ErrorCategory.FATAL, ClickHouseErrorClassifier.classify(e),
                "the refusal must not be classified retriable: " + e.getMessage());
        assertEquals(ErrorCategory.FATAL,
                ClickHouseErrorClassifier.classify(new RuntimeException("batch failed", e)),
                "a wrapped refusal must stay FATAL");
    }

    @Test
    @DisplayName("Regression: a two-argument engine still binds 1 for a DELETE and 0 for a live row")
    public void twoArgumentEngineStillBindsDeleteFlag() throws Exception {
        Map<String, String> columns = bareTableColumns();
        ClickHouseStruct delete = record(ClickHouseConverter.CDC_OPERATION.DELETE);
        Map<Integer, Object> boundDelete = new HashMap<>();
        new PreparedStatementFieldMapper("is_deleted", true, null, "_version", "db1", ZoneId.of("UTC"))
                .insertPreparedStatement(indexes("id", "_version", "is_deleted"), recordingStatement(boundDelete),
                        ROW_SCHEMA.fields(), delete, delete.getBeforeStruct(), true, config(), columns,
                        DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "orders");
        assertEquals(1, boundDelete.get(3), "a DELETE sets is_deleted = 1; bound: " + boundDelete);

        ClickHouseStruct live = record(ClickHouseConverter.CDC_OPERATION.CREATE);
        Map<Integer, Object> boundLive = new HashMap<>();
        new PreparedStatementFieldMapper("is_deleted", true, null, "_version", "db1", ZoneId.of("UTC"))
                .insertPreparedStatement(indexes("id", "_version", "is_deleted"), recordingStatement(boundLive),
                        ROW_SCHEMA.fields(), live, live.getAfterStruct(), false, config(), columns,
                        DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "orders");
        assertEquals(0, boundLive.get(3), "a live row sets is_deleted = 0; bound: " + boundLive);
    }
}

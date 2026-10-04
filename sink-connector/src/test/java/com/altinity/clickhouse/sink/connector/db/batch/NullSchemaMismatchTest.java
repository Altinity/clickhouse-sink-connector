package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 07.07 section 3.2.3: a source NULL for a ClickHouse column that cannot
 * store NULL is a schema mismatch. The NULL is still bound as NULL (never a
 * stand-in such as {@code []}, {@code 0} or the column DEFAULT), and the
 * mismatch is reported once per column, naming the column.
 *
 * <p>The source shape is a MySQL generated column of type JSON
 * ({@code versions json GENERATED ALWAYS AS (json_extract(doc, '$.items[*].v')) STORED})
 * that MySQL computes as NULL when the path matches nothing, replicated into a
 * ClickHouse {@code Array(Int64)} column. Debezium delivers it as an optional
 * STRING named {@code io.debezium.data.Json}. ClickHouse refuses the NULL with
 * {@code Code: 53 Cannot insert NULL value into a column of type 'Array(Int64)'},
 * a message that does not name the column.</p>
 */
public class NullSchemaMismatchTest {

    private static final Schema ROW = SchemaBuilder.struct()
            .field("id", Schema.INT64_SCHEMA)
            .field("versions", SchemaBuilder.string().optional().name("io.debezium.data.Json").build())
            .field("note", Schema.OPTIONAL_STRING_SCHEMA)
            .field("qty", Schema.OPTIONAL_INT64_SCHEMA)
            .build();

    /** One recorded driver call: method name, parameter index, bound value. */
    private static final class Call {
        final String method;
        final int index;
        final Object value;

        Call(String method, int index, Object value) {
            this.method = method;
            this.index = index;
            this.value = value;
        }
    }

    private static PreparedStatement recording(List<Call> calls) {
        InvocationHandler h = (proxy, method, args) -> {
            String name = method.getName();
            if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer) {
                calls.add(new Call(name, (Integer) args[0], args[1]));
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
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, h);
    }

    private static List<Call> callsAt(List<Call> calls, int index) {
        List<Call> out = new ArrayList<>();
        for (Call c : calls) {
            if (c.index == index) {
                out.add(c);
            }
        }
        return out;
    }

    private static List<Call> bind(Struct after, Map<String, String> columns, String table) throws Exception {
        ClickHouseStruct record = new ClickHouseStruct(
                0L, "topic", null, 0, System.currentTimeMillis(),
                null, after, null, ClickHouseConverter.CDC_OPERATION.CREATE);
        record.setDatabase("db");
        Map<String, Integer> indexMap = new LinkedHashMap<>();
        int i = 1;
        for (String column : columns.keySet()) {
            indexMap.put(column, i++);
        }
        List<Call> calls = new ArrayList<>();
        new PreparedStatementFieldMapper("is_deleted", true, null, "_version", "db", ZoneId.of("UTC"))
                .insertPreparedStatement(indexMap, recording(calls), after.schema().fields(),
                        record, after, false, new ClickHouseSinkConnectorConfig(new HashMap<>()),
                        columns, DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, table);
        return calls;
    }

    private static Map<String, String> columns() {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("id", "Int64");
        columns.put("versions", "Array(Int64)");
        columns.put("note", "Nullable(String)");
        columns.put("qty", "Int64");
        return columns;
    }

    private static boolean reported(String key) {
        return PreparedStatementFieldMapper.REPORTED_NULL_SCHEMA_MISMATCH_COLUMNS.contains(key);
    }

    @Test
    @DisplayName("A source NULL for an Array column is bound as NULL, never as [] or another stand-in")
    public void nullIntoArrayColumnIsBoundAsNullNotSubstituted() throws Exception {
        Struct after = new Struct(ROW).put("id", 1L).put("versions", null).put("note", "x").put("qty", 1L);

        List<Call> calls = bind(after, columns(), "documents_a");

        List<Call> versions = callsAt(calls, 2);
        assertEquals(1, versions.size(), "exactly one binding for the column");
        assertEquals("setNull", versions.get(0).method,
                "the source value is NULL; binding [] would store a value the source never had");
    }

    @Test
    @DisplayName("A source NULL for a column that cannot store NULL is reported as a schema mismatch naming the column")
    public void nullIntoNonNullableColumnsIsReportedAsSchemaMismatch() throws Exception {
        Struct after = new Struct(ROW).put("id", 1L).put("versions", null).put("note", null).put("qty", null);

        List<Call> calls = bind(after, columns(), "documents_b");

        assertTrue(reported("db.documents_b.versions"), "Array(Int64) cannot store NULL");
        assertTrue(reported("db.documents_b.qty"), "Int64 cannot store NULL");
        assertFalse(reported("db.documents_b.note"), "Nullable(String) stores the NULL; no mismatch");
        for (int index = 2; index <= 4; index++) {
            List<Call> at = callsAt(calls, index);
            assertEquals(1, at.size());
            assertEquals("setNull", at.get(0).method, "parameter " + index + " is bound as NULL");
        }
    }

    @Test
    @DisplayName("A non-NULL value is bound exactly as before and reports nothing")
    public void nonNullValuesAreUnchangedAndNotReported() throws Exception {
        Struct after = new Struct(ROW).put("id", 1L).put("versions", "[3, 4]").put("note", "x").put("qty", 1L);

        List<Call> calls = bind(after, columns(), "documents_c");

        List<Call> versions = callsAt(calls, 2);
        assertEquals(1, versions.size());
        assertEquals("setObject", versions.get(0).method, "a JSON field is bound with setObject");
        assertEquals("[3, 4]", versions.get(0).value);
        assertFalse(reported("db.documents_c.versions"));
        assertFalse(reported("db.documents_c.qty"));
    }

    @Test
    @DisplayName("canHoldNull recognises the types that can store NULL")
    public void canHoldNullRecognisesNullCapableTypes() {
        assertTrue(PreparedStatementFieldMapper.canHoldNull("Nullable(String)"));
        assertTrue(PreparedStatementFieldMapper.canHoldNull("LowCardinality(Nullable(String))"));
        assertTrue(PreparedStatementFieldMapper.canHoldNull("Variant(String, UInt64)"));
        assertTrue(PreparedStatementFieldMapper.canHoldNull("Dynamic"));
        assertTrue(PreparedStatementFieldMapper.canHoldNull("JSON"));
        assertTrue(PreparedStatementFieldMapper.canHoldNull(null));
        assertFalse(PreparedStatementFieldMapper.canHoldNull("Array(Int64)"));
        assertFalse(PreparedStatementFieldMapper.canHoldNull("Array(Nullable(String))"));
        assertFalse(PreparedStatementFieldMapper.canHoldNull("Map(String, String)"));
        assertFalse(PreparedStatementFieldMapper.canHoldNull("LowCardinality(String)"));
        assertFalse(PreparedStatementFieldMapper.canHoldNull("Int64"));
    }
}

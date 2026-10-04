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
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 07.07 section 3.2.3: a source NULL for an {@code Array} column is bound
 * as the empty array, not as NULL.
 *
 * <p>The source shape is a MySQL generated column of type JSON
 * ({@code versions json GENERATED ALWAYS AS (json_extract(doc, '$.items[*].v')) STORED})
 * that MySQL computes as NULL when the path matches nothing, replicated into a
 * ClickHouse {@code Array(Int64)} column. Debezium delivers the JSON value as an
 * optional STRING named {@code io.debezium.data.Json}, so a NULL arrives as a
 * null field. Under
 * {@code input_format_null_as_default=0} (section 3.2.1) a bound NULL is refused
 * with {@code Code: 53 Cannot insert NULL value into a column of type
 * 'Array(Int64)'}; the column cannot be made {@code Nullable(Array(Int64))}
 * (Code 43), so the refusal stopped the connector on every restart with no
 * recovery but a schema change.</p>
 */
public class NullIntoArrayColumnTest {

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

    /** The element type each createArrayOf call was asked for. */
    private final List<String> arrayElementTypes = new ArrayList<>();

    private Array array(Object[] elements) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getArray":
                    return elements;
                case "toString":
                    return "RecordingArray";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return null;
            }
        };
        return (Array) Proxy.newProxyInstance(Array.class.getClassLoader(), new Class<?>[]{Array.class}, h);
    }

    private Connection connection() {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "createArrayOf":
                    arrayElementTypes.add((String) args[0]);
                    return array((Object[]) args[1]);
                case "toString":
                    return "RecordingConnection";
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

    private PreparedStatement recording(List<Call> calls) {
        Connection connection = connection();
        InvocationHandler h = (proxy, method, args) -> {
            String name = method.getName();
            if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer) {
                calls.add(new Call(name, (Integer) args[0], args[1]));
                return null;
            }
            switch (name) {
                case "getConnection":
                    return connection;
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

    private List<Call> bind(Struct after, Map<String, String> columns) throws Exception {
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
                        columns, DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "documents");
        return calls;
    }

    private static Map<String, String> columns(String versionsType) {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("id", "Int64");
        columns.put("versions", versionsType);
        columns.put("note", "Nullable(String)");
        columns.put("qty", "Int64");
        return columns;
    }

    @Test
    @DisplayName("A source NULL for an Array column is bound as the empty array, never as NULL")
    public void nullIntoArrayColumnIsBoundAsEmptyArray() throws Exception {
        Struct after = new Struct(ROW).put("id", 1L).put("versions", null).put("note", null).put("qty", null);

        List<Call> calls = bind(after, columns("Array(Int64)"));

        List<Call> versions = callsAt(calls, 2);
        assertEquals(1, versions.size(), "exactly one binding for the Array column");
        assertEquals("setArray", versions.get(0).method,
                "a NULL bound into Array(Int64) is refused by ClickHouse (Code 53) under "
                        + "input_format_null_as_default=0, and the column cannot be Nullable");
        assertArrayEquals(new Object[0], (Object[]) ((Array) versions.get(0).value).getArray());
        assertEquals(List.of("Int64"), arrayElementTypes, "created with the column's element type");
        assertReported("db.documents.versions");
    }

    @Test
    @DisplayName("A NULL for a nested Array column is the empty array of the declared element type")
    public void nullIntoNestedArrayColumnUsesTheDeclaredElementType() throws Exception {
        Struct after = new Struct(ROW).put("id", 1L).put("versions", null).put("note", "x").put("qty", 1L);

        List<Call> calls = bind(after, columns("Array(Array(Nullable(String)))"));

        assertEquals("setArray", callsAt(calls, 2).get(0).method);
        assertEquals(List.of("Array(Nullable(String))"), arrayElementTypes);
    }

    @Test
    @DisplayName("A source NULL for a scalar column is still bound as NULL (Nullable or not)")
    public void nullIntoScalarColumnsIsStillBoundAsNull() throws Exception {
        Struct after = new Struct(ROW).put("id", 1L).put("versions", null).put("note", null).put("qty", null);

        List<Call> calls = bind(after, columns("Array(Int64)"));

        List<Call> note = callsAt(calls, 3);
        assertEquals(1, note.size());
        assertEquals("setNull", note.get(0).method, "Nullable(String): NULL is the source value");
        List<Call> qty = callsAt(calls, 4);
        assertEquals(1, qty.size());
        assertEquals("setNull", qty.get(0).method,
                "non-Nullable Int64 keeps the loud Code 53 refusal of FM-07.07-1; its recovery "
                        + "(MODIFY COLUMN Nullable(Int64)) exists, so it must not be defaulted");
    }

    @Test
    @DisplayName("A non-NULL value for an Array column is bound exactly as before")
    public void nonNullValueForArrayColumnIsUnchanged() throws Exception {
        Struct after = new Struct(ROW).put("id", 1L).put("versions", "[3, 4]").put("note", "x").put("qty", 1L);

        List<Call> calls = bind(after, columns("Array(Int64)"));

        List<Call> versions = callsAt(calls, 2);
        assertEquals(1, versions.size());
        assertEquals("setObject", versions.get(0).method, "a JSON field is bound with setObject");
        assertEquals("[3, 4]", versions.get(0).value);
        assertTrue(arrayElementTypes.isEmpty(), "no empty array is created for a real value");
    }

    @Test
    @DisplayName("isArrayType recognises Array types only")
    public void isArrayTypeRecognisesArrayTypesOnly() {
        assertTrue(PreparedStatementFieldMapper.isArrayType("Array(Int64)"));
        assertTrue(PreparedStatementFieldMapper.isArrayType(" Array(Array(String))"));
        assertFalse(PreparedStatementFieldMapper.isArrayType("Nullable(String)"));
        assertFalse(PreparedStatementFieldMapper.isArrayType("Map(String, Array(Int64))"));
        assertFalse(PreparedStatementFieldMapper.isArrayType("String"));
        assertFalse(PreparedStatementFieldMapper.isArrayType(null));
    }

    private static void assertReported(String key) {
        assertTrue(PreparedStatementFieldMapper.REPORTED_NULL_AS_EMPTY_ARRAY_COLUMNS.contains(key),
                "the substitution is reported at WARN once per column: "
                        + PreparedStatementFieldMapper.REPORTED_NULL_AS_EMPTY_ARRAY_COLUMNS);
    }
}

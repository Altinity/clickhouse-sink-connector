package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ZoneFixtures;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.batch.PreparedStatementFieldMapper;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.BeforeEach;
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
 * Spec 07.12 through {@link PreparedStatementFieldMapper}: the bind loop reports a text value whose trailing NUL is
 * bound into a FixedString(N) column, so the report is not only a property of the helper. Removing the call from the
 * loop leaves the counts below at zero and the strict case stops refusing the row.
 */
public class FixedStringNulCallSiteTest {

    private static final Schema ROW = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .field("code4", Schema.OPTIONAL_STRING_SCHEMA)
            .build();

    @BeforeEach
    void resetCounts() {
        LossyConversionReporter.resetForTests();
    }

    private static PreparedStatement recording(Map<Integer, Object> bound) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "setString":
                case "setInt":
                case "setObject":
                    bound.put((Integer) args[0], args[1]);
                    return null;
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
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, h);
    }

    private static ClickHouseSinkConnectorConfig config(boolean clamp) {
        Map<String, String> props = new HashMap<>();
        props.put("clamp.out.of.range", Boolean.toString(clamp));
        return new ClickHouseSinkConnectorConfig(ZoneFixtures.withZones(props));
    }

    private static Map<Integer, Object> bind(String code, String chType, boolean clamp) throws Exception {
        Struct after = new Struct(ROW).put("id", 1).put("code4", code);
        ClickHouseStruct record = new ClickHouseStruct(
                0L, "topic", null, 0, System.currentTimeMillis(),
                null, after, null, ClickHouseConverter.CDC_OPERATION.CREATE);
        record.setDatabase("db");

        Map<String, Integer> indexMap = new LinkedHashMap<>();
        indexMap.put("id", 1);
        indexMap.put("code4", 2);
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("id", "Int32");
        columns.put("code4", chType);

        Map<Integer, Object> bound = new HashMap<>();
        new PreparedStatementFieldMapper("is_deleted", true, null, "_version", "db", ZoneId.of("UTC"))
                .insertPreparedStatement(indexMap, recording(bound), after.schema().fields(),
                        record, after, false, config(clamp), columns,
                        DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "codes");
        return bound;
    }

    private static long count() {
        return LossyConversionReporter.count(LossyConversionReporter.Kind.FIXEDSTRING_NUL);
    }

    @Test
    public void aTrailingNulIntoFixedStringIsReportedAndTheValueIsBoundUnchanged() throws Exception {
        Map<Integer, Object> bound = bind("ab\0", "FixedString(4)", true);

        assertEquals(1L, count());
        assertEquals("ab\0", bound.get(2), "the report does not change the bound value; ClickHouse pads it");
    }

    @Test
    public void nullableFixedStringIsReportedToo() throws Exception {
        bind("ab\0", "Nullable(FixedString(4))", true);

        assertEquals(1L, count());
    }

    @Test
    public void paddingOnlyAndOtherColumnTypesAreNotReported() throws Exception {
        bind("abc", "FixedString(4)", true);
        bind("ab\0", "String", true);

        assertEquals(0L, count());
    }

    @Test
    public void strictModeRefusesTheRowNamingTheColumn() {
        DebeziumConverter.ValueOutOfRangeException refused = assertThrows(
                DebeziumConverter.ValueOutOfRangeException.class,
                () -> bind("ab\0", "FixedString(4)", false));

        assertTrue(refused.getMessage().contains("db.codes.code4"), refused.getMessage());
    }
}

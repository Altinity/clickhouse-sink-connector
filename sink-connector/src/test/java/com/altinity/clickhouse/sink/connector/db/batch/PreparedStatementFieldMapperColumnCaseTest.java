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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 04.03 section 3.4: a ClickHouse column whose name differs from the
 * source field only in letter case must be bound from that field.
 *
 * <p>Membership is decided case-insensitively ({@code QueryFormatter} and
 * {@code getFieldByColumnName}), but the value read used the ClickHouse
 * column name verbatim ({@code struct.getWithoutDefault(colName)}), which
 * Kafka Connect resolves case-sensitively. For a table created by hand as
 * {@code `ID` Int32, `Amount` Float64} while the source columns are
 * {@code id, amount}, the read threw {@code DataException}, the mapper turned
 * it into {@code StaleSchemaCacheException}, and the batch was retried
 * forever against a cache that was never stale -- a permanent stall.</p>
 */
public class PreparedStatementFieldMapperColumnCaseTest {

    private static final Schema ROW = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .field("amount", Schema.OPTIONAL_FLOAT64_SCHEMA)
            .field("note", Schema.OPTIONAL_STRING_SCHEMA)
            .build();

    private static PreparedStatement recording(Map<Integer, Object> bound, List<Integer> nulls) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "setInt":
                case "setString":
                case "setObject":
                case "setDouble":
                    bound.put((Integer) args[0], args[1]);
                    return null;
                case "setNull":
                    nulls.add((Integer) args[0]);
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

    @Test
    @DisplayName("A ClickHouse column differing only in case from the source field is bound, not stalled on")
    public void columnCaseMismatchIsResolvedToTheSourceField() throws Exception {
        // note is NULL at the source: the modified-field list (what the
        // executor passes) does not carry it, so the case must be resolved
        // from the record's schema, not only from that list.
        Struct after = new Struct(ROW).put("id", 7).put("amount", 12.5).put("note", null);
        ClickHouseStruct record = new ClickHouseStruct(
                0L, "topic", null, 0, System.currentTimeMillis(),
                null, after, null, ClickHouseConverter.CDC_OPERATION.CREATE);
        record.setDatabase("db");

        // The ClickHouse table was created by hand with different letter case.
        Map<String, Integer> indexMap = new LinkedHashMap<>();
        indexMap.put("ID", 1);
        indexMap.put("Amount", 2);
        indexMap.put("NOTE", 3);
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("ID", "Int32");
        columns.put("Amount", "Nullable(Float64)");
        columns.put("NOTE", "Nullable(String)");

        Map<Integer, Object> bound = new HashMap<>();
        List<Integer> nulls = new ArrayList<>();
        new PreparedStatementFieldMapper("is_deleted", true, null, "_version", "db", ZoneId.of("UTC"))
                .insertPreparedStatement(indexMap, recording(bound, nulls), record.getAfterModifiedFields(),
                        record, after, false, new ClickHouseSinkConnectorConfig(new HashMap<>()), columns,
                        DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "orders");

        assertEquals(7, bound.get(1), "id must be bound to `ID`; bound: " + bound);
        assertTrue(bound.containsKey(2), "amount must be bound to `Amount`; bound: " + bound);
        assertTrue(nulls.contains(3), "the NULL note must be bound as NULL to `NOTE`; nulls: " + nulls);
    }
}

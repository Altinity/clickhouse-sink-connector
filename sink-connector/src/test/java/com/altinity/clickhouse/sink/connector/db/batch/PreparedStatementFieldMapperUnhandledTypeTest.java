package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 07.07 section 3.2.2 rule 1: a field whose Kafka type has no handler in
 * {@code ClickHouseDataTypeMapper.convert} must fail the batch. The mapper used
 * to log {@code DATA TYPE NOT HANDLED} and continue with the parameter left
 * unbound; on the V2 JDBC driver, whose {@code addBatch()} does not clear its
 * bound values, every row after the first then silently reused the previous
 * row's value at that index.
 */
public class PreparedStatementFieldMapperUnhandledTypeTest {

    private static final Schema ROW = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .field("attrs", SchemaBuilder.map(Schema.STRING_SCHEMA, Schema.STRING_SCHEMA).optional().build())
            .build();

    private static PreparedStatement recording(Map<Integer, Object> bound) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "setInt":
                case "setString":
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

    @Test
    @DisplayName("A field with no type handler fails the batch instead of leaving its parameter unbound")
    public void unhandledTypeFailsTheBatch() {
        Struct after = new Struct(ROW).put("id", 1).put("attrs", Collections.singletonMap("k", "v"));
        ClickHouseStruct record = new ClickHouseStruct(
                0L, "topic", null, 0, System.currentTimeMillis(),
                null, after, null, ClickHouseConverter.CDC_OPERATION.CREATE);
        record.setDatabase("db");

        Map<String, Integer> indexMap = new LinkedHashMap<>();
        indexMap.put("id", 1);
        indexMap.put("attrs", 2);
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("id", "Int32");
        columns.put("attrs", "String");

        Map<Integer, Object> bound = new HashMap<>();
        DataException e = assertThrows(DataException.class, () ->
                new PreparedStatementFieldMapper("is_deleted", true, null, "_version", "db", ZoneId.of("UTC"))
                        .insertPreparedStatement(indexMap, recording(bound), after.schema().fields(),
                                record, after, false, new ClickHouseSinkConnectorConfig(new HashMap<>()),
                                columns, DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "orders"),
                "a MAP field has no handler; continuing leaves parameter 2 unbound (or, on the V2 "
                        + "driver, bound to the previous row's value)");
        assertTrue(e.getMessage().contains("attrs"), e.getMessage());
        assertTrue(e.getMessage().contains("orders"), e.getMessage());
        assertFalse(bound.containsKey(2), "nothing may be bound for the unhandled field: " + bound);
    }
}

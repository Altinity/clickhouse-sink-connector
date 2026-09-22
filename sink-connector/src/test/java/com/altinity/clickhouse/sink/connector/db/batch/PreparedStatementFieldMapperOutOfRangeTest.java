package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.converters.DebeziumConverter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import io.debezium.time.Timestamp;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 07.03 section 3.3 through {@link PreparedStatementFieldMapper}: the
 * out-of-range failure names the database, table and column, and the
 * {@code clamp.out.of.range} setting is honoured on the same path.
 */
public class PreparedStatementFieldMapperOutOfRangeTest {

    private static final Schema ROW = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .field("expires_at", SchemaBuilder.int64().name(Timestamp.SCHEMA_NAME).optional().build())
            .build();

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

    private static Map<Integer, Object> bind(ClickHouseSinkConnectorConfig config) throws Exception {
        long sentinel = LocalDateTime.of(9999, 12, 31, 23, 59, 59).toInstant(ZoneOffset.UTC).toEpochMilli();
        Struct after = new Struct(ROW).put("id", 1).put("expires_at", sentinel);
        ClickHouseStruct record = new ClickHouseStruct(
                0L, "topic", null, 0, System.currentTimeMillis(),
                null, after, null, ClickHouseConverter.CDC_OPERATION.CREATE);
        record.setDatabase("db");

        Map<String, Integer> indexMap = new LinkedHashMap<>();
        indexMap.put("id", 1);
        indexMap.put("expires_at", 2);
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("id", "Int32");
        columns.put("expires_at", "Nullable(DateTime64(3, 'UTC'))");

        Map<Integer, Object> bound = new HashMap<>();
        new PreparedStatementFieldMapper("is_deleted", true, null, "_version", "db", ZoneId.of("UTC"))
                .insertPreparedStatement(indexMap, recording(bound), after.schema().fields(),
                        record, after, false, config, columns,
                        DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "orders");
        return bound;
    }

    @Test
    @DisplayName("An out-of-range value fails the batch naming db.table.column; clamp.out.of.range=true saturates")
    public void outOfRangeValueNamesDatabaseTableAndColumn() throws Exception {
        DebeziumConverter.ValueOutOfRangeException e = assertThrows(
                DebeziumConverter.ValueOutOfRangeException.class,
                () -> bind(new ClickHouseSinkConnectorConfig(new HashMap<>())),
                "the default must reject 9999-12-31 23:59:59 for a DateTime64 column");
        assertTrue(e.getMessage().contains("db.orders.expires_at"),
                "the operator must be told which column: " + e.getMessage());
        assertTrue(e.getMessage().contains("9999-12-31T23:59:59Z"), e.getMessage());

        Map<String, String> props = new HashMap<>();
        props.put("clamp.out.of.range", "true");
        Map<Integer, Object> bound = bind(new ClickHouseSinkConnectorConfig(props));
        assertEquals("2299-12-31 23:59:59.000", bound.get(2),
                "with the setting on, the same row saturates to the DateTime64 bound");
    }
}

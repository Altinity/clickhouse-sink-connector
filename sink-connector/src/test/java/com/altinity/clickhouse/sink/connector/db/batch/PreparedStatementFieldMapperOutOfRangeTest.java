package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ZoneFixtures;
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
        return bind(config, "Nullable(DateTime64(3, 'UTC'))");
    }

    private static Map<Integer, Object> bind(ClickHouseSinkConnectorConfig config, String expiresAtType) throws Exception {
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
        columns.put("expires_at", expiresAtType);

        Map<Integer, Object> bound = new HashMap<>();
        new PreparedStatementFieldMapper("is_deleted", true, null, "_version", "db", ZoneId.of("UTC"))
                .insertPreparedStatement(indexMap, recording(bound), after.schema().fields(),
                        record, after, false, config, columns,
                        DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "orders");
        return bound;
    }

    @Test
    @DisplayName("The saturation bound follows the column's declared DateTime64 scale (Spec 07.03 section 3.1.8)")
    public void boundFollowsTheDeclaredScale() throws Exception {
        // database.connectionTimeZone=UTC is explicit: an unset value now means America/Chicago (spec 07.03 3.1.6),
        // which differs from the UTC server zone of this binding, so the instant would be bound as epoch text.
        Map<String, String> utc = new HashMap<>();
        utc.put("database.connectionTimeZone", "UTC");
        ClickHouseSinkConnectorConfig defaults = new ClickHouseSinkConnectorConfig(ZoneFixtures.withZones(utc));
        // DateTime64(9) is capped at 2262-04-11 23:47:16.854775807; 2299-12-31 raises DECIMAL_OVERFLOW there.
        assertEquals("2262-04-11 23:47:16.854", bind(defaults, "DateTime64(9, 'UTC')").get(2));
        assertEquals("2299-12-31 23:59:59.000", bind(defaults, "DateTime64(0, 'UTC')").get(2));
        assertEquals("2299-12-31 23:59:59.999", bind(defaults, "DateTime64(6, 'UTC')").get(2));
    }

    @Test
    @DisplayName("By default an out-of-range value saturates; clamp.out.of.range=false fails the batch naming db.table.column")
    public void outOfRangeValueNamesDatabaseTableAndColumn() throws Exception {
        // database.connectionTimeZone=UTC is explicit: an unset value now means America/Chicago (spec 07.03 3.1.6),
        // which would move the sentinel to year 10000 UTC and change the instant named below.
        Map<String, String> defaults = new HashMap<>();
        defaults.put("database.connectionTimeZone", "UTC");
        Map<Integer, Object> bound = bind(new ClickHouseSinkConnectorConfig(ZoneFixtures.withZones(defaults)));
        assertEquals("2299-12-31 23:59:59.999", bound.get(2),
                "the default saturates 9999-12-31 23:59:59 to the DateTime64 bound");

        Map<String, String> props = new HashMap<>();
        props.put("clamp.out.of.range", "false");
        props.put("database.connectionTimeZone", "UTC");
        DebeziumConverter.ValueOutOfRangeException e = assertThrows(
                DebeziumConverter.ValueOutOfRangeException.class,
                () -> bind(new ClickHouseSinkConnectorConfig(ZoneFixtures.withZones(props))),
                "with the setting off, the same row must be refused");
        assertTrue(e.getMessage().contains("db.orders.expires_at"),
                "the operator must be told which column: " + e.getMessage());
        assertTrue(e.getMessage().contains("9999-12-31T23:59:59Z"), e.getMessage());
    }
}

package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ZoneFixtures;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.ClickHouseDbConstants;
import com.altinity.clickhouse.sink.connector.metadata.DataTypeRange;
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
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Spec 12.04 section 3.5: the history columns {@code deleted_time} and
 * {@code deleted_from_time} are DateTime columns bound as unix epoch seconds
 * with {@code setLong}, never as wall-clock text in a zone. The bound value is
 * the same whatever the source or server zone is configured to be.
 */
public class PreparedStatementFieldMapperHistoryEpochTest {

    private static final Schema ROW = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .build();

    /** Records the typed binds: setLong and setString are kept apart so a text bind is visible. */
    private static PreparedStatement recording(Map<Integer, Object> longs, Map<Integer, Object> strings) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "setLong":
                    longs.put((Integer) args[0], args[1]);
                    return null;
                case "setString":
                    strings.put((Integer) args[0], args[1]);
                    return null;
                case "setInt":
                case "setObject":
                    strings.put((Integer) args[0], args[1]);
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

    private static Map<Integer, Object>[] bind(ClickHouseConverter.CDC_OPERATION op, long tsMillis, String sourceZone,
                                               String serverZone) throws Exception {
        Struct after = new Struct(ROW).put("id", 1);
        ClickHouseStruct record = new ClickHouseStruct(
                0L, "topic", null, 0, tsMillis,
                null, after, null, op);
        record.setDatabase("db");
        // The binlog event time is tsSec (set by the source offset), not the constructor's ts_ms.
        record.setTsSec(tsMillis / 1000);

        Map<String, Integer> indexMap = new LinkedHashMap<>();
        indexMap.put("id", 1);
        indexMap.put(ClickHouseDbConstants.DELETED_TIME_COLUMN, 2);
        indexMap.put(ClickHouseDbConstants.DELETED_FROM_TIME_COLUMN, 3);
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("id", "Int32");
        columns.put(ClickHouseDbConstants.DELETED_TIME_COLUMN, "DateTime");
        columns.put(ClickHouseDbConstants.DELETED_FROM_TIME_COLUMN, "DateTime");

        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(ZoneFixtures.withZones(new HashMap<String, String>() {{
            put(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString(), "true");
            put(ClickHouseSinkConnectorConfigVariables.SOURCE_DATETIME_TIMEZONE.toString(), sourceZone);
        }}));

        Map<Integer, Object> longs = new HashMap<>();
        Map<Integer, Object> strings = new HashMap<>();
        new PreparedStatementFieldMapper("is_deleted", true, null, "_version", "db", ZoneId.of(serverZone))
                .insertPreparedStatement(indexMap, recording(longs, strings), after.schema().fields(),
                        record, after, false, config, columns,
                        DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "orders");
        @SuppressWarnings("unchecked")
        Map<Integer, Object>[] result = new Map[]{longs, strings};
        return result;
    }

    @Test
    @DisplayName("deleted_from_time binds the event's epoch seconds as a number, not text")
    public void deletedFromTimeBindsEpochSecondsAsNumber() throws Exception {
        long tsMillis = 1793518200123L; // 2026-11-01T07:30:00.123Z, the overlap second pass
        Map<Integer, Object>[] bound = bind(ClickHouseConverter.CDC_OPERATION.UPDATE, tsMillis, "America/Chicago", "America/Chicago");
        assertEquals(1793518200L, bound[0].get(3));
        assertFalse(bound[1].containsKey(3), "no text bind may carry the history instant");
    }

    @Test
    @DisplayName("The bound epoch is identical for every source and server zone (zone-independent golden value)")
    public void boundValueDoesNotDependOnAnyZone() throws Exception {
        long tsMillis = 1793518200123L;
        Object utc = bind(ClickHouseConverter.CDC_OPERATION.DELETE, tsMillis, "UTC", "UTC")[0].get(2);
        Object chicago = bind(ClickHouseConverter.CDC_OPERATION.DELETE, tsMillis, "America/Chicago", "America/Chicago")[0].get(2);
        Object apia = bind(ClickHouseConverter.CDC_OPERATION.DELETE, tsMillis, "Pacific/Apia", "Asia/Kolkata")[0].get(2);
        assertEquals(1793518200L, utc);
        assertEquals(utc, chicago);
        assertEquals(utc, apia);
    }

    @Test
    @DisplayName("An insert closes nothing: deleted_time binds the sentinel 4102444800 as a number")
    public void insertBindsTheSentinelAsNumber() throws Exception {
        Map<Integer, Object>[] bound = bind(ClickHouseConverter.CDC_OPERATION.CREATE, 1793518200123L, "UTC", "UTC");
        assertEquals(DataTypeRange.DATETIME32_MAX_TTL, bound[0].get(2));
        assertEquals(4102444800L, bound[0].get(2));
    }
}

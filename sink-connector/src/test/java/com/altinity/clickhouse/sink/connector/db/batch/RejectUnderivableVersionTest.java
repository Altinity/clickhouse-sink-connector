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
 * Spec 02.05: {@code rejectUnderivableVersion} refuses any non-positive
 * version, on both bind paths.
 *
 * <p>{@code 0} is reached organically here through the raw-GTID branch of
 * {@code calculateVersion(false)}: with {@code snowflake.id=false} the version
 * IS the GTID transaction number, so a record carrying transaction number 0 --
 * which no MySQL server issues -- would previously have been written with
 * {@code _version = 0}.</p>
 */
public class RejectUnderivableVersionTest {

    private static final String VERSION_COLUMN = "_version";

    private static final Schema ROW_SCHEMA = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .build();

    private static PreparedStatement recordingStatement(Map<Integer, Long> longs) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "setLong":
                    longs.put((Integer) args[0], (Long) args[1]);
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

    /** Raw-GTID versioning: the version bound is exactly the GTID number. */
    private static ClickHouseSinkConnectorConfig rawGtidConfig() {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.SNOWFLAKE_ID.toString(), "false");
        return new ClickHouseSinkConnectorConfig(props);
    }

    private static ClickHouseStruct insertWithGtid(long gtid) {
        Struct after = new Struct(ROW_SCHEMA).put("id", 1);
        ClickHouseStruct record = new ClickHouseStruct(
                3L, "topic", null, 0, System.currentTimeMillis(),
                null, after, null, ClickHouseConverter.CDC_OPERATION.CREATE);
        record.setDatabase("db");
        record.setGtid(gtid);
        record.setTs_ms(1767225600000L);
        return record;
    }

    private static ClickHouseStruct relocationWithGtid(long gtid) {
        Struct before = new Struct(ROW_SCHEMA).put("id", 1);
        Struct after = new Struct(ROW_SCHEMA).put("id", 2);
        ClickHouseStruct record = new ClickHouseStruct(
                3L, "topic", null, 0, System.currentTimeMillis(),
                before, after, null, ClickHouseConverter.CDC_OPERATION.UPDATE);
        record.setDatabase("db");
        record.setGtid(gtid);
        record.setTs_ms(1767225600000L);
        return record;
    }

    private static Map<String, Integer> indexMap() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("id", 1);
        m.put(VERSION_COLUMN, 2);
        return m;
    }

    private static Map<String, String> typeMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "Int32");
        m.put(VERSION_COLUMN, "UInt64");
        return m;
    }

    private static PreparedStatementFieldMapper mapper() {
        return new PreparedStatementFieldMapper("is_deleted", true, null, VERSION_COLUMN, "db",
                ZoneId.of("UTC"));
    }

    @Test
    @DisplayName("_version == 0 fails the batch on the live-row bind path")
    public void testZeroVersionIsRejected() {
        ClickHouseStruct record = insertWithGtid(0L);
        Map<Integer, Long> longs = new HashMap<>();

        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                mapper().insertPreparedStatement(indexMap(), recordingStatement(longs),
                        ROW_SCHEMA.fields(), record, record.getAfterStruct(), false,
                        rawGtidConfig(), typeMap(),
                        DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "t"),
                "a record whose derived version is 0 must not be written; 0 is not "
                        + "producible by any real source coordinate");
        assertTrue(e.getMessage().contains("topic"), e.getMessage());
        assertTrue(!longs.containsKey(2), "nothing may be bound to _version: " + longs);
    }

    @Test
    @DisplayName("_version == 0 fails the batch on the tombstone bind path too")
    public void testZeroVersionIsRejectedForTombstone() {
        ClickHouseStruct record = relocationWithGtid(0L);
        Map<Integer, Long> longs = new HashMap<>();

        assertThrows(IllegalStateException.class, () ->
                mapper().insertTombstonePreparedStatement(indexMap(), recordingStatement(longs),
                        ROW_SCHEMA.fields(), record, record.getBeforeStruct(),
                        rawGtidConfig(), typeMap(),
                        DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "t"));
        assertTrue(!longs.containsKey(2), "nothing may be bound to _version: " + longs);
    }

    @Test
    @DisplayName("A positive version is bound as-is")
    public void testPositiveVersionIsBound() throws Exception {
        ClickHouseStruct record = insertWithGtid(1L);
        Map<Integer, Long> longs = new HashMap<>();

        mapper().insertPreparedStatement(indexMap(), recordingStatement(longs),
                ROW_SCHEMA.fields(), record, record.getAfterStruct(), false,
                rawGtidConfig(), typeMap(),
                DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "t");

        assertEquals(1L, (long) longs.get(2));
    }
}

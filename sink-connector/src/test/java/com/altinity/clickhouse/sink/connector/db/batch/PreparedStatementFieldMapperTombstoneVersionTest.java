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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 05.02 section 3.2: the relocation tombstone carries the record's own
 * {@code _version}, unchanged.
 *
 * <p>Under GTID versioning every row event of one MySQL transaction shares one
 * version. An {@code INSERT (k='a')} followed in the same transaction by
 * {@code UPDATE ... SET k='b'} therefore stores the live row at version
 * {@code V}; a tombstone written at {@code V - 1} is OLDER than that row,
 * loses the ReplacingMergeTree merge, and leaves a ghost row at {@code 'a'}
 * next to the new row at {@code 'b'}. MySQL has one row, ClickHouse two.</p>
 *
 * <p>At {@code V} the tombstone ties the live row, and ClickHouse resolves an
 * equal-version tie to the later-inserted row -- the tombstone. The tombstone
 * and the after-image never share a sorting key, so the decrement never bought
 * anything on that side.</p>
 */
public class PreparedStatementFieldMapperTombstoneVersionTest {

    private static final String VERSION_COLUMN = "_version";
    private static final String DELETE_COLUMN = "is_deleted";

    private static final Schema ROW_SCHEMA = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .field("k", Schema.STRING_SCHEMA)
            .build();

    /** Records every setLong / setInt by parameter index. */
    private static PreparedStatement recordingStatement(Map<Integer, Long> longs,
                                                        Map<Integer, Integer> ints) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "setLong":
                    longs.put((Integer) args[0], (Long) args[1]);
                    return null;
                case "setInt":
                    ints.put((Integer) args[0], (Integer) args[1]);
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

    /** An UPDATE relocating the sorting key column {@code k} from 'a' to 'b'. */
    private static ClickHouseStruct relocatingUpdate() {
        Struct before = new Struct(ROW_SCHEMA).put("id", 1).put("k", "a");
        Struct after = new Struct(ROW_SCHEMA).put("id", 1).put("k", "b");
        ClickHouseStruct record = new ClickHouseStruct(
                7L, "topic", null, 0, System.currentTimeMillis(),
                before, after, null, ClickHouseConverter.CDC_OPERATION.UPDATE);
        record.setDatabase("db");
        // A GTID-versioned record: the same version the after-image is written with.
        record.setGtid(4242L);
        record.setTs_ms(1767225600000L);
        return record;
    }

    private static Map<String, Integer> indexMap() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("id", 1);
        m.put("k", 2);
        m.put(VERSION_COLUMN, 3);
        m.put(DELETE_COLUMN, 4);
        return m;
    }

    private static Map<String, String> typeMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "Int32");
        m.put("k", "String");
        m.put(VERSION_COLUMN, "UInt64");
        m.put(DELETE_COLUMN, "UInt8");
        return m;
    }

    @Test
    @DisplayName("The tombstone binds _version = record.getVersion(), not V - 1")
    public void testTombstoneCarriesRecordVersionUnchanged() throws Exception {
        ClickHouseStruct record = relocatingUpdate();
        Map<Integer, Long> longs = new HashMap<>();
        Map<Integer, Integer> ints = new HashMap<>();

        new PreparedStatementFieldMapper(DELETE_COLUMN, true, null, VERSION_COLUMN, "db",
                ZoneId.of("UTC")).insertTombstonePreparedStatement(
                indexMap(), recordingStatement(longs, ints), ROW_SCHEMA.fields(), record,
                record.getBeforeStruct(), new ClickHouseSinkConnectorConfig(new HashMap<>()),
                typeMap(), DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "t");

        long recordVersion = record.getVersion();
        assertTrue(recordVersion > 0, "precondition: the record derived a version");
        assertTrue(longs.containsKey(3), "the tombstone must bind the version column");
        assertEquals(recordVersion, (long) longs.get(3),
                "tombstone _version must equal the record's own version V. A tombstone at "
                        + "V - 1 is older than a live row written by the same transaction "
                        + "(same GTID => same V) and loses the merge, leaving a ghost row "
                        + "at the old sorting key.");
    }

    @Test
    @DisplayName("The tombstone still forces the delete marker on")
    public void testTombstoneStillSetsDeleteMarker() throws Exception {
        ClickHouseStruct record = relocatingUpdate();
        Map<Integer, Long> longs = new HashMap<>();
        Map<Integer, Integer> ints = new HashMap<>();

        new PreparedStatementFieldMapper(DELETE_COLUMN, true, null, VERSION_COLUMN, "db",
                ZoneId.of("UTC")).insertTombstonePreparedStatement(
                indexMap(), recordingStatement(longs, ints), ROW_SCHEMA.fields(), record,
                record.getBeforeStruct(), new ClickHouseSinkConnectorConfig(new HashMap<>()),
                typeMap(), DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "t");

        assertEquals(1, (int) ints.get(4), "is_deleted must be 1 on the tombstone");
    }
}

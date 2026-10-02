package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.Disabled;
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

/**
 * Spec 05.02 section 6, FM-05.02-2: {@code ignore_delete=true} and the
 * relocation tombstone.
 *
 * <p>{@code ignore_delete} is documented as "source row removals are NOT
 * replicated". A sorting-key relocation is not a source removal: MySQL still
 * holds exactly one row, at its new key. The tombstone retires the row's OLD
 * position. {@code insertTombstonePreparedStatement} forces the marker only
 * when {@code ignore_delete} is false; otherwise the delete column keeps the
 * {@code setNull(index, Types.OTHER)} that {@code insertPreparedStatement}
 * binds for connector-managed columns (observed: parameter 4 = 1111, i.e.
 * {@code java.sql.Types.OTHER}). Stored as the column default 0 -- which is
 * what every row written under {@code ignore_delete=true} relies on -- the
 * tombstone is a second LIVE row at the old key, so ClickHouse shows two rows
 * for one source row, forever (ReplacingMergeTree never merges rows of
 * different sorting keys).</p>
 */
public class PreparedStatementFieldMapperTombstoneIgnoreDeleteTest {

    private static final Schema ROW_SCHEMA = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .field("k", Schema.STRING_SCHEMA)
            .build();

    private static PreparedStatement recordingStatement(Map<Integer, Object> bound) {
        InvocationHandler h = (proxy, method, args) -> {
            String name = method.getName();
            if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer) {
                bound.put((Integer) args[0], args[1]);
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

    @Test
    @Disabled("DEFECT FM-05.02-2: with ignore_delete=true the relocation tombstone is bound without the delete "
            + "marker, so the pre-update row survives as a live ghost row at the old sorting key")
    @DisplayName("With ignore_delete=true a relocation tombstone still carries the delete marker")
    public void relocationTombstoneUnderIgnoreDeleteStillSetsTheDeleteMarker() throws Exception {
        Struct before = new Struct(ROW_SCHEMA).put("id", 1).put("k", "a");
        Struct after = new Struct(ROW_SCHEMA).put("id", 1).put("k", "b");
        ClickHouseStruct record = new ClickHouseStruct(7L, "topic", null, 0, System.currentTimeMillis(),
                before, after, null, ClickHouseConverter.CDC_OPERATION.UPDATE);
        record.setDatabase("db");
        record.setGtid(4242L);
        record.setTs_ms(1767225600000L);

        Map<String, Integer> index = new LinkedHashMap<>();
        index.put("id", 1);
        index.put("k", 2);
        index.put("_version", 3);
        index.put("is_deleted", 4);
        Map<String, String> types = new LinkedHashMap<>();
        types.put("id", "Int32");
        types.put("k", "String");
        types.put("_version", "UInt64");
        types.put("is_deleted", "UInt8");
        Map<String, String> props = new HashMap<>();
        props.put("ignore_delete", "true");

        Map<Integer, Object> bound = new HashMap<>();
        new PreparedStatementFieldMapper("is_deleted", true, null, "_version", "db", ZoneId.of("UTC"))
                .insertTombstonePreparedStatement(index, recordingStatement(bound), ROW_SCHEMA.fields(), record,
                        record.getBeforeStruct(), new ClickHouseSinkConnectorConfig(props), types,
                        DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "t");

        assertEquals(1, bound.get(4), "the tombstone must be a delete marker (is_deleted = 1) whatever ignore_delete "
                + "says: it retires the row's old sorting-key position, it does not replicate a source removal. "
                + "Bound: " + bound);
    }
}

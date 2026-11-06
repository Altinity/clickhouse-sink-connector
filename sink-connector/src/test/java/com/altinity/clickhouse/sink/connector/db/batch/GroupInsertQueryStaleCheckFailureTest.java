package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ZoneFixtures;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.common.ClickHouseErrorClassifier;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Spec 04.06: the staleness check of spec 08.03 is the only guard
 * against a record column that the cached ClickHouse column map lacks -- the
 * INSERT is built from the column map and the binder walks the same map, so a
 * record field outside it is never visited at bind time. When the metadata
 * re-read of that check FAILS (ClickHouse refuses the {@code system.columns}
 * query: too many simultaneous queries, a timeout, a dropped connection),
 * {@code refreshIfRecordHasUnknownColumn} must not return the cached map in
 * that case: it raises {@link StaleSchemaCacheException} (retriable), so the
 * batch is retried against a rebuilt writer. Returning null here would let
 * {@code groupQueryWithRecords} memoise the schema as verified (spec 04.06
 * section 3.1), group every record under a template without the column, and
 * write the rows with the ClickHouse DEFAULT in place of the source value.
 */
public class GroupInsertQueryStaleCheckFailureTest {

    private static final Schema ROW_SCHEMA = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .field("note", Schema.OPTIONAL_STRING_SCHEMA)
            .build();

    /** A connection on which every metadata query is refused. */
    private static Connection refusingConnection() {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "isClosed":
                    return false;
                case "isValid":
                    return true;
                case "toString":
                    return "RefusingConnection";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "createStatement":
                case "prepareStatement":
                    throw new SQLException("Code: 202. DB::Exception: Too many simultaneous queries for user.");
                default:
                    return null;
            }
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, h);
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.ENABLE_SCHEMA_EVOLUTION.toString(), "false");
        props.put(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE.toString(), "true");
        return new ClickHouseSinkConnectorConfig(ZoneFixtures.withZones(props));
    }

    @Test
    @DisplayName("a failed staleness re-read fails the batch instead of dropping the unknown column")
    public void failedMetadataReReadDoesNotDropTheUnknownColumn() {
        Map<String, String> cachedWithoutNote = new LinkedHashMap<>();
        cachedWithoutNote.put("id", "Int32");
        cachedWithoutNote.put("_version", "UInt64");
        cachedWithoutNote.put("is_deleted", "UInt8");

        List<ClickHouseStruct> records = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Struct after = new Struct(ROW_SCHEMA).put("id", i).put("note", "value " + i);
            ClickHouseStruct record = new ClickHouseStruct(i, "topic", null, 0, System.currentTimeMillis(),
                    null, after, null, ClickHouseConverter.CDC_OPERATION.CREATE);
            record.setDatabase("db");
            records.add(record);
        }

        List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> segments = new ArrayList<>();
        assertThrows(StaleSchemaCacheException.class, () -> new GroupInsertQueryWithBatchRecords().groupQueryWithRecords(
                        records, segments, new HashMap<>(), config(), "t", "db", refusingConnection(),
                        cachedWithoutNote),
                "the record carries 'note', the cached map lacks it and the re-read failed: grouping the batch "
                        + "under a template without 'note' writes every row with the ClickHouse DEFAULT in its place");
    }

    @Test
    @DisplayName("a staleness failure is retriable, not FATAL, so the batch is retried against fresh metadata")
    public void stalenessFailureIsRetriable() {
        StaleSchemaCacheException failure = new StaleSchemaCacheException("column 'note' of db.t");
        assertNotEquals(ClickHouseErrorClassifier.ErrorCategory.FATAL,
                ClickHouseErrorClassifier.classify(failure));
    }
}

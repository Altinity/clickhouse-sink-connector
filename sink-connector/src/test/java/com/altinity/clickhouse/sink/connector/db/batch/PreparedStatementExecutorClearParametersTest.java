package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.model.BlockMetaData;
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
import java.sql.PreparedStatement;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 07.07 section 3.2.2 rule 2 / Spec 03.06 section 3.2: the executor
 * clears the statement's bound parameters after every {@code addBatch()}.
 *
 * <p>The V2 JDBC driver's {@code PreparedStatementImpl.addBatch()} substitutes
 * the bound values into the SQL template and keeps them; only
 * {@code clearParameters()} resets them. A parameter one row fails to bind
 * therefore silently carries the previous row's value unless the executor
 * clears between rows.</p>
 */
public class PreparedStatementExecutorClearParametersTest {

    private static final Schema ROW = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .field("name", Schema.OPTIONAL_STRING_SCHEMA)
            .build();

    /** Records the order of every JDBC call made on the statement. */
    private static PreparedStatement recordingStatement(List<String> calls) {
        InvocationHandler h = (proxy, method, args) -> {
            calls.add(method.getName());
            switch (method.getName()) {
                case "executeBatch":
                    return new int[0];
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

    private static Connection connectionReturning(PreparedStatement ps) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "prepareStatement":
                    return ps;
                case "isClosed":
                    return false;
                case "toString":
                    return "RecordingConnection";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return null;
            }
        };
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, h);
    }

    private static ClickHouseStruct insertRecord(int id, String name) {
        Struct after = new Struct(ROW).put("id", id).put("name", name);
        ClickHouseStruct record = new ClickHouseStruct(
                0L, "topic", null, 0, System.currentTimeMillis(),
                null, after, null, ClickHouseConverter.CDC_OPERATION.CREATE);
        record.setDatabase("db");
        return record;
    }

    @Test
    @DisplayName("Every addBatch() is followed by clearParameters()")
    public void parametersAreClearedAfterEveryAddBatch() throws Exception {
        Map<String, Integer> indexMap = new LinkedHashMap<>();
        indexMap.put("id", 1);
        indexMap.put("name", 2);
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("id", "Int32");
        columns.put("name", "Nullable(String)");

        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> queryToRecords = new HashMap<>();
        queryToRecords.put(new MutablePair<>("insert into db.orders(`id`,`name`) select `id`,`name` from input('`id` Int32,`name` Nullable(String)')", indexMap),
                new ArrayList<>(Arrays.asList(insertRecord(1, "a"), insertRecord(2, "b"))));

        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        PreparedStatement ps = recordingStatement(calls);

        new PreparedStatementExecutor("is_deleted", true, null, "_version", "db", ZoneId.of("UTC"))
                .addToPreparedStatementBatch("topic", queryToRecords, new BlockMetaData(),
                        new ClickHouseSinkConnectorConfig(new HashMap<>()), connectionReturning(ps),
                        "orders", columns, DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE);

        long addBatches = calls.stream().filter("addBatch"::equals).count();
        assertEquals(2, addBatches, "one addBatch per row; calls: " + calls);
        for (int i = 0; i < calls.size(); i++) {
            if ("addBatch".equals(calls.get(i))) {
                assertTrue(i + 1 < calls.size() && "clearParameters".equals(calls.get(i + 1)),
                        "addBatch at position " + i + " must be followed by clearParameters, otherwise a "
                                + "parameter the next row fails to bind keeps this row's value on the V2 "
                                + "driver; calls: " + calls);
            }
        }
        assertTrue(calls.contains("executeBatch"), calls.toString());
    }
}

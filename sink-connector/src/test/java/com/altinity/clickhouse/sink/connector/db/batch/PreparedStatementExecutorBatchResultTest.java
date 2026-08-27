package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.model.BlockMetaData;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.Assert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Issue #1254: {@code executePreparedStatement} tracked batch success in an
 * {@code AtomicBoolean} that was only ever set to {@code true}, and returned
 * that flag as the method's verdict.
 *
 * <p>The flag could not report a failure. Every failure path inside the lambda
 * rethrows ({@code throw new RuntimeException(e)}), so a failed partition
 * leaves the method by exception and never reaches {@code return result.get()}
 * at all. The one thing the flag DID decide was the opposite case: when the
 * partition list is empty, {@code Lists.partition(...).forEach(...)} runs zero
 * iterations, the flag keeps its initial {@code false}, and "nothing was
 * executed" is returned to the caller as "the batch failed".</p>
 *
 * <p>That verdict is not cosmetic any more. The caller,
 * {@code addToPreparedStatementBatch}, logs an ERROR, breaks its loop and
 * returns {@code false}; in the single-threaded writer that {@code false}
 * is now escalated to a {@code BatchPersistenceException} by the #1285 fix,
 * i.e. the connector fails a batch that had nothing to write.</p>
 *
 * <p>So the flag never carried the information the method needed, and the
 * only value it did produce was wrong. It is removed in favour of the
 * exception that already governs control flow. The rethrow is deliberately
 * left exactly as it was -- the #1285 fix depends on failures reaching the
 * caller -- and the control tests below pin that.</p>
 */
public class PreparedStatementExecutorBatchResultTest {

    private static final String DB = "testdb";
    private static final String TABLE = "orders";
    private static final String TOPIC = "SERVER5432.testdb.orders";
    private static final String INSERT_QUERY =
            "INSERT INTO `orders`(id,name) VALUES(?,?)";

    private static final Schema ROW_SCHEMA = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .field("name", Schema.STRING_SCHEMA)
            .build();

    /**
     * Counts what actually reached the driver. Mockito is not on the test
     * classpath, so this uses JDK proxies in the same style as
     * {@code PreparedStatementFieldMapperNullabilityTest}.
     */
    private static final class RecordingConnection {

        private final AtomicInteger prepareCalls = new AtomicInteger();
        private final AtomicInteger executeBatchCalls = new AtomicInteger();
        /** 1-based index of the prepareStatement call whose executeBatch fails; 0 = none. */
        private final int failOnStatement;

        RecordingConnection(int failOnStatement) {
            this.failOnStatement = failOnStatement;
        }

        private PreparedStatement statement(final int ordinal) {
            InvocationHandler h = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "executeBatch":
                        this.executeBatchCalls.incrementAndGet();
                        if (ordinal == this.failOnStatement) {
                            throw new SQLException(
                                    "simulated ClickHouse insert failure on batch " + ordinal);
                        }
                        return new int[]{1};
                    case "toString":
                        return "RecordingPreparedStatement#" + ordinal;
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        return defaultValue(method.getReturnType());
                }
            };
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, h);
        }

        Connection connection() {
            InvocationHandler h = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "prepareStatement":
                        return statement(this.prepareCalls.incrementAndGet());
                    case "isClosed":
                        return false;
                    case "toString":
                        return "RecordingConnection";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        return defaultValue(method.getReturnType());
                }
            };
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, h);
        }
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        return null;
    }

    private static ClickHouseSinkConnectorConfig config(long maxRecordsInBatch) {
        Map<String, String> props = new HashMap<>();
        props.put("clickhouse.server.url", "127.0.0.1");
        props.put("clickhouse.server.port", "8123");
        props.put("clickhouse.server.user", "default");
        props.put("clickhouse.server.password", "");
        props.put("clickhouse.server.database", DB);
        props.put("buffer.max.records", String.valueOf(maxRecordsInBatch));
        return new ClickHouseSinkConnectorConfig(props);
    }

    private static Map<String, String> columnTypes() {
        Map<String, String> types = new LinkedHashMap<>();
        types.put("id", "Int32");
        types.put("name", "String");
        return types;
    }

    private static MutablePair<String, Map<String, Integer>> queryKey() {
        Map<String, Integer> columnToIndex = new LinkedHashMap<>();
        columnToIndex.put("id", 1);
        columnToIndex.put("name", 2);
        MutablePair<String, Map<String, Integer>> key = new MutablePair<>();
        key.setLeft(INSERT_QUERY);
        key.setRight(columnToIndex);
        return key;
    }

    private static ClickHouseStruct record(int id) {
        Struct row = new Struct(ROW_SCHEMA).put("id", id).put("name", "row" + id);
        ClickHouseStruct chStruct = new ClickHouseStruct(id, TOPIC, null, 0,
                System.currentTimeMillis(), null, row, null,
                ClickHouseConverter.CDC_OPERATION.CREATE);
        chStruct.setDatabase(DB);
        return chStruct;
    }

    private static Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>
            queryToRecordsMap(int recordCount) {
        List<ClickHouseStruct> records = new ArrayList<>();
        for (int i = 1; i <= recordCount; i++) {
            records.add(record(i));
        }
        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> map =
                new HashMap<>();
        map.put(queryKey(), records);
        return map;
    }

    private static PreparedStatementExecutor executor() {
        return new PreparedStatementExecutor("is_deleted", true, "sign", "_version",
                DB, ZoneId.of("UTC"));
    }

    private static boolean run(
            Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> map,
            RecordingConnection recorder, long maxRecordsInBatch) throws Exception {
        return executor().addToPreparedStatementBatch(TOPIC, map, new BlockMetaData(),
                config(maxRecordsInBatch), recorder.connection(), TABLE, columnTypes(), null);
    }

    // ------------------------------------------------------------------
    // The defect.
    // ------------------------------------------------------------------

    /**
     * The one verdict the AtomicBoolean actually produced, and it was wrong:
     * an entry with no records executes nothing, so the flag stays false and
     * "nothing to do" is reported to the caller as a failed batch.
     */
    @Test
    @DisplayName("#1254: an entry with no records is not a batch failure")
    public void emptyEntryIsNotReportedAsFailure() throws Exception {
        RecordingConnection recorder = new RecordingConnection(0);
        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> map =
                queryToRecordsMap(0);

        boolean result = run(map, recorder, 1000L);

        Assert.assertEquals("nothing may be sent to ClickHouse for an empty entry",
                0, recorder.prepareCalls.get());
        Assert.assertEquals("no batch may be executed for an empty entry",
                0, recorder.executeBatchCalls.get());
        Assert.assertTrue("an entry with no records executed nothing and failed nothing, "
                        + "but the AtomicBoolean's initial false was returned as the batch "
                        + "verdict; the single-threaded writer turns that false into a "
                        + "BatchPersistenceException and fails a batch that had nothing to "
                        + "write (issue #1254)",
                result);
        Assert.assertTrue("a fully consumed entry must be removed from the query map; "
                        + "the pre-fix break skipped the removal",
                map.isEmpty());
    }

    // ------------------------------------------------------------------
    // Controls: behaviour that must not change.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("control: a single successful partition still reports success")
    public void singleSuccessfulPartitionReportsSuccess() throws Exception {
        RecordingConnection recorder = new RecordingConnection(0);

        boolean result = run(queryToRecordsMap(3), recorder, 1000L);

        Assert.assertTrue("a batch that was written must report success", result);
        Assert.assertEquals("all three records belong to one partition",
                1, recorder.executeBatchCalls.get());
    }

    @Test
    @DisplayName("control: every partition is still executed when they all succeed")
    public void allPartitionsAreExecuted() throws Exception {
        RecordingConnection recorder = new RecordingConnection(0);

        boolean result = run(queryToRecordsMap(3), recorder, 1L);

        Assert.assertTrue("three successful partitions must report success", result);
        Assert.assertEquals("one partition per record at buffer.max.records=1",
                3, recorder.executeBatchCalls.get());
    }

    /**
     * The semantics the #1285 fix depends on: a failed insert must reach the
     * caller as an exception, not as a return value.
     */
    @Test
    @DisplayName("control: a failing partition still throws out of addToPreparedStatementBatch")
    public void failingPartitionStillThrows() {
        RecordingConnection recorder = new RecordingConnection(1);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> run(queryToRecordsMap(3), recorder, 1000L),
                "a failed insert must not be swallowed -- #1285 relies on it reaching "
                        + "the caller so the offset is not committed past unwritten records");

        Assert.assertNotNull("the driver failure must be carried in the cause chain",
                thrown.getCause());
        Assert.assertTrue("the cause must be the SQLException the driver raised, got: "
                        + thrown.getCause(),
                thrown.getCause() instanceof SQLException);
    }

    /**
     * The scenario the issue is named for: an earlier partition succeeded, so
     * a success flag is already set when a later partition fails. The failure
     * must still be the outcome.
     */
    @Test
    @DisplayName("control: a later partition failing after an earlier success is still a failure")
    public void failureAfterEarlierSuccessIsStillAFailure() {
        RecordingConnection recorder = new RecordingConnection(2);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> run(queryToRecordsMap(3), recorder, 1L),
                "the success of partition 1 must not mask the failure of partition 2");

        Assert.assertEquals("execution must stop at the failing partition, not carry on",
                2, recorder.executeBatchCalls.get());
        Assert.assertTrue("the cause must be the SQLException the driver raised, got: "
                        + thrown.getCause(),
                thrown.getCause() instanceof SQLException);
    }
}

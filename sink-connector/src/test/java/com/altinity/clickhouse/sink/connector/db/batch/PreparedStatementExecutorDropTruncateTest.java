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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Issue #1287, second half: {@code disable.drop.truncate} did not exist at all
 * on the CDC data path.
 *
 * <p>A MySQL {@code TRUNCATE TABLE} reaches ClickHouse twice over. As DDL it
 * goes through the lightweight DDL guard; as a CDC event it arrives as a
 * record with operation {@code TRUNCATE} and is executed by
 * {@link PreparedStatementExecutor}, which called
 * {@code metadata.truncateTable(...)} unconditionally
 * (PreparedStatementExecutor line 233). {@code disable.drop.truncate} had no
 * hits anywhere under {@code sink-connector/src/main}, so a user who set it to
 * protect their target still had the table emptied by this path.</p>
 *
 * <p>The setting is read here through the same mechanism every other setting
 * in this class uses -- {@code config.getBoolean(...)} on the
 * {@link ClickHouseSinkConnectorConfig} the method is already handed -- so no
 * new plumbing is introduced.</p>
 *
 * <p>The default is unchanged and the CONTROL tests below pin that: with the
 * setting absent or false, the TRUNCATE is executed exactly as before.</p>
 */
public class PreparedStatementExecutorDropTruncateTest {

    private static final String DB = "testdb";
    private static final String TABLE = "orders";
    private static final String TOPIC = "SERVER5432.testdb.orders";
    private static final String INSERT_QUERY =
            "INSERT INTO `orders`(id,name) VALUES(?,?)";
    private static final String EXPECTED_TRUNCATE = "TRUNCATE TABLE testdb.orders";

    private static final Schema ROW_SCHEMA = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .field("name", Schema.STRING_SCHEMA)
            .build();

    /**
     * Records the SQL that actually reached the driver. Mockito is not on the
     * test classpath, so this uses JDK proxies in the same style as
     * {@code PreparedStatementExecutorBatchResultTest}.
     */
    private static final class RecordingConnection {

        private final List<String> preparedSql =
                Collections.synchronizedList(new ArrayList<String>());
        private final AtomicInteger executeBatchCalls = new AtomicInteger();

        private PreparedStatement statement(final String sql) {
            InvocationHandler h = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "executeBatch":
                        this.executeBatchCalls.incrementAndGet();
                        return new int[]{1};
                    case "execute":
                        return true;
                    case "toString":
                        return "RecordingPreparedStatement[" + sql + "]";
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
                    case "prepareStatement": {
                        String sql = args != null && args.length > 0
                                ? String.valueOf(args[0]) : "";
                        this.preparedSql.add(sql);
                        return statement(sql);
                    }
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

        List<String> truncateStatements() {
            List<String> found = new ArrayList<>();
            for (String sql : new ArrayList<>(this.preparedSql)) {
                if (sql != null && sql.toUpperCase(Locale.ROOT).trim().startsWith("TRUNCATE")) {
                    found.add(sql.trim());
                }
            }
            return found;
        }

        List<String> insertStatements() {
            List<String> found = new ArrayList<>();
            for (String sql : new ArrayList<>(this.preparedSql)) {
                if (sql != null && sql.toUpperCase(Locale.ROOT).trim().startsWith("INSERT")) {
                    found.add(sql.trim());
                }
            }
            return found;
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

    /**
     * @param disableDropTruncate the user's setting, or null to leave it unset.
     */
    private static ClickHouseSinkConnectorConfig config(String disableDropTruncate) {
        Map<String, String> props = new HashMap<>();
        props.put("clickhouse.server.url", "127.0.0.1");
        props.put("clickhouse.server.port", "8123");
        props.put("clickhouse.server.user", "default");
        props.put("clickhouse.server.password", "");
        props.put("clickhouse.server.database", DB);
        props.put("buffer.max.records", "1000");
        // Keep the pooled-reconnect path out of this test; nothing here fails.
        props.put("connection.pool.disable", "true");
        if (disableDropTruncate != null) {
            props.put("disable.drop.truncate", disableDropTruncate);
        }
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

    private static ClickHouseStruct insertRecord(int id) {
        Struct row = new Struct(ROW_SCHEMA).put("id", id).put("name", "row" + id);
        ClickHouseStruct chStruct = new ClickHouseStruct(id, TOPIC, null, 0,
                System.currentTimeMillis(), null, row, null,
                ClickHouseConverter.CDC_OPERATION.CREATE);
        chStruct.setDatabase(DB);
        return chStruct;
    }

    private static ClickHouseStruct truncateRecord(int offset) {
        ClickHouseStruct chStruct = new ClickHouseStruct(offset, TOPIC, null, 0,
                System.currentTimeMillis(), null, null, null,
                ClickHouseConverter.CDC_OPERATION.TRUNCATE);
        chStruct.setDatabase(DB);
        return chStruct;
    }

    /** An INSERT, then the TRUNCATE, then another INSERT -- the ordering case. */
    private static Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>
            insertTruncateInsert() {
        List<ClickHouseStruct> records = new ArrayList<>();
        records.add(insertRecord(1));
        records.add(truncateRecord(2));
        records.add(insertRecord(3));
        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> map =
                new HashMap<>();
        map.put(queryKey(), records);
        return map;
    }

    private static PreparedStatementExecutor executor() {
        return new PreparedStatementExecutor("is_deleted", true, "sign", "_version",
                DB, ZoneId.of("UTC"));
    }

    private static RecordingConnection run(String disableDropTruncate) throws Exception {
        RecordingConnection recorder = new RecordingConnection();
        executor().addToPreparedStatementBatch(TOPIC, insertTruncateInsert(),
                new BlockMetaData(), config(disableDropTruncate), recorder.connection(),
                TABLE, columnTypes(), null);
        return recorder;
    }

    // ------------------------------------------------------------------
    // The defect.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("#1287: disable.drop.truncate=true must stop the CDC TRUNCATE")
    public void enabledSuppressesCdcTruncate() throws Exception {
        RecordingConnection recorder = run("true");

        Assert.assertEquals("disable.drop.truncate is enabled, so no TRUNCATE may be "
                        + "sent to ClickHouse; PreparedStatementExecutor called "
                        + "metadata.truncateTable() unconditionally and the setting had "
                        + "zero references anywhere in sink-connector/src/main (#1287), "
                        + "statements seen: " + recorder.truncateStatements(),
                Collections.emptyList(), recorder.truncateStatements());
    }

    @Test
    @DisplayName("#1287: suppressing the TRUNCATE must not stop the surrounding inserts")
    public void enabledStillReplicatesSurroundingInserts() throws Exception {
        RecordingConnection recorder = run("true");

        Assert.assertEquals("the insert statement must still be prepared",
                1, recorder.insertStatements().size());
        Assert.assertTrue("the rows either side of the suppressed TRUNCATE must still "
                        + "be written",
                recorder.executeBatchCalls.get() >= 1);
    }

    // ------------------------------------------------------------------
    // CONTROLS: the default is unchanged.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("CONTROL: with the setting unset, the CDC TRUNCATE still executes")
    public void defaultStillTruncates() throws Exception {
        RecordingConnection recorder = run(null);

        Assert.assertEquals("the default must not change: an unset "
                        + "disable.drop.truncate leaves TRUNCATE replicating",
                Collections.singletonList(EXPECTED_TRUNCATE),
                recorder.truncateStatements());
    }

    @Test
    @DisplayName("CONTROL: disable.drop.truncate=false still executes the CDC TRUNCATE")
    public void explicitFalseStillTruncates() throws Exception {
        RecordingConnection recorder = run("false");

        Assert.assertEquals("an explicit false must behave exactly like the default",
                Collections.singletonList(EXPECTED_TRUNCATE),
                recorder.truncateStatements());
    }

    @Test
    @DisplayName("CONTROL: the pre-TRUNCATE flush ordering is unchanged when enabled=false")
    public void defaultKeepsPreTruncateFlush() throws Exception {
        RecordingConnection recorder = run(null);

        // One flush before the TRUNCATE (so the pre-truncate rows land first)
        // and one at the end of the batch for the rows after it.
        Assert.assertEquals("the TRUNCATE must still be applied at its binlog position, "
                        + "between the two flushes",
                2, recorder.executeBatchCalls.get());
    }
}

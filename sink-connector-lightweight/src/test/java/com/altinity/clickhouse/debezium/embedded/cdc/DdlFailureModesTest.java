package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchExecutor;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failure modes of the DDL execution path (Spec 06.08 section 6, Spec 06.03
 * section 6, Spec 06.01 section 6), driven through
 * {@code processEveryChangeRecord} with a recording offset committer, so each
 * test asserts the property Invariant I15 cares about: a DDL that did not
 * reach ClickHouse is LOUD and its offset is NEVER acknowledged.
 *
 * <p>No ClickHouse, MySQL or Debezium engine is needed: the writer's JDBC
 * connection is a proxy that refuses the statement the way the server
 * would.</p>
 */
public class DdlFailureModesTest {

    private static final ThreadFactory FACTORY = r -> {
        Thread t = new Thread(r, "ddl-failure-modes-test");
        t.setDaemon(true);
        return t;
    };

    /** Records every committer call; nothing is ever persisted. */
    private static final class RecordingCommitter implements InvocationHandler {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "toString":
                    return "recording-committer";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    calls.add(method.getName());
                    return null;
            }
        }

        @SuppressWarnings("unchecked")
        DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> proxy() {
            return (DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>>) Proxy.newProxyInstance(
                    DebeziumEngine.RecordCommitter.class.getClassLoader(),
                    new Class<?>[]{DebeziumEngine.RecordCommitter.class}, this);
        }
    }

    private static ChangeEvent<SourceRecord, SourceRecord> ddlEvent(String ddl) {
        Schema schema = SchemaBuilder.struct().field("ddl", Schema.STRING_SCHEMA).build();
        Struct value = new Struct(schema);
        value.put("ddl", ddl);
        SourceRecord record = new SourceRecord(null, null, "topic", schema, value);
        return new ChangeEvent<SourceRecord, SourceRecord>() {
            @Override
            public SourceRecord key() {
                return null;
            }

            @Override
            public SourceRecord value() {
                return record;
            }

            @Override
            public String destination() {
                return "topic";
            }

            @Override
            public Integer partition() {
                return null;
            }
        };
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = DebeziumChangeEventCapture.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object invokeProcess(DebeziumChangeEventCapture capture,
                                        ChangeEvent<SourceRecord, SourceRecord> record,
                                        ClickHouseSinkConnectorConfig config,
                                        DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> committer)
            throws Exception {
        Method m = DebeziumChangeEventCapture.class.getDeclaredMethod(
                "processEveryChangeRecord",
                Properties.class,
                ChangeEvent.class,
                DebeziumRecordParserService.class,
                ClickHouseSinkConnectorConfig.class,
                DebeziumEngine.RecordCommitter.class,
                boolean.class,
                DebeziumChangeEventCapture.VersionAssignment.class);
        m.setAccessible(true);
        try {
            return m.invoke(capture, new Properties(), record, null, config, committer, true,
                    new DebeziumChangeEventCapture.VersionAssignment(1000000001L, 1000L));
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw ite;
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }

    /**
     * A connection that refuses every statement containing {@code refuseContaining}
     * with {@code refusal}, and every other statement (metadata lookups, the
     * schema-visibility poll) with a code-less error that the callers treat as
     * "unknown".
     */
    private static Connection refusingConnection(String refuseContaining, String refusal) {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "isClosed":
                    return false;
                case "close":
                    return null;
                case "prepareStatement":
                    if (args != null && args.length > 0 && String.valueOf(args[0]).contains(refuseContaining)) {
                        throw new SQLException(refusal);
                    }
                    throw new SQLException("simulated: metadata query not available in this test");
                case "createStatement":
                    throw new SQLException("simulated: metadata query not available in this test");
                case "toString":
                    return "refusing-connection";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, handler);
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        ClickHouseSinkConnectorConfig.setDefaultValues(props);
        // No Hikari pool: a failed statement must not try to open a real connection.
        props.put(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE.toString(), "true");
        props.put(ClickHouseSinkConnectorConfigVariables.DDL_SCHEMA_CHANGE_TIMEOUT_MS.toString(), "200");
        return new ClickHouseSinkConnectorConfig(props);
    }

    private static DebeziumChangeEventCapture singleThreadedCapture(Connection connection) throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        setField(capture, "executor", null);
        setField(capture, "pgConfig", new PostgresConnectorConfig(new Properties()));
        capture.setWriter(new BaseDbWriter("localhost", 8123, "system", "default", "",
                config(), connection));
        return capture;
    }

    private static SQLException sqlCause(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException) {
                return (SQLException) c;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return null;
    }

    /**
     * FM-06.08-1: a DDL that ClickHouse refuses with a code classified
     * non-retryable ({@code Code: 62}) halts the pipeline and its offset is
     * never acknowledged, so a restart re-delivers it.
     */
    @Test
    @DisplayName("FM-06.08-1: a DDL refused with a non-retryable code is loud and never acknowledged")
    public void ddlRejectedWithNonRetryableCodeIsNotAcknowledged() throws Exception {
        RecordingCommitter committer = new RecordingCommitter();
        DebeziumChangeEventCapture capture = singleThreadedCapture(refusingConnection("ALTER TABLE",
                "Code: 62. DB::Exception: Syntax error: failed at position 1"));

        DDLReplicationException thrown = assertThrows(DDLReplicationException.class,
                () -> invokeProcess(capture, ddlEvent("ALTER TABLE t MODIFY COLUMN c BIGINT NULL"),
                        config(), committer.proxy()));
        SQLException cause = sqlCause(thrown);
        assertNotNull(cause, "the ClickHouse refusal is the cause");
        assertTrue(cause.getMessage().contains("Code: 62"), cause.getMessage());
        assertFalse(committer.calls.contains("markProcessed"),
                "the offset of a DDL that did not reach ClickHouse must never be acknowledged: " + committer.calls);
    }

    /**
     * FM-06.08-2 (DEFECT). A DDL refused with any code NOT in
     * {@code DBMetadata.NON_RETRYABLE_ERROR_CODES} -- e.g. {@code Code: 524
     * ALTER_OF_COLUMN_IS_FORBIDDEN}, {@code Code: 36 BAD_ARGUMENTS}, {@code
     * Code: 10} on a replayed MODIFY, {@code Code: 159} (ON CLUSTER timeout),
     * {@code Code: 242} (read-only replica), or a connection refused while
     * ClickHouse restarts -- is retried inside
     * {@code DBMetadata.executeSystemQuery}, which then RETURNS NORMALLY once
     * its budget is spent. {@code executeDDL} therefore reports success,
     * {@code performDDLOperation} acknowledges the DDL offset and the row
     * stream continues against a schema that never changed.
     *
     * <p>The budget is cut to one attempt so the test does not sleep out the
     * 45 s of the real linear backoff; the outcome is the same.</p>
     */
    @Test
    @Disabled("DEFECT FM-06.08-2: DBMetadata.executeSystemQuery returns normally after exhausting retries on a "
            + "retryable ClickHouse error, so a refused DDL is acknowledged as applied")
    @DisplayName("FM-06.08-2: a DDL refused with a retryable code is loud and never acknowledged")
    public void ddlRejectedWithRetryableCodeIsLoudAndNotAcknowledged() throws Exception {
        DBMetadata.setMaxRetries(1);
        try {
            RecordingCommitter committer = new RecordingCommitter();
            DebeziumChangeEventCapture capture = singleThreadedCapture(refusingConnection("ALTER TABLE",
                    "Code: 524. DB::Exception: ALTER of key column c is forbidden. (ALTER_OF_COLUMN_IS_FORBIDDEN)"));

            assertThrows(DDLReplicationException.class,
                    () -> invokeProcess(capture, ddlEvent("ALTER TABLE t MODIFY COLUMN c BIGINT NULL"),
                            config(), committer.proxy()),
                    "a DDL ClickHouse refused must halt the pipeline, whatever the error code");
            assertFalse(committer.calls.contains("markProcessed"),
                    "the offset of a refused DDL was acknowledged as if it had been applied: " + committer.calls);
        } finally {
            DBMetadata.setMaxRetries(10);
        }
    }

    /**
     * FM-06.03-1: a statement the ANTLR grammar rejects
     * ({@code ErrorListenerImpl} throws "Error parsing DDL") halts the
     * pipeline with {@link DDLReplicationException} naming the statement;
     * nothing is executed and the offset is not acknowledged.
     */
    @Test
    @DisplayName("FM-06.03-1: an unparseable DDL halts the pipeline and is never acknowledged")
    public void unparseableDdlHaltsWithoutAcknowledging() throws Exception {
        RecordingCommitter committer = new RecordingCommitter();
        DebeziumChangeEventCapture capture = singleThreadedCapture(refusingConnection("ALTER TABLE",
                "Code: 62. DB::Exception: must not be reached"));
        String ddl = "ALTER TABLE t FROBNICATE COLUMN c";

        DDLReplicationException thrown = assertThrows(DDLReplicationException.class,
                () -> invokeProcess(capture, ddlEvent(ddl), config(), committer.proxy()));
        assertTrue(thrown.getMessage().contains(ddl), "the loud failure names the statement: " + thrown.getMessage());
        boolean parseCause = false;
        for (Throwable c = thrown; c != null; c = c.getCause()) {
            if (c.getMessage() != null && c.getMessage().contains("Error parsing DDL")) {
                parseCause = true;
            }
        }
        assertTrue(parseCause, "the cause is the parser's refusal");
        assertTrue(sqlCause(thrown) == null, "nothing was sent to ClickHouse");
        assertFalse(committer.calls.contains("markProcessed"), String.valueOf(committer.calls));
    }

    /**
     * FM-06.01-3: a DDL attempt that fails at the barrier (here: the drain is
     * interrupted, as on engine shutdown) leaves the writer pool RESUMED, so
     * the failure is a retryable event rather than a silent stall of every
     * table.
     */
    @Test
    @DisplayName("FM-06.01-3: a DDL that fails at the barrier leaves the worker pool resumed")
    public void failedDdlLeavesThePoolResumed() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        ClickHouseBatchExecutor executor = new ClickHouseBatchExecutor(1, FACTORY);
        LinkedBlockingQueue<List<ClickHouseStruct>> records = new LinkedBlockingQueue<>();
        try {
            records.put(new ArrayList<>());
            setField(capture, "executor", executor);
            setField(capture, "records", records);
            RecordingCommitter committer = new RecordingCommitter();

            Thread.currentThread().interrupt();
            assertThrows(DDLReplicationException.class,
                    () -> invokeProcess(capture, ddlEvent("ALTER TABLE t ADD COLUMN c INT NULL"), config(),
                            committer.proxy()));
            Thread.interrupted();

            Field paused = ClickHouseBatchExecutor.class.getDeclaredField("isPaused");
            paused.setAccessible(true);
            assertFalse((Boolean) paused.get(executor),
                    "the pool must be resumed after a failed DDL attempt, or every table stops silently");
            assertFalse(committer.calls.contains("markProcessed"), String.valueOf(committer.calls));
            assertTrue(records.size() == 1, "the pending batch is never discarded by a failed drain");
        } finally {
            Thread.interrupted();
            executor.shutdownNow();
        }
    }
}

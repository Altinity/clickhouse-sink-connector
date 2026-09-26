package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.config.SinkConnectorLightWeightConfig;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchExecutor;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A DDL change event that cannot be applied must halt the pipeline LOUDLY,
 * not be swallowed while the row stream and its offsets march on.
 *
 * <p><b>The defect.</b> {@code processEveryChangeRecord} handles a DDL event
 * inside the same try whose catch-all is
 * {@code catch (Exception e) { log.error("Exception processing record", e); }}.
 * That catch exists to keep one malformed DML record from killing the stream.
 * A DDL failure is the opposite case: {@code drainBeforeDDL()} aborting, or
 * {@code performDDLOperation()} exhausting its retries, threw a plain
 * exception that the catch-all absorbed. {@code handleChangeEventBatch} then
 * carried on, committed offsets past the unapplied DDL, and every later row
 * was written against a ClickHouse schema that no longer matched MySQL — a
 * silent, count-clean divergence. In single-threaded mode the same path NPE'd
 * on a null executor and dropped the FIRST DDL the same way.</p>
 *
 * <p><b>The fix.</b> DDL failures are raised as {@link DDLReplicationException}
 * and re-thrown ahead of the catch-all, so the failure leaves
 * {@code processEveryChangeRecord} and halts the engine. Offsets are not
 * advanced past the DDL; a restart re-delivers it from the last committed
 * position — the genuine retry the drain abort was always meant to enable.
 * Single-threaded mode has no pool to drain, so the drain is a no-op there
 * instead of an NPE.</p>
 *
 * <p>The tests drive {@code processEveryChangeRecord} / {@code drainBeforeDDL}
 * reflectively (their only caller is the live Debezium batch handler). No
 * ClickHouse, MySQL or Debezium engine is required. The drain is made to abort
 * instantly by interrupting the calling thread, so the test does not wait out
 * {@code DDL_DRAIN_TIMEOUT_MS}.</p>
 */
public class DdlFailureLoudTest {

    private static final ThreadFactory FACTORY = r -> {
        Thread t = new Thread(r, "ddl-failure-loud-test");
        t.setDaemon(true);
        return t;
    };

    /** A DDL change event: a Struct carrying a {@code ddl} field, no {@code op}. */
    private static ChangeEvent<SourceRecord, SourceRecord> ddlEvent(String ddl) {
        Schema schema = SchemaBuilder.struct()
                .field("ddl", Schema.STRING_SCHEMA)
                .build();
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
                                        ChangeEvent<SourceRecord, SourceRecord> record)
            throws Exception {
        return invokeProcess(capture, record, new Properties(), null);
    }

    private static Object invokeProcess(DebeziumChangeEventCapture capture,
                                        ChangeEvent<SourceRecord, SourceRecord> record,
                                        Properties props,
                                        ClickHouseSinkConnectorConfig config)
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
            // Mirrors handleBatch: the version 1000000001 = effectiveTs 1000 * 1e6 + 1.
            return m.invoke(capture, props, record, null, config, null, true,
                    new DebeziumChangeEventCapture.VersionAssignment(1000000001L, 1000L));
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw ite;
        }
    }

    private static void invokeDrain(DebeziumChangeEventCapture capture) throws Exception {
        Method drain = DebeziumChangeEventCapture.class.getDeclaredMethod("drainBeforeDDL");
        drain.setAccessible(true);
        try {
            drain.invoke(capture);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw ite;
        }
    }

    /**
     * The regression: a DDL whose drain aborts must throw
     * {@link DDLReplicationException} out of {@code processEveryChangeRecord},
     * never be swallowed and return normally.
     *
     * <p>Against the pre-fix code this fails: the drain's
     * {@code IllegalStateException} is caught by the method's catch-all,
     * logged, and the method returns {@code null} — the offsets then advance
     * past the DDL that never reached ClickHouse.</p>
     */
    @Test
    @DisplayName("A DDL that cannot be applied halts the pipeline loudly instead of being swallowed")
    public void ddlFailurePropagatesInsteadOfBeingSwallowed() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        ClickHouseBatchExecutor executor = new ClickHouseBatchExecutor(2, FACTORY);
        LinkedBlockingQueue<List<ClickHouseStruct>> records = new LinkedBlockingQueue<>();

        try {
            // Non-empty queue with no consumer: the drain cannot make progress.
            records.put(new ArrayList<>());
            setField(capture, "executor", executor);
            setField(capture, "records", records);

            // Interrupt this thread so the drain's first sleep throws at once,
            // aborting the drain immediately instead of after the full timeout.
            Thread.currentThread().interrupt();

            DDLReplicationException thrown = assertThrows(DDLReplicationException.class,
                    () -> invokeProcess(capture, ddlEvent("ALTER TABLE t ADD COLUMN c INT")),
                    "a DDL that cannot be applied must propagate DDLReplicationException, not be "
                            + "swallowed by the catch-all while offsets advance past it");
            assertInstanceOf(IllegalStateException.class, thrown.getCause(),
                    "the loud failure must carry the underlying drain abort as its cause");
        } finally {
            // Clear the interrupt so it cannot leak into later tests.
            Thread.interrupted();
            executor.shutdownNow();
        }
    }

    /**
     * Single-threaded mode has no worker pool: {@code this.executor} is null.
     * The drain must be a no-op there, not an NPE that drops the first DDL.
     */
    @Test
    @DisplayName("drainBeforeDDL is a no-op in single-threaded mode (null executor), not an NPE")
    public void drainIsNoOpWhenExecutorIsNull() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        LinkedBlockingQueue<List<ClickHouseStruct>> records = new LinkedBlockingQueue<>();
        // A non-empty queue proves the guard short-circuits before touching it.
        records.put(new ArrayList<>());
        setField(capture, "executor", null);
        setField(capture, "records", records);

        assertDoesNotThrow(() -> invokeDrain(capture),
                "with no worker pool the drain must return immediately; rows are already "
                        + "persisted inline, so there is nothing to drain and no executor to pause");
        assertTrue(records.size() == 1,
                "the guard must return before consuming the queue in single-threaded mode");
    }


    // ------------------------------------------------------------------
    // DDL EXECUTION failure (the statement itself is rejected by ClickHouse)
    // ------------------------------------------------------------------

    /** A deterministic, non-retryable ClickHouse rejection of the ALTER. */
    private static final String CLICKHOUSE_REJECTION =
            "Code: 62. DB::Exception: Syntax error: failed at position 1";

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
     * A JDBC connection that reports itself open and rejects every statement
     * with {@link #CLICKHOUSE_REJECTION}. That is what the writer sees when
     * ClickHouse refuses the translated DDL.
     */
    private static Connection rejectingConnection() {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "isClosed":
                    return false;
                case "close":
                    return null;
                case "prepareStatement":
                case "createStatement":
                    throw new SQLException(CLICKHOUSE_REJECTION);
                case "toString":
                    return "rejecting-connection";
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
        return new ClickHouseSinkConnectorConfig(props);
    }

    /**
     * A capture in single-threaded mode (no pool, so the drain is a no-op and
     * the DDL execution itself is what is exercised) whose DDL writer talks to
     * a ClickHouse that rejects the statement.
     */
    private static DebeziumChangeEventCapture singleThreadedCaptureWithRejectingWriter() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        setField(capture, "executor", null);
        // getDatabaseName() consults the source-side naming options; give it
        // the defaults, as setup() would.
        setField(capture, "pgConfig", new PostgresConnectorConfig(new Properties()));
        capture.setWriter(new BaseDbWriter("localhost", 8123, "system", "default", "",
                config(), rejectingConnection()));
        return capture;
    }

    /** The first SQLException in the cause chain, or null. */
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
     * The regression for the default configuration. {@code ddl.retry} is unset,
     * ClickHouse rejects the ALTER: the pipeline must halt loudly.
     *
     * <p>Against the pre-fix code this fails: {@code performDDLOperation} hit
     * {@code if (retryDDLProperty == false) break;} BEFORE the retry-exhaustion
     * guard that throws, so the loop was left normally, the method returned
     * null, and the row stream carried on against a schema that no longer
     * matched MySQL.</p>
     */
    @Test
    @DisplayName("A DDL that ClickHouse rejects halts the pipeline loudly even when ddl.retry is not enabled")
    public void ddlExecutionFailureWithoutRetryIsLoud() throws Exception {
        DebeziumChangeEventCapture capture = singleThreadedCaptureWithRejectingWriter();
        Properties props = new Properties();   // ddl.retry deliberately absent: the default

        DDLReplicationException thrown = assertThrows(DDLReplicationException.class,
                () -> invokeProcess(capture, ddlEvent("ALTER TABLE t ADD COLUMN c INT NULL"),
                        props, config()),
                "a DDL that ClickHouse rejects must halt the pipeline with DDLReplicationException; "
                        + "with ddl.retry unset the failure was logged and skipped, and later rows were "
                        + "written against a schema missing the column");
        SQLException cause = sqlCause(thrown);
        assertNotNull(cause, "the loud failure must carry the ClickHouse rejection as its cause");
        assertTrue(cause.getMessage().contains("Code: 62"),
                "the cause must be the actual ClickHouse error: " + cause.getMessage());
    }

    /**
     * With {@code ddl.retry=true} the attempts are repeated, and when the
     * budget is exhausted the failure is just as loud -- and names the last
     * ClickHouse error as its cause.
     *
     * <p>The retry budget is cut to one attempt and the calling thread is
     * interrupted so the 10 s back-off returns at once; the test does not wait
     * out the real budget.</p>
     */
    @Test
    @DisplayName("With ddl.retry=true an exhausted retry budget halts the pipeline loudly, naming the last failure")
    public void ddlExecutionFailureAfterRetriesExhaustedIsLoud() throws Exception {
        int savedRetries = DebeziumChangeEventCapture.MAX_RETRIES;
        DebeziumChangeEventCapture.MAX_RETRIES = 1;
        try {
            DebeziumChangeEventCapture capture = singleThreadedCaptureWithRejectingWriter();
            Properties props = new Properties();
            props.setProperty(SinkConnectorLightWeightConfig.DDL_RETRY, "true");

            Thread.currentThread().interrupt();

            DDLReplicationException thrown = assertThrows(DDLReplicationException.class,
                    () -> invokeProcess(capture, ddlEvent("ALTER TABLE t ADD COLUMN c INT NULL"),
                            props, config()),
                    "an exhausted DDL retry budget must halt the pipeline with DDLReplicationException");
            assertTrue(thrown.getMessage().contains("Max retries"),
                    "the terminal failure must say the retry budget was exhausted: "
                            + thrown.getMessage());
            assertNotNull(sqlCause(thrown),
                    "the terminal failure must carry the last ClickHouse error as its cause, "
                            + "not a null cause");
        } finally {
            Thread.interrupted();
            DebeziumChangeEventCapture.MAX_RETRIES = savedRetries;
        }
    }
}

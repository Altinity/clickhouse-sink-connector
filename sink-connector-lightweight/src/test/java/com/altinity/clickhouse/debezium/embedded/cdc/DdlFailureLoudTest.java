package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
        Method m = DebeziumChangeEventCapture.class.getDeclaredMethod(
                "processEveryChangeRecord",
                Properties.class,
                ChangeEvent.class,
                DebeziumRecordParserService.class,
                ClickHouseSinkConnectorConfig.class,
                DebeziumEngine.RecordCommitter.class,
                boolean.class,
                long.class);
        m.setAccessible(true);
        try {
            return m.invoke(capture, new Properties(), record, null, null, null, true, 1000000001L);
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
}

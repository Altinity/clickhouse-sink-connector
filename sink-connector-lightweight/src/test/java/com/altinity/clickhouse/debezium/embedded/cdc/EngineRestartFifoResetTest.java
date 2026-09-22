package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchExecutor;
import com.altinity.clickhouse.sink.connector.executor.DebeziumOffsetManagement;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.RoutedBatch;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An in-process engine restart must not poison the offset FIFO
 * (spec 09.01 §3.8, spec 01.01 §3.3).
 *
 * <p><b>The defect.</b> {@code DebeziumOffsetManagement}'s bookkeeping is
 * static, but the embedded engine is restarted inside the process by the REST
 * API ({@code /restart}, {@code /start} after {@code /stop}) and by the
 * restart monitor: a NEW {@code DebeziumChangeEventCapture} on the SAME FIFO.
 * The old {@code stop()} shut the worker pool down FIRST -- abandoning every
 * queued batch -- then closed the engine, and never touched the FIFO. A unit
 * the old engine had handed off but no worker had written stayed the FIFO head
 * for the life of the JVM: every unit of the new engine parked behind it,
 * nothing was ever acknowledged again, {@code hasUnwrittenBatches()} stayed
 * true (no control-record commit, every DDL drain timed out into a restart
 * loop), inserts kept flowing while the durable offset froze, and the parked
 * units' record lists leaked.</p>
 *
 * <p><b>The rule.</b> {@code stop()} closes the engine first (no more
 * handoffs), lets the still-running pool drain what is queued (bounded),
 * shuts the pool down, and only then resets the FIFO: whatever is still
 * outstanding is abandoned -- never acknowledged, so the next engine
 * redelivers it from the last committed offset. {@code setup()} refuses to
 * start a new engine while anything is outstanding.</p>
 *
 * <p>The tests wire a real {@link ClickHouseBatchExecutor} and real queues
 * exactly as {@code setupProcessingThread} does; no ClickHouse, MySQL or
 * Debezium engine is required.</p>
 */
public class EngineRestartFifoResetTest {

    private static final ThreadFactory FACTORY = r -> {
        Thread t = new Thread(r, "engine-restart-fifo-test");
        t.setDaemon(true);
        return t;
    };

    private long savedStopDrainTimeout;

    @BeforeEach
    public void resetFifo() throws Exception {
        clearFifo();
        savedStopDrainTimeout = DebeziumChangeEventCapture.stopDrainTimeoutMs;
    }

    @AfterEach
    public void restore() throws Exception {
        DebeziumChangeEventCapture.stopDrainTimeoutMs = savedStopDrainTimeout;
        clearFifo();
    }

    /** Records what the engine's committer was asked to do. */
    private static final class RecordingCommitter
            implements DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> {

        final List<ChangeEvent<SourceRecord, SourceRecord>> processed = new ArrayList<>();
        int batchesFinished = 0;

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record) {
            processed.add(record);
        }

        @Override
        public void markBatchFinished() {
            batchesFinished++;
        }

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record,
                                  DebeziumEngine.Offsets sourceOffsets) {
            processed.add(record);
        }

        @Override
        public DebeziumEngine.Offsets buildOffsets() {
            return (key, value) -> { };
        }
    }

    /** A stand-in engine that records whether the pool was still alive when it was closed. */
    private static final class ObservingEngine
            implements DebeziumEngine<ChangeEvent<SourceRecord, SourceRecord>> {
        final ClickHouseBatchExecutor pool;
        final AtomicBoolean closed = new AtomicBoolean(false);
        final AtomicBoolean poolWasShutDownAtClose = new AtomicBoolean(true);

        ObservingEngine(ClickHouseBatchExecutor pool) {
            this.pool = pool;
        }

        @Override
        public void run() {
        }

        @Override
        public void close() {
            poolWasShutDownAtClose.set(pool.isShutdown());
            closed.set(true);
        }
    }

    private static ChangeEvent<SourceRecord, SourceRecord> changeEvent(SourceRecord record) {
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
                return record == null ? "orders" : record.topic();
            }

            @Override
            public Integer partition() {
                return null;
            }
        };
    }

    /** A heartbeat exactly as Debezium emits one: {@code ts_ms} only, no {@code op}. */
    private static ChangeEvent<SourceRecord, SourceRecord> heartbeat() {
        Schema valueSchema = SchemaBuilder.struct()
                .name("io.debezium.connector.common.Heartbeat")
                .field("ts_ms", Schema.INT64_SCHEMA)
                .build();
        Struct value = new Struct(valueSchema);
        value.put("ts_ms", 1788182208680L);
        Map<String, Object> partition = new LinkedHashMap<>();
        partition.put("server", "db1");
        Map<String, Object> offset = new LinkedHashMap<>();
        offset.put("file", "binlog.000042");
        offset.put("pos", 10240L);
        return changeEvent(new SourceRecord(partition, offset, "__debezium-heartbeat.db1", 0,
                null, null, valueSchema, value));
    }

    /** A handed-off unit whose terminal record is wired to {@code committer}. */
    private static List<ClickHouseStruct> unit(RecordingCommitter committer) {
        ClickHouseStruct s = new ClickHouseStruct();
        s.setTopic("db1.shop.orders");
        s.setCommitter(committer);
        s.setSourceRecord(changeEvent(null));
        s.setLastRecordInBatch(true);
        return Collections.singletonList(s);
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        ClickHouseSinkConnectorConfig.setDefaultValues(props);
        return new ClickHouseSinkConnectorConfig(props);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = DebeziumChangeEventCapture.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** Wires the capture as setupProcessingThread does for hash routing. */
    private static List<LinkedBlockingQueue<RoutedBatch>> wire(DebeziumChangeEventCapture capture,
                                                             ClickHouseBatchExecutor executor,
                                                             int threads) throws Exception {
        List<LinkedBlockingQueue<RoutedBatch>> routed = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            routed.add(new LinkedBlockingQueue<>());
        }
        setField(capture, "executor", executor);
        setField(capture, "records", new LinkedBlockingQueue<List<ClickHouseStruct>>());
        setField(capture, "routedQueues", routed);
        setField(capture, "threadPoolSize", threads);
        return routed;
    }

    @SuppressWarnings("unchecked")
    private static int sizeOf(String fifoField) throws Exception {
        Field f = DebeziumOffsetManagement.class.getDeclaredField(fifoField);
        f.setAccessible(true);
        Object c = f.get(null);
        return c instanceof Map ? ((Map<Object, Object>) c).size()
                : ((java.util.Collection<Object>) c).size();
    }

    private static void clearFifo() throws Exception {
        for (String name : new String[] {"outstandingSequences", "groupToUnit", "completedUnits"}) {
            Field f = DebeziumOffsetManagement.class.getDeclaredField(name);
            f.setAccessible(true);
            Object c = f.get(null);
            if (c instanceof Map) {
                ((Map<?, ?>) c).clear();
            } else {
                ((java.util.Collection<?>) c).clear();
            }
        }
    }

    /**
     * The regression. A unit handed off by the old engine and never written
     * (its worker was gone) must not block the new engine.
     *
     * <p>Against the old {@code stop()} this fails at the first assertion:
     * the unit stays outstanding, so the new engine's heartbeat is never
     * committed and its own unit is parked behind the ghost.</p>
     */
    @Test
    @DisplayName("A handoff never written is abandoned by stop(); the next engine's heartbeat commits and its first unit is acknowledged")
    public void stopThenStartNewInstanceIsNotPoisoned() throws Exception {
        DebeziumChangeEventCapture.stopDrainTimeoutMs = 300;
        DebeziumChangeEventCapture old = new DebeziumChangeEventCapture();
        ClickHouseBatchExecutor oldPool = new ClickHouseBatchExecutor(2, FACTORY);
        wire(old, oldPool, 2);

        // The old engine handed this off; no worker ever wrote it.
        List<ClickHouseStruct> ghost = unit(new RecordingCommitter());
        DebeziumOffsetManagement.registerHandoff(ghost, Collections.singletonList(ghost));
        assertTrue(DebeziumOffsetManagement.hasUnwrittenBatches(), "sanity: the ghost is outstanding");

        old.stop();

        assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches(),
                "stop() must abandon the unit its terminated pool can never write; leaving it "
                        + "outstanding parks every unit of the next engine behind it forever");

        // The new engine, same JVM, same static FIFO.
        DebeziumChangeEventCapture fresh = new DebeziumChangeEventCapture();
        RecordingCommitter heartbeatCommitter = new RecordingCommitter();
        fresh.handleChangeEventBatch(Collections.singletonList(heartbeat()), heartbeatCommitter,
                new Properties(), new SourceRecordParserService(), config());
        assertEquals(1, heartbeatCommitter.processed.size(),
                "the new engine's first heartbeat must be committed (the pipeline IS quiescent)");
        assertEquals(1, heartbeatCommitter.batchesFinished);

        RecordingCommitter committer = new RecordingCommitter();
        List<ClickHouseStruct> first = unit(committer);
        DebeziumOffsetManagement.registerHandoff(first, Collections.singletonList(first));
        assertTrue(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(first),
                "the new engine's first written unit must be acknowledged immediately: it is the "
                        + "FIFO head, not parked behind the old engine's ghost");
        assertEquals(1, committer.processed.size());
        assertEquals(1, committer.batchesFinished);
        assertEquals(0, sizeOf("completedUnits"), "nothing may stay parked");
        assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches());
    }

    @Test
    @DisplayName("stop() leaves nothing outstanding, unwritten or parked")
    public void stopLeavesNothingOutstanding() throws Exception {
        DebeziumChangeEventCapture.stopDrainTimeoutMs = 300;
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        ClickHouseBatchExecutor pool = new ClickHouseBatchExecutor(2, FACTORY);
        wire(capture, pool, 2);

        // One unit unwritten, one written-but-parked behind it.
        List<ClickHouseStruct> unwritten = unit(new RecordingCommitter());
        DebeziumOffsetManagement.registerHandoff(unwritten, Collections.singletonList(unwritten));
        List<ClickHouseStruct> parked = unit(new RecordingCommitter());
        DebeziumOffsetManagement.registerHandoff(parked, Collections.singletonList(parked));
        assertFalse(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(parked), "sanity: parked");
        assertEquals(1, sizeOf("completedUnits"));

        capture.stop();

        assertEquals(0, DebeziumOffsetManagement.outstandingCount(), "outstanding must be empty");
        assertEquals(0, sizeOf("groupToUnit"), "no unwritten group may be tracked");
        assertEquals(0, sizeOf("completedUnits"), "no parked unit may be retained (heap leak)");
        assertTrue(pool.isShutdown(), "the pool must be shut down");
    }

    /**
     * Order matters: the engine (the producer) must be closed while the pool
     * is still alive, so nothing can be handed off after the pool stops
     * consuming, and queued work has a chance to drain.
     *
     * <p>Against the old {@code stop()} this fails: the pool was shut down
     * first, and the engine closed last.</p>
     */
    @Test
    @DisplayName("stop() closes the engine before it shuts the worker pool down")
    public void stopClosesEngineBeforeShuttingThePool() throws Exception {
        DebeziumChangeEventCapture.stopDrainTimeoutMs = 300;
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        ClickHouseBatchExecutor pool = new ClickHouseBatchExecutor(2, FACTORY);
        wire(capture, pool, 2);
        ObservingEngine engine = new ObservingEngine(pool);
        capture.engine = engine;

        capture.stop();

        assertTrue(engine.closed.get(), "the engine must be closed by stop()");
        assertFalse(engine.poolWasShutDownAtClose.get(),
                "the pool must still be running when the engine is closed: shutting it down first "
                        + "abandons every queued batch and lets the closing engine hand off more");
        assertTrue(pool.isShutdown(), "the pool must be shut down after the engine is closed");
    }

    /**
     * Work that a live worker CAN still finish must be drained and
     * acknowledged, not abandoned: abandoning it costs a redelivery on the next
     * start for no reason.
     *
     * <p>Against the old {@code stop()} this fails: {@code shutdown()} cancels
     * the periodic worker before it runs, the unit is never written, and
     * nothing is acknowledged.</p>
     */
    @Test
    @DisplayName("stop() drains in-flight work through the still-running pool before shutting it down")
    public void stopDrainsInFlightWorkBeforeShuttingThePool() throws Exception {
        DebeziumChangeEventCapture.stopDrainTimeoutMs = 5_000;
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        ClickHouseBatchExecutor pool = new ClickHouseBatchExecutor(2, FACTORY);
        wire(capture, pool, 2);

        RecordingCommitter committer = new RecordingCommitter();
        List<ClickHouseStruct> inFlight = unit(committer);
        DebeziumOffsetManagement.registerHandoff(inFlight, Collections.singletonList(inFlight));

        // The owning worker, as ClickHouseBatchRunnable: scheduled on the pool,
        // finishes the write 300 ms from now and reports it.
        AtomicInteger writes = new AtomicInteger();
        long releaseAt = System.currentTimeMillis() + 300;
        pool.scheduleAtFixedRate(() -> {
            if (System.currentTimeMillis() >= releaseAt && writes.compareAndSet(0, 1)) {
                try {
                    DebeziumOffsetManagement.checkIfBatchCanBeCommitted(inFlight);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }, 0, 10, TimeUnit.MILLISECONDS);

        int abandoned = capture.stop();

        assertEquals(1, writes.get(), "the worker must have been allowed to finish the write");
        assertEquals(1, committer.processed.size(),
                "the drained unit must be acknowledged, not abandoned to a needless redelivery");
        assertEquals(1, committer.batchesFinished);
        assertEquals(0, abandoned, "nothing was left to abandon");
        assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches());
    }

    /**
     * Starting a new engine on a FIFO that still has outstanding units would
     * park every new unit behind them. Refuse, loudly, rather than start a
     * connector that can never acknowledge an offset.
     */
    @Test
    @DisplayName("setup() refuses to start while handed-off batches are still unacknowledged")
    public void setupRefusesWhileBatchesAreOutstanding() {
        List<ClickHouseStruct> ghost = unit(new RecordingCommitter());
        DebeziumOffsetManagement.registerHandoff(ghost, Collections.singletonList(ghost));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new DebeziumChangeEventCapture().setup(new Properties(), null, false),
                "a new engine must not be started on top of unacknowledged units");
        assertTrue(ex.getMessage().contains("unacknowledged"),
                "the refusal must say why: " + ex.getMessage());
    }
}

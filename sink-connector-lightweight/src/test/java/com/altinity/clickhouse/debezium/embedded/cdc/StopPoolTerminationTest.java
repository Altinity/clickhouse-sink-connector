package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchExecutor;
import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchRunnable;
import com.altinity.clickhouse.sink.connector.executor.DebeziumOffsetManagement;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.RoutedBatch;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what {@code stop()} does with the batch worker pool's await result
 * (spec 01.01 section 3.3, steps 4, 4a, 5 and 6).
 *
 * <p>A pool that does not terminate within its bound still has workers inside
 * a write, a retry or a backoff. Closing their connections, resetting the FIFO
 * or clearing the active engine would pull state out from under them. The rule
 * under test: only a terminated pool is torn down. A pool still running keeps
 * its connections, its FIFO units and the active engine; the stop is logged at
 * ERROR; and a daemon thread closes the connections once the pool terminates.</p>
 *
 * <p>The tests use a real {@link ClickHouseBatchExecutor}, a real task that
 * blocks like a worker in a write, and a counting subclass of
 * {@link ClickHouseBatchRunnable} that records {@code closeConnections()}. No
 * ClickHouse, MySQL or Debezium engine is required.</p>
 */
public class StopPoolTerminationTest {

    private static final ThreadFactory FACTORY = r -> {
        Thread t = new Thread(r, "stop-pool-termination-test");
        t.setDaemon(true);
        return t;
    };

    private long savedStopDrainTimeout;
    private long savedStopPoolTerminationTimeout;

    @BeforeEach
    public void resetState() throws Exception {
        clearFifo();
        setActiveEngine(null);
        savedStopDrainTimeout = DebeziumChangeEventCapture.stopDrainTimeoutMs;
        savedStopPoolTerminationTimeout = DebeziumChangeEventCapture.stopPoolTerminationTimeoutMs;
    }

    @AfterEach
    public void restoreState() throws Exception {
        DebeziumChangeEventCapture.stopDrainTimeoutMs = savedStopDrainTimeout;
        DebeziumChangeEventCapture.stopPoolTerminationTimeoutMs = savedStopPoolTerminationTimeout;
        clearFifo();
        setActiveEngine(null);
    }

    /**
     * A pool that does not terminate within its bound: the worker is still in
     * a write when {@code stop()} runs. Nothing a running worker uses may be
     * released, and the stop returns without abandoning anything itself. Once
     * the worker finishes, the deferred close releases its connections.
     */
    @Test
    @DisplayName("A pool that does not terminate in time keeps its connections, FIFO units and active engine, and its connections are closed once it terminates")
    public void stopWhenPoolDoesNotTerminateKeepsConnectionsAndFifo() throws Exception {
        DebeziumChangeEventCapture.stopDrainTimeoutMs = 300;
        DebeziumChangeEventCapture.stopPoolTerminationTimeoutMs = 300;
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        ClickHouseBatchExecutor pool = new ClickHouseBatchExecutor(2, FACTORY);
        wire(capture, pool, 2);
        CountingWorker worker = new CountingWorker(pool);
        capture.workerRunnables.add(worker);
        setActiveEngine(capture);

        // A worker still inside a write: it returns only when released.
        CountDownLatch inWrite = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pool.execute(() -> {
            inWrite.countDown();
            try {
                release.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(inWrite.await(10, TimeUnit.SECONDS), "sanity: the worker is inside its write");

        // A unit the running worker has not yet written.
        List<ClickHouseStruct> pending = unit();
        DebeziumOffsetManagement.registerHandoff(pending, Collections.singletonList(pending));

        int abandoned = capture.stop();

        assertFalse(pool.isTerminated(), "sanity: the pool did not terminate within its bound");
        assertEquals(0, abandoned, "a stop whose pool is still running abandons nothing itself");
        assertEquals(0, worker.closeCalls.get(),
                "a worker still running keeps its connections open");
        assertTrue(DebeziumOffsetManagement.hasUnwrittenBatches(),
                "the FIFO keeps the unit the running worker can still report");
        assertSame(capture, activeEngine(),
                "the active engine is kept while its workers still use it");

        // The worker finishes; the deferred close releases its connections only now.
        release.countDown();
        assertTrue(worker.closed.await(10, TimeUnit.SECONDS),
                "the connections are closed once the pool terminates");
        assertTrue(worker.closedWithPoolTerminated.get(),
                "the connections are closed only after the pool has terminated");
        assertEquals(1, worker.closeCalls.get());
    }

    /**
     * The normal path, unchanged: the pool is idle and terminates within its
     * bound, so the connections are closed, the FIFO is reset, the abandoned
     * count is returned and the active engine is cleared, all by the stop
     * itself.
     */
    @Test
    @DisplayName("A pool that terminates in time is torn down as before: connections closed, FIFO reset, active engine cleared")
    public void stopWhenPoolTerminatesInTimeTearsDownAsBefore() throws Exception {
        DebeziumChangeEventCapture.stopDrainTimeoutMs = 300;
        DebeziumChangeEventCapture.stopPoolTerminationTimeoutMs = 5_000;
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        ClickHouseBatchExecutor pool = new ClickHouseBatchExecutor(2, FACTORY);
        wire(capture, pool, 2);
        CountingWorker worker = new CountingWorker(pool);
        capture.workerRunnables.add(worker);
        setActiveEngine(capture);

        // A unit no worker wrote.
        List<ClickHouseStruct> pending = unit();
        DebeziumOffsetManagement.registerHandoff(pending, Collections.singletonList(pending));

        int abandoned = capture.stop();

        assertEquals(1, abandoned, "the unit no worker wrote is abandoned by the FIFO reset");
        assertEquals(1, worker.closeCalls.get(),
                "the connections of a terminated pool's workers are closed by stop()");
        assertTrue(worker.closedWithPoolTerminated.get());
        assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches(),
                "the FIFO is reset once the pool has terminated");
        assertNull(activeEngine(), "the active engine is cleared once the pool has terminated");
    }

    /** A worker whose connection close is counted. It owns no real connections. */
    private static final class CountingWorker extends ClickHouseBatchRunnable {
        final AtomicInteger closeCalls = new AtomicInteger();
        final AtomicBoolean closedWithPoolTerminated = new AtomicBoolean(false);
        final CountDownLatch closed = new CountDownLatch(1);
        private final ClickHouseBatchExecutor pool;

        CountingWorker(ClickHouseBatchExecutor pool) {
            super(new LinkedBlockingQueue<RoutedBatch>(), 0, config(), new HashMap<String, String>());
            this.pool = pool;
        }

        @Override
        public synchronized void closeConnections() {
            closeCalls.incrementAndGet();
            closedWithPoolTerminated.set(pool.isTerminated());
            closed.countDown();
        }
    }

    /** A committer that records nothing: these tests never acknowledge a unit. */
    private static final class SilentCommitter
            implements DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> {

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record) {
        }

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record,
                                  DebeziumEngine.Offsets sourceOffsets) {
        }

        @Override
        public void markBatchFinished() {
        }

        @Override
        public DebeziumEngine.Offsets buildOffsets() {
            return (key, value) -> { };
        }
    }

    private static ChangeEvent<SourceRecord, SourceRecord> changeEvent() {
        return new ChangeEvent<SourceRecord, SourceRecord>() {
            @Override
            public SourceRecord key() {
                return null;
            }

            @Override
            public SourceRecord value() {
                return null;
            }

            @Override
            public String destination() {
                return "orders";
            }

            @Override
            public Integer partition() {
                return null;
            }
        };
    }

    /** A handed-off unit with one record. */
    private static List<ClickHouseStruct> unit() {
        ClickHouseStruct s = new ClickHouseStruct();
        s.setTopic("db1.shop.orders");
        s.setCommitter(new SilentCommitter());
        s.setSourceRecord(changeEvent());
        s.setLastRecordInBatch(true);
        return Collections.singletonList(s);
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        ClickHouseSinkConnectorConfig.setDefaultValues(props);
        props.put("database.connectionTimeZone", "UTC");
        props.put("clickhouse.datetime.timezone", "UTC");
        return new ClickHouseSinkConnectorConfig(props);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = DebeziumChangeEventCapture.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** Wires the capture as setupProcessingThread does for hash routing. */
    private static void wire(DebeziumChangeEventCapture capture, ClickHouseBatchExecutor executor,
                             int threads) throws Exception {
        List<LinkedBlockingQueue<RoutedBatch>> routed = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            routed.add(new LinkedBlockingQueue<>());
        }
        setField(capture, "executor", executor);
        setField(capture, "records", new LinkedBlockingQueue<List<ClickHouseStruct>>());
        setField(capture, "routedQueues", routed);
        setField(capture, "threadPoolSize", threads);
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

    private static void setActiveEngine(DebeziumChangeEventCapture engine) throws Exception {
        Field f = DebeziumChangeEventCapture.class.getDeclaredField("activeEngine");
        f.setAccessible(true);
        f.set(null, engine);
    }

    private static DebeziumChangeEventCapture activeEngine() throws Exception {
        Field f = DebeziumChangeEventCapture.class.getDeclaredField("activeEngine");
        f.setAccessible(true);
        return (DebeziumChangeEventCapture) f.get(null);
    }
}

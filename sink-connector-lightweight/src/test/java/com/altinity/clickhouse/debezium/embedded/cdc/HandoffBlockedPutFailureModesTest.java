package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.executor.DebeziumOffsetManagement;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.RoutedBatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failure modes of the enqueue step of the handoff (spec 01.05 section 6).
 *
 * <p>{@code appendToRecords} first waits at the hard cap (bounded by
 * {@code sink.connector.handoff.wait.timeout.ms}, with the dead-worker check
 * between 50 ms slices), then registers the unit and calls
 * {@code LinkedBlockingQueue.put} on the owning worker's queue. The queue is
 * bounded in BATCHES ({@code sink.connector.max.queue.size}) and a routed group
 * can hold a single row, so a queue can be full while the row and byte caps are
 * not met -- and {@code put} has neither a timeout nor a liveness check.</p>
 */
public class HandoffBlockedPutFailureModesTest {

    private static final int POOL = 2;

    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);

    @AfterEach
    public void cleanup() {
        executor.shutdownNow();
        Thread.interrupted();
        // Whatever these tests registered is abandoned so later tests start quiescent.
        DebeziumOffsetManagement.reset();
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

    /** Routing mode with one queue of capacity {@code capacity} per worker. */
    private static List<LinkedBlockingQueue<RoutedBatch>> wire(DebeziumChangeEventCapture capture, int capacity)
            throws Exception {
        List<LinkedBlockingQueue<RoutedBatch>> queues = new ArrayList<>();
        for (int i = 0; i < POOL; i++) {
            queues.add(new LinkedBlockingQueue<>(capacity));
        }
        Field pool = DebeziumChangeEventCapture.class.getDeclaredField("threadPoolSize");
        pool.setAccessible(true);
        pool.setInt(capture, POOL);
        setField(capture, "routedQueues", queues);
        setField(capture, "keyRoutingEnabled", false);
        return queues;
    }

    private static List<ClickHouseStruct> oneRowUnit() {
        ClickHouseStruct row = new ClickHouseStruct();
        row.setTopic("embeddedconnector.db.orders");
        List<ClickHouseStruct> unit = new ArrayList<>();
        unit.add(row);
        DebeziumChangeEventCapture.markTerminalRecord(unit);
        return unit;
    }

    private static void append(DebeziumChangeEventCapture capture, List<ClickHouseStruct> unit) throws Exception {
        Method m = DebeziumChangeEventCapture.class.getDeclaredMethod(
                "appendToRecords", List.class, ClickHouseSinkConnectorConfig.class);
        m.setAccessible(true);
        try {
            m.invoke(capture, unit, config());
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw ite;
        }
    }

    @Test
    @DisplayName("An interrupted handoff propagates and leaves the unit outstanding (spec 01.05 section 3.5)")
    public void interruptedPutKeepsTheUnitOutstanding() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        wire(capture, 10);
        assertEquals(0, DebeziumOffsetManagement.outstandingCount(), "sanity: quiescent FIFO");

        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> append(capture, oneRowUnit()),
                    "an interrupt during the handoff must leave handleChangeEventBatch and stop the engine");
        } finally {
            Thread.interrupted();
        }
        assertTrue(DebeziumOffsetManagement.hasUnwrittenBatches(),
                "the unit never reached a queue, so it must stay outstanding: no offset may pass it");
        assertEquals(1, DebeziumOffsetManagement.outstandingCount());
    }

    @Test
    @Disabled("DEFECT FM-01.05-4: LinkedBlockingQueue.put in appendToRecordsWithHashRouting has no timeout "
            + "and no dead-worker check; a worker that dies while the reader is blocked on its full queue "
            + "leaves the Debezium thread blocked forever with no error from the capture loop")
    @DisplayName("A worker that dies while the reader is blocked on its full queue stops the engine loudly")
    public void workerDeathWhileTheReaderIsBlockedInPutStopsTheEngine() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        List<LinkedBlockingQueue<RoutedBatch>> queues = wire(capture, 1);
        // Every queue is full (a slow worker's backlog in batches, far under the row cap).
        for (int i = 0; i < POOL; i++) {
            queues.get(i).put(new RoutedBatch(new ArrayList<>(), i, "orders", -1L));
        }
        // The worker is alive when the handoff starts and dies 300 ms later, exactly as
        // ClickHouseBatchRunnable#run does on a FATAL classification.
        ScheduledFuture<?> worker = executor.scheduleAtFixedRate(() -> {
            throw new RuntimeException("Fatal ClickHouse error, stopping task",
                    new RuntimeException("Code: 60. DB::Exception: Table db.orders does not exist"));
        }, 300, 10, TimeUnit.MILLISECONDS);
        capture.workerFutures.add(worker);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                        assertThrows(RuntimeException.class, () -> append(capture, oneRowUnit()),
                                "the dead worker's cause must end the handoff"),
                "the reader stayed blocked in put() on a queue whose only consumer is dead: replication "
                        + "stands still with no error from the capture loop (the worker logged its FATAL once)");
    }
}

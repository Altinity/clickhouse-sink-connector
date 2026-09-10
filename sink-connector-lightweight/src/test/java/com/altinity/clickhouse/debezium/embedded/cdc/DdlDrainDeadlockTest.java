package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchExecutor;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code drainBeforeDDL()} must not pause the writer pool before the handoff
 * queue has been drained.
 *
 * <p><b>The defect.</b> {@code pause()} parks every pool thread inside
 * {@code beforeExecute()}, so while it is in effect no thread can dequeue.
 * Draining after pausing therefore waits on a queue that is guaranteed never
 * to shrink: the loop burns the whole {@code DDL_DRAIN_TIMEOUT_MS} and then
 * throws. The throw is not a safe fallback -- the queued batches are dropped
 * with the aborted DDL attempt, so rows that MySQL holds never reach
 * ClickHouse. Observed in production on 2026-09-09 as a one-day, tail-shaped
 * shortfall of 78,049 rows in a single table, with every other day matching
 * exactly.</p>
 *
 * <p><b>Why draining first is safe.</b> The queue has exactly one producer:
 * {@code appendToRecords()}, reached only from
 * {@code handleChangeEventBatch()}, which runs on the same Debezium thread
 * that executes the drain. No batch can be appended while the drain is
 * running, so the queued set is already fixed and the pool can consume it to
 * empty. The pause still happens -- in step 2, before
 * {@code awaitQuiescent()} -- so the DDL is applied against a genuinely
 * quiescent writer.</p>
 *
 * <p>The tests drive {@code drainBeforeDDL()} through reflection with a real
 * {@link ClickHouseBatchExecutor} and a real queue, and a consumer that
 * behaves like {@code ClickHouseBatchRunnable}: it drains only while it is
 * allowed to run. No ClickHouse, MySQL or Debezium engine is required, so the
 * outcome is deterministic rather than timing-dependent.</p>
 */
public class DdlDrainDeadlockTest {

    private static final ThreadFactory FACTORY = r -> {
        Thread t = new Thread(r, "ddl-drain-deadlock-test");
        t.setDaemon(true);
        return t;
    };

    /** Shortened so a regression fails fast instead of hanging the suite. */
    private static final long DRAIN_BUDGET_MS = 60_000;

    private static Object invokeDrain(DebeziumChangeEventCapture capture) throws Exception {
        Method drain = DebeziumChangeEventCapture.class.getDeclaredMethod("drainBeforeDDL");
        drain.setAccessible(true);
        try {
            return drain.invoke(capture);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw ite;
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = DebeziumChangeEventCapture.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /**
     * Reads the executor's pause flag. It is package-private in another
     * package, so it cannot be referenced directly from this test.
     */
    private static boolean isPaused(ClickHouseBatchExecutor executor) throws Exception {
        Field f = ClickHouseBatchExecutor.class.getDeclaredField("isPaused");
        f.setAccessible(true);
        return f.getBoolean(executor);
    }

    /**
     * The regression itself: with queued work and a consumer that only runs
     * while the pool is unpaused, the drain must complete.
     *
     * <p>Against the pause-first ordering this fails: the consumer is parked by
     * the pause, the queue never empties, and the drain throws after the full
     * timeout -- taking the queued rows with it.</p>
     */
    @Test
    @DisplayName("drainBeforeDDL drains a non-empty queue instead of deadlocking on its own pause")
    public void testDrainDoesNotDeadlockOnItsOwnPause() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        ClickHouseBatchExecutor executor = new ClickHouseBatchExecutor(2, FACTORY);
        LinkedBlockingQueue<List<ClickHouseStruct>> records = new LinkedBlockingQueue<>();
        AtomicBoolean stop = new AtomicBoolean(false);

        try {
            // Queued work, exactly as appendToRecords() would have left it
            // when a DDL arrives on a busy table.
            for (int i = 0; i < 8; i++) {
                records.put(new ArrayList<>());
            }

            setField(capture, "executor", executor);
            setField(capture, "records", records);

            // A consumer with the same contract as ClickHouseBatchRunnable:
            // it is scheduled on the pool, so while the pool is paused it
            // cannot run, and nothing is dequeued.
            executor.scheduleAtFixedRate(() -> {
                if (!stop.get()) {
                    records.poll();
                }
            }, 0, 10, TimeUnit.MILLISECONDS);

            long started = System.currentTimeMillis();
            invokeDrain(capture);
            long elapsed = System.currentTimeMillis() - started;

            assertTrue(records.isEmpty(),
                    "the queue must be drained before the DDL is applied; "
                            + records.size() + " batch(es) were left behind and would be "
                            + "dropped with the aborted DDL attempt");
            assertTrue(elapsed < DRAIN_BUDGET_MS,
                    "the drain must not burn its whole timeout waiting on a queue that its "
                            + "own pause prevents anyone from consuming (took " + elapsed + " ms)");
        } finally {
            stop.set(true);
            executor.shutdownNow();
        }
    }

    /**
     * The safety property the ordering must not give up: once the drain
     * returns, the writer is quiescent and the pool is still paused, so the
     * ALTER cannot interleave with a batch.
     */
    @Test
    @DisplayName("The writer is paused and quiescent when drainBeforeDDL returns")
    public void testWriterIsPausedAndQuiescentAfterDrain() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        ClickHouseBatchExecutor executor = new ClickHouseBatchExecutor(2, FACTORY);
        LinkedBlockingQueue<List<ClickHouseStruct>> records = new LinkedBlockingQueue<>();
        AtomicBoolean stop = new AtomicBoolean(false);

        try {
            for (int i = 0; i < 4; i++) {
                records.put(new ArrayList<>());
            }
            setField(capture, "executor", executor);
            setField(capture, "records", records);

            executor.scheduleAtFixedRate(() -> {
                if (!stop.get()) {
                    records.poll();
                }
            }, 0, 10, TimeUnit.MILLISECONDS);

            invokeDrain(capture);

            assertTrue(isPaused(executor),
                    "the pool must still be paused when the drain returns, or a batch could "
                            + "start while the ALTER is being applied");
            assertTrue(executor.awaitQuiescent(0),
                    "no batch may be inside a task body when the drain returns");
            assertTrue(records.isEmpty(), "the queue must be empty when the drain returns");
        } finally {
            stop.set(true);
            executor.resume();
            executor.shutdownNow();
        }
    }

    /**
     * The abort must still fire when the queue genuinely cannot be drained --
     * a stuck consumer, not a self-inflicted pause. Applying the DDL over
     * those rows is the silent-corruption path the abort exists to prevent, so
     * this behaviour is preserved deliberately.
     */
    @Test
    @DisplayName("A genuinely undrainable queue still aborts the DDL rather than applying it")
    public void testGenuinelyStuckQueueStillAborts() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        ClickHouseBatchExecutor executor = new ClickHouseBatchExecutor(2, FACTORY);
        LinkedBlockingQueue<List<ClickHouseStruct>> records = new LinkedBlockingQueue<>();

        try {
            records.put(new ArrayList<>());
            setField(capture, "executor", executor);
            setField(capture, "records", records);

            // No consumer at all: the queue cannot drain for a real reason.
            boolean aborted = false;
            try {
                invokeDrain(capture);
            } catch (IllegalStateException expected) {
                aborted = true;
                assertTrue(expected.getMessage().contains("DDL drain"),
                        "the abort must name the drain as the cause: " + expected.getMessage());
            }

            assertTrue(aborted,
                    "an undrainable queue must abort the DDL attempt; applying the ALTER over "
                            + "records captured under the previous schema is the silent "
                            + "corruption this guard exists to prevent");
            assertEquals(1, records.size(), "the undrained batch must not have been discarded");
            assertFalse(records.isEmpty(), "sanity: the queue really was non-empty");
        } finally {
            executor.shutdownNow();
        }
    }
}

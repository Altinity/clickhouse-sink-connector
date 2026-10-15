package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchExecutor;
import com.altinity.clickhouse.sink.connector.executor.DebeziumOffsetManagement;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.RoutedBatch;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code drainBeforeDDL()} must not pause the writer pool before the handoff
 * queue has been drained.
 *
 * <p><b>The defect.</b> {@code pause()} parks every pool thread inside
 * {@code beforeExecute()}, so while it is in effect no thread can dequeue.
 * Draining after pausing therefore waits on a queue that is guaranteed never
 * to shrink: the loop burned the whole drain timeout of the day and then
 * threw. The throw is not a safe fallback -- the queued batches are dropped
 * with the aborted DDL attempt, so rows that MySQL holds never reach
 * ClickHouse. The loss is tail-shaped: a single day short by an arbitrary
 * number of rows while every other day matches exactly, which does not look
 * like lag and never self-heals.</p>
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

    /** Collects everything the class under test logs during one call. */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

        CapturingAppender() {
            super("capture-ddl-drain", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    private long savedWarnInterval;

    @BeforeEach
    public void shortenWarnInterval() {
        savedWarnInterval = DebeziumChangeEventCapture.ddlDrainWarnIntervalMs;
        DebeziumChangeEventCapture.ddlDrainWarnIntervalMs = 150;
    }

    @AfterEach
    public void restoreWarnInterval() {
        DebeziumChangeEventCapture.ddlDrainWarnIntervalMs = savedWarnInterval;
    }

    /**
     * A worker whose scheduled task has already died, exactly as
     * {@code failIfWorkerDied} sees one: its future is done exceptionally.
     */
    private static ScheduledFuture<?> deadWorker(ClickHouseBatchExecutor executor) throws Exception {
        ScheduledFuture<?> future = executor.schedule(() -> {
            throw new IllegalStateException("FATAL classification: worker died");
        }, 0, TimeUnit.MILLISECONDS);
        long deadline = System.currentTimeMillis() + 5_000;
        while (!future.isDone() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(future.isDone(), "sanity: the dead worker's future must be done");
        return future;
    }

    /** How long the routed-queue worker / acknowledging writer holds back. */
    private static final long RELEASE_DELAY_MS = 500;

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
     * INVERTED from {@code testGenuinelyStuckQueueStillAborts}, which pinned a
     * 60 s timeout abort. A queue that is not draining is not, by itself, a
     * reason to abort: a worker retrying a transient ClickHouse error
     * ({@code TOO_MANY_PARTS}, a reconnect) can hold a queue for longer than any
     * fixed timeout, and aborting turned that into {@code DDLReplicationException}
     * -> engine restart -> the same drain -> terminal stop (spec 10.04 §3.5).
     * The only backlog that can never drain is one whose worker is DEAD, and
     * that is what aborts -- immediately, naming the drain and carrying the
     * worker's cause (spec 06.01 §3.2 step 1).
     */
    @Test
    @DisplayName("An undrainable queue aborts the DDL only because its worker is dead -- promptly, with the cause")
    public void testStuckQueueWithDeadWorkerAborts() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        ClickHouseBatchExecutor executor = new ClickHouseBatchExecutor(2, FACTORY);
        LinkedBlockingQueue<List<ClickHouseStruct>> records = new LinkedBlockingQueue<>();

        try {
            records.put(new ArrayList<>());
            setField(capture, "executor", executor);
            setField(capture, "records", records);
            capture.workerFutures.add(deadWorker(executor));

            long started = System.currentTimeMillis();
            IllegalStateException aborted = null;
            try {
                invokeDrain(capture);
            } catch (IllegalStateException expected) {
                aborted = expected;
            }
            long elapsed = System.currentTimeMillis() - started;

            assertNotNull(aborted,
                    "a backlog whose worker is dead can never drain; the DDL attempt must abort "
                            + "rather than wait forever or apply the ALTER over the pending rows");
            assertTrue(aborted.getMessage().contains("DDL drain"),
                    "the abort must name the drain as the cause: " + aborted.getMessage());
            assertNotNull(aborted.getCause(), "the abort must carry the dead worker's cause");
            assertTrue(elapsed < 5_000,
                    "a dead worker is detected on the next poll, not after a timeout (took "
                            + elapsed + " ms)");
            assertEquals(1, records.size(), "the undrained batch must not have been discarded");
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * With LIVE workers a backlog that is slow to drain is waited for, not
     * aborted: the drain logs a WARN naming the backlog every
     * {@code ddlDrainWarnIntervalMs} and keeps waiting. Only an interrupt (the
     * engine being closed) ends it.
     *
     * <p>Against the pre-fix code this fails: the drain threw after its fixed
     * timeout with the queue still full.</p>
     */
    @Test
    @DisplayName("A slow backlog with live workers is waited for past the old timeout, with periodic WARNs, and never aborted")
    public void testUndrainableQueueWithLiveWorkersKeepsWaiting() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        ClickHouseBatchExecutor executor = new ClickHouseBatchExecutor(2, FACTORY);
        LinkedBlockingQueue<List<ClickHouseStruct>> records = new LinkedBlockingQueue<>();
        Logger coreLogger = (Logger) LogManager.getLogger(DebeziumChangeEventCapture.class);
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        coreLogger.addAppender(appender);

        try {
            records.put(new ArrayList<>());
            setField(capture, "executor", executor);
            setField(capture, "records", records);
            // A live worker that is (for the duration of the test) making no
            // progress on this queue -- e.g. retrying a transient error.
            capture.workerFutures.add(executor.scheduleAtFixedRate(() -> { }, 0, 10,
                    TimeUnit.MILLISECONDS));

            Throwable[] outcome = new Throwable[1];
            Thread drainer = new Thread(() -> {
                try {
                    invokeDrain(capture);
                } catch (Throwable t) {
                    outcome[0] = t;
                }
            }, "ddl-drain-live-worker-test");
            drainer.setDaemon(true);
            drainer.start();

            // Several warn intervals (150 ms each) later: still waiting, still warning.
            drainer.join(900);
            assertTrue(drainer.isAlive(),
                    "with live workers the drain must keep waiting, not abort on a timeout; it ended "
                            + "with: " + outcome[0]);
            long warns = appender.events.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .filter(e -> e.getMessage().getFormattedMessage().contains("still pending"))
                    .count();
            assertTrue(warns >= 1, "the wait must be visible: a WARN naming the pending backlog");
            assertEquals(1, records.size(), "the batch is still pending, not discarded");

            // The engine closing interrupts the Debezium thread: that, and only
            // that, ends the wait -- loudly.
            drainer.interrupt();
            drainer.join(5_000);
            assertFalse(drainer.isAlive(), "an interrupt must end the drain");
            assertTrue(outcome[0] instanceof IllegalStateException,
                    "an interrupted drain aborts the DDL attempt: " + outcome[0]);
        } finally {
            coreLogger.removeAppender(appender);
            appender.stop();
            executor.resume();
            executor.shutdownNow();
        }
    }


    // ------------------------------------------------------------------
    // Hash-routing mode (thread.pool.size > 1, the default): the barrier
    // must also cover the per-thread routed queues and the batches a
    // worker has taken off a queue but not yet acknowledged.
    // ------------------------------------------------------------------

    /**
     * Wires the capture as setupProcessingThread does for hash routing: an
     * EMPTY legacy queue plus one routed queue per worker thread.
     */
    private static List<LinkedBlockingQueue<RoutedBatch>> wireHashRouting(DebeziumChangeEventCapture capture,
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

    /**
     * Clears the handoff FIFO's static bookkeeping so a registration left by an
     * earlier test in this JVM cannot make the drain wait on a ghost unit.
     * Mirrors the sink-connector test helper {@code OffsetTestSupport.resetFifo()}.
     */
    private static void resetOffsetFifo() throws Exception {
        for (String name : new String[] {"outstandingSequences", "groupToUnit", "completedUnits"}) {
            Field f = DebeziumOffsetManagement.class.getDeclaredField(name);
            f.setAccessible(true);
            Object collection = f.get(null);
            if (collection instanceof java.util.Map) {
                ((java.util.Map<?, ?>) collection).clear();
            } else if (collection instanceof java.util.Collection) {
                ((java.util.Collection<?>) collection).clear();
            }
        }
    }

    private static RoutedBatch routedBatch(int threadId) {
        return new RoutedBatch(new ArrayList<>(), threadId, "orders", 0L);
    }

    /**
     * The regression: with the legacy queue empty and pre-DDL batches sitting
     * on a routed queue, the drain must wait for that queue.
     *
     * <p>Against the pre-fix code this fails: the only wait was on the legacy
     * queue, so the drain paused the pool and returned within milliseconds
     * while the routed batches were still queued -- they were then written
     * against the altered table.</p>
     */
    @Test
    @DisplayName("drainBeforeDDL waits for the per-thread routed queues, not only the legacy queue")
    public void testDrainWaitsForRoutedQueues() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        ClickHouseBatchExecutor executor = new ClickHouseBatchExecutor(2, FACTORY);
        AtomicBoolean stop = new AtomicBoolean(false);

        try {
            List<LinkedBlockingQueue<RoutedBatch>> routed = wireHashRouting(capture, executor, 2);
            LinkedBlockingQueue<RoutedBatch> busy = routed.get(1);
            for (int i = 0; i < 3; i++) {
                busy.put(routedBatch(1));
            }

            // The owning worker, as ClickHouseBatchRunnable in routing mode:
            // scheduled on the pool, drains ONLY its own queue, and -- to make
            // the wait observable -- only from RELEASE_DELAY_MS onwards.
            long releaseAt = System.currentTimeMillis() + RELEASE_DELAY_MS;
            executor.scheduleAtFixedRate(() -> {
                if (!stop.get() && System.currentTimeMillis() >= releaseAt) {
                    busy.poll();
                }
            }, 0, 10, TimeUnit.MILLISECONDS);

            long started = System.currentTimeMillis();
            invokeDrain(capture);
            long elapsed = System.currentTimeMillis() - started;

            assertTrue(busy.isEmpty(),
                    "the drain returned with " + busy.size() + " routed batch(es) still queued; "
                            + "applying the DDL now would write them against the altered table");
            assertTrue(elapsed >= RELEASE_DELAY_MS,
                    "the drain returned after " + elapsed + " ms, before the routed queue could "
                            + "have been emptied -- it did not wait on the routed queues at all");
            assertTrue(isPaused(executor),
                    "the pool must be paused when the drain returns, or a batch could start "
                            + "while the ALTER is being applied");
        } finally {
            stop.set(true);
            executor.resume();
            executor.shutdownNow();
        }
    }

    /**
     * INVERTED from {@code testUndrainableRoutedQueueAborts} (a 60 s timeout
     * abort): a routed backlog whose worker is DEAD aborts the DDL attempt
     * promptly, exactly as an undrainable legacy queue does, and the message
     * names the routed backlog. The routed-queue coverage of the drain is
     * unchanged: it still fails against code that never looks at the routed
     * queues (which returned normally).
     */
    @Test
    @DisplayName("A routed backlog whose worker is dead aborts the DDL, naming the routed backlog")
    public void testUndrainableRoutedQueueWithDeadWorkerAborts() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        ClickHouseBatchExecutor executor = new ClickHouseBatchExecutor(2, FACTORY);

        try {
            List<LinkedBlockingQueue<RoutedBatch>> routed = wireHashRouting(capture, executor, 2);
            routed.get(0).put(routedBatch(0));
            capture.workerFutures.add(deadWorker(executor));

            IllegalStateException aborted = null;
            try {
                invokeDrain(capture);
            } catch (IllegalStateException expected) {
                aborted = expected;
            }

            assertNotNull(aborted,
                    "a routed backlog whose worker is dead must abort the DDL attempt; applying "
                            + "the ALTER over records captured under the previous schema is the "
                            + "silent corruption this guard exists to prevent");
            assertTrue(aborted.getMessage().contains("DDL drain"),
                    "the abort must name the drain as the cause: " + aborted.getMessage());
            assertTrue(aborted.getMessage().toLowerCase().contains("routed"),
                    "the abort must name the routed backlog: " + aborted.getMessage());
            assertEquals(1, routed.get(0).size(), "the undrained batch must not have been discarded");
        } finally {
            executor.resume();
            executor.shutdownNow();
        }
    }

    /**
     * Both queue sets empty is still not quiescent while a worker holds a
     * batch it has dequeued but not yet written and acknowledged. Such a batch
     * is registered with {@code DebeziumOffsetManagement} at handoff and
     * released only on acknowledgement; the drain must wait for that release.
     *
     * <p>Against the pre-fix code this fails: nothing consulted
     * {@code hasUnwrittenBatches()}, so the drain returned at once.</p>
     */
    @Test
    @DisplayName("drainBeforeDDL waits for handed-off batches that are not yet acknowledged")
    public void testDrainWaitsForUnacknowledgedBatches() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        ClickHouseBatchExecutor executor = new ClickHouseBatchExecutor(2, FACTORY);
        AtomicBoolean released = new AtomicBoolean(false);

        // A unit the producer handed to the writers (registered with its
        // handoff sequence, as appendToRecords does); no consumer has written
        // it yet, so the pipeline is NOT quiescent even though every queue is
        // empty. The record carries no committer, so acknowledging it later is
        // a pure bookkeeping release.
        resetOffsetFifo();
        List<ClickHouseStruct> unit = java.util.Collections.singletonList(new ClickHouseStruct());
        DebeziumOffsetManagement.registerHandoff(unit, java.util.Collections.singletonList(unit));
        // The writer finishing that unit, RELEASE_DELAY_MS from now: reporting
        // the group written acknowledges the unit (it is the FIFO head).
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(RELEASE_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            released.set(true);
            try {
                DebeziumOffsetManagement.checkIfBatchCanBeCommitted(unit);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }, "ddl-drain-ack-test");
        writer.setDaemon(true);

        try {
            wireHashRouting(capture, executor, 2);
            writer.start();

            long started = System.currentTimeMillis();
            invokeDrain(capture);
            long elapsed = System.currentTimeMillis() - started;

            assertTrue(released.get(),
                    "the drain returned while a handed-off batch was still unacknowledged; "
                            + "that batch would be written against the altered table");
            assertTrue(elapsed >= RELEASE_DELAY_MS,
                    "the drain returned after " + elapsed + " ms, before the batch could have "
                            + "been acknowledged -- it did not wait on unacknowledged batches");
            assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches(),
                    "sanity: nothing may be outstanding once the drain has returned");
        } finally {
            writer.join(5_000);
            if (DebeziumOffsetManagement.hasUnwrittenBatches()) {
                // Do not leak the registration into later tests in this JVM.
                resetOffsetFifo();
            }
            executor.resume();
            executor.shutdownNow();
        }
    }
}

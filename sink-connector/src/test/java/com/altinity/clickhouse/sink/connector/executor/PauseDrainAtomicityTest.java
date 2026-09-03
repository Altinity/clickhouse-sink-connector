package com.altinity.clickhouse.sink.connector.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Deterministic proof that the pause check and the in-flight registration in
 * {@link ClickHouseBatchExecutor} are ONE atomic step.
 *
 * <p>A companion to {@code PauseDrainRaceTest}, which drives the executor
 * through realistic pause/drain/resume cycles. That test is a useful guard but
 * it is NOT a proof: it passes against the non-atomic implementation too,
 * because it cannot force the interleaving and the window is a few
 * instructions wide. Verified by reverting the fix and re-running it -- 3/3
 * still passed. A test that cannot fail on the broken code proves nothing
 * about the fixed code.</p>
 *
 * <p>This test therefore does not race at all. It inspects the property
 * directly: it holds the executor's internal monitor and shows that no pool
 * thread can complete the check-and-register step while it is held. With the
 * two operations separate, a pool thread can pass the check and increment the
 * counter with the monitor held by someone else, so the drain can observe
 * zero in-flight batches at the exact moment one is about to run.</p>
 *
 * <p>Why this matters: a drain that reports success while a batch is about to
 * start lets a DDL be applied between the two, so records captured under the
 * pre-ALTER schema are bound against the post-ALTER table. The rows insert
 * successfully with wrong contents and identical row counts -- undetectable
 * without a value-level checksum, which is how the production incident ran for
 * two days.</p>
 */
public class PauseDrainAtomicityTest {

    private static final ThreadFactory FACTORY = r -> {
        Thread t = new Thread(r, "pause-drain-atomicity-test");
        t.setDaemon(true);
        return t;
    };

    /**
     * Reads the executor's gate monitor by reflection.
     *
     * <p>Reaching into a private field is deliberate. The atomicity of
     * check-and-register is not observable from the public API precisely
     * because it is a timing property, and asserting it through timing is what
     * made the companion test unable to fail. Naming the field here also makes
     * the test fail loudly if the synchronisation strategy is replaced, which
     * is the correct outcome: whatever replaces it must be re-proven.</p>
     */
    private static Object gateOf(ClickHouseBatchExecutor executor) throws Exception {
        java.lang.reflect.Field f =
                ClickHouseBatchExecutor.class.getDeclaredField("gate");
        f.setAccessible(true);
        return f.get(executor);
    }

    /**
     * With the gate held, no task may complete {@code beforeExecute}. That is
     * exactly the guarantee {@code awaitQuiescent()} relies on: it holds the
     * same monitor, so a task cannot slip from "not yet checked" to "running"
     * underneath it.
     *
     * <p>Against a non-atomic implementation the submitted task passes the
     * unsynchronised {@code isPaused} check and runs while the monitor is held,
     * failing this assertion.</p>
     */
    @Test
    @DisplayName("No task can start while the drain holds the gate")
    public void testNoTaskStartsWhileGateHeld() throws Exception {
        ClickHouseBatchExecutor executor = new ClickHouseBatchExecutor(4, FACTORY);
        try {
            CountDownLatch started = new CountDownLatch(1);
            Object gate = gateOf(executor);

            synchronized (gate) {
                // Submitted while the monitor is held: the pool has a free
                // thread and the executor is NOT paused, so the only thing
                // that can hold this task back is the gate itself.
                executor.submit(started::countDown);

                assertFalse("a task began executing while the gate was held. The pause "
                                + "check and the in-flight increment are not atomic, so "
                                + "awaitQuiescent() can report a quiescent writer while this "
                                + "task is about to run -- and a DDL applied at that moment "
                                + "interleaves with the batch's writes",
                        started.await(1500, TimeUnit.MILLISECONDS));
            }

            assertTrue("the task must run once the gate is released",
                    started.await(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * The end-to-end consequence, stated as the invariant the DDL path depends
     * on: once {@code pause()} returns, no task may begin, so a subsequent
     * successful {@code awaitQuiescent()} really does mean the writer is
     * stopped.
     *
     * <p>Many tasks are queued against an idle pool while paused, which
     * maximises the chance that one slips through if the check is
     * unsynchronised.</p>
     */
    @Test
    @DisplayName("After pause() returns, no queued task may begin")
    public void testNothingStartsAfterPauseReturns() throws Exception {
        ClickHouseBatchExecutor executor = new ClickHouseBatchExecutor(8, FACTORY);
        try {
            AtomicInteger startedWhilePaused = new AtomicInteger();
            AtomicBoolean paused = new AtomicBoolean(false);
            CountDownLatch allDone = new CountDownLatch(200);

            executor.pause();
            paused.set(true);

            for (int i = 0; i < 200; i++) {
                executor.submit(() -> {
                    if (paused.get()) {
                        startedWhilePaused.incrementAndGet();
                    }
                    allDone.countDown();
                });
            }

            assertTrue("with nothing running, the drain must succeed",
                    executor.awaitQuiescent(5_000));

            // This is the DDL window. Nothing may execute in it.
            Thread.sleep(500);

            paused.set(false);
            executor.resume();

            assertTrue("every task must run after resume",
                    allDone.await(30, TimeUnit.SECONDS));
            assertEquals("tasks began while the executor was paused and the drain had "
                            + "already reported a quiescent writer; a DDL applied in that "
                            + "window would interleave with their writes",
                    0, startedWhilePaused.get());
        } finally {
            executor.shutdownNow();
        }
    }
}

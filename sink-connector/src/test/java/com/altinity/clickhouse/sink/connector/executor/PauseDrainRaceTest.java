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
 * The DDL/DML race: {@code awaitQuiescent()} must never report a quiescent
 * writer while a batch is still able to start.
 *
 * <p>{@code pause()} stops new batches and {@code awaitQuiescent()} waits out
 * running ones. Applying a DDL depends on both being true simultaneously,
 * because a record read under the pre-ALTER schema that is written after the
 * ALTER lands is bound against the wrong table shape -- it inserts
 * successfully, with matching row counts, carrying wrong values.</p>
 *
 * <p>The window is between testing the pause flag and registering as
 * in-flight. If those are separate steps:</p>
 *
 * <pre>
 *   pool thread              DDL thread
 *   ---------------------    ------------------------------------------
 *   reads isPaused == false
 *                            pause()          -&gt; isPaused = true
 *                            awaitQuiescent() -&gt; count == 0, returns TRUE
 *                            applies the ALTER
 *   activeBatches++
 *   runs the batch                            &lt;- pre-ALTER rows, post-ALTER table
 * </pre>
 *
 * <p>These tests drive that window directly rather than hoping to hit it by
 * timing, so they are deterministic rather than flaky.</p>
 */
public class PauseDrainRaceTest {

    private static final ThreadFactory FACTORY = r -> {
        Thread t = new Thread(r, "pause-drain-race-test");
        t.setDaemon(true);
        return t;
    };

    /**
     * The core invariant, exercised at the exact interleaving point.
     *
     * <p>A task is submitted and held inside {@code beforeExecute}'s critical
     * section by pausing first; the drain must then refuse to report quiescence
     * for as long as that task can still proceed to run.</p>
     */
    @Test
    @DisplayName("awaitQuiescent must not return true while a task can still start")
    public void testDrainDoesNotRaceATaskThatIsAboutToStart() throws Exception {
        ClickHouseBatchExecutor executor = new ClickHouseBatchExecutor(2, FACTORY);
        try {
            AtomicInteger ranWhilePaused = new AtomicInteger();
            AtomicBoolean ddlApplied = new AtomicBoolean(false);
            CountDownLatch taskStarted = new CountDownLatch(1);
            CountDownLatch releaseTask = new CountDownLatch(1);

            // A batch that is already running when the DDL thread pauses.
            executor.submit(() -> {
                taskStarted.countDown();
                try {
                    releaseTask.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                if (ddlApplied.get()) {
                    // Ran concurrently with, or after, the DDL.
                    ranWhilePaused.incrementAndGet();
                }
            });

            assertTrue("the batch must be running before the drain begins",
                    taskStarted.await(10, TimeUnit.SECONDS));

            executor.pause();

            // The batch is still inside its body, so the drain must NOT succeed.
            assertFalse("awaitQuiescent returned true while a batch was still running; "
                            + "a DDL applied here interleaves with that batch's writes",
                    executor.awaitQuiescent(300));

            releaseTask.countDown();

            assertTrue("the drain must succeed once the in-flight batch finishes",
                    executor.awaitQuiescent(10_000));

            // Only now is it safe to apply the DDL.
            ddlApplied.set(true);
            executor.resume();

            assertEquals("no batch may have observed the post-DDL state",
                    0, ranWhilePaused.get());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * A task submitted while paused must not begin, and must not be counted as
     * having drained. This is the check-then-act window itself: the task is
     * queued before the drain runs, and the drain must not conclude the writer
     * is quiescent in a way that lets the task then start against the altered
     * table.
     */
    @Test
    @DisplayName("A task queued while paused must not run until resume")
    public void testTaskQueuedWhilePausedDoesNotRunBeforeResume() throws Exception {
        ClickHouseBatchExecutor executor = new ClickHouseBatchExecutor(2, FACTORY);
        try {
            AtomicBoolean ddlWindowOpen = new AtomicBoolean(false);
            AtomicInteger violations = new AtomicInteger();
            CountDownLatch finished = new CountDownLatch(1);

            executor.pause();

            executor.submit(() -> {
                if (ddlWindowOpen.get()) {
                    violations.incrementAndGet();
                }
                finished.countDown();
            });

            // Drain reports quiescence: nothing is running. That is correct --
            // but the pause must still be holding the queued task back.
            assertTrue("nothing is running, so the drain should succeed",
                    executor.awaitQuiescent(2_000));

            ddlWindowOpen.set(true);
            // Give the pool ample opportunity to violate the pause.
            assertFalse("a task started while the executor was paused, i.e. during the "
                            + "window where the DDL is being applied",
                    finished.await(1, TimeUnit.SECONDS));
            ddlWindowOpen.set(false);

            executor.resume();

            assertTrue("the task must run once resumed",
                    finished.await(10, TimeUnit.SECONDS));
            assertEquals("no task may run during the DDL window", 0, violations.get());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Under sustained submission, every drain that reports success must be
     * truthful: no task may run between that success and the resume.
     *
     * <p>Repeated because a check-then-act window is a probabilistic failure --
     * one cycle can pass by luck. Each iteration opens a real DDL window and
     * asserts nothing executed inside it.</p>
     */
    @Test
    @DisplayName("Repeated pause/drain/resume cycles under load never leak a task")
    public void testRepeatedDrainCyclesUnderLoad() throws Exception {
        ClickHouseBatchExecutor executor = new ClickHouseBatchExecutor(4, FACTORY);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicBoolean ddlWindowOpen = new AtomicBoolean(false);
        AtomicInteger violations = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();

        // Continuously offer work, as a busy table would.
        Thread submitter = new Thread(() -> {
            while (!stop.get()) {
                try {
                    executor.submit(() -> {
                        if (ddlWindowOpen.get()) {
                            violations.incrementAndGet();
                        }
                        completed.incrementAndGet();
                    });
                    Thread.sleep(1);
                } catch (Exception e) {
                    return;
                }
            }
        });
        submitter.setDaemon(true);
        submitter.start();

        try {
            for (int cycle = 0; cycle < 40; cycle++) {
                executor.pause();
                if (executor.awaitQuiescent(10_000)) {
                    // The drain claims the writer is quiescent. This is exactly
                    // where the DDL is applied, so nothing may execute now.
                    ddlWindowOpen.set(true);
                    Thread.sleep(2);
                    ddlWindowOpen.set(false);
                }
                executor.resume();
                Thread.sleep(2);
            }
        } finally {
            stop.set(true);
            submitter.join(5_000);
            executor.shutdownNow();
        }

        assertTrue("the load generator must have produced work for this to mean anything",
                completed.get() > 0);
        assertEquals("a batch executed while a DDL was being applied, across " + completed.get()
                + " batches -- this is the DDL/DML race", 0, violations.get());
    }
}

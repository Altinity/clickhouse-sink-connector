package com.altinity.clickhouse.sink.connector.executor;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pool thread interrupted while it is parked in {@code beforeExecute} by {@code pause()}: spec 03.01
 * section 6 FM-03.01-4 (the in-flight count goes to -1) and spec 06.02 section 6 FM-06.02-1 (the batch runs
 * while the DDL barrier holds). Both are the same code path, pinned from the two sides.
 *
 * <p>{@code beforeExecute} returns WITHOUT incrementing the in-flight count when its wait is interrupted,
 * but {@code ThreadPoolExecutor.runWorker} then still runs the task body and calls {@code afterExecute},
 * which decrements it. The count goes to -1: the task body ran during a pause, and every later
 * {@code awaitQuiescent()} under-counts by one, so a DDL drain can report a quiescent writer while one
 * batch is running (the race Invariant I5 exists to close).</p>
 */
public class ClickHouseBatchExecutorInterruptTest {

    @Test
    @Disabled("DEFECT FM-03.01-4: beforeExecute returns without registering the batch when interrupted while "
            + "paused, runWorker still runs the body and afterExecute decrements: activeBatches becomes -1 and "
            + "awaitQuiescent() reports quiescence while a batch is running")
    @DisplayName("An interrupt while paused does not unbalance the in-flight count")
    public void interruptWhilePausedDoesNotUnbalanceTheInFlightCount() {
        ClickHouseBatchExecutor executor = new ClickHouseBatchExecutor(1, Executors.defaultThreadFactory());
        Runnable task = () -> { };
        try {
            executor.pause();
            Thread.currentThread().interrupt();
            // What runWorker does for a task that started while paused and whose
            // thread is interrupted (shutdownNow(), Future.cancel(true)).
            executor.beforeExecute(Thread.currentThread(), task);
            Thread.interrupted();
            executor.afterExecute(task, null);
            executor.resume();

            // A real batch now starts and is inside its task body.
            executor.beforeExecute(Thread.currentThread(), task);
            assertFalse(executor.awaitQuiescent(50L),
                    "one batch is running, so the writer is NOT quiescent; a true here means the interrupted "
                            + "pair left activeBatches at -1 and the DDL drain would apply the ALTER over this batch");
            executor.afterExecute(task, null);
        } finally {
            Thread.interrupted();
            executor.shutdownNow();
        }
    }


    /**
     * (DEFECT) {@code beforeExecute} handles an interrupt while parked with
     * {@code t.interrupt(); return;}. {@code ThreadPoolExecutor.runWorker}
     * then runs the task -- while the executor is still paused, i.e. while a
     * DDL may be executing -- and {@code afterExecute} decrements
     * {@code activeBatches}, which was never incremented, to -1. From then on
     * {@code awaitQuiescent()} reports quiescence while one batch is running.
     */
    @Test
    @Disabled("DEFECT FM-06.02-1: an interrupted parked worker runs its batch during the pause and "
            + "drives activeBatches negative")
    @DisplayName("FM-06.02-1: an interrupted parked worker does not run during the pause")
    public void interruptedParkedWorkerDoesNotRunWhilePaused() throws Exception {
        AtomicReference<Thread> worker = new AtomicReference<>();
        ClickHouseBatchExecutor executor = new ClickHouseBatchExecutor(1, r -> {
            Thread t = new Thread(r, "interrupt-test-worker");
            t.setDaemon(true);
            worker.set(t);
            return t;
        });
        try {
            AtomicBoolean ranWhilePaused = new AtomicBoolean(false);
            CountDownLatch ran = new CountDownLatch(1);
            executor.pause();
            executor.submit(() -> {
                if (executor.isPaused) {
                    ranWhilePaused.set(true);
                }
                ran.countDown();
            });
            long deadline = System.currentTimeMillis() + 5_000;
            while (worker.get() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertNotNull(worker.get(), "the pool thread was started");
            // Let the worker reach beforeExecute and park on the gate.
            Thread.sleep(300);
            worker.get().interrupt();

            boolean completed = ran.await(1, TimeUnit.SECONDS);
            assertFalse(completed && ranWhilePaused.get(),
                    "a batch ran while the executor was paused (the DDL barrier was held)");
            executor.resume();
            assertTrue(executor.awaitQuiescent(2_000));
            Field f = ClickHouseBatchExecutor.class.getDeclaredField("activeBatches");
            f.setAccessible(true);
            int active = ((AtomicInteger) f.get(executor)).get();
            assertTrue(active >= 0, "activeBatches went negative (" + active + "): awaitQuiescent can no "
                    + "longer see a running batch");
        } finally {
            executor.shutdownNow();
        }
    }
}

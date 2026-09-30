package com.altinity.clickhouse.sink.connector.executor;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 06.02 section 6, FM-06.02-1: a worker parked in {@code beforeExecute}
 * by {@code pause()} must not run its batch while the DDL barrier holds,
 * even when its thread is interrupted, and the in-flight counter must never
 * be driven below zero.
 */
public class ClickHouseBatchExecutorInterruptTest {

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

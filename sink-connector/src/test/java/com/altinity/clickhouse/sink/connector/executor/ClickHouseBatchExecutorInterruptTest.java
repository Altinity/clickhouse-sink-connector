package com.altinity.clickhouse.sink.connector.executor;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Spec 03.01 section 6, FM-03.01-4: a pool thread interrupted while it is
 * parked in {@code beforeExecute} by {@code pause()}.
 *
 * <p>{@code beforeExecute} returns WITHOUT incrementing the in-flight count
 * when its wait is interrupted, but {@code ThreadPoolExecutor.runWorker} then
 * still runs the task body and calls {@code afterExecute}, which decrements
 * it. The count goes to -1: the task body ran during a pause, and every later
 * {@code awaitQuiescent()} under-counts by one, so a DDL drain can report a
 * quiescent writer while one batch is running (the race Invariant I5 exists
 * to close). The calls are made directly on the caller's thread, which is
 * exactly the sequence a pool worker performs.</p>
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
}

package com.altinity.clickhouse.sink.connector.executor;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * ClickHouseBatchExecutor is a custom executor that supports
 * pausing and resuming of task execution.
 *
 * <p>This executor extends ScheduledThreadPoolExecutor and
 * implements a pause/resume mechanism in the beforeExecute method.
 */
public class ClickHouseBatchExecutor extends
        ScheduledThreadPoolExecutor {

    /**
     * Polling interval in milliseconds used when the executor is paused.
     */
    private static final long POLLING_INTERVAL_MS = 100;

    /**
     * Flag indicating whether the executor is paused.
     *
     * <p>Written by the Debezium event thread in {@link #pause()} and
     * {@link #resume()}, read by every pool thread in
     * {@link #beforeExecute}. Without {@code volatile} there is no
     * happens-before edge between those threads, so a pool thread is not
     * guaranteed to observe either transition: it may miss the pause and
     * begin a batch while a DDL is being applied, or spin past the resume
     * and stall.
     */
    volatile boolean isPaused = false;

    /**
     * Number of batches currently inside a task body.
     *
     * <p>{@link #pause()} only stops NEW tasks from starting; a batch already
     * running keeps going. That distinction matters for DDL: a record read
     * under the pre-ALTER schema must reach ClickHouse BEFORE the ALTER is
     * applied, or it is written against the post-ALTER table. This counter lets
     * the DDL path wait for in-flight batches to finish draining.</p>
     */
    private final java.util.concurrent.atomic.AtomicInteger activeBatches =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Constructs a ClickHouseBatchExecutor with the given core pool size
     * and thread factory.
     *
     * @param corePoolSize  the number of threads to keep in the pool
     * @param threadFactory the factory to use when creating new threads
     */
    public ClickHouseBatchExecutor(int corePoolSize,
                                   ThreadFactory threadFactory) {
        super(corePoolSize, threadFactory);
    }

    /**
     * Pauses the executor, causing tasks to wait before execution.
     */
    public void pause() {
        isPaused = true;
    }

    /**
     * Invoked before execution of a task.
     *
     * <p>This method polls until the executor is resumed.
     *
     * @param t the thread that will run task r
     * @param r the task that will be executed
     */
    @Override
    public void beforeExecute(Thread t, Runnable r) {
        while (isPaused) {
            try {
                TimeUnit.MILLISECONDS.sleep(POLLING_INTERVAL_MS);
            } catch (InterruptedException ie) {
                t.interrupt();
            }
        }
        activeBatches.incrementAndGet();
    }

    /**
     * Invoked after execution of a task.
     *
     * @param r the task that was executed
     * @param t the exception that terminated the task, or null
     */
    @Override
    public void afterExecute(Runnable r, Throwable t) {
        activeBatches.decrementAndGet();
        super.afterExecute(r, t);
    }

    /**
     * Waits until no batch is executing, up to {@code timeoutMs}.
     *
     * <p>Call after {@link #pause()} so that {@link #pause()} (no new batches)
     * plus this (no running batches) together give a genuinely quiescent
     * writer, which is what applying a DDL safely requires.</p>
     *
     * @param timeoutMs maximum time to wait, in milliseconds.
     * @return true if the executor became quiescent within the timeout.
     */
    public boolean awaitQuiescent(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (activeBatches.get() > 0) {
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(POLLING_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * Resumes the executor, allowing tasks to execute.
     */
    public void resume() {
        isPaused = false;
    }
}

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
     */
    boolean isPaused = false;

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
    }

    /**
     * Resumes the executor, allowing tasks to execute.
     */
    public void resume() {
        isPaused = false;
    }
}

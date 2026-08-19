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
     * <p>Must be {@code volatile}. This flag is the DDL-versus-DML barrier:
     * {@code DebeziumChangeEventCapture} calls {@link #pause()} from the
     * Debezium change-event thread before applying a DDL and {@link #resume()}
     * after it, while the batch-pool threads read the flag in the
     * {@link #beforeExecute} spin loop. Writer and readers are different
     * threads with no lock and no other happens-before edge between them, so
     * without {@code volatile} the JMM gives no visibility guarantee (JLS
     * 17.4), and the tight {@code while (isPaused)} loop - whose body touches
     * no other shared state - may hoist the read out of the loop entirely.</p>
     *
     * <p>The consequence is not only a stall but silent corruption: a batch
     * thread that never observes the pause applies DML against a schema that
     * is mid-DDL; one that spins past the resume stalls instead. Pinned by
     * {@code ExecutorPauseVisibilityTest}.</p>
     */
    volatile boolean isPaused = false;

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

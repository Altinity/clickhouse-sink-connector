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
     * Guards the pause flag and the in-flight counter together.
     *
     * <p>Testing {@code isPaused} and incrementing {@code activeBatches} must
     * be ONE atomic step. Performed separately they form a check-then-act race
     * that defeats the drain entirely:</p>
     *
     * <pre>
     *   pool thread              Debezium thread
     *   ---------------------    ------------------------------------------
     *   reads isPaused == false
     *                            pause()           -&gt; isPaused = true
     *                            awaitQuiescent()  -&gt; activeBatches == 0, TRUE
     *                            applies the ALTER
     *   activeBatches++
     *   writes the batch                           &lt;- pre-ALTER rows, post-ALTER table
     * </pre>
     *
     * <p>The drain reports a quiescent writer while a batch is about to run,
     * and the rows that batch carries were read under the previous schema. That
     * is the DDL/DML race behind the silent column loss: the writer binds a
     * record captured before the ALTER against the table as it exists after it,
     * and the row inserts successfully with the wrong contents.</p>
     */
    private final Object gate = new Object();

    /**
     * Pauses the executor, causing tasks to wait before execution.
     *
     * <p>Publishes the flag under {@link #gate}, so any thread that has not yet
     * completed its check-and-register step in {@link #beforeExecute} is
     * guaranteed to observe it.</p>
     */
    public void pause() {
        synchronized (gate) {
            isPaused = true;
            gate.notifyAll();
        }
    }

    /**
     * Invoked before execution of a task.
     *
     * <p>Waits while the executor is paused and registers this batch as
     * in-flight atomically with that check, under {@link #gate}.</p>
     *
     * @param t the thread that will run task r
     * @param r the task that will be executed
     */
    @Override
    public void beforeExecute(Thread t, Runnable r) {
        synchronized (gate) {
            while (isPaused) {
                try {
                    // Waiting on the monitor rather than sleeping releases the
                    // lock while parked, so a concurrent pause()/resume() or
                    // awaitQuiescent() is never blocked by a waiting pool
                    // thread, and the resume is observed immediately.
                    gate.wait(POLLING_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    t.interrupt();
                    return;
                }
            }
            // Same critical section as the check above: if this increments,
            // isPaused was false and awaitQuiescent() is guaranteed to see
            // this batch rather than racing past it.
            activeBatches.incrementAndGet();
        }
    }

    /**
     * Invoked after execution of a task.
     *
     * @param r the task that was executed
     * @param t the exception that terminated the task, or null
     */
    @Override
    public void afterExecute(Runnable r, Throwable t) {
        synchronized (gate) {
            activeBatches.decrementAndGet();
            gate.notifyAll();
        }
        super.afterExecute(r, t);
    }

    /**
     * Waits until no batch is executing, up to {@code timeoutMs}.
     *
     * <p>Call after {@link #pause()} so that {@link #pause()} (no new batches)
     * plus this (no running batches) together give a genuinely quiescent
     * writer, which is what applying a DDL safely requires. Because the pause
     * check and the in-flight increment share {@link #gate}, a {@code true}
     * result now means no batch is running AND none can start.</p>
     *
     * @param timeoutMs maximum time to wait, in milliseconds.
     * @return true if the executor became quiescent within the timeout.
     */
    public boolean awaitQuiescent(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (gate) {
            while (activeBatches.get() > 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    gate.wait(Math.min(remaining, POLLING_INTERVAL_MS));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Resumes the executor, allowing tasks to execute.
     */
    public void resume() {
        synchronized (gate) {
            isPaused = false;
            gate.notifyAll();
        }
    }
}

package com.altinity.clickhouse.sink.connector.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Memory-visibility contract for {@link ClickHouseBatchExecutor}'s pause flag.
 *
 * <p>{@code pause()} / {@code resume()} are the DDL-versus-DML barrier. In
 * {@code DebeziumChangeEventCapture.processEveryChangeRecord} a DDL statement
 * calls {@code executor.pause()}, applies the schema change, and only then
 * calls {@code executor.resume()}. Every batch thread is expected to park
 * inside {@code beforeExecute} for the whole of that window, so no DML is
 * applied against a schema that is mid-change.</p>
 *
 * <p>The barrier is only as strong as the flag's visibility. {@code isPaused}
 * is written by the Debezium change-event thread and read by the batch-pool
 * threads, with no lock and no other happens-before edge between them: the
 * write is a plain field store and the read sits in a tight
 * {@code while (isPaused)} spin whose body touches nothing else the JMM could
 * use to force a re-read. Under JLS 17.4 a non-volatile field carries no
 * visibility guarantee across threads, and such a loop is a textbook
 * hoisting candidate — the reading thread is permitted to cache {@code false}
 * and never observe the pause at all. The failure is silent and one-directional:
 * DML lands against the pre-DDL schema, which is a corruption vector, not a
 * stall.</p>
 *
 * <p>These tests therefore pin the property structurally rather than by trying
 * to observe a race. A timing-based test would be non-deterministic — it would
 * pass on a machine that happens not to hoist, which is exactly the false
 * assurance this suite exists to prevent. The declaration check is exact and
 * fails deterministically on any JVM if the modifier is ever dropped.</p>
 */
public class ExecutorPauseVisibilityTest {

    /** Bounds the handshake waits. Generous: a pass must never depend on it. */
    private static final long TIMEOUT_SECONDS = 10;

    private static ClickHouseBatchExecutor newExecutor() {
        return new ClickHouseBatchExecutor(1, r -> new Thread(r, "test-batch"));
    }

    @Test
    @DisplayName("isPaused must be declared volatile — it is the DDL/DML barrier")
    public void pauseFlagIsVolatile() throws NoSuchFieldException {
        Field f = ClickHouseBatchExecutor.class.getDeclaredField("isPaused");

        assertTrue(java.lang.reflect.Modifier.isVolatile(f.getModifiers()),
                "ClickHouseBatchExecutor.isPaused is written by the Debezium "
                        + "change-event thread (pause/resume around a DDL) and read by the "
                        + "batch-pool threads in the beforeExecute spin loop. Without "
                        + "volatile there is no happens-before edge between those threads, "
                        + "so a batch thread may never observe the pause and can apply DML "
                        + "against a schema that is mid-DDL. Restore the volatile modifier "
                        + "rather than relaxing this assertion.");
    }

    @Test
    @DisplayName("A paused executor holds a task in beforeExecute until resume()")
    public void pausedExecutorBlocksUntilResumed() throws Exception {
        ClickHouseBatchExecutor executor = newExecutor();
        try {
            AtomicBoolean taskRan = new AtomicBoolean(false);
            CountDownLatch finished = new CountDownLatch(1);

            executor.pause();
            executor.execute(() -> {
                taskRan.set(true);
                finished.countDown();
            });

            // The task must NOT run while paused. This direction of the assertion
            // is safe to check with a bounded wait: awaiting a latch that should
            // not fire can only produce a false PASS under a scheduling delay,
            // never a false failure, and the resume half below closes that gap by
            // proving the very same task does run once the flag is cleared.
            assertFalse(finished.await(500, TimeUnit.MILLISECONDS),
                    "Task executed while the executor was paused — the DDL barrier "
                            + "did not hold, so DML can be applied against a mid-DDL schema.");
            assertFalse(taskRan.get(), "Task body ran during the pause window.");

            executor.resume();

            assertTrue(finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Task did not run after resume() — replication would stall "
                            + "permanently once a DDL is seen.");
            assertTrue(taskRan.get(), "Task body did not run after resume().");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("resume() published from another thread is observed by the pool thread")
    public void resumeIsVisibleAcrossThreads() throws Exception {
        ClickHouseBatchExecutor executor = newExecutor();
        try {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(1);

            executor.pause();
            executor.execute(() -> {
                started.countDown();
                finished.countDown();
            });

            // Cross-thread publish, mirroring the real caller: the Debezium
            // change-event thread resumes, the batch-pool thread must see it.
            Thread resumer = new Thread(executor::resume, "test-ddl-thread");
            resumer.start();
            resumer.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));

            assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Pool thread never observed resume() published by another "
                            + "thread — the pause flag is not being read across threads.");
            assertTrue(finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Task did not complete after a cross-thread resume().");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("pause/resume is re-entrant across successive DDL statements")
    public void repeatedPauseResumeCyclesKeepWorking() throws Exception {
        ClickHouseBatchExecutor executor = newExecutor();
        try {
            // A stream carrying several DDLs pauses and resumes repeatedly. If
            // either transition were sticky, replication would stall on the
            // second statement rather than the first, so one cycle is not
            // sufficient coverage.
            for (int cycle = 0; cycle < 5; cycle++) {
                CountDownLatch finished = new CountDownLatch(1);
                executor.pause();
                executor.execute(finished::countDown);

                assertFalse(finished.await(200, TimeUnit.MILLISECONDS),
                        "Cycle " + cycle + ": task ran while paused.");

                executor.resume();

                assertTrue(finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                        "Cycle " + cycle + ": task did not run after resume().");
            }
        } finally {
            executor.shutdownNow();
        }
    }
}

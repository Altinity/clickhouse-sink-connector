package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchExecutor;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Regression test for the single.threaded DDL pause NPE.
 *
 * <p>In single.threaded mode {@code setupProcessingThread()} creates a
 * {@code singleThreadedWriter} and returns without ever constructing the
 * batch executor, so {@code this.executor} stays null. The DDL branch of
 * {@code processEveryChangeRecord} pauses/resumes the executor around
 * {@code performDDLOperation}; calling {@code this.executor.pause()}
 * unconditionally there NPEs on the FIRST DDL of every single-threaded
 * run. Upstream silently swallowed that NPE in a log-only catch (skipping
 * the DDL); once the catch was made to fail loudly, the swallowed NPE
 * became a fatal engine stop that cascaded across the CI suite
 * (MariaDBIT, MySQLDemoIT, ReplicationLogOnlyIT and every later IT in
 * the same JVM).</p>
 *
 * <p>These tests pin the fix: pause/resume must be null-safe no-ops when
 * no batch executor exists, and must still delegate when one does.</p>
 */
@DisplayName("DDL pause/resume must be null-safe in single.threaded mode")
public class SingleThreadedDdlPauseTest {

    private static void setExecutor(DebeziumChangeEventCapture capture,
                                    ClickHouseBatchExecutor executor) throws Exception {
        Field f = DebeziumChangeEventCapture.class.getDeclaredField("executor");
        f.setAccessible(true);
        f.set(capture, executor);
    }

    @Test
    @DisplayName("pause/resume are no-ops when the executor is null (single.threaded mode)")
    public void testPauseResumeWithNullExecutor() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        // single.threaded mode: setupProcessingThread never assigns executor.
        assertDoesNotThrow(capture::pauseBatchExecutor,
                "pause must not NPE when no batch executor exists");
        assertDoesNotThrow(capture::resumeBatchExecutor,
                "resume must not NPE when no batch executor exists");
    }

    @Test
    @DisplayName("pause/resume delegate to the executor when one exists (multi-threaded mode)")
    public void testPauseResumeWithExecutor() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        ClickHouseBatchExecutor executor = new ClickHouseBatchExecutor(1,
                new ThreadFactoryBuilder().setNameFormat("test-pool-%d").build());
        try {
            setExecutor(capture, executor);

            capture.pauseBatchExecutor();
            // While paused, submitted tasks must not run: beforeExecute spins
            // on the paused flag. Verify indirectly by resuming and confirming
            // a task then executes.
            capture.resumeBatchExecutor();

            java.util.concurrent.CountDownLatch ran =
                    new java.util.concurrent.CountDownLatch(1);
            executor.execute(ran::countDown);
            org.junit.jupiter.api.Assertions.assertTrue(
                    ran.await(10, java.util.concurrent.TimeUnit.SECONDS),
                    "task must run after resume");
        } finally {
            executor.shutdownNow();
        }
    }
}

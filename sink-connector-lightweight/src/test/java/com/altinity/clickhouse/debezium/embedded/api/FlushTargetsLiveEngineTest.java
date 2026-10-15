package com.altinity.clickhouse.debezium.embedded.api;

import com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ThreadFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 01.01 section 3.4: {@code /flush} and {@code /resume} act on the engine
 * that is running NOW. The REST server is started once per JVM while
 * {@code /start}, {@code /restart} and the monitor replace the engine; the
 * instance passed to {@code startRestApi} goes stale at the first restart.
 * Before the fix the handlers paused that stale instance -- its pool was shut
 * down, so the pause paused nothing -- and answered 200 "flushed" while the
 * live engine kept writing.
 */
public class FlushTargetsLiveEngineTest {

    private static final ThreadFactory FACTORY = r -> {
        Thread t = new Thread(r, "flush-live-engine-test");
        t.setDaemon(true);
        return t;
    };

    private static void setApplicationEngine(DebeziumChangeEventCapture engine) throws Exception {
        Field f = ClickHouseDebeziumEmbeddedApplication.class.getDeclaredField("debeziumChangeEventCapture");
        f.setAccessible(true);
        f.set(null, engine);
    }

    private static void setExecutor(DebeziumChangeEventCapture engine, ClickHouseBatchExecutor pool)
            throws Exception {
        Field f = DebeziumChangeEventCapture.class.getDeclaredField("executor");
        f.setAccessible(true);
        f.set(engine, pool);
    }

    private static boolean isPaused(ClickHouseBatchExecutor pool) throws Exception {
        Field f = ClickHouseBatchExecutor.class.getDeclaredField("isPaused");
        f.setAccessible(true);
        return (Boolean) f.get(pool);
    }

    @AfterEach
    public void clearApplicationEngine() throws Exception {
        setApplicationEngine(null);
    }

    @Test
    @DisplayName("01.01 s3.4: after a restart the REST handlers resolve the new engine, not the startup one")
    public void handlersResolveTheEngineRunningNow() throws Exception {
        DebeziumChangeEventCapture startedWith = new DebeziumChangeEventCapture();
        DebeziumChangeEventCapture afterRestart = new DebeziumChangeEventCapture();
        setApplicationEngine(afterRestart);

        assertSame(afterRestart, DebeziumEmbeddedRestApi.liveEngine(startedWith));
    }

    @Test
    @DisplayName("01.01 s3.4: without an application engine the handlers fall back to the startup instance")
    public void handlersFallBackToTheStartupInstance() {
        DebeziumChangeEventCapture startedWith = new DebeziumChangeEventCapture();

        assertSame(startedWith, DebeziumEmbeddedRestApi.liveEngine(startedWith));
        assertThrows(IllegalStateException.class, () -> DebeziumEmbeddedRestApi.liveEngine(null));
    }

    @Test
    @DisplayName("01.01 s3.4: flushing a stopped engine is refused, never reported as success")
    public void flushOfAStoppedEngineIsRefused() throws Exception {
        DebeziumChangeEventCapture stopped = new DebeziumChangeEventCapture();
        ClickHouseBatchExecutor pool = new ClickHouseBatchExecutor(1, FACTORY);
        setExecutor(stopped, pool);
        pool.shutdown();

        assertThrows(IllegalStateException.class, stopped::flushAndPause);
        assertThrows(IllegalStateException.class, stopped::resumeAfterFlush);
        assertFalse(isPaused(pool), "a refused flush must not leave the flag set");
    }

    @Test
    @DisplayName("01.01 s3.4: flushing a running engine pauses its pool and resume releases it")
    public void flushOfARunningEnginePausesItsPool() throws Exception {
        DebeziumChangeEventCapture running = new DebeziumChangeEventCapture();
        ClickHouseBatchExecutor pool = new ClickHouseBatchExecutor(1, FACTORY);
        try {
            setExecutor(running, pool);

            running.flushAndPause();
            assertTrue(isPaused(pool));
            running.resumeAfterFlush();
            assertFalse(isPaused(pool));
        } finally {
            pool.shutdownNow();
        }
    }
}

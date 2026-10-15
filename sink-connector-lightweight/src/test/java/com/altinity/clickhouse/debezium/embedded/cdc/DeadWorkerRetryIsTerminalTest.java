package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.config.SinkConnectorLightWeightConfig;
import org.apache.kafka.connect.errors.ConnectException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An engine failure while a sink worker is dead is terminal at once: it is
 * never retried against the same pool (spec 10.04 §3.5 rule 6, spec 03.01
 * §3.3 item 5, spec 09.01 §3.8 item 4).
 *
 * <p><b>The defect.</b> The completion-callback retry recreates the engine
 * on the same {@code DebeziumChangeEventCapture}; the worker pool is kept.
 * A worker whose scheduled task has terminated stays dead across every
 * retry, {@code failIfWorkerDied} stops each recreated engine on its first
 * batch before anything is written or acknowledged (so the progress refill
 * never happens either), and the process only exits once the budget is
 * spent. Observed: a source connection dropped mid-transaction, the engine
 * stopped, one worker died 1.5 s later on {@code OffsetStorageWriter is
 * already flushing}, and the engine was restarted ten times in 160 s -- ten
 * identical "Sink worker 1 of 10 is dead" failures, one fresh binlog dump
 * from the source per attempt, ~0.5 GB of replayed-row log -- before the
 * terminal exit that should have happened at the first failure.</p>
 *
 * <p><b>The rule.</b> {@code handleEngineCompletion} asks
 * {@code hasDeadWorker()} -- any worker future {@code isDone()}, the same
 * predicate {@code failIfWorkerDied} throws on -- after the FATAL check and
 * before drawing on the budget, and goes terminal when it is true. A dead
 * worker is deterministic for the life of the process; only a process
 * restart gives a fresh pool.</p>
 */
public class DeadWorkerRetryIsTerminalTest {

    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);

    private int savedMaxRetries;
    private int savedSleep;
    private IntConsumer savedHook;
    private final List<Integer> exitCodes = new ArrayList<>();

    @BeforeEach
    public void arm() {
        savedMaxRetries = DebeziumChangeEventCapture.MAX_RETRIES;
        savedSleep = DebeziumChangeEventCapture.SLEEP_TIME;
        savedHook = DebeziumChangeEventCapture.terminalFailureHook;
        DebeziumChangeEventCapture.MAX_RETRIES = 3;
        DebeziumChangeEventCapture.SLEEP_TIME = 0;
        DebeziumChangeEventCapture.terminalFailureHook = exitCodes::add;
    }

    @AfterEach
    public void disarm() {
        DebeziumChangeEventCapture.MAX_RETRIES = savedMaxRetries;
        DebeziumChangeEventCapture.SLEEP_TIME = savedSleep;
        DebeziumChangeEventCapture.terminalFailureHook = savedHook;
        executor.shutdownNow();
    }

    /**
     * A worker exactly as production loses one: a periodic task whose run
     * throws. The executor completes its future exceptionally and never
     * schedules it again.
     */
    private ScheduledFuture<?> deadWorker() throws Exception {
        ScheduledFuture<?> future = executor.scheduleAtFixedRate(() -> {
            throw new RuntimeException("OffsetStorageWriter is permanently stuck flushing; "
                    + "stopping to prevent silent data divergence",
                    new ConnectException("OffsetStorageWriter is already flushing"));
        }, 0, 10, TimeUnit.MILLISECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!future.isDone() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(future.isDone(), "the worker task must have terminated");
        return future;
    }

    /** A worker that keeps running: its periodic task returns normally every tick. */
    private ScheduledFuture<?> liveWorker() {
        return executor.scheduleAtFixedRate(() -> { }, 0, 10, TimeUnit.MILLISECONDS);
    }

    /** The exception {@code failIfWorkerDied} raises for the dead worker above. */
    private static RuntimeException deadWorkerFailure() {
        return new RuntimeException("Sink worker 1 of 10 is dead: its scheduled task has terminated.",
                new RuntimeException("OffsetStorageWriter is permanently stuck flushing; "
                        + "stopping to prevent silent data divergence",
                        new ConnectException("OffsetStorageWriter is already flushing")));
    }

    /** The source-side failure that preceded the worker's death in production. */
    private static RuntimeException sourceConnectionLost() {
        return new ConnectException("An exception occurred in the change event producer. "
                + "This connector will be stopped.",
                new RuntimeException("Failed to deserialize data of EventHeaderV4",
                        new java.io.EOFException("Failed to read next byte from position 659748909")));
    }

    private static Properties props() {
        Properties p = new Properties();
        p.setProperty(SinkConnectorLightWeightConfig.EXIT_ON_TERMINAL_FAILURE, "true");
        return p;
    }

    @Test
    @DisplayName("A dead worker makes the engine failure terminal at once: no retry, exit hook, replication stopped")
    public void deadWorkerIsTerminalAtOnce() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        capture.workerFutures.add(deadWorker());
        assertTrue(capture.hasDeadWorker());
        AtomicInteger restarts = new AtomicInteger();

        capture.handleEngineCompletion(false, "engine stopped", deadWorkerFailure(), props(),
                restarts::incrementAndGet);

        assertEquals(0, restarts.get(),
                "the retry keeps the pool, so a recreated engine would stop on its first batch the same "
                        + "way (pre-fix: MAX_RETRIES identical failures, one engine start and one binlog "
                        + "dump each, before the same exit)");
        assertEquals(Collections.singletonList(DebeziumChangeEventCapture.TERMINAL_FAILURE_EXIT_CODE),
                exitCodes, "the failure must exit the process exactly as a spent budget does");
        assertFalse(ReplicationStatusSingleton.getInstance().isReplicationRunning(),
                "replication must be reported as not running");
    }

    @Test
    @DisplayName("The predicate is the pool, not the exception: a source-side failure while a worker is dead is terminal too")
    public void engineFailureWhileAWorkerIsDeadIsTerminalToo() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        capture.workerFutures.add(liveWorker());
        capture.workerFutures.add(deadWorker());
        AtomicInteger restarts = new AtomicInteger();

        capture.handleEngineCompletion(false, "engine stopped", sourceConnectionLost(), props(),
                restarts::incrementAndGet);

        assertEquals(0, restarts.get(),
                "one dead worker out of two is enough: failIfWorkerDied would stop the recreated engine");
        assertEquals(1, exitCodes.size());
    }

    @Test
    @DisplayName("With every worker alive the same unclassified failure still draws on the retry budget")
    public void liveWorkersKeepTheRetryPath() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        capture.workerFutures.add(liveWorker());
        capture.workerFutures.add(liveWorker());
        assertFalse(capture.hasDeadWorker());
        AtomicInteger restarts = new AtomicInteger();

        capture.handleEngineCompletion(false, "engine stopped", sourceConnectionLost(), props(),
                restarts::incrementAndGet);

        assertEquals(1, restarts.get(), "a live pool can serve a recreated engine: the engine is recreated");
        assertTrue(exitCodes.isEmpty(), "no exit while retries remain");
    }
}

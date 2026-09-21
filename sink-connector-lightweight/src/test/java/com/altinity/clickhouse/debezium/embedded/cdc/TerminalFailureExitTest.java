package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.config.SinkConnectorLightWeightConfig;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A terminal engine failure must terminate, not idle (spec 10.04 §3.5).
 *
 * <p><b>The defect.</b> The engine's {@code CompletionCallback} recreated the
 * engine on every failure until {@code numRetries} passed {@code MAX_RETRIES}
 * -- and then did nothing. The JVM stayed up with replication stopped, the
 * REST API answering, the metrics port open, and no process-level signal for
 * a supervisor or a liveness probe to act on. {@code numRetries} was never
 * reset either, so a connector that had recovered from ten transient failures
 * over a month died silently on the eleventh.</p>
 *
 * <p><b>The rule.</b> A successful start resets the budget. When the budget
 * is exhausted the failure is logged at FATAL and, unless
 * {@code exit.on.terminal.failure=false}, the process exits through
 * {@code terminalFailureHook} (which is {@code System::exit} in production).
 * With the exit disabled, replication is marked not running so {@code /status}
 * reports {@code Replica_Running=false} -- a liveness failure a probe can see.</p>
 */
public class TerminalFailureExitTest {

    /** Collects everything the class under test logs during one call. */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

        CapturingAppender() {
            super("capture-terminal-failure", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

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
    }

    private static Properties props(String exitOnTerminalFailure) {
        Properties p = new Properties();
        if (exitOnTerminalFailure != null) {
            p.setProperty(SinkConnectorLightWeightConfig.EXIT_ON_TERMINAL_FAILURE, exitOnTerminalFailure);
        }
        return p;
    }

    private static void fail(DebeziumChangeEventCapture capture, Properties props, Runnable restart) {
        capture.handleEngineCompletion(false, "engine stopped", new RuntimeException("source unreachable"),
                props, restart);
    }

    @Test
    @DisplayName("The exit hook fires once the retry budget is exhausted, and not before")
    public void exitHookFiresAfterMaxRetries() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        AtomicInteger restarts = new AtomicInteger();
        Runnable restart = restarts::incrementAndGet;

        for (int i = 1; i <= DebeziumChangeEventCapture.MAX_RETRIES; i++) {
            fail(capture, props(null), restart);
            assertEquals(i, restarts.get(), "failure " + i + " must be retried");
            assertTrue(exitCodes.isEmpty(), "the process must not exit while retries remain");
        }

        fail(capture, props(null), restart);

        assertEquals(DebeziumChangeEventCapture.MAX_RETRIES, restarts.get(),
                "no further restart once the budget is spent");
        assertEquals(Collections.singletonList(DebeziumChangeEventCapture.TERMINAL_FAILURE_EXIT_CODE),
                exitCodes,
                "the terminal failure must exit the process (pre-fix: nothing happened and the JVM "
                        + "idled with replication stopped)");
        assertFalse(ReplicationStatusSingleton.getInstance().isReplicationRunning(),
                "replication must be reported as not running");
    }

    @Test
    @DisplayName("A successful start resets the retry budget")
    public void successfulStartResetsTheBudget() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        AtomicInteger restarts = new AtomicInteger();
        Runnable restart = restarts::incrementAndGet;

        fail(capture, props(null), restart);
        fail(capture, props(null), restart);
        assertEquals(2, restarts.get());

        // The engine came up: the budget is whole again.
        capture.markEngineStarted();

        for (int i = 1; i <= DebeziumChangeEventCapture.MAX_RETRIES; i++) {
            fail(capture, props(null), restart);
            assertTrue(exitCodes.isEmpty(),
                    "after a successful start the full budget applies again (pre-fix: numRetries was "
                            + "never reset, so old failures counted against new ones)");
        }
        assertEquals(2 + DebeziumChangeEventCapture.MAX_RETRIES, restarts.get());

        fail(capture, props(null), restart);
        assertEquals(1, exitCodes.size(), "the budget is spent again");
    }

    @Test
    @DisplayName("With exit.on.terminal.failure=false the process stays up, replication is reported stopped, FATAL is logged")
    public void exitDisabledIsALoudLivenessFailure() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        AtomicInteger restarts = new AtomicInteger();
        Properties props = props("false");

        Logger coreLogger = (Logger) LogManager.getLogger(DebeziumChangeEventCapture.class);
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        coreLogger.addAppender(appender);
        try {
            for (int i = 0; i <= DebeziumChangeEventCapture.MAX_RETRIES; i++) {
                fail(capture, props, restarts::incrementAndGet);
            }
        } finally {
            coreLogger.removeAppender(appender);
            appender.stop();
        }

        assertTrue(exitCodes.isEmpty(), "exit.on.terminal.failure=false must not exit");
        assertEquals(DebeziumChangeEventCapture.MAX_RETRIES, restarts.get());
        assertFalse(ReplicationStatusSingleton.getInstance().isReplicationRunning(),
                "the liveness signal must still say replication is stopped");
        boolean fatal = appender.events.stream().anyMatch(e -> e.getLevel() == Level.FATAL
                && e.getMessage().getFormattedMessage().contains("STOPPED"));
        assertTrue(fatal, "the terminal failure must be logged at FATAL naming replication as STOPPED");
    }

    @Test
    @DisplayName("A successful completion neither restarts nor exits")
    public void successIsANoOp() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        AtomicInteger restarts = new AtomicInteger();

        capture.handleEngineCompletion(true, "done", null, props(null), restarts::incrementAndGet);

        assertEquals(0, restarts.get());
        assertTrue(exitCodes.isEmpty());
    }
}

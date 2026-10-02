package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.config.SinkConnectorLightWeightConfig;
import com.altinity.clickhouse.sink.connector.converters.DebeziumConverter;
import com.altinity.clickhouse.sink.connector.executor.DebeziumOffsetManagement;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.kafka.connect.source.SourceRecord;
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
 * <p><b>The second defect.</b> The first fix reset {@code numRetries} in the
 * {@code connectorStarted} callback. But an engine that dies on a
 * deterministic error -- an unrepresentable value in the first batch after
 * the committed offset -- starts cleanly every time, streams to the same
 * record and stops again, so every failure was "retry 1 of 10": the budget
 * was never spent, the terminal path never ran, and the connector restarted
 * its engine every {@code SLEEP_TIME} forever with replication stopped and
 * {@code /status} reporting it running.</p>
 *
 * <p><b>The rule.</b> The budget refills on PROGRESS -- an offset acknowledged
 * to Debezium since the previous failure ({@link
 * DebeziumOffsetManagement#acknowledgements()}) -- never on a bare start.
 * When the budget is exhausted the failure is logged at FATAL and, unless
 * {@code exit.on.terminal.failure=false}, the process exits through
 * {@code terminalFailureHook} (which is {@code System::exit} in production).
 * With the exit disabled, replication is marked not running so {@code /status}
 * reports {@code Replica_Running=false} -- a liveness failure a probe can see.</p>
 */
public class TerminalFailureExitTest {

    /** The least a committer must do for {@code acknowledgeRecords} to count an offset. */
    private static final class NoopCommitter
            implements DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> {
        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record) {
        }

        @Override
        public void markBatchFinished() {
        }

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record,
                                  DebeziumEngine.Offsets sourceOffsets) {
        }

        @Override
        public DebeziumEngine.Offsets buildOffsets() {
            return (key, value) -> { };
        }
    }

    /** A non-null change event; its content is irrelevant to the acknowledgement count. */
    private static ChangeEvent<SourceRecord, SourceRecord> anyEvent() {
        return new ChangeEvent<SourceRecord, SourceRecord>() {
            @Override
            public SourceRecord key() {
                return null;
            }

            @Override
            public SourceRecord value() {
                return null;
            }

            @Override
            public String destination() {
                return "srv.db.orders";
            }

            @Override
            public Integer partition() {
                return null;
            }
        };
    }

    /**
     * The engine made progress: one offset acknowledged through the real
     * acknowledgement path, exactly as a written batch or a committed control
     * record does it.
     */
    private static void acknowledgeOneOffset() throws InterruptedException {
        DebeziumOffsetManagement.acknowledgeRecords(new NoopCommitter(), anyEvent(), true);
    }

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

    /**
     * The failure shape of an unrepresentable value in production: the sink
     * worker dies with the batch retained, the Debezium thread reports the
     * dead worker, and the cause chain ends in
     * {@link DebeziumConverter.ValueOutOfRangeException} (spec 10.01 §3.1).
     */
    private static RuntimeException deadWorkerOnUnrepresentableValue() {
        return new RuntimeException("Sink worker 7 of 10 is dead: its scheduled task has terminated.",
                new RuntimeException("Fatal ClickHouse error, stopping task",
                        new RuntimeException(new DebeziumConverter.ValueOutOfRangeException(
                                "Value 9999-12-31T23:59:59Z for column db.orders.expires_at is outside "
                                        + "the ClickHouse DateTime64 range"))));
    }

    @Test
    @DisplayName("A FATAL failure (terminal exception type in the cause chain) is terminal at once: no retry, exit")
    public void fatalTerminalTypeIsNotRetried() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        AtomicInteger restarts = new AtomicInteger();

        capture.handleEngineCompletion(false, "engine stopped", deadWorkerOnUnrepresentableValue(),
                props(null), restarts::incrementAndGet);

        assertEquals(0, restarts.get(),
                "a deterministic failure must not consume the retry budget (pre-fix: 'retry 1 of 10', "
                        + "connectorStarted() reset the counter, and the engine restarted on the same "
                        + "event every SLEEP_TIME forever)");
        assertEquals(Collections.singletonList(DebeziumChangeEventCapture.TERMINAL_FAILURE_EXIT_CODE),
                exitCodes, "the FATAL failure must exit the process like a spent budget does");
        assertFalse(ReplicationStatusSingleton.getInstance().isReplicationRunning(),
                "replication must be reported as not running");
    }

    @Test
    @DisplayName("A FATAL ClickHouse error code in the cause chain is terminal at once as well")
    public void fatalErrorCodeIsNotRetried() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        AtomicInteger restarts = new AtomicInteger();
        RuntimeException unknownTable = new RuntimeException("Sink worker 2 of 10 is dead",
                new RuntimeException("Fatal ClickHouse error, stopping task",
                        new java.sql.SQLException("Code: 60. DB::Exception: Table db.orders does not exist. "
                                + "(UNKNOWN_TABLE)")));

        capture.handleEngineCompletion(false, "engine stopped", unknownTable, props(null),
                restarts::incrementAndGet);

        assertEquals(0, restarts.get(), "UNKNOWN_TABLE is deterministic: no retry");
        assertEquals(Collections.singletonList(DebeziumChangeEventCapture.TERMINAL_FAILURE_EXIT_CODE),
                exitCodes);
    }

    @Test
    @DisplayName("A retriable failure still draws on the retry budget (the fix is scoped to FATAL)")
    public void retriableFailureStillRetries() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        AtomicInteger restarts = new AtomicInteger();
        RuntimeException tooManyParts = new RuntimeException("Sink worker 1 of 10 is dead",
                new java.sql.SQLException("Code: 252. DB::Exception: Too many parts (3000). (TOO_MANY_PARTS)"));

        capture.handleEngineCompletion(false, "engine stopped", tooManyParts, props(null),
                restarts::incrementAndGet);

        assertEquals(1, restarts.get(), "TOO_MANY_PARTS clears on its own: the engine is recreated");
        assertTrue(exitCodes.isEmpty());
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
    @DisplayName("Progress -- an acknowledged offset -- resets the retry budget")
    public void progressResetsTheBudget() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        AtomicInteger restarts = new AtomicInteger();
        Runnable restart = restarts::incrementAndGet;

        fail(capture, props(null), restart);
        fail(capture, props(null), restart);
        assertEquals(2, restarts.get());

        // The engine came up AND committed an offset: it had recovered.
        capture.markEngineStarted();
        acknowledgeOneOffset();

        for (int i = 1; i <= DebeziumChangeEventCapture.MAX_RETRIES; i++) {
            fail(capture, props(null), restart);
            assertTrue(exitCodes.isEmpty(),
                    "after progress the full budget applies again (pre-fix: numRetries was never "
                            + "reset, so old failures counted against new ones)");
        }
        assertEquals(2 + DebeziumChangeEventCapture.MAX_RETRIES, restarts.get());

        fail(capture, props(null), restart);
        assertEquals(1, exitCodes.size(), "the budget is spent again");
    }

    /**
     * The deterministic-error loop: the engine starts cleanly, streams to the
     * same unrepresentable record, stops, and is restarted -- without a single
     * offset committed in between. Each start is NOT a recovery.
     */
    @Test
    @DisplayName("A start that commits nothing before failing again does not reset the retry budget")
    public void startWithoutProgressDoesNotResetTheBudget() {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        AtomicInteger restarts = new AtomicInteger();
        Runnable restart = () -> {
            restarts.incrementAndGet();
            // Every retry brings the engine up again before it dies on the same record.
            capture.markEngineStarted();
        };

        capture.markEngineStarted();
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
                "the terminal failure must exit the process (pre-fix: every clean start reset the "
                        + "counter, so the engine was restarted forever at 'retry 1 of N')");
        assertFalse(ReplicationStatusSingleton.getInstance().isReplicationRunning(),
                "replication must be reported as not running");
    }

    @Test
    @DisplayName("Progress made before the first failure is not a recovery from anything")
    public void progressBeforeAnyFailureDoesNotWidenTheBudget() throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        AtomicInteger restarts = new AtomicInteger();
        Runnable restart = restarts::incrementAndGet;

        capture.markEngineStarted();
        acknowledgeOneOffset();

        for (int i = 1; i <= DebeziumChangeEventCapture.MAX_RETRIES; i++) {
            fail(capture, props(null), restart);
        }
        fail(capture, props(null), restart);

        assertEquals(DebeziumChangeEventCapture.MAX_RETRIES, restarts.get());
        assertEquals(1, exitCodes.size(), "exactly MAX_RETRIES retries, then terminal");
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

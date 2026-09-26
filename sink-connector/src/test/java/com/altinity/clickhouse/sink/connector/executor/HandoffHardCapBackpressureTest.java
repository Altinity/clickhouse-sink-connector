package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.executor.OffsetTestSupport.RecordingCommitter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hard cap on the reader's lead over the writers (spec 01.05 §3.4, spec
 * 09.01 §3.1 step 5).
 *
 * <p><b>The defect.</b> Every row handed to the writers stays on the heap until
 * its unit is acknowledged, and the only bound on the handoff was the
 * per-queue capacity, counted in BATCHES (default 500,000 per queue) -- a
 * Debezium batch holds one row or ten thousand, so that bound is no bound in
 * bytes. A reader that outran stalled writers therefore handed off rows until
 * the heap was full: on one deployment the writers stopped acknowledging, the
 * reader handed off 1.4M more rows in the next nineteen minutes, and the JVM
 * spent the rest of its life in back-to-back full garbage collections -- a
 * stall with no error line -- until the source aborted the binlog dump the
 * reader had stopped draining. The backlog advisory (§3.1 step 4) named the
 * condition once and could do nothing about it.</p>
 *
 * <p><b>The rule.</b> The producer pauses BEFORE the next handoff while the
 * outstanding ROWS are at or above the cap, resumes as soon as an
 * acknowledgement (or a reset) brings them under it, logs one WARN at the
 * start of a wait and one INFO at its end, runs the caller's dead-worker check
 * between slices, and fails loudly after the configured limit. The cap counts
 * rows, follows the FIFO exactly (a parked unit still counts), and is off at
 * zero.</p>
 */
public class HandoffHardCapBackpressureTest {

    /** Collects everything {@link DebeziumOffsetManagement} logs during one test. */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

        CapturingAppender() {
            super("capture-handoff-hard-cap", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }

        List<LogEvent> capLines() {
            List<LogEvent> lines = new ArrayList<>();
            synchronized (events) {
                for (LogEvent e : events) {
                    if (e.getMessage().getFormattedMessage().contains("Handoff hard cap")) {
                        lines.add(e);
                    }
                }
            }
            return lines;
        }
    }

    private CapturingAppender appender;
    private Level savedLevel;

    @BeforeEach
    public void attach() {
        DebeziumOffsetManagement.reset();
        Logger logger = (Logger) LogManager.getLogger(DebeziumOffsetManagement.class);
        savedLevel = logger.getLevel();
        Configurator.setLevel(DebeziumOffsetManagement.class.getName(), Level.INFO);
        appender = new CapturingAppender();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    public void detach() {
        Logger logger = (Logger) LogManager.getLogger(DebeziumOffsetManagement.class);
        logger.removeAppender(appender);
        appender.stop();
        Configurator.setLevel(DebeziumOffsetManagement.class.getName(), savedLevel);
        DebeziumOffsetManagement.reset();
    }

    /** Hands off one unit of {@code rows} records and returns it. */
    private static List<ClickHouseStruct> handOff(RecordingCommitter committer, int id, int rows) {
        String[] names = new String[rows];
        for (int i = 0; i < rows; i++) {
            names[i] = "u" + id + "r" + i;
        }
        List<ClickHouseStruct> unit = OffsetTestSupport.unit(committer, 1_000L + id, names);
        DebeziumOffsetManagement.registerHandoff(unit, Collections.singletonList(unit));
        return unit;
    }

    /** Runs {@code body} on another thread after {@code delayMs}; failures are re-thrown by {@link #join}. */
    private static Thread after(long delayMs, ThrowingRunnable body, AtomicReference<Throwable> failure) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delayMs);
                body.run();
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "hard-cap-test-writer");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void join(Thread t, AtomicReference<Throwable> failure) throws InterruptedException {
        t.join(10_000);
        assertFalse(t.isAlive(), "the helper thread finished");
        if (failure.get() != null) {
            throw new AssertionError("the helper thread failed", failure.get());
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    @DisplayName("the outstanding row count follows the FIFO: added at handoff, released only when the unit is acknowledged")
    public void rowCountFollowsHandoffAndAcknowledgement() throws InterruptedException {
        RecordingCommitter committer = new RecordingCommitter();
        List<ClickHouseStruct> a = handOff(committer, 0, 1);
        List<ClickHouseStruct> b = handOff(committer, 1, 2);
        List<ClickHouseStruct> c = handOff(committer, 2, 3);
        assertEquals(6, DebeziumOffsetManagement.outstandingRecordCount(), "1 + 2 + 3 rows handed off");

        // b written out of turn: parked behind a, still on the heap, still counted.
        DebeziumOffsetManagement.checkIfBatchCanBeCommitted(b);
        assertEquals(6, DebeziumOffsetManagement.outstandingRecordCount(),
                "a parked unit's rows are still held; the count releases at acknowledgement, not at write");

        // a written: a and the parked b are acknowledged together.
        DebeziumOffsetManagement.checkIfBatchCanBeCommitted(a);
        assertEquals(3, DebeziumOffsetManagement.outstandingRecordCount(), "only c's 3 rows remain");

        DebeziumOffsetManagement.checkIfBatchCanBeCommitted(c);
        assertEquals(0, DebeziumOffsetManagement.outstandingRecordCount());
        assertEquals(0, DebeziumOffsetManagement.reset(), "nothing left to abandon");
    }

    @Test
    @DisplayName("under the cap the producer is not delayed and nothing is logged")
    public void underTheCapReturnsAtOnce() throws InterruptedException {
        RecordingCommitter committer = new RecordingCommitter();
        handOff(committer, 0, 2);
        handOff(committer, 1, 2);
        long start = System.nanoTime();
        DebeziumOffsetManagement.awaitHandoffCapacity(5, 1_000, null);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMs < 500, "4 rows against a cap of 5: no wait, took " + elapsedMs + " ms");
        assertEquals(0, appender.capLines().size(), "nothing to say when the cap is not met");
    }

    @Test
    @DisplayName("a cap of zero disables the wait however far the reader is ahead")
    public void zeroDisablesTheCap() throws InterruptedException {
        RecordingCommitter committer = new RecordingCommitter();
        for (int i = 0; i < 50; i++) {
            handOff(committer, i, 4);
        }
        assertEquals(200, DebeziumOffsetManagement.outstandingRecordCount());
        long start = System.nanoTime();
        DebeziumOffsetManagement.awaitHandoffCapacity(0, 100, null);
        assertTrue((System.nanoTime() - start) / 1_000_000L < 500, "disabled: no wait");
        assertEquals(0, appender.capLines().size());
    }

    @Test
    @DisplayName("at the cap the producer waits until the head is acknowledged, with one WARN and one INFO")
    public void atTheCapWaitsUntilTheHeadIsAcknowledged() throws InterruptedException {
        RecordingCommitter committer = new RecordingCommitter();
        List<ClickHouseStruct> head = handOff(committer, 0, 2);
        handOff(committer, 1, 2);
        assertEquals(4, DebeziumOffsetManagement.outstandingRecordCount());

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = after(300, () -> DebeziumOffsetManagement.checkIfBatchCanBeCommitted(head), failure);

        long start = System.nanoTime();
        DebeziumOffsetManagement.awaitHandoffCapacity(4, 10_000, null);
        long waitedMs = (System.nanoTime() - start) / 1_000_000L;
        join(writer, failure);

        assertTrue(waitedMs >= 200, "the producer was held until the acknowledgement (" + waitedMs + " ms)");
        assertTrue(waitedMs < 5_000, "and released promptly after it (" + waitedMs + " ms)");
        assertEquals(2, DebeziumOffsetManagement.outstandingRecordCount(), "the head's 2 rows were released");

        List<LogEvent> lines = appender.capLines();
        assertEquals(2, lines.size(), "one line when the wait begins, one when it ends: " + lines.size());
        assertEquals(Level.WARN, lines.get(0).getLevel(), "the pause is announced at WARN");
        assertTrue(lines.get(0).getMessage().getFormattedMessage().contains("4 row(s) in 2 unit(s)"),
                "the WARN names the counts: " + lines.get(0).getMessage().getFormattedMessage());
        assertEquals(Level.INFO, lines.get(1).getLevel(), "the release is one INFO");
        assertTrue(lines.get(1).getMessage().getFormattedMessage().contains("released"),
                lines.get(1).getMessage().getFormattedMessage());
    }

    @Test
    @DisplayName("a reset releases a waiting producer: the units it waited on no longer exist")
    public void resetReleasesTheWaiter() throws InterruptedException {
        RecordingCommitter committer = new RecordingCommitter();
        handOff(committer, 0, 3);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread restart = after(300, DebeziumOffsetManagement::reset, failure);

        long start = System.nanoTime();
        DebeziumOffsetManagement.awaitHandoffCapacity(3, 10_000, null);
        long waitedMs = (System.nanoTime() - start) / 1_000_000L;
        join(restart, failure);

        assertTrue(waitedMs >= 200 && waitedMs < 5_000, "held until the reset, then released (" + waitedMs + " ms)");
        assertEquals(0, DebeziumOffsetManagement.outstandingRecordCount());
    }

    @Test
    @DisplayName("the dead-worker check runs while waiting: its throw ends the wait with that exception")
    public void livenessCheckThrowEndsTheWait() {
        RecordingCommitter committer = new RecordingCommitter();
        handOff(committer, 0, 3);
        RuntimeException dead = new IllegalStateException("sink worker 1 of 10 is dead");
        long start = System.nanoTime();
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> DebeziumOffsetManagement.awaitHandoffCapacity(3, 10_000, () -> { throw dead; }));
        long waitedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(thrown == dead, "the caller's exception, not a wrapper");
        assertTrue(waitedMs < 5_000, "surfaced on the first slice, not at the timeout (" + waitedMs + " ms)");
    }

    @Test
    @DisplayName("writers that never acknowledge end the wait loudly after the limit, naming the counts")
    public void theWaitIsBoundedAndLoud() {
        RecordingCommitter committer = new RecordingCommitter();
        handOff(committer, 0, 3);
        long start = System.nanoTime();
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> DebeziumOffsetManagement.awaitHandoffCapacity(3, 250, null));
        long waitedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(waitedMs >= 250, "the limit was honoured (" + waitedMs + " ms)");
        assertTrue(waitedMs < 5_000, "and not overshot (" + waitedMs + " ms)");
        String message = thrown.getMessage();
        assertNotNull(message);
        assertTrue(message.contains("3 row(s) in 1 unit(s)"), "names the counts: " + message);
        assertTrue(message.contains("sink.connector.handoff.wait.timeout.ms"), "names the knob: " + message);
        assertEquals(3, DebeziumOffsetManagement.outstandingRecordCount(),
                "nothing was abandoned by the failure itself; the engine stop does that");
        List<LogEvent> lines = appender.capLines();
        assertEquals(1, lines.size(), "the WARN that began the wait; the failure is the exception, not a second line");
        assertEquals(Level.WARN, lines.get(0).getLevel());
    }
}

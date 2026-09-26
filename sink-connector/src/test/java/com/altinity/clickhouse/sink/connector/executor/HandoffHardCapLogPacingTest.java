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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Log pacing of the hard cap on the reader's lead over the writers (spec
 * 01.05 §3.4 item 6).
 *
 * <p><b>The defect.</b> The cap holds the reader until the writers acknowledge
 * the head of the FIFO, and one acknowledgement is exactly one unit: the reader
 * is released, hands off the next unit, meets the cap again. At the cap it
 * therefore oscillates one batch at a time, and "one WARN when a wait begins,
 * one INFO when it ends" became one WARN/INFO pair per batch. Measured on the
 * first deployment that met the cap: ~300 {@code Handoff hard cap} lines a
 * minute, 990 WARNs into the error log in seven minutes -- while the cap was
 * doing exactly its job and nothing was wrong.</p>
 *
 * <p><b>The rule.</b> A PACING PERIOD opens with the WARN (and that first wait
 * keeps its release INFO). Every later wait that begins within the re-arm
 * window of the previous release is counted, not logged. One summary INFO per
 * interval while the period lasts. One "pacing ended" INFO when a wait begins
 * after a longer quiet gap, or on {@code reset()}. The waits themselves, the
 * dead-worker check and the wait limit are untouched -- only the lines.</p>
 *
 * <p>The bookkeeping reads a substitutable clock so the windows can be crossed
 * without sleeping; the waits run on the real clock and are released by a real
 * acknowledgement on another thread.</p>
 */
public class HandoffHardCapLogPacingTest {

    private static final long CAP = 4;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    /** Collects everything {@link DebeziumOffsetManagement} logs during one test. */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

        CapturingAppender() {
            super("capture-handoff-hard-cap-pacing", null, null, true, Property.EMPTY_ARRAY);
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
    private final AtomicLong clock = new AtomicLong(1_000L * NANOS_PER_SECOND);
    private final RecordingCommitter committer = new RecordingCommitter();
    /** Units handed off and not yet acknowledged, oldest first: the FIFO as this test sees it. */
    private final Deque<List<ClickHouseStruct>> outstanding = new ArrayDeque<>();
    private int nextUnitId;

    @BeforeEach
    public void attach() {
        DebeziumOffsetManagement.reset();
        DebeziumOffsetManagement.capacityClock = clock::get;
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
        DebeziumOffsetManagement.capacityClock = System::nanoTime;
        DebeziumOffsetManagement.reset();
    }

    /** Hands off units of two rows until the outstanding rows meet the cap. */
    private void fillToTheCap() {
        while (DebeziumOffsetManagement.outstandingRecordCount() < CAP) {
            int id = nextUnitId++;
            List<ClickHouseStruct> unit = OffsetTestSupport.unit(committer, 1_000L + id, "u" + id + "a", "u" + id + "b");
            DebeziumOffsetManagement.registerHandoff(unit, Collections.singletonList(unit));
            outstanding.addLast(unit);
        }
    }

    /**
     * One oscillation of the cap, {@code secondsLater} on the pacing clock:
     * fill to the cap, then wait while another thread acknowledges the head
     * after a real 100 ms. Returns how many {@code Handoff hard cap} lines the
     * cycle added.
     */
    private int cycle(long secondsLater) throws InterruptedException {
        clock.addAndGet(secondsLater * NANOS_PER_SECOND);
        fillToTheCap();
        int before = appender.capLines().size();
        List<ClickHouseStruct> head = outstanding.removeFirst();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(100);
                DebeziumOffsetManagement.checkIfBatchCanBeCommitted(head);
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "hard-cap-pacing-test-writer");
        writer.setDaemon(true);
        writer.start();
        DebeziumOffsetManagement.awaitHandoffCapacity(CAP, 10_000, null);
        writer.join(10_000);
        assertFalse(writer.isAlive(), "the acknowledging thread finished");
        if (failure.get() != null) {
            throw new AssertionError("the acknowledging thread failed", failure.get());
        }
        assertTrue(DebeziumOffsetManagement.outstandingRecordCount() < CAP, "the wait ended under the cap");
        return appender.capLines().size() - before;
    }

    private static String text(LogEvent e) {
        return e.getMessage().getFormattedMessage();
    }

    @Test
    @DisplayName("pauses that begin within the re-arm window of the previous release add no line")
    public void pausesWithinTheRearmWindowAreCountedNotLogged() throws InterruptedException {
        assertEquals(2, cycle(0), "the first pause of a period: one WARN, one release INFO");
        assertEquals(0, cycle(1), "a pause one second later is counted, not logged");
        assertEquals(0, cycle(1), "and the next one");
        assertEquals(0, cycle(50), "and one 50 s after the previous release: inside the re-arm window, "
                + "and still inside the first summary interval (52 s into the period)");

        List<LogEvent> lines = appender.capLines();
        assertEquals(2, lines.size(), "four pauses, two lines: " + lines.size());
        assertEquals(Level.WARN, lines.get(0).getLevel(), "the period opens at WARN");
        assertTrue(text(lines.get(0)).contains("4 row(s) in 2 unit(s)"), "the WARN names the counts: " + text(lines.get(0)));
        assertTrue(text(lines.get(0)).contains("counted, not logged"), "and says what follows: " + text(lines.get(0)));
        assertEquals(Level.INFO, lines.get(1).getLevel());
        assertTrue(text(lines.get(1)).contains("released"), "the first wait keeps its release line: " + text(lines.get(1)));
    }

    @Test
    @DisplayName("while the reader stays paced, one summary INFO per interval names the pauses since the previous line")
    public void oneSummaryLinePerIntervalWhilePaced() throws InterruptedException {
        assertEquals(2, cycle(0));
        assertEquals(0, cycle(15));
        assertEquals(0, cycle(15));
        assertEquals(0, cycle(15));
        assertEquals(1, cycle(15), "60 s into the period the fourth silent pause carries the summary");

        List<LogEvent> lines = appender.capLines();
        assertEquals(3, lines.size(), "WARN, release, one summary: " + lines.size());
        LogEvent summary = lines.get(2);
        assertEquals(Level.INFO, summary.getLevel(), "the summary is INFO, not WARN");
        assertTrue(text(summary).contains("Handoff hard cap pacing:"), text(summary));
        assertTrue(text(summary).contains("4 pause(s) totalling"), "the four silent pauses since the previous line: " + text(summary));
        assertTrue(text(summary).contains("5 pause(s),"), "five pauses since the period began: " + text(summary));
        assertTrue(text(summary).contains("nothing has failed"), text(summary));

        assertEquals(0, cycle(1), "the interval restarts at the summary: the next pause is silent again");
        assertEquals(0, cycle(30), "still inside the next interval");
        assertEquals(1, cycle(30), "and the next interval closes with one more summary");
        List<LogEvent> after = appender.capLines();
        assertEquals(4, after.size(), "one summary per interval: " + after.size());
        assertTrue(text(after.get(3)).contains("3 pause(s) totalling"), "only the pauses since the previous summary: " + text(after.get(3)));
    }

    @Test
    @DisplayName("a pause after a quiet gap longer than the re-arm window closes the period with one line and opens a new one")
    public void pacingEndedIsReportedWhenTheReaderStopsBeingPaced() throws InterruptedException {
        assertEquals(2, cycle(0));
        assertEquals(0, cycle(1));
        assertEquals(3, cycle(120), "pacing ended, then a fresh WARN and its release");

        List<LogEvent> lines = appender.capLines();
        assertEquals(5, lines.size(), lines.size() + " lines");
        LogEvent ended = lines.get(2);
        assertEquals(Level.INFO, ended.getLevel());
        assertTrue(text(ended).contains("Handoff hard cap pacing ended"), text(ended));
        assertTrue(text(ended).contains("2 pause(s) totalling"), "both pauses of the closed period: " + text(ended));
        assertTrue(text(ended).contains("stayed under the cap for more than 60 s"), text(ended));
        assertEquals(Level.WARN, lines.get(3).getLevel(), "the new period opens at WARN");
        assertTrue(text(lines.get(4)).contains("released"), "and its first wait keeps the release line");
    }

    @Test
    @DisplayName("reset() closes the period with the same line; the next pause opens a new period")
    public void resetClosesThePacingPeriod() throws InterruptedException {
        assertEquals(2, cycle(0));
        assertEquals(0, cycle(1));

        int before = appender.capLines().size();
        DebeziumOffsetManagement.reset();
        outstanding.clear();
        List<LogEvent> lines = appender.capLines();
        assertEquals(before + 1, lines.size(), "reset adds exactly the pacing-ended line");
        LogEvent ended = lines.get(lines.size() - 1);
        assertEquals(Level.INFO, ended.getLevel());
        assertTrue(text(ended).contains("pacing ended (reset)"), text(ended));
        assertTrue(text(ended).contains("2 pause(s) totalling"), text(ended));

        assertEquals(2, cycle(1), "after the reset the next pause is a new period: WARN and release");
        assertEquals(1, DebeziumOffsetManagement.reset(), "the cycle's second unit was still outstanding");
        assertEquals(before + 1 + 2 + 1, appender.capLines().size(), "and that reset closes the new period with one line");
    }
}

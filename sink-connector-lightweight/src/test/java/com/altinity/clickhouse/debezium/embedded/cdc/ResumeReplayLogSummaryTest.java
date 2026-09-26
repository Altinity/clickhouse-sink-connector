package com.altinity.clickhouse.debezium.embedded.cdc;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Debezium's per-event resume-replay lines are replaced by one summary of
 * operation types and counts (spec 01.07 §3.5).
 *
 * <p><b>The noise.</b> On every start Debezium re-reads the resumed
 * transaction from its BEGIN and logs each already-delivered event at INFO
 * with its full row image. 5,227 such events at one start filled seven rotated
 * log files in fourteen seconds with row data. The rule: never the row data;
 * the operation type and the count of that operation.</p>
 */
public class ResumeReplayLogSummaryTest {

    private static final String SKIP_INSERT = "Skipping previously processed row event: Event{header=EventHeaderV4{"
            + "timestamp=1790369503000, eventType=EXT_WRITE_ROWS, serverId=220, headerLength=19, dataLength=8124, "
            + "nextPosition=%d, flags=0}, data=WriteRowsEventData{tableId=627313, includedColumns={0, 1}, rows=[\n"
            + "    [42, secret-row-payload-%d]\n]}}";
    private static final String SKIP_UPDATE = "Skipping previously processed row event: Event{header=EventHeaderV4{"
            + "timestamp=1790369503000, eventType=EXT_UPDATE_ROWS, serverId=220, headerLength=19, dataLength=812, "
            + "nextPosition=%d, flags=0}, data=UpdateRowsEventData{tableId=1, includedColumns={0}, rows=[[1] -> [2]]}}";
    private static final String SKIP_OTHER = "Skipping previously processed XID event: Event{header=EventHeaderV4{"
            + "timestamp=1, eventType=XID, serverId=220, headerLength=19, dataLength=8, nextPosition=%d, flags=0}}";

    /** Collects what {@link ResumeReplayLogSummary} itself logs. */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

        CapturingAppender(String name) {
            super(name, null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }

        List<String> messages() {
            List<String> out = new ArrayList<>();
            synchronized (events) {
                for (LogEvent e : events) {
                    out.add(e.getMessage().getFormattedMessage());
                }
            }
            return out;
        }
    }

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private CapturingAppender summaryAppender;
    private Level savedLevel;

    @BeforeEach
    public void attach() {
        ResumeReplayLogSummary.clock = clock::get;
        Logger logger = (Logger) LogManager.getLogger(ResumeReplayLogSummary.class);
        savedLevel = logger.getLevel();
        Configurator.setLevel(ResumeReplayLogSummary.class.getName(), Level.INFO);
        summaryAppender = new CapturingAppender("capture-resume-replay-summary");
        summaryAppender.start();
        logger.addAppender(summaryAppender);
    }

    @AfterEach
    public void detach() {
        Logger logger = (Logger) LogManager.getLogger(ResumeReplayLogSummary.class);
        logger.removeAppender(summaryAppender);
        summaryAppender.stop();
        Configurator.setLevel(ResumeReplayLogSummary.class.getName(), savedLevel);
        ResumeReplayLogSummary.clock = System::currentTimeMillis;
    }

    private static LogEvent debeziumEvent(String message) {
        return Log4jLogEvent.newBuilder()
                .setLoggerName(ResumeReplayLogSummary.DEBEZIUM_LOGGER)
                .setLevel(Level.INFO)
                .setMessage(new SimpleMessage(message))
                .build();
    }

    private static LogEvent otherLoggerEvent(String message) {
        return Log4jLogEvent.newBuilder()
                .setLoggerName("io.debezium.connector.common.BaseSourceTask")
                .setLevel(Level.INFO)
                .setMessage(new SimpleMessage(message))
                .build();
    }

    @Test
    @DisplayName("skip lines are denied and counted by operation; the next non-skip line from the same logger emits one summary")
    public void skipLinesAreCountedAndSummarisedOnce() {
        ResumeReplayLogSummary filter = new ResumeReplayLogSummary();
        for (int i = 0; i < 3; i++) {
            assertEquals(Filter.Result.DENY, filter.filter(debeziumEvent(String.format(SKIP_INSERT, 1000 + i, i))));
        }
        assertEquals(Filter.Result.DENY, filter.filter(debeziumEvent(String.format(SKIP_UPDATE, 2000))));
        assertEquals(Filter.Result.DENY, filter.filter(debeziumEvent(String.format(SKIP_OTHER, 3000))));
        assertEquals(5, filter.skipped());
        Map<String, Long> counts = filter.countsByOperation();
        assertEquals(3L, counts.get("INSERT"));
        assertEquals(1L, counts.get("UPDATE"));
        assertEquals(1L, counts.get("XID"));
        assertTrue(summaryAppender.messages().isEmpty(), "nothing is written while the replay runs");

        assertEquals(Filter.Result.NEUTRAL, filter.filter(debeziumEvent("Connected to binlog at binary.000042/1234")),
                "a normal line from the streaming source passes");
        List<String> lines = summaryAppender.messages();
        assertEquals(1, lines.size(), "exactly one summary: " + lines);
        String summary = lines.get(0);
        assertTrue(summary.contains("skipped 5 previously processed binlog event(s)"), summary);
        assertTrue(summary.contains("INSERT=3"), summary);
        assertTrue(summary.contains("UPDATE=1"), summary);
        assertTrue(summary.contains("XID=1"), summary);
        assertTrue(summary.contains("positions 1000..3000"), summary);
        assertFalse(summary.contains("secret-row-payload"), "no row image in the summary");
        assertEquals(0, filter.skipped(), "counts reset after the summary");

        assertEquals(Filter.Result.NEUTRAL, filter.filter(debeziumEvent("Another ordinary line")));
        assertEquals(1, summaryAppender.messages().size(), "no summary when nothing was skipped");
    }

    @Test
    @DisplayName("lines from other loggers are neutral and never counted")
    public void otherLoggersAreUntouched() {
        ResumeReplayLogSummary filter = new ResumeReplayLogSummary();
        assertEquals(Filter.Result.NEUTRAL, filter.filter(otherLoggerEvent(String.format(SKIP_INSERT, 1, 1))));
        assertEquals(0, filter.skipped());
        assertTrue(summaryAppender.messages().isEmpty());
    }

    @Test
    @DisplayName("a long replay reports progress once per interval, still without row data")
    public void longReplayReportsProgress() {
        ResumeReplayLogSummary filter = new ResumeReplayLogSummary();
        filter.filter(debeziumEvent(String.format(SKIP_INSERT, 1, 1)));
        clock.addAndGet(ResumeReplayLogSummary.PROGRESS_INTERVAL_MS - 1);
        filter.filter(debeziumEvent(String.format(SKIP_INSERT, 2, 2)));
        assertTrue(summaryAppender.messages().isEmpty(), "under the interval: silent");
        clock.addAndGet(1);
        filter.filter(debeziumEvent(String.format(SKIP_INSERT, 3, 3)));
        List<String> lines = summaryAppender.messages();
        assertEquals(1, lines.size(), "one progress line at the interval");
        assertTrue(lines.get(0).contains("in progress: 3 previously processed"), lines.get(0));
        assertTrue(lines.get(0).contains("INSERT=3"), lines.get(0));
        assertFalse(lines.get(0).contains("secret-row-payload"));
        clock.addAndGet(10);
        filter.filter(debeziumEvent(String.format(SKIP_INSERT, 4, 4)));
        assertEquals(1, summaryAppender.messages().size(), "not one per event");
        filter.flush("engine stop");
        assertEquals(2, summaryAppender.messages().size(), "the stop flushes the final summary");
        assertTrue(summaryAppender.messages().get(1).contains("skipped 4 previously processed"));
    }

    @Test
    @DisplayName("install() is idempotent and routes Debezium's real logger through the filter")
    public void installIsIdempotentAndEffective() {
        ResumeReplayLogSummary first = ResumeReplayLogSummary.install();
        ResumeReplayLogSummary second = ResumeReplayLogSummary.install();
        assertSame(first, second, "one filter per process");
        assertSame(first, ResumeReplayLogSummary.installed());

        CapturingAppender rootCapture = new CapturingAppender("capture-root-resume-replay");
        rootCapture.start();
        Logger root = (Logger) LogManager.getRootLogger();
        root.addAppender(rootCapture);
        try {
            long before = first.skipped();
            LogManager.getLogger(ResumeReplayLogSummary.DEBEZIUM_LOGGER).info(String.format(SKIP_INSERT, 77, 77));
            assertEquals(before + 1, first.skipped(), "the real logger's skip line reached the filter");
            for (String m : rootCapture.messages()) {
                assertFalse(m.contains("secret-row-payload-77"), "the row image was not written: " + m);
            }
        } finally {
            root.removeAppender(rootCapture);
            rootCapture.stop();
            first.flush("test");
        }
    }

    @Test
    @DisplayName("binlog event types map to the row operation they carry")
    public void operationMapping() {
        assertEquals("INSERT", ResumeReplayLogSummary.operationOf("x eventType=WRITE_ROWS y"));
        assertEquals("INSERT", ResumeReplayLogSummary.operationOf("x eventType=EXT_WRITE_ROWS y"));
        assertEquals("UPDATE", ResumeReplayLogSummary.operationOf("x eventType=EXT_UPDATE_ROWS y"));
        assertEquals("DELETE", ResumeReplayLogSummary.operationOf("x eventType=DELETE_ROWS y"));
        assertEquals("TABLE_MAP", ResumeReplayLogSummary.operationOf("x eventType=TABLE_MAP y"));
        assertEquals("UNKNOWN", ResumeReplayLogSummary.operationOf("no type here"));
    }
}

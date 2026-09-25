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

import static com.altinity.clickhouse.sink.connector.executor.DebeziumOffsetManagement.BACKLOG_ADVISORY_THRESHOLD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The handoff backlog advisory is edge-triggered (spec 09.01 §3.1 step 4).
 *
 * <p><b>The defect.</b> {@code registerHandoff} logged
 * {@code "Batches awaiting acknowledgement is greater than 1000"} at ERROR on
 * EVERY handoff while the outstanding set was above the threshold. A reader
 * that runs ahead of the writers -- the normal shape of a replay -- therefore
 * produced one ERROR line per batch for as long as the backlog lasted: 4,003
 * lines in seven minutes on one deployment, in an error log that otherwise
 * held six genuine warnings for the hour. Nothing had failed; the FIFO was
 * acknowledging every unit in binlog order the whole time.</p>
 *
 * <p><b>The rule.</b> One WARN when the count first exceeds the threshold,
 * naming the count; one INFO when an acknowledgement brings it back to or
 * under the threshold; then re-armed. Never one line per handoff, never at
 * ERROR. {@code reset()} clears the advisory with the set it abandons.</p>
 */
public class HandoffBacklogAdvisoryTest {

    /** Collects everything {@link DebeziumOffsetManagement} logs during one test. */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

        CapturingAppender() {
            super("capture-handoff-backlog", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }

        List<LogEvent> backlogLines() {
            List<LogEvent> lines = new ArrayList<>();
            for (LogEvent e : events) {
                if (e.getMessage().getFormattedMessage().contains("Handoff backlog")) {
                    lines.add(e);
                }
            }
            return lines;
        }

        long countAt(Level level) {
            return events.stream().filter(e -> e.getLevel() == level).count();
        }
    }

    private CapturingAppender appender;
    private Level savedLevel;

    @BeforeEach
    public void attach() {
        // Nothing outstanding from another test, and the advisory re-armed.
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

    /** Hands off {@code count} single-record units, in binlog order, and returns them. */
    private static List<List<ClickHouseStruct>> handOff(RecordingCommitter committer, int from, int count) {
        List<List<ClickHouseStruct>> units = new ArrayList<>();
        for (int i = from; i < from + count; i++) {
            List<ClickHouseStruct> unit = OffsetTestSupport.unit(committer, 1_000L + i, "r" + i);
            DebeziumOffsetManagement.registerHandoff(unit, Collections.singletonList(unit));
            units.add(unit);
        }
        return units;
    }

    /** Writes the given units in handoff order, acknowledging them through the FIFO. */
    private static void write(List<List<ClickHouseStruct>> units) throws InterruptedException {
        for (List<ClickHouseStruct> unit : units) {
            DebeziumOffsetManagement.checkIfBatchCanBeCommitted(unit);
        }
    }

    @Test
    @DisplayName("crossing the threshold logs exactly one WARN naming the count, never an ERROR per handoff")
    public void raisedOnceAtWarnWhenCrossingTheThreshold() {
        RecordingCommitter committer = new RecordingCommitter();
        int above = 25;
        handOff(committer, 0, BACKLOG_ADVISORY_THRESHOLD + above);

        List<LogEvent> lines = appender.backlogLines();
        assertEquals(1, lines.size(),
                "one advisory per crossing; pre-fix every handoff above the threshold logged a line "
                        + "(" + above + " expected here), one per batch for the life of the backlog");
        assertEquals(Level.WARN, lines.get(0).getLevel(),
                "a backlog is the reader ahead of the writers -- capacity, not failure");
        String message = lines.get(0).getMessage().getFormattedMessage();
        assertTrue(message.contains(String.valueOf(BACKLOG_ADVISORY_THRESHOLD + 1)),
                "the advisory names the count at the crossing: " + message);
        assertEquals(0, appender.countAt(Level.ERROR),
                "nothing failed, so nothing is logged at ERROR");
        assertTrue(DebeziumOffsetManagement.isBacklogAdvisoryRaised());
    }

    @Test
    @DisplayName("draining back under the threshold logs exactly one INFO and re-arms the advisory")
    public void clearedOnceWhenTheBacklogDrainsUnderTheThreshold() throws InterruptedException {
        RecordingCommitter committer = new RecordingCommitter();
        int above = 3;
        List<List<ClickHouseStruct>> units = handOff(committer, 0, BACKLOG_ADVISORY_THRESHOLD + above);
        assertEquals(1, appender.backlogLines().size(), "sanity: raised once");

        // Acknowledging the first `above` units brings the count to the
        // threshold exactly: back under (<=), one INFO.
        write(units.subList(0, above));
        assertEquals(BACKLOG_ADVISORY_THRESHOLD, DebeziumOffsetManagement.outstandingCount());
        List<LogEvent> lines = appender.backlogLines();
        assertEquals(2, lines.size(), "raised once, cleared once");
        assertEquals(Level.INFO, lines.get(1).getLevel());
        assertTrue(lines.get(1).getMessage().getFormattedMessage()
                        .contains(String.valueOf(BACKLOG_ADVISORY_THRESHOLD)),
                "the clearing line names the count");
        assertFalse(DebeziumOffsetManagement.isBacklogAdvisoryRaised());

        // Further drain below the threshold, and handoffs that stay under it:
        // silent.
        write(units.subList(above, above + 40));
        handOff(committer, BACKLOG_ADVISORY_THRESHOLD + above, 10);
        assertEquals(2, appender.backlogLines().size(),
                "under the threshold there is nothing to advise; no line per handoff or per ack");

        // Re-armed: the next crossing raises exactly one more WARN.
        handOff(committer, BACKLOG_ADVISORY_THRESHOLD + above + 10, 40);
        lines = appender.backlogLines();
        assertEquals(3, lines.size(), "a second crossing is a second advisory");
        assertEquals(Level.WARN, lines.get(2).getLevel());
        assertEquals(0, appender.countAt(Level.ERROR));
    }

    @Test
    @DisplayName("reset() clears the advisory with the set it abandons, silently")
    public void resetClearsTheAdvisory() {
        RecordingCommitter committer = new RecordingCommitter();
        handOff(committer, 0, BACKLOG_ADVISORY_THRESHOLD + 1);
        assertTrue(DebeziumOffsetManagement.isBacklogAdvisoryRaised());

        DebeziumOffsetManagement.reset();
        assertFalse(DebeziumOffsetManagement.isBacklogAdvisoryRaised());
        assertEquals(1, appender.backlogLines().size(),
                "the abandonment WARN is the line for a reset; no 'back under' INFO for a set that is gone");

        // Re-armed: the next engine's crossing is advised again, once.
        handOff(committer, BACKLOG_ADVISORY_THRESHOLD + 1, BACKLOG_ADVISORY_THRESHOLD + 1);
        assertEquals(2, appender.backlogLines().size());
        assertEquals(0, appender.countAt(Level.ERROR));
    }
}

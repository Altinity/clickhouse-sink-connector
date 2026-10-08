package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.model.SourcePosition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A step of the SOURCE host clock does not break version order (spec 01.04
 * section 7, FM-01.04-2).
 *
 * <p>{@code source.ts_ms} comes from the MySQL host clock. An NTP step, a
 * manual {@code date -s}, or a failover to a host whose clock is off moves it
 * forward or backward while the binlog keeps growing in commit order. The
 * version of a first delivery is floored at the highest timestamp versioned so
 * far ({@code nextVersionAssignment}), so a backward step is clamped and a
 * forward step followed by its correction keeps every later row above the rows
 * versioned during the excursion. The only cost of a forward excursion is that
 * rows written after the correction are versioned at the excursion's timestamp
 * until the source clock passes it again; ordering is unaffected.</p>
 */
public class SourceClockStepVersionTest {

    private static final long T = 1_788_000_000_000L;
    private static final long ONE_DAY = 24L * 3600L * 1000L;

    @BeforeEach
    public void resetSequenceState() {
        VersionSequencer.sequenceNumber = VersionSequencer.SEQUENCE_START;
        VersionSequencer.sequenceAnchorTs = 0L;
        VersionSequencer.sequenceHighWaterPosition = null;
        VersionSequencer.sequenceHighWaterEffectiveTs = 0L;
        VersionSequencer.sequenceMaxSourceTs = 0L;
    }

    private static long version(long sourceTs, long pos) {
        return VersionSequencer.nextSequenceNumber(sourceTs,
                SourcePosition.ofBinlog("mysql-bin.000042", pos, 0));
    }

    @Test
    @DisplayName("A backward step of the source clock is clamped: later commits still rank higher")
    public void backwardStepIsClamped() {
        long before = version(T, 100);
        // The source clock is stepped back one hour; the next commit is later in the log.
        long after = version(T - 3_600_000L, 200);
        assertTrue(after > before, "a commit later in the binlog must out-rank the earlier one "
                + "whatever the source clock says: " + after + " <= " + before);
    }

    @Test
    @DisplayName("A forward excursion of the source clock and its correction keep versions strictly increasing")
    public void forwardExcursionAndCorrectionStayMonotonic() {
        long normal = version(T, 100);
        long excursion = version(T + ONE_DAY, 200);       // clock stepped one day ahead
        long duringExcursion = version(T + ONE_DAY + 5, 300);
        long corrected = version(T + 1_000L, 400);        // clock corrected back
        long correctedLater = version(T + 2_000L, 500);

        assertTrue(excursion > normal);
        assertTrue(duringExcursion > excursion);
        assertTrue(corrected > duringExcursion,
                "the first commit after the correction must rank above the excursion's rows");
        assertTrue(correctedLater > corrected);
        assertEquals(T + ONE_DAY + 5, VersionSequencer.sequenceMaxSourceTs,
                "the floor stays at the excursion's highest timestamp until the source clock passes it");
    }
}

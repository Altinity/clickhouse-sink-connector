package com.altinity.clickhouse.debezium.embedded.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.altinity.clickhouse.sink.connector.model.SourcePosition;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Failure mode FM-02.03-1 (spec 02.03 section 6): the counter reset of
 * {@code nextVersionAssignment} when more than one million rows were versioned
 * in the window that ends.
 *
 * <p>The emitted version is {@code effectiveTs * 1_000_000 + counter}; the
 * counter restarts at {@code SEQUENCE_START} when the effective clock is two
 * seconds past the anchor. Every row of a window adds one to the counter, and
 * the multiplier leaves only six decimal digits for it: a window holding more
 * than {@code 10^6} rows carries the counter into the timestamp field by
 * {@code rows / 10^6} milliseconds. The first row after the reset is then
 * versioned below the last rows of the window that ended whenever it follows
 * them by fewer than {@code rows / 10^6} milliseconds of effective time. Two
 * production shapes produce such a window: one statement touching millions of
 * rows (every row event of it carries the same statement timestamp), and a
 * long clamp at the floor (after a restart on a lagging source, a clock seed,
 * or a source clock stepped back), during which the counter never resets.</p>
 */
public class CounterCarryResetInversionTest {

    /** A fixed source millisecond. */
    private static final long TS = 1_757_900_000_000L;
    private static final String LOG = "mysql-bin.000007";

    @BeforeEach
    public void resetSequenceState() {
        VersionSequencer.sequenceNumber = VersionSequencer.SEQUENCE_START;
        VersionSequencer.sequenceAnchorTs = 0L;
        VersionSequencer.sequenceHighWaterPosition = null;
        VersionSequencer.sequenceHighWaterEffectiveTs = 0L;
        VersionSequencer.sequenceMaxSourceTs = 0L;
    }

    private static long version(long ts, long pos, int row) {
        return VersionSequencer.nextSequenceNumber(ts, SourcePosition.ofBinlog(LOG, pos, row));
    }

    /**
     * One statement of {@code rows} rows at statement time {@code ts}, delivered as
     * rows events of 1000 rows at consecutive positions starting at {@code firstPos}.
     *
     * @return the version of its last row.
     */
    private static long bigStatement(long ts, long firstPos, int rows) {
        long last = 0;
        int events = rows / 1000;
        for (int e = 0; e < events; e++) {
            for (int r = 0; r < 1000; r++) {
                last = version(ts, firstPos + e, r);
            }
        }
        return last;
    }

    /** Leaves the start-of-run domain and anchors a fresh window at {@code TS}. */
    private static void anchorAtTs() {
        version(TS - 10_000, 100, 0);
        version(TS, 200, 0);
        assertEquals(TS, VersionSequencer.sequenceAnchorTs, "the window is anchored at TS");
    }

    @Test
    @DisplayName("fewer than 10^6 rows in a window: the reset 1 ms later still ranks above the window's last row")
    public void underAMillionRowsPerWindowTheResetStillRanksAbove() {
        anchorAtTs();
        long lastOfBigStatement = bigStatement(TS + 1_999, 1_000, 900_000);

        // A small transaction that started 1 ms after the big UPDATE, waited on its
        // row lock and committed after it: the first record two seconds past the
        // anchor, so the counter resets here.
        long next = version(TS + 2_000, 1_000_000, 0);

        assertEquals(VersionSequencer.SEQUENCE_START, VersionSequencer.sequenceNumber,
                "the counter reset on this record");
        assertTrue(next > lastOfBigStatement,
                "900 000 rows carry 0.9 ms into the timestamp field; a 1 ms step still ranks above");
    }

    @Test
    @DisplayName("more than 10^6 rows in one statement: the commit after it must rank above its last row")
    public void resetAfterMoreThanAMillionRowsInOneWindowStillRanksAbove() {
        anchorAtTs();
        long lastOfBigStatement = bigStatement(TS + 1_999, 1_000, 2_000_000);

        long next = version(TS + 2_000, 1_000_000, 0);

        assertTrue(next > lastOfBigStatement,
                "the commit that follows a 2 000 000-row statement in the binlog is the newer state of "
                        + "any key both touched, but it was versioned " + next + " < " + lastOfBigStatement
                        + ": the reset re-bases the counter one millisecond later while the window's "
                        + "counter had carried two milliseconds into the timestamp field");
    }

    @Test
    @DisplayName("after a long clamp at the floor, the first reset must rank above the clamped rows")
    public void resetAfterALongClampStillRanksAbove() {
        // A restart on a lagging source: the floor is seeded well ahead of the rows
        // the new run reads first (spec 02.02 section 3.5), so they are clamped to it.
        long floor = TS + 60_000;
        VersionSequencer.raiseVersionFloor(floor);
        long lastClamped = bigStatement(TS, 1_000, 1_200_000);
        assertEquals(floor, VersionSequencer.sequenceMaxSourceTs, "every row was clamped to the floor");

        // The source clock passes the floor: one row just below the reset boundary,
        // then the row that resets the counter.
        long beforeReset = version(floor + 1_999, 2_000_000, 0);
        long atReset = version(floor + 2_000, 2_000_001, 0);

        assertTrue(beforeReset > lastClamped, "no reset yet: the counter keeps growing");
        assertTrue(atReset > beforeReset,
                "the row at the reset is the newer commit but was versioned " + atReset + " < "
                        + beforeReset + ": 1 200 000 clamped rows carried the counter 1.2 ms into the "
                        + "timestamp field and the reset re-based it at +1 ms");
    }

    @Test
    public void laterStatementOfTheSameCompressedEventRetainsCounterCarryAtReset() {
        anchorAtTs();
        long previous = 0L;
        // A compressed payload shares its coordinate across statements, while
        // each inner rows event can restart the row index at zero.
        for (int row = 0; row < 2_000_000; row++) {
            previous = version(TS + 1_999, 1_000, row % 1_000);
        }
        assertEquals(previous + 1, version(TS + 2_000, 1_000, 0),
                "the later statement of the marked event is also a first-delivery row");
    }

    @Test
    public void resetAtExactlyOneMillionRowsCannotReuseThePreviousVersion() {
        anchorAtTs();
        long previous = bigStatement(TS + 1_999, 1_000, 1_000_000);
        long next = version(TS + 2_000, 1_000_000, 0);
        assertEquals(previous + 1, next, "a reset that would tie must advance by one");
    }

    @Test
    public void replayResetDoesNotEraseCarryNeededByTheNextFirstDelivery() {
        anchorAtTs();
        long previous = bigStatement(TS + 1_999, 1_000, 2_000_000);
        long replay = version(TS + 2_000, 50, 0);
        assertEquals((TS + 2_000) * 1_000_000L + VersionSequencer.SEQUENCE_START, replay,
                "an older position keeps the existing raw-timestamp reset assignment");
        long next = version(TS + 2_000, 1_000_000, 0);
        assertEquals(previous + 1, next, "the next first delivery must retain the discarded carry");
    }

    @Test
    public void positionlessResetDoesNotEraseCarryNeededByTheNextFirstDelivery() {
        anchorAtTs();
        long previous = bigStatement(TS + 1_999, 1_000, 2_000_000);
        long unknown = VersionSequencer.nextSequenceNumber(TS + 2_000, null);
        assertEquals((TS + 2_000) * 1_000_000L + VersionSequencer.SEQUENCE_START, unknown,
                "positionless assignment must not be promoted to the maximum");
        assertEquals(previous + 1, version(TS + 2_000, 1_000_000, 0));
    }

    @Test
    public void reinitializingTheAnchorClearsTheAssignedVersionMaximum() {
        version(TS + 60_000, 100, 0);
        resetSequenceState();
        assertEquals(TS * 1_000_000L + VersionSequencer.SEQUENCE_START_INITIAL + 1,
                version(TS, 100, 0), "a fresh counter lifecycle must not inherit the old maximum");
    }

    @Test
    public void guardedVersionRetainsItsTimestampAndFitsTheExistingDurableHorizon() {
        anchorAtTs();
        long previous = bigStatement(TS + 1_999, 1_000, 2_000_000);
        VersionSequencer.VersionAssignment next = VersionSequencer.nextVersionAssignment(
                TS + 2_000, SourcePosition.ofBinlog(LOG, 1_000_000L, 0));
        assertEquals(previous + 1, next.sequenceNumber);
        assertEquals(TS + 2_000, next.effectiveTs, "only the counter retains carry");
        assertEquals(next.sequenceNumber, next.effectiveTs * 1_000_000L + VersionSequencer.sequenceNumber);

        VersionHighWaterMarkTest.FakeClickHouse fake = new VersionHighWaterMarkTest.FakeClickHouse();
        VersionHighWaterMark mark = new VersionHighWaterMark(fake::connection,
                "offsets_db.replica_source_info", () -> TS + 10_000, 1, 0L);
        mark.cover(next.sequenceNumber);
        long horizon = mark.horizon();
        assertTrue(horizon >= next.sequenceNumber, "the unchanged horizon covers counter carry");
        resetSequenceState();
        VersionSequencer.seedVersionFloor(horizon);
        assertTrue(version(TS, 2_000_000, 0) > next.sequenceNumber,
                "the unchanged sequence-domain seed orders the next run above the guarded value");
    }
}

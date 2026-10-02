package com.altinity.clickhouse.debezium.embedded.cdc;

import org.junit.Assert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The sequence seeds overflow the space the timestamp multiplier leaves them
 * (inherited 2.8.0 arithmetic, preserved for upgrade/downgrade compatibility;
 * spec 02.01 section 3.3 / section 4).
 *
 * <p>The emitted version is {@code sourceTsMs * 1_000_000 + sequence}, so the
 * sequence has six decimal digits of room. Both seeds are ten digits, so the
 * addition carries into the timestamp field and is worth roughly 1000&nbsp;ms.
 * With the floor starting at 0 after a restart that inverted ordering: a
 * genuinely NEWER event seeded at
 * {@link DebeziumChangeEventCapture#SEQUENCE_START_INITIAL} ranked BELOW an
 * older pre-restart event seeded at
 * {@link DebeziumChangeEventCapture#SEQUENCE_START}, and ReplacingMergeTree
 * kept the stale row -- with row counts still matching on both sides.</p>
 *
 * <pre>
 *   older, pre-restart  (T)     -&gt; T*1e6 + 1_000_000_000 = 1787635798000000000
 *   newer, post-restart (T+1ms) -&gt; (T+1)*1e6 +   500_000_000 = 1787635797501000000
 * </pre>
 *
 * <p>The {@code diff &gt; 1} second reset does not cover it: a 1&nbsp;ms advance
 * yields {@code diff == 0}, so the 500m seed is still in force.</p>
 *
 * <p>These assertions read the REAL constants and pin the raw arithmetic -- the
 * UNSEEDED case -- which must stay exactly as it is: a version written by 2.8.0,
 * 2.9.1 or 2.10.x must rank consistently against one written by 2.11.0 in both
 * directions. The inversion itself is closed elsewhere, without touching the
 * arithmetic: the floor is seeded at engine start from the durable high-water
 * mark, so the post-restart event is clamped to {@code T + 1001} and out-ranks
 * the older one (spec 02.02 section 3.5;
 * {@code DebeziumChangeEventCaptureTest
 * .newerEventOneMillisecondAfterSeededRestartRanksAboveOlderPreRestartEvent}).</p>
 */
@DisplayName("Inherited arithmetic: sequence seeds carry into the timestamp field (the seeded floor compensates)")
public class SequenceSeedOverflowTest {

    /** The multiplier used by the version encoding in handleBatch. */
    private static final long MULTIPLIER = 1_000_000L;

    /**
     * The seed must not fit in the room the multiplier leaves it.
     *
     * <p>Bound to the production constants: fixing the encoding (widening the
     * multiplier or shrinking the seeds) makes this fail, which is the signal
     * to convert this class into the real ordering guarantee.</p>
     */
    @Test
    public void testSeedDoesNotFitBeneathTheMultiplier() {
        Assert.assertTrue("SEQUENCE_START (" + DebeziumChangeEventCapture.SEQUENCE_START
                        + ") still overflows the multiplier (" + MULTIPLIER + "). When this "
                        + "fails the encoding has been fixed -- invert the ordering "
                        + "assertion below into the guarantee it could not make before.",
                DebeziumChangeEventCapture.SEQUENCE_START >= MULTIPLIER);
    }

    /**
     * A newer post-resume event currently loses to an older pre-restart one.
     *
     * <p>This is the user-visible consequence: the newer update is dropped.
     * Computed from the production constants so it tracks any change to
     * them.</p>
     */
    @Test
    public void testNewerPostResumeEventCurrentlyRanksBelowOlderPreRestartEvent() {
        final long olderSourceTs = 1787635797000L;
        // One millisecond later, so the second-granularity reset does not fire.
        final long newerSourceTs = olderSourceTs + 1;

        long olderPreRestart =
                olderSourceTs * MULTIPLIER + DebeziumChangeEventCapture.SEQUENCE_START;
        long newerPostRestart =
                newerSourceTs * MULTIPLIER + DebeziumChangeEventCapture.SEQUENCE_START_INITIAL;

        Assert.assertTrue("KNOWN DEFECT: newer(" + newerPostRestart + ") should out-rank "
                        + "older(" + olderPreRestart + ") but does not, so "
                        + "ReplacingMergeTree drops the newer row. When this fails the "
                        + "defect is fixed -- change it to assert newer > older.",
                newerPostRestart < olderPreRestart);
    }

    /**
     * The resume seed must stay below the normal seed.
     *
     * <p>This part is intended and must survive the encoding fix: it is what
     * makes a re-delivery of the SAME event lose to the copy already stored.
     * Fixing the overflow must not accidentally invert this.</p>
     */
    @Test
    public void testResumeSeedStaysBelowTheNormalSeed() {
        Assert.assertTrue("a resumed event must rank below a pre-restart write of the "
                        + "SAME event; this ordering is intended and must survive any "
                        + "fix to the multiplier overflow",
                DebeziumChangeEventCapture.SEQUENCE_START_INITIAL
                        < DebeziumChangeEventCapture.SEQUENCE_START);
    }
}

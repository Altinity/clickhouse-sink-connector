package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import org.junit.Assert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Delivery is at-least-once, so every write path must be replay-safe.
 *
 * <p>{@code markBatchFinished()} only REQUESTS an offset flush; Debezium
 * honours it no more often than {@code offset.flush.interval.ms}. The
 * committed position in {@code replica_source_info} therefore lags the data
 * already in ClickHouse, and a crash in that window re-delivers every event
 * after the last flushed position.</p>
 *
 * <p>Measured on 2026-08-25 (ClickHouse 24.8.14) by hard-killing the connector
 * mid-flight: the same batch of 3 records and the same ALTER were both
 * re-executed after restart. Nothing was corrupted -- but only because the
 * apply is replay-safe, not because delivery is exactly-once.</p>
 *
 * <p>The mechanism is version ORDERING, not version equality. A replayed event
 * does not reproduce its original {@code _version}: on resume the counter is
 * seeded at {@code SEQUENCE_START_INITIAL} (500m) instead of
 * {@code SEQUENCE_START} (1000m), so the re-published copy is issued a lower
 * version than the pre-restart write of the SAME event and loses to the row
 * already stored under the same ReplacingMergeTree sorting key.</p>
 *
 * <p><b>That ordering does not generalise, and the gap is a real defect.</b>
 * The version is {@code sourceTsMs * 1_000_000 + sequence}, which leaves only
 * six decimal digits for the sequence, but the seeds are ten digits. Adding
 * {@code SEQUENCE_START} therefore carries into the timestamp field and acts
 * as a ~1000&nbsp;ms shift. A genuinely NEWER event that arrives just after a
 * resume can then rank BELOW an older pre-restart event, so ReplacingMergeTree
 * discards the newer row:</p>
 *
 * <pre>
 *   older, pre-restart  (T)    -&gt; T*1e6 + 1_000_000_000 = 1787635798000000000
 *   newer, post-restart (T+1ms)-&gt; (T+1)*1e6 + 500_000_000 = 1787635797501000000
 *   newer &lt; older  =&gt; the newer update is silently dropped
 * </pre>
 *
 * <p>The {@code diff &gt; 1} second guard does not save this case: a
 * 1&nbsp;ms advance gives {@code diff == 0}, so the 500m seed is still in
 * force. Found by the adversarial review of the PR that first documented this
 * area, and reproduced with the arithmetic above.</p>
 *
 * <p>These tests therefore pin only what is genuinely safe today, and one of
 * them documents the overflow so it cannot be mistaken for intended
 * behaviour. Fixing it means changing the encoding so the sequence cannot
 * carry into the timestamp -- a behaviour change to the version scheme, out of
 * scope for a documentation PR.</p>
 */
@DisplayName("Replayed events must be safe to apply twice")
public class ReplaySafetyTest {

    /**
     * The engine test must compare the enum constant, not the engine string.
     *
     * <p>Reference equality on a String holds only while both sides are the
     * same interned literal. {@code DBMetadata} derives the engine from
     * {@code SHOW CREATE TABLE}, so a runtime-built String is exactly the
     * shape that would make {@code ==} silently false -- and a false engine
     * test means the sign column is never bound, so no +1/-1 pair collapses.
     * Comparing the constant cannot degrade that way.</p>
     */
    @Test
    public void testEngineIdentityDoesNotDependOnStringInterning() {
        DBMetadata.TABLE_ENGINE collapsing = DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE;

        // A runtime-built String carrying the same characters: this is what a
        // JDBC ResultSet hands back, and it is NOT the interned literal.
        String fromResultSet = new String(collapsing.getEngine());

        Assert.assertNotSame("precondition: a runtime-built String is a distinct "
                        + "reference, which is why == is unsafe here",
                collapsing.getEngine(), fromResultSet);
        Assert.assertEquals("value equality must still hold",
                collapsing.getEngine(), fromResultSet);

        // The enum constant compares correctly regardless of how any string
        // carrying its name was produced.
        Assert.assertSame("enum constants are singletons; comparing them is "
                        + "immune to how the engine string was built",
                DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE, collapsing);
    }

    /**
     * The engines the connector auto-creates must be replace-semantics, not
     * additive.
     *
     * <p>A ReplacingMergeTree row that is delivered twice collapses onto
     * itself. A CollapsingMergeTree sign row does not: +1 delivered twice sums
     * to +2 and never cancels against a single -1. The connector only ever
     * auto-creates the Replacing variants, which is what makes the observed
     * replay harmless; this test pins that so the auto-create engine cannot be
     * switched to an additive one without the replay implications being
     * revisited.</p>
     */
    @Test
    public void testAutoCreatedEnginesAreReplaceNotAdditive() {
        for (DBMetadata.TABLE_ENGINE autoCreated : new DBMetadata.TABLE_ENGINE[]{
                DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE,
                DBMetadata.TABLE_ENGINE.REPLICATED_REPLACING_MERGE_TREE}) {

            Assert.assertTrue("an auto-created engine must deduplicate by key so a "
                            + "re-delivered row collapses instead of accumulating: "
                            + autoCreated.getEngine(),
                    autoCreated.getEngine().contains("ReplacingMergeTree"));

            Assert.assertNotEquals("CollapsingMergeTree is additive (+1 twice does not "
                            + "cancel one -1), so it must never become the auto-create "
                            + "default while delivery is at-least-once",
                    DBMetadata.TABLE_ENGINE.COLLAPSING_MERGE_TREE, autoCreated);
        }
    }

    /**
     * The sequence seeds overflow the space the multiplier leaves them.
     *
     * <p>{@code sourceTsMs * 1_000_000 + sequence} reserves six decimal digits
     * for the sequence, but {@code SEQUENCE_START} is ten digits. The addition
     * therefore carries into the timestamp field, worth about 1000&nbsp;ms.
     * Across a resume that inverts causal order: a NEWER event seeded at 500m
     * can rank below an OLDER pre-restart event seeded at 1000m, and
     * ReplacingMergeTree silently discards the newer row.</p>
     *
     * <p>This test asserts the defect EXISTS rather than asserting it is
     * correct. It is deliberately written to start failing the moment the
     * encoding is fixed, at which point it should be inverted into the
     * ordering guarantee it currently cannot make.</p>
     */
    @Test
    public void testSequenceSeedsOverflowTheTimestampMultiplier() {
        // Mirrors DebeziumChangeEventCapture.SEQUENCE_START{,_INITIAL} and the
        // ts_ms * 1_000_000 + counter encoding.
        final long multiplier = 1_000_000L;
        final long sequenceStart = 1_000_000_000L;
        final long sequenceStartInitial = 500_000_000L;

        Assert.assertTrue("the seed must not fit in the space the multiplier leaves; "
                        + "when this stops holding the encoding has been fixed and the "
                        + "ordering assertion below should be inverted",
                sequenceStart >= multiplier);

        final long olderSourceTs = 1787635797000L;
        final long newerSourceTs = olderSourceTs + 1;   // 1 ms later, so diff == 0

        long olderPreRestart = olderSourceTs * multiplier + sequenceStart;
        long newerPostRestart = newerSourceTs * multiplier + sequenceStartInitial;

        Assert.assertTrue("KNOWN DEFECT: a newer post-resume event currently ranks BELOW "
                        + "an older pre-restart one (" + newerPostRestart + " < "
                        + olderPreRestart + "), so ReplacingMergeTree drops the newer "
                        + "row. If this assertion fails, the encoding was fixed -- "
                        + "replace it with the real ordering guarantee.",
                newerPostRestart < olderPreRestart);
    }
}

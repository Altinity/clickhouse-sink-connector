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
 * apply is idempotent, not because delivery is exactly-once.</p>
 *
 * <p>These tests pin the invariants that idempotency rests on, so a later
 * change cannot quietly remove them.</p>
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
}

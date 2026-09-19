package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the offset-tracking map key collision.
 *
 * <p><b>The defect.</b> {@code inFlightBatches}/{@code completedBatches} were
 * keyed by the batch's {@code (minTs, maxTs)} timestamp range. Two DISTINCT
 * batches routinely share a range — a single multi-row statement split across
 * batches, or two batches whose rows fall in the same millisecond. Keying by the
 * range collided them: {@code put} overwrote the earlier batch's entry and the
 * "same batch" skip in the overlap check treated a distinct sibling as itself, so
 * an unwritten older batch stopped blocking the offset commit. The committed
 * binlog position could then advance past rows not yet in ClickHouse and lose
 * them on a crash.</p>
 *
 * <p><b>The fix.</b> Key both maps by batch object identity ({@code BatchKey}).
 * Each of these tests fails against the range-keyed code and passes against the
 * identity-keyed code.</p>
 */
public class OffsetBatchIdentityTest {

    @BeforeEach
    public void reset() {
        DebeziumOffsetManagement.inFlightBatches.clear();
        DebeziumOffsetManagement.completedBatches.clear();
    }

    private static ClickHouseStruct rec(long tsMs) {
        ClickHouseStruct s = new ClickHouseStruct();
        s.setDebezium_ts_ms(tsMs);
        return s;
    }

    /** Two distinct batches with the SAME (min,max) range. */
    private static List<ClickHouseStruct> batch(long minTs, long maxTs) {
        List<ClickHouseStruct> b = new ArrayList<>();
        b.add(rec(minTs));
        b.add(rec(maxTs));
        return b;
    }

    @Test
    @DisplayName("Two distinct batches sharing a timestamp range are tracked as two entries, not one")
    public void equalTimestampBatchesDoNotCollide() {
        List<ClickHouseStruct> a = batch(100L, 200L);
        List<ClickHouseStruct> b = batch(100L, 200L);

        DebeziumOffsetManagement.addToBatchTimestamps(a);
        DebeziumOffsetManagement.addToBatchTimestamps(b);

        // Range-keyed code: the second put overwrites the first -> size 1, and
        // the first batch is silently untracked. Identity-keyed code: size 2.
        assertEquals(2, DebeziumOffsetManagement.inFlightBatches.size(),
                "two distinct batches with equal timestamps must be tracked independently");
    }

    @Test
    @DisplayName("A distinct in-flight sibling with the same range still blocks the commit")
    public void siblingWithSameRangeStillBlocks() {
        List<ClickHouseStruct> a = batch(100L, 200L);
        List<ClickHouseStruct> b = batch(100L, 200L);

        DebeziumOffsetManagement.addToBatchTimestamps(a);
        DebeziumOffsetManagement.addToBatchTimestamps(b);

        // `a` overlaps its distinct sibling `b`, so `a` must NOT be committable
        // yet. Range-keyed code skipped `b` as if it were `a` (equal ranges) and
        // wrongly reported no overlap.
        assertTrue(DebeziumOffsetManagement.checkIfThereAreInflightRequests(a),
                "a distinct overlapping in-flight batch must block the commit even when its "
                        + "timestamp range is identical");
    }

    @Test
    @DisplayName("A batch does not treat itself as an overlapping in-flight request")
    public void aBatchDoesNotBlockItself() {
        List<ClickHouseStruct> a = batch(100L, 200L);
        DebeziumOffsetManagement.addToBatchTimestamps(a);

        assertFalse(DebeziumOffsetManagement.checkIfThereAreInflightRequests(a),
                "with only itself in flight, a batch must be committable (no self-overlap)");
    }

    @Test
    @DisplayName("Removing one batch by identity leaves its equal-timestamp sibling tracked")
    public void identityRemovalLeavesSibling() {
        List<ClickHouseStruct> a = batch(100L, 200L);
        List<ClickHouseStruct> b = batch(100L, 200L);
        DebeziumOffsetManagement.addToBatchTimestamps(a);
        DebeziumOffsetManagement.addToBatchTimestamps(b);

        DebeziumOffsetManagement.inFlightBatches.remove(
                new DebeziumOffsetManagement.BatchKey(a));

        assertEquals(1, DebeziumOffsetManagement.inFlightBatches.size(),
                "removing one batch must not remove its equal-timestamp sibling");
        assertTrue(DebeziumOffsetManagement.inFlightBatches.containsKey(
                        new DebeziumOffsetManagement.BatchKey(b)),
                "the sibling must still be tracked after the other is removed");
    }
}

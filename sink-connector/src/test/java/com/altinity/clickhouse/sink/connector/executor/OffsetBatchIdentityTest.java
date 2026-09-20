package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.executor.OffsetTestSupport.RecordingCommitter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static com.altinity.clickhouse.sink.connector.executor.OffsetTestSupport.unit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the offset-tracking map key collision.
 *
 * <p><b>The defect.</b> The tracking maps were keyed by the batch's
 * {@code (minTs, maxTs)} timestamp range. Two DISTINCT batches routinely share
 * a range — a single multi-row statement split across batches, or two batches
 * whose rows fall in the same millisecond. Keying by the range collided them:
 * {@code put} overwrote the earlier batch's entry and the "same batch" skip
 * treated a distinct sibling as itself, so an unwritten older batch stopped
 * blocking the offset commit. The committed binlog position could then advance
 * past rows not yet in ClickHouse and lose them on a crash.</p>
 *
 * <p><b>The rule.</b> Every handed-off batch is tracked by object identity
 * ({@code BatchKey}) under its own handoff sequence, and commit order is the
 * sequence order. Equal timestamps play no part. Each of these tests fails
 * against range-keyed code.</p>
 */
public class OffsetBatchIdentityTest {

    @BeforeEach
    public void reset() {
        OffsetTestSupport.resetFifo();
    }

    private static long handoff(List<ClickHouseStruct> batch) {
        return DebeziumOffsetManagement.registerHandoff(batch, Collections.singletonList(batch));
    }

    @Test
    @DisplayName("Two distinct batches sharing a timestamp range are tracked as two entries, not one")
    public void equalTimestampBatchesDoNotCollide() {
        RecordingCommitter committer = new RecordingCommitter();
        List<ClickHouseStruct> a = unit(committer, 100L, "a1", "a2");
        List<ClickHouseStruct> b = unit(committer, 100L, "b1", "b2");

        long seqA = handoff(a);
        long seqB = handoff(b);

        // Range-keyed code: the second put overwrites the first -> one entry,
        // and the first batch is silently untracked. Identity-keyed code: two.
        assertNotEquals(seqA, seqB, "two distinct batches must receive two sequences");
        assertEquals(2, DebeziumOffsetManagement.outstandingSequences.size(),
                "two distinct batches with equal timestamps must be tracked independently");
        assertEquals(2, DebeziumOffsetManagement.groupToUnit.size());
    }

    @Test
    @DisplayName("A distinct sibling with the same range still blocks the commit")
    public void siblingWithSameRangeStillBlocks() throws Exception {
        RecordingCommitter committer = new RecordingCommitter();
        List<ClickHouseStruct> a = unit(committer, 100L, "a1", "a2");
        List<ClickHouseStruct> b = unit(committer, 100L, "b1", "b2");
        handoff(a);
        handoff(b);

        // `b` is younger than its distinct sibling `a`, so `b` must NOT be
        // committable yet. Range-keyed code skipped `a` as if it were `b`
        // (equal ranges) and wrongly acknowledged it.
        assertFalse(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(b),
                "a distinct older in-flight batch must block the commit even when its "
                        + "timestamp range is identical");
        assertTrue(committer.processed.isEmpty());
    }

    @Test
    @DisplayName("A batch does not block itself")
    public void aBatchDoesNotBlockItself() throws Exception {
        RecordingCommitter committer = new RecordingCommitter();
        List<ClickHouseStruct> a = unit(committer, 100L, "a1", "a2");
        handoff(a);

        assertTrue(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(a),
                "with only itself outstanding, a batch must be committable (no self-blocking)");
        assertEquals(2, committer.processed.size());
        assertEquals(1, committer.batchesFinished);
    }

    @Test
    @DisplayName("Acknowledging one batch by identity leaves its equal-timestamp sibling tracked")
    public void identityRemovalLeavesSibling() throws Exception {
        RecordingCommitter committer = new RecordingCommitter();
        List<ClickHouseStruct> a = unit(committer, 100L, "a1", "a2");
        List<ClickHouseStruct> b = unit(committer, 100L, "b1", "b2");
        handoff(a);
        long seqB = handoff(b);

        assertTrue(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(a));

        assertEquals(1, DebeziumOffsetManagement.outstandingSequences.size(),
                "acknowledging one batch must not release its equal-timestamp sibling");
        assertTrue(DebeziumOffsetManagement.outstandingSequences.contains(seqB),
                "the sibling must still be outstanding after the other is acknowledged");
        assertTrue(DebeziumOffsetManagement.groupToUnit.containsKey(
                        new DebeziumOffsetManagement.BatchKey(b)),
                "the sibling must still be tracked by identity");
        assertTrue(DebeziumOffsetManagement.hasUnwrittenBatches());
    }
}

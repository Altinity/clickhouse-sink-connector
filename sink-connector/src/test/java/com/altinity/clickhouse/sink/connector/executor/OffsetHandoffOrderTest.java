package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.executor.OffsetTestSupport.RecordingCommitter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.altinity.clickhouse.sink.connector.executor.OffsetTestSupport.processedNames;
import static com.altinity.clickhouse.sink.connector.executor.OffsetTestSupport.record;
import static com.altinity.clickhouse.sink.connector.executor.OffsetTestSupport.unit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offsets are acknowledged in HANDOFF-SEQUENCE order (binlog order), never by
 * wall-clock timestamp overlap (spec 09.01).
 *
 * <p><b>The defect.</b> Commit eligibility was decided by envelope-timestamp
 * overlap among the batches a worker had already PICKED UP. Under per-table
 * hash routing one Debezium batch becomes one group per table on different
 * workers' queues, and only the last row of the whole batch carried the
 * terminal marker. An idle worker finished its small group, saw no in-flight
 * overlap (the sibling group was still QUEUED on a busy worker, so it was in
 * neither map), acknowledged the terminal row, and the offset was committed at
 * the end of the batch. A crash before the busy worker dequeued its group lost
 * those rows. Equal timestamps (strict {@code >}) never blocked either.</p>
 *
 * <p>Every test here fails against the timestamp-overlap code.</p>
 */
public class OffsetHandoffOrderTest {

    @BeforeEach
    public void reset() {
        OffsetTestSupport.resetFifo();
    }

    private static long handoff(List<ClickHouseStruct> batch) {
        return DebeziumOffsetManagement.registerHandoff(batch, Collections.singletonList(batch));
    }

    @Test
    @DisplayName("A younger batch written while an older one is still QUEUED is parked, not acknowledged")
    public void queuedOlderBatchBlocksYoungerCommit() throws Exception {
        RecordingCommitter committer = new RecordingCommitter();
        // Same millisecond on purpose: the deleted rule saw no overlap here.
        List<ClickHouseStruct> a = unit(committer, 100L, "a1", "a2");
        List<ClickHouseStruct> b = unit(committer, 100L, "b1");

        long seqA = handoff(a);
        long seqB = handoff(b);
        assertTrue(seqA < seqB, "handoff order must be the acknowledgement order");

        // Only B's worker is fast: A is still sitting in another worker's queue
        // (it was never picked up, so the old rule could not see it at all).
        boolean bAcked = DebeziumOffsetManagement.checkIfBatchCanBeCommitted(b);

        assertFalse(bAcked, "B must not be acknowledged while the older A is unwritten");
        assertTrue(committer.processed.isEmpty(),
                "no offset may be staged while an older batch is outstanding; the old rule "
                        + "committed B's terminal row here and lost A on a crash");
        assertEquals(0, committer.batchesFinished);
        assertTrue(DebeziumOffsetManagement.completedUnits.containsKey(seqB), "B must be parked");
        assertTrue(DebeziumOffsetManagement.hasUnwrittenBatches());

        // A's worker finally writes A: A is acknowledged first, then the drain
        // releases B.
        boolean aAcked = DebeziumOffsetManagement.checkIfBatchCanBeCommitted(a);

        assertTrue(aAcked);
        assertEquals(Arrays.asList("a1", "a2", "b1"), processedNames(committer),
                "acknowledgement order must be handoff (binlog) order: A before B");
        assertEquals(2, committer.batchesFinished, "one markBatchFinished per unit");
        assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches());
        assertTrue(DebeziumOffsetManagement.completedUnits.isEmpty());
    }

    @Test
    @DisplayName("Two in-flight batches with equal timestamps are still acknowledged in handoff order")
    public void equalTimestampsInFlightStillOrdered() throws Exception {
        RecordingCommitter committer = new RecordingCommitter();
        List<ClickHouseStruct> a = unit(committer, 100L, "a1");
        List<ClickHouseStruct> b = unit(committer, 100L, "b1");
        handoff(a);
        handoff(b);
        // Both picked up by their workers; B finishes first.

        assertFalse(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(b),
                "equal timestamps did not block under the strict > rule; the sequence must");
        assertTrue(committer.processed.isEmpty());

        assertTrue(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(a));
        assertEquals(Arrays.asList("a1", "b1"), processedNames(committer));
        assertEquals(2, committer.batchesFinished);
    }

    @Test
    @DisplayName("Batches written 3,1,2 are acknowledged 1,2,3 with exactly one markBatchFinished each")
    public void outOfOrderCompletionAcknowledgesInSequence() throws Exception {
        RecordingCommitter committer = new RecordingCommitter();
        // Distinct, INCREASING timestamps: the old rule would happily acknowledge
        // batch 3 first (nothing in flight overlaps it), committing past 1 and 2.
        List<ClickHouseStruct> one = unit(committer, 100L, "1a", "1b");
        List<ClickHouseStruct> two = unit(committer, 200L, "2a");
        List<ClickHouseStruct> three = unit(committer, 300L, "3a", "3b");
        handoff(one);
        handoff(two);
        handoff(three);

        assertFalse(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(three));
        assertTrue(committer.processed.isEmpty(), "3 must wait for 1 and 2");

        assertTrue(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(one));
        assertEquals(Arrays.asList("1a", "1b"), processedNames(committer), "only 1 so far: 2 is unwritten");
        assertEquals(1, committer.batchesFinished);

        assertTrue(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(two));
        assertEquals(Arrays.asList("1a", "1b", "2a", "3a", "3b"), processedNames(committer),
                "2 acknowledged, then the parked 3 drained behind it");
        assertEquals(3, committer.batchesFinished, "exactly one markBatchFinished per Debezium batch");
        assertEquals(Arrays.asList(2, 3, 5), committer.finishedAfter,
                "each markBatchFinished must follow every markProcessed of its own unit");
        assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches());
    }

    @Test
    @DisplayName("Routed groups of one Debezium batch are acknowledged as one unit, in binlog order")
    public void routedGroupsAcknowledgedAsOneUnitInBinlogOrder() throws Exception {
        RecordingCommitter committer = new RecordingCommitter();
        // Binlog order interleaves the two tables: o1, c1, o2. Only the unit's
        // last row (o2) is the terminal.
        ClickHouseStruct o1 = record("orders-1", 100L, committer);
        ClickHouseStruct c1 = record("customers-1", 100L, committer);
        ClickHouseStruct o2 = record("orders-2", 100L, committer);
        o2.setLastRecordInBatch(true);
        List<ClickHouseStruct> unit = Arrays.asList(o1, c1, o2);
        List<ClickHouseStruct> orders = new ArrayList<>(Arrays.asList(o1, o2));
        List<ClickHouseStruct> customers = new ArrayList<>(Collections.singletonList(c1));

        long seq = DebeziumOffsetManagement.registerHandoff(unit, Arrays.asList(orders, customers));

        // The idle worker writes the small customers group first (the exact
        // production scenario). Nothing may be acknowledged: orders is queued.
        assertFalse(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(customers));
        assertTrue(committer.processed.isEmpty(), "a partly written unit must not stage any offset");
        assertTrue(DebeziumOffsetManagement.hasUnwrittenBatches());
        assertFalse(DebeziumOffsetManagement.completedUnits.containsKey(seq), "not complete yet");

        // The busy worker writes orders: the whole unit is acknowledged in
        // BINLOG order (o1, c1, o2), not group order, with ONE markBatchFinished.
        assertTrue(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(orders));
        assertEquals(Arrays.asList("orders-1", "customers-1", "orders-2"), processedNames(committer),
                "offsets must be staged in binlog order so the flushed position is monotone");
        assertEquals(1, committer.batchesFinished, "one Debezium batch, one markBatchFinished");
        assertEquals(Collections.singletonList(3), committer.finishedAfter);
        assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches());
    }

    @Test
    @DisplayName("A committer-bearing batch that bypassed registerHandoff is rejected loudly")
    public void committerBearingBatchWithoutHandoffIsRejected() {
        RecordingCommitter committer = new RecordingCommitter();
        List<ClickHouseStruct> stray = unit(committer, 100L, "s1");

        assertThrows(IllegalStateException.class,
                () -> DebeziumOffsetManagement.checkIfBatchCanBeCommitted(stray),
                "an unordered batch must never be acknowledged silently");
        assertTrue(committer.processed.isEmpty());
    }

    @Test
    @DisplayName("A batch without a committer and without a handoff (Kafka Connect sink) is a no-op success")
    public void committerlessBatchWithoutHandoffIsAcceptedAsNoOp() throws Exception {
        ClickHouseStruct s = new ClickHouseStruct();
        s.setTopic("srv.db.t");
        List<ClickHouseStruct> kafkaBatch = new ArrayList<>(Collections.singletonList(s));

        assertTrue(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(kafkaBatch));
        assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches());
    }
}

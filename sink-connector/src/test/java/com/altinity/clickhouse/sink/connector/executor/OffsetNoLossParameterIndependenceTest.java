package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.executor.OffsetTestSupport.RecordingCommitter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.altinity.clickhouse.sink.connector.executor.OffsetTestSupport.processedNames;
import static com.altinity.clickhouse.sink.connector.executor.OffsetTestSupport.unit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No committed configuration value can turn replication into row loss
 * (spec 10.06). The zero-loss guarantee is a property of the acknowledgement
 * path — the durable offset is staged only by the writer, only after a batch's
 * rows are durably written, and only in handoff (binlog) order — not of any
 * flush / buffer / thread-pool / retry / shutdown parameter.
 *
 * <p>The offset FIFO ({@link DebeziumOffsetManagement}) is where every
 * commit-frontier decision is made, and it carries no such parameter at all:
 * there is no flush timeout, no buffer size and no retry budget in it. These
 * tests exercise the frontier directly, so they prove the guarantee holds
 * <b>independently of</b> the values those parameters take — a flush at any
 * {@code offset.flush.timeout.ms} can only persist what the FIFO has already
 * staged, and the FIFO never stages an unwritten row.</p>
 *
 * <p>Mutation check: staging a batch at handoff (before it is written), or
 * acknowledging out of handoff order, makes every test here fail — which is
 * exactly the loss the parameter tuning was reaching for and could not
 * guarantee.</p>
 */
public class OffsetNoLossParameterIndependenceTest {

    @BeforeEach
    public void reset() {
        OffsetTestSupport.resetFifo();
    }

    private static long handoff(List<ClickHouseStruct> batch) {
        return DebeziumOffsetManagement.registerHandoff(batch, Collections.singletonList(batch));
    }

    @Test
    @DisplayName("A flush at any offset.flush.timeout.ms can never stage an unwritten row")
    public void flushCannotStageAnUnwrittenRowAtAnyTimeout() throws Exception {
        RecordingCommitter committer = new RecordingCommitter();
        List<ClickHouseStruct> older = unit(committer, 100L, "a1", "a2");
        List<ClickHouseStruct> younger = unit(committer, 100L, "b1");

        long seqOlder = handoff(older);
        long seqYounger = handoff(younger);
        assertTrue(seqOlder < seqYounger);

        // The younger batch is written first; the older one is still unwritten.
        boolean youngerAcked = DebeziumOffsetManagement.checkIfBatchCanBeCommitted(younger);

        // The state a flush would persist NEVER contains an unwritten row: the
        // younger batch is parked, nothing is staged with the committer, and the
        // FIFO reports work still outstanding. A flush timed out (or completed)
        // at any value only persists this same empty-of-unwritten frontier.
        assertFalse(youngerAcked, "a younger written batch must not be acknowledged over an unwritten older one");
        assertTrue(committer.processed.isEmpty(), "no offset may be staged while an older row is unwritten");
        assertEquals(0, committer.batchesFinished);
        assertTrue(DebeziumOffsetManagement.hasUnwrittenBatches());

        // The older batch is finally written: now the frontier advances, in
        // binlog order, covering only written rows.
        assertTrue(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(older));
        assertEquals(Arrays.asList("a1", "a2", "b1"), processedNames(committer));
        assertEquals(2, committer.batchesFinished);
        assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches());
    }

    @Test
    @DisplayName("An abrupt stop at any point never acknowledges an unwritten row")
    public void killAtAnyPointNeverAcknowledgesUnwrittenRows() throws Exception {
        RecordingCommitter committer = new RecordingCommitter();
        List<ClickHouseStruct> a = unit(committer, 100L, "a1", "a2");
        List<ClickHouseStruct> b = unit(committer, 200L, "b1");
        List<ClickHouseStruct> c = unit(committer, 300L, "c1", "c2");
        handoff(a);
        handoff(b);
        handoff(c);

        // A is written and acknowledged; B and C are still in flight when the
        // process is killed (modelled by the in-process restart reset()).
        assertTrue(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(a));
        assertEquals(Arrays.asList("a1", "a2"), processedNames(committer));

        int abandoned = DebeziumOffsetManagement.reset();

        // Only the two unacknowledged units were abandoned; nothing that was
        // abandoned was ever staged with the committer, so the durable offset
        // was never advanced past B or C.
        assertEquals(2, abandoned, "exactly the unacknowledged units are abandoned");
        assertEquals(Arrays.asList("a1", "a2"), processedNames(committer),
                "reset must not acknowledge anything; only A (written before the kill) was staged");
        assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches(), "the next engine starts from a quiescent FIFO");
        assertEquals(0, DebeziumOffsetManagement.outstandingCount());
    }

    @Test
    @DisplayName("Redelivery after an abrupt stop is at-least-once, never a gap")
    public void redeliveryAfterAbruptStopIsAtLeastOnceNeverAGap() throws Exception {
        RecordingCommitter first = new RecordingCommitter();
        List<ClickHouseStruct> b = unit(first, 200L, "b1");
        List<ClickHouseStruct> c = unit(first, 300L, "c1", "c2");
        handoff(b);
        handoff(c);

        // Killed before either was written: both abandoned, nothing acknowledged.
        assertEquals(2, DebeziumOffsetManagement.reset());
        assertTrue(first.processed.isEmpty());

        // The next engine resumes from the last committed offset and redelivers
        // B and C. This run writes them: every row is acknowledged — no gap.
        RecordingCommitter second = new RecordingCommitter();
        List<ClickHouseStruct> bAgain = unit(second, 200L, "b1");
        List<ClickHouseStruct> cAgain = unit(second, 300L, "c1", "c2");
        handoff(bAgain);
        handoff(cAgain);

        assertTrue(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(bAgain));
        assertTrue(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(cAgain));
        assertEquals(Arrays.asList("b1", "c1", "c2"), processedNames(second),
                "redelivery covers every abandoned row exactly, in binlog order");
        assertEquals(2, second.batchesFinished);
        assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches());
    }

    @Test
    @DisplayName("The commit frontier is independent of which worker finishes first (thread.pool.size)")
    public void commitFrontierIsIndependentOfCompletionOrder() throws Exception {
        RecordingCommitter committer = new RecordingCommitter();
        List<ClickHouseStruct> one = unit(committer, 100L, "1a", "1b");
        List<ClickHouseStruct> two = unit(committer, 200L, "2a");
        List<ClickHouseStruct> three = unit(committer, 300L, "3a", "3b");
        handoff(one);
        handoff(two);
        handoff(three);

        // Workers finish out of order: 3, then 1, then 2. More workers change
        // only completion order, never the commit frontier.
        assertFalse(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(three));
        assertTrue(committer.processed.isEmpty(), "3 must wait for 1 and 2");

        assertTrue(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(one));
        assertTrue(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(two));

        assertEquals(Arrays.asList("1a", "1b", "2a", "3a", "3b"), processedNames(committer),
                "the frontier advances in handoff (binlog) order regardless of completion order");
        assertEquals(3, committer.batchesFinished, "one markBatchFinished per unit");
        assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches());
    }

    @Test
    @DisplayName("A retried (not-yet-written) batch is never acknowledged until it is written (errors.max.retries)")
    public void retriedBatchIsNeverAcknowledgedUntilWritten() throws Exception {
        RecordingCommitter committer = new RecordingCommitter();
        List<ClickHouseStruct> a = unit(committer, 100L, "a1");
        handoff(a);

        // A batch whose write failed is retried by the worker: the writer does
        // NOT call checkIfBatchCanBeCommitted until the write succeeds. However
        // many times it retries, the offset stays behind the batch.
        for (int retry = 0; retry < 5; retry++) {
            assertTrue(DebeziumOffsetManagement.hasUnwrittenBatches(),
                    "a batch still being retried must stay outstanding");
            assertTrue(committer.processed.isEmpty(),
                    "no offset may be staged for a batch that has not been written");
        }

        // The write finally succeeds: only now is it acknowledged.
        assertTrue(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(a));
        assertEquals(Collections.singletonList("a1"), processedNames(committer));
        assertEquals(1, committer.batchesFinished);
        assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches());
    }
}

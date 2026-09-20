package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.executor.OffsetTestSupport.RecordingCommitter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.RoutedBatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import static com.altinity.clickhouse.sink.connector.executor.OffsetTestSupport.unit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WRITTEN-ONCE (spec 09.01 section 3.2): a batch whose rows are durably written
 * is never executed again, even while its offset is parked behind older
 * batches.
 *
 * <p><b>The defect.</b> {@code processBatch} cleared {@code currentBatch} only
 * when the batch was commit-eligible, and both run loops re-enter
 * {@code processBatch} while {@code currentBatch != null} ("RETRYING the same
 * batch again"). A batch that was WRITTEN successfully but parked because an
 * older batch was still in flight was therefore RE-INSERTED into ClickHouse on
 * every scheduler tick (every 30 ms) until it became committable. Observed as
 * every row present exactly 3 times in a non-{@code FINAL} read and ~0.07%
 * duplicate raw rows on hot tables.</p>
 *
 * <p>The writer is mocked by overriding the per-topic write step; it counts
 * executions per batch and refuses to run a batch more than three times so
 * the old code fails instead of spinning forever.</p>
 */
public class ParkedBatchWrittenOnceTest {

    /** Counts how many times each batch (by identity) reached the writer. */
    private static final class CountingRunnable extends ClickHouseBatchRunnable {
        final Map<List<ClickHouseStruct>, Integer> executions = new IdentityHashMap<>();

        CountingRunnable(LinkedBlockingQueue<RoutedBatch> queue, ClickHouseSinkConnectorConfig config) {
            super(queue, 0, config, new HashMap<>());
        }

        @Override
        boolean processRecordsByTopic(String topicName, List<ClickHouseStruct> records) {
            // processBatch groups the batch per topic into a fresh list; the
            // batch under test has one topic, so count by its first record's
            // owning batch identity.
            List<ClickHouseStruct> batch = owningBatch(records.get(0));
            int n = executions.merge(batch, 1, Integer::sum);
            if (n > 3) {
                throw new IllegalStateException("the same written batch was executed " + n + " times");
            }
            return true;
        }

        private final Map<ClickHouseStruct, List<ClickHouseStruct>> recordToBatch = new IdentityHashMap<>();

        void track(List<ClickHouseStruct> batch) {
            for (ClickHouseStruct r : batch) {
                recordToBatch.put(r, batch);
            }
        }

        private List<ClickHouseStruct> owningBatch(ClickHouseStruct record) {
            return recordToBatch.get(record);
        }
    }

    @BeforeEach
    public void reset() {
        OffsetTestSupport.resetFifo();
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        // Keep the per-batch pacing sleep negligible so the test is fast.
        props.put("buffer.flush.time.ms", "1");
        return new ClickHouseSinkConnectorConfig(props);
    }

    @Test
    @DisplayName("A written batch parked behind an older one is executed exactly once")
    public void parkedBatchIsNotReinserted() throws Exception {
        RecordingCommitter committer = new RecordingCommitter();
        // A is older and still queued on some other worker: never written here.
        List<ClickHouseStruct> a = unit(committer, 100L, "a1");
        // B is this worker's batch. Its commit eligibility is false until A is
        // acknowledged. One topic, so the per-topic write step runs exactly
        // once per execution of the batch and the counter counts executions.
        List<ClickHouseStruct> b = unit(committer, 100L, "b1", "b2");
        for (ClickHouseStruct r : b) {
            r.setTopic("srv.db.orders");
        }
        long seqA = DebeziumOffsetManagement.registerHandoff(a, Collections.singletonList(a));
        long seqB = DebeziumOffsetManagement.registerHandoff(b, Collections.singletonList(b));

        LinkedBlockingQueue<RoutedBatch> queue = new LinkedBlockingQueue<>();
        CountingRunnable worker = new CountingRunnable(queue, config());
        worker.track(b);
        queue.put(new RoutedBatch(b, 0, "b", seqB));

        // One scheduled tick: drains this worker's queue.
        worker.run();

        assertEquals(1, (int) worker.executions.getOrDefault(b, 0),
                "a written batch must be executed exactly once; the old code re-ran it on "
                        + "every tick while it was parked (duplicate rows, write amplification)");
        assertTrue(queue.isEmpty(), "the worker must move on; the batch is not re-queued");
        // Written but not acknowledged: parked in the FIFO behind A.
        assertTrue(DebeziumOffsetManagement.completedUnits.containsKey(seqB), "B must be parked");
        assertTrue(DebeziumOffsetManagement.outstandingSequences.contains(seqA), "A is still outstanding");
        assertTrue(committer.processed.isEmpty(), "B's offset must not be staged before A's");

        // A second tick must not touch B again either.
        worker.run();
        assertEquals(1, (int) worker.executions.getOrDefault(b, 0));

        // When A is finally written, B is acknowledged behind it without any
        // further execution.
        DebeziumOffsetManagement.checkIfBatchCanBeCommitted(a);
        assertEquals(3, committer.processed.size(), "a1, b1, b2 staged in handoff order");
        assertEquals(2, committer.batchesFinished);
        assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches());
        assertEquals(1, (int) worker.executions.getOrDefault(b, 0));
    }
}

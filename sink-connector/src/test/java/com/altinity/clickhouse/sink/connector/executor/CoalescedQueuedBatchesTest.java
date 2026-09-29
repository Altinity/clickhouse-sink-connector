package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.executor.OffsetTestSupport.RecordingCommitter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.RoutedBatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import static com.altinity.clickhouse.sink.connector.executor.OffsetTestSupport.processedNames;
import static com.altinity.clickhouse.sink.connector.executor.OffsetTestSupport.unit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 03.03 section 3.1.1: a routing-mode worker writes the batches queued
 * behind the one it polled as ONE write, bounded by {@code buffer.max.records},
 * preserving dequeue order per table, and reports every group to the offset
 * FIFO once, in dequeue order, only after the write succeeded.
 */
public class CoalescedQueuedBatchesTest {

    /** One observed per-table write: the topic and the record names, in order. */
    static final class Write {
        final String topic;
        final List<String> names;

        Write(String topic, List<String> names) {
            this.topic = topic;
            this.names = names;
        }

        @Override
        public String toString() {
            return topic + names;
        }
    }

    /** Records every per-table write; can be told to fail the first N attempts. */
    private static final class RecordingRunnable extends ClickHouseBatchRunnable {
        final List<Write> writes = new ArrayList<>();
        int failFirstAttempts = 0;
        int attempts = 0;

        RecordingRunnable(LinkedBlockingQueue<RoutedBatch> queue, ClickHouseSinkConnectorConfig config) {
            super(queue, 0, config, new HashMap<>());
        }

        @Override
        boolean processRecordsByTopic(String topicName, List<ClickHouseStruct> records) {
            List<String> names = new ArrayList<>();
            for (ClickHouseStruct r : records) {
                names.add(r.getSourceRecord().destination());
            }
            writes.add(new Write(topicName, names));
            attempts++;
            return attempts > failFirstAttempts;
        }
    }

    @BeforeEach
    public void reset() {
        OffsetTestSupport.resetFifo();
    }

    private static ClickHouseSinkConnectorConfig config(long bufferMaxRecords) {
        Map<String, String> props = new HashMap<>();
        props.put("buffer.flush.time.ms", "1");
        props.put("batch.retry.backoff.initial.ms", "1");
        props.put("batch.retry.backoff.max.ms", "2");
        props.put("buffer.max.records", Long.toString(bufferMaxRecords));
        return new ClickHouseSinkConnectorConfig(props);
    }

    /** A handed-off unit of one group on {@code topic}, registered with the FIFO and queued. */
    private static List<ClickHouseStruct> handOff(RecordingCommitter committer, LinkedBlockingQueue<RoutedBatch> queue,
                                                 String topic, String... names) throws InterruptedException {
        List<ClickHouseStruct> group = unit(committer, 100L, names);
        for (ClickHouseStruct r : group) {
            r.setTopic(topic);
        }
        long seq = DebeziumOffsetManagement.registerHandoff(group, Collections.singletonList(group));
        queue.put(new RoutedBatch(group, 0, RoutedBatch.extractTableName(topic), seq));
        return group;
    }

    @Test
    @DisplayName("Three queued groups on two tables: one write, per-table order kept, all units acknowledged in order")
    public void queuedBatchesAreWrittenAsOneAndAcknowledgedInOrder() throws Exception {
        RecordingCommitter committer = new RecordingCommitter();
        LinkedBlockingQueue<RoutedBatch> queue = new LinkedBlockingQueue<>();
        handOff(committer, queue, "srv.db.t1", "a1", "a2");
        handOff(committer, queue, "srv.db.t1", "b1");
        handOff(committer, queue, "srv.db.t2", "c1");

        RecordingRunnable worker = new RecordingRunnable(queue, config(100_000L));
        worker.run();

        assertEquals(2, worker.writes.size(), "one per-table write for t1 and one for t2, not three batches");
        Map<String, List<String>> byTopic = new HashMap<>();
        for (Write w : worker.writes) {
            byTopic.put(w.topic, w.names);
        }
        assertEquals(Arrays.asList("a1", "a2", "b1"), byTopic.get("srv.db.t1"),
                "t1 rows of both groups in one write, in dequeue (binlog) order");
        assertEquals(Collections.singletonList("c1"), byTopic.get("srv.db.t2"));
        assertTrue(queue.isEmpty());
        assertEquals(Arrays.asList("a1", "a2", "b1", "c1"), processedNames(committer),
                "every unit acknowledged, in handoff order");
        assertEquals(3, committer.batchesFinished, "one markBatchFinished per unit");
        assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches());
    }

    @Test
    @DisplayName("buffer.max.records bounds the coalesced write; groups are never split")
    public void coalescingStopsAtBufferMaxRecords() throws Exception {
        RecordingCommitter committer = new RecordingCommitter();
        LinkedBlockingQueue<RoutedBatch> queue = new LinkedBlockingQueue<>();
        handOff(committer, queue, "srv.db.t1", "a1", "a2");
        handOff(committer, queue, "srv.db.t1", "b1", "b2");
        handOff(committer, queue, "srv.db.t1", "c1", "c2");

        // Two groups would be 4 rows > 3: each group is written on its own.
        RecordingRunnable worker = new RecordingRunnable(queue, config(3L));
        worker.run();

        assertEquals(3, worker.writes.size(), "no two groups fit under the row bound together");
        assertEquals(Arrays.asList("a1", "a2"), worker.writes.get(0).names);
        assertEquals(Arrays.asList("b1", "b2"), worker.writes.get(1).names);
        assertEquals(Arrays.asList("c1", "c2"), worker.writes.get(2).names);
        assertEquals(Arrays.asList("a1", "a2", "b1", "b2", "c1", "c2"), processedNames(committer));
        assertEquals(3, committer.batchesFinished);
        assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches());
    }

    @Test
    @DisplayName("A failed coalesced write is retried as the same set and nothing is reported early")
    public void aFailedCoalescedWriteRetriesTheWholeSetAndReportsNothingEarly() throws Exception {
        RecordingCommitter committer = new RecordingCommitter();
        LinkedBlockingQueue<RoutedBatch> queue = new LinkedBlockingQueue<>();
        handOff(committer, queue, "srv.db.t1", "a1");
        handOff(committer, queue, "srv.db.t1", "b1");

        RecordingRunnable worker = new RecordingRunnable(queue, config(100_000L));
        worker.failFirstAttempts = 1;
        worker.run();

        assertEquals(2, worker.writes.size(), "the failed attempt and its retry");
        assertEquals(Arrays.asList("a1", "b1"), worker.writes.get(0).names, "first attempt: both groups");
        assertEquals(Arrays.asList("a1", "b1"), worker.writes.get(1).names,
                "the retry is the SAME coalesced set, not a re-polled or narrowed one");
        assertEquals(Arrays.asList("a1", "b1"), processedNames(committer),
                "both units acknowledged, in order, only after the retry succeeded");
        assertEquals(2, committer.batchesFinished);
        assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches());
    }
}

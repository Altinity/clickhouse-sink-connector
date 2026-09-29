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

    private static ClickHouseSinkConnectorConfig configWithWait(long bufferMaxRecords, long waitMs) {
        Map<String, String> props = new HashMap<>();
        props.put("buffer.flush.time.ms", "1");
        props.put("batch.retry.backoff.initial.ms", "1");
        props.put("batch.retry.backoff.max.ms", "2");
        props.put("buffer.max.records", Long.toString(bufferMaxRecords));
        props.put("coalesce.max.wait.ms", Long.toString(waitMs));
        return new ClickHouseSinkConnectorConfig(props);
    }

    /** Hands off {@code names} on {@code topic} after {@code delayMs}, from another thread. */
    private static Thread handOffLater(RecordingCommitter committer, LinkedBlockingQueue<RoutedBatch> queue,
                                       long delayMs, String topic, String... names) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delayMs);
                handOff(committer, queue, topic, names);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Test
    @DisplayName("coalesce.max.wait.ms: a batch handed off during the wait joins the write; with 0 it does not")
    public void waitCoalescesABatchThatArrivesDuringTheWindow() throws Exception {
        RecordingCommitter committer = new RecordingCommitter();
        LinkedBlockingQueue<RoutedBatch> queue = new LinkedBlockingQueue<>();
        handOff(committer, queue, "srv.db.t1", "a1", "a2");
        Thread later = handOffLater(committer, queue, 80L, "srv.db.t1", "b1");

        RecordingRunnable worker = new RecordingRunnable(queue, configWithWait(100_000L, 400L));
        worker.run();
        later.join(2000L);

        assertEquals(1, worker.writes.size(), "the batch that arrived 80 ms into a 400 ms wait is written with the first");
        assertEquals(Arrays.asList("a1", "a2", "b1"), worker.writes.get(0).names);
        assertEquals(Arrays.asList("a1", "a2", "b1"), processedNames(committer), "both units acknowledged in order");
        assertEquals(2, committer.batchesFinished);
        assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches());

        // The same sequence with the default (0): the worker writes at once, twice.
        OffsetTestSupport.resetFifo();
        RecordingCommitter c2 = new RecordingCommitter();
        LinkedBlockingQueue<RoutedBatch> q2 = new LinkedBlockingQueue<>();
        handOff(c2, q2, "srv.db.t1", "a1", "a2");
        Thread later2 = handOffLater(c2, q2, 80L, "srv.db.t1", "b1");
        RecordingRunnable w2 = new RecordingRunnable(q2, config(100_000L));
        w2.run();
        later2.join(2000L);
        w2.run();
        assertEquals(2, w2.writes.size(), "without a wait the first tick writes what is queued and returns");
        assertEquals(Arrays.asList("a1", "a2", "b1"), processedNames(c2));
    }

    @Test
    @DisplayName("coalesce.max.wait.ms: the wait stops at the row bound and a batch that does not fit is carried over")
    public void waitStopsAtTheRowBoundAndCarriesTheRestOver() throws Exception {
        RecordingCommitter committer = new RecordingCommitter();
        LinkedBlockingQueue<RoutedBatch> queue = new LinkedBlockingQueue<>();
        handOff(committer, queue, "srv.db.t1", "a1", "a2");
        Thread later = handOffLater(committer, queue, 80L, "srv.db.t1", "b1", "b2");

        long t0 = System.nanoTime();
        RecordingRunnable worker = new RecordingRunnable(queue, configWithWait(3L, 4000L));
        worker.run();
        later.join(2000L);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        assertEquals(2, worker.writes.size(), "b does not fit under 3 rows with a: it is carried over and opens the next write");
        assertEquals(Arrays.asList("a1", "a2"), worker.writes.get(0).names);
        assertEquals(Arrays.asList("b1", "b2"), worker.writes.get(1).names);
        // The first write ended the moment b arrived (80 ms in) because b did
        // not fit; only the second write -- b alone, queue empty, nothing more
        // coming -- sits out its own 4 s window. Two full windows would be 8 s.
        assertTrue(elapsedMs < 6000L, "reaching the bound must end the first wait at once; two full 4 s windows "
                + "would mean the bound was ignored (took " + elapsedMs + " ms)");
        assertEquals(Arrays.asList("a1", "a2", "b1", "b2"), processedNames(committer), "acknowledged in handoff order");
        assertEquals(2, committer.batchesFinished);
        assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches());
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

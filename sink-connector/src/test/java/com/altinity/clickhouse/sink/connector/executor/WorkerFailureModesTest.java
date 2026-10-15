package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.executor.OffsetTestSupport.RecordingCommitter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.RoutedBatch;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.altinity.clickhouse.sink.connector.executor.OffsetTestSupport.processedNames;
import static com.altinity.clickhouse.sink.connector.executor.OffsetTestSupport.unit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failure modes of a routing-mode worker (spec 03.01 section 6, spec 03.03
 * section 6): what {@code ClickHouseBatchRunnable.run} does with an
 * {@link Error}, with the ClickHouse backpressure codes 252 / 241, with a
 * FATAL code, and with a write that failed after part of the batch was
 * already in ClickHouse. No ClickHouse server: the per-table write is
 * replaced by a scripted {@code processRecordsByTopic}.
 */
public class WorkerFailureModesTest {

    /** A worker whose per-table write is scripted per attempt. */
    private static final class ScriptedRunnable extends ClickHouseBatchRunnable {
        final List<String> writes = new ArrayList<>();
        /** Called with the 1-based attempt number; returns normally or throws. */
        Function<Integer, Boolean> script = attempt -> true;
        int attempts = 0;

        ScriptedRunnable(LinkedBlockingQueue<RoutedBatch> queue, ClickHouseSinkConnectorConfig config) {
            super(queue, 0, config, new HashMap<>());
        }

        @Override
        boolean processRecordsByTopic(String topicName, List<ClickHouseStruct> records) {
            attempts++;
            Boolean ok = script.apply(attempts);
            // Only a per-table write that returned is "in ClickHouse".
            if (Boolean.TRUE.equals(ok)) {
                writes.add(topicName);
            }
            return Boolean.TRUE.equals(ok);
        }
    }

    private ClickHouseBatchExecutor pool;

    @BeforeEach
    public void reset() {
        OffsetTestSupport.resetFifo();
    }

    @AfterEach
    public void shutdown() {
        if (pool != null) {
            pool.shutdownNow();
        }
        OffsetTestSupport.resetFifo();
    }

    private static ClickHouseSinkConnectorConfig config(Map<String, String> extra) {
        Map<String, String> props = new HashMap<>();
        props.put("buffer.flush.time.ms", "1");
        props.put("batch.retry.backoff.initial.ms", "1");
        props.put("batch.retry.backoff.max.ms", "2");
        props.putAll(extra);
        return new ClickHouseSinkConnectorConfig(props);
    }

    private static ClickHouseSinkConnectorConfig config() {
        return config(Collections.emptyMap());
    }

    /** One handed-off unit whose rows span the given topics (one row per topic). */
    private static List<ClickHouseStruct> handOff(RecordingCommitter committer, LinkedBlockingQueue<RoutedBatch> queue,
                                                 String... topics) throws InterruptedException {
        String[] names = new String[topics.length];
        for (int i = 0; i < topics.length; i++) {
            names[i] = "r" + i;
        }
        List<ClickHouseStruct> group = unit(committer, 100L, names);
        for (int i = 0; i < topics.length; i++) {
            group.get(i).setTopic(topics[i]);
        }
        long seq = DebeziumOffsetManagement.registerHandoff(group, Collections.singletonList(group));
        queue.put(new RoutedBatch(group, 0, RoutedBatch.extractTableName(topics[0]), seq));
        return group;
    }

    @Test
    @DisplayName("An Error (e.g. OutOfMemoryError) escapes run(), ends the periodic task and leaves the unit outstanding")
    public void anErrorFromAWriteEscapesTheRunnableAndEndsItsPeriodicTask() throws Exception {
        RecordingCommitter committer = new RecordingCommitter();
        LinkedBlockingQueue<RoutedBatch> queue = new LinkedBlockingQueue<>();
        handOff(committer, queue, "srv.db.t1");
        ScriptedRunnable worker = new ScriptedRunnable(queue, config());
        worker.script = attempt -> {
            throw new OutOfMemoryError("simulated: Java heap space");
        };

        pool = new ClickHouseBatchExecutor(1, new ThreadFactoryBuilder().setNameFormat("fm-worker-%d").build());
        ScheduledFuture<?> future = pool.scheduleAtFixedRate(worker, 0, 1, TimeUnit.MILLISECONDS);
        long deadline = System.currentTimeMillis() + 10_000L;
        while (!future.isDone() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }

        assertTrue(future.isDone(), "run() catches Exception only: an Error must end the periodic task, "
                + "so the capture loop's failIfWorkerDied() can see it");
        ExecutionException ee = assertThrows(ExecutionException.class, future::get);
        assertTrue(ee.getCause() instanceof OutOfMemoryError, "the worker's Error is the future's cause: " + ee.getCause());
        assertEquals(1, worker.attempts, "a dead periodic task is never run again");
        assertTrue(DebeziumOffsetManagement.hasUnwrittenBatches(),
                "the unit stays outstanding, so no offset can pass rows that never reached ClickHouse");
        assertEquals(0, committer.batchesFinished);
        assertTrue(pool.awaitQuiescent(1_000L),
                "afterExecute still ran: the in-flight count is released and a DDL drain is not wedged by the dead task");
    }

    @Test
    @DisplayName("TOO_MANY_PARTS (252) and MEMORY_LIMIT_EXCEEDED (241) are retried with the same batch until they clear")
    public void backpressureCodesAreRetriedWithTheBatchKeptUntilTheyClear() throws Exception {
        for (String error : Arrays.asList(
                "Code: 252. DB::Exception: Too many parts (3001) in partition 202609. Merges are processing "
                        + "significantly slower than inserts. (TOO_MANY_PARTS)",
                "Code: 241. DB::Exception: Memory limit (total) exceeded: would use 28.01 GiB. "
                        + "(MEMORY_LIMIT_EXCEEDED)")) {
            OffsetTestSupport.resetFifo();
            RecordingCommitter committer = new RecordingCommitter();
            LinkedBlockingQueue<RoutedBatch> queue = new LinkedBlockingQueue<>();
            handOff(committer, queue, "srv.db.t1");
            ScriptedRunnable worker = new ScriptedRunnable(queue, config());
            worker.script = attempt -> {
                if (attempt <= 2) {
                    throw new RuntimeException(new java.sql.SQLException(error));
                }
                return true;
            };

            worker.run();   // attempt 1 fails: retriable, sleeps its backoff, returns
            worker.run();   // attempt 2 fails
            assertEquals(0, committer.batchesFinished, "nothing is acknowledged while the batch is failing: " + error);
            assertTrue(DebeziumOffsetManagement.hasUnwrittenBatches());
            worker.run();   // attempt 3: the condition cleared

            assertEquals(3, worker.attempts, "the SAME batch is retried, not dropped: " + error);
            assertEquals(Collections.singletonList("srv.db.t1"), worker.writes, "written exactly once when it cleared");
            assertEquals(Collections.singletonList("r0"), processedNames(committer));
            assertEquals(1, committer.batchesFinished);
            assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches());
        }
    }

    @Test
    @DisplayName("A FATAL code (60 UNKNOWN_TABLE) rethrows from run() with the unit still outstanding")
    public void aFatalCodeEndsTheWorkerWithTheBatchStillOutstanding() throws Exception {
        RecordingCommitter committer = new RecordingCommitter();
        LinkedBlockingQueue<RoutedBatch> queue = new LinkedBlockingQueue<>();
        handOff(committer, queue, "srv.db.t1");
        ScriptedRunnable worker = new ScriptedRunnable(queue, config());
        worker.script = attempt -> {
            throw new RuntimeException(new java.sql.SQLException(
                    "Code: 60. DB::Exception: Table db.t1 does not exist. (UNKNOWN_TABLE)"));
        };

        RuntimeException thrown = assertThrows(RuntimeException.class, worker::run);
        assertTrue(thrown.getMessage().contains("Fatal ClickHouse error"), thrown.getMessage());
        assertTrue(DebeziumOffsetManagement.hasUnwrittenBatches(),
                "the batch is retained, so no control-record offset can pass it while the engine stops");
        assertEquals(0, committer.batchesFinished);
    }

    @Test
    @DisplayName("A write that fails after another table of the same batch was written retries the whole batch (at-least-once)")
    public void aRetryAfterAPartialWriteRewritesTheTablesAlreadyWritten() throws Exception {
        RecordingCommitter committer = new RecordingCommitter();
        LinkedBlockingQueue<RoutedBatch> queue = new LinkedBlockingQueue<>();
        handOff(committer, queue, "srv.db.t1", "srv.db.t2");
        ScriptedRunnable worker = new ScriptedRunnable(queue, config());
        // The first table of attempt 1 is written, the second one fails once.
        // Which topic is iterated first is a hash-map detail; both orders give
        // the same outcome, which is the point.
        worker.script = attempt -> {
            if (attempt == 2) {
                throw new RuntimeException(new java.sql.SQLException(
                        "Code: 252. DB::Exception: Too many parts (3001). (TOO_MANY_PARTS)"));
            }
            return true;
        };

        worker.run();
        assertEquals(1, worker.writes.size(), "one table reached ClickHouse before the failure");
        String firstWritten = worker.writes.get(0);
        assertEquals(0, committer.batchesFinished);

        worker.run();
        assertEquals(3, worker.writes.size(), "the retry writes BOTH tables again");
        assertEquals(2, Collections.frequency(worker.writes, firstWritten),
                "the table written before the failure is written twice: the duplicate is collapsed by "
                        + "ReplacingMergeTree (same key, same _version) but is additive on a CollapsingMergeTree "
                        + "or plain MergeTree target (spec 05.04 section 6)");
        assertEquals(1, committer.batchesFinished, "the unit is acknowledged once, after the whole batch is written");
    }

    @Test
    @Disabled("DEFECT FM-03.03-1: a failure classified RETRIABLE/UNKNOWN is retried forever (RetryBackoff has no "
            + "attempt or time cap); a deterministic one (241 for a batch larger than the budget, a connector "
            + "refusal without an error code) stalls every table on the worker and every offset behind it, "
            + "with no bound on the time to a terminal, restartable failure")
    @DisplayName("A retriable failure that never clears stops the worker once the retry deadline has passed")
    public void aRetriableFailureThatNeverClearsEventuallyStopsTheWorker() throws Exception {
        RecordingCommitter committer = new RecordingCommitter();
        LinkedBlockingQueue<RoutedBatch> queue = new LinkedBlockingQueue<>();
        handOff(committer, queue, "srv.db.t1");
        Map<String, String> extra = new HashMap<>();
        // Proposed key (does not exist on 2.11.0): the longest one batch may be
        // retried before the worker escalates to a terminal failure.
        extra.put("batch.retry.max.duration.ms", "200");
        ScriptedRunnable worker = new ScriptedRunnable(queue, config(extra));
        worker.script = attempt -> {
            throw new RuntimeException(new java.sql.SQLException(
                    "Code: 241. DB::Exception: Memory limit (for query) exceeded. (MEMORY_LIMIT_EXCEEDED)"));
        };

        long deadline = System.currentTimeMillis() + 3_000L;
        RuntimeException stopped = null;
        while (stopped == null && System.currentTimeMillis() < deadline) {
            try {
                worker.run();
            } catch (RuntimeException e) {
                stopped = e;
            }
        }
        assertTrue(stopped != null, "after " + worker.attempts + " attempts over 3 s the worker is still retrying "
                + "a batch whose retry deadline was 200 ms");
        assertTrue(DebeziumOffsetManagement.hasUnwrittenBatches(), "the batch stays outstanding when the worker stops");
    }
}

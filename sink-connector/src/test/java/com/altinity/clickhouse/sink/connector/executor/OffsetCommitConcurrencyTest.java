package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency contract for the Debezium offset-commit path.
 *
 * <p>This is the failure reported against this connector in production:</p>
 * <pre>
 * ERROR OffsetStorageWriter - Invalid call to OffsetStorageWriter beginFlush()
 *       while already flushing, the framework should not allow this
 * ConnectException: OffsetStorageWriter is already flushing
 *       at OffsetStorageWriter.beginFlush(OffsetStorageWriter.java:120)
 *       at EmbeddedEngine.commitOffsets(EmbeddedEngine.java:905)
 *       at DebeziumOffsetManagement.markBatchFinishedSafely(...:272)
 * </pre>
 *
 * <p>Kafka's {@code OffsetStorageWriter} is single-flush: {@code beginFlush()}
 * throws if a flush is already open. Debezium builds a <em>new</em>
 * {@code RecordCommitter} per batch in {@code EmbeddedEngine.buildRecordCommitter},
 * so the committer's own {@code synchronized} methods lock different monitors
 * for different batches and provide no mutual exclusion between worker threads.
 * The only real barrier is {@code DebeziumOffsetManagement.OFFSET_COMMIT_LOCK}.
 * When that barrier is missing or too narrow, two threads enter
 * {@code markBatchFinished()} concurrently and the second one throws — the
 * batch fails, is retried, and records are re-applied or their offsets are
 * never advanced.</p>
 *
 * <p>The committer stub below models exactly that single-flush semantic: it
 * raises the same {@code ConnectException} if a second thread enters the
 * finish window while one is open. Making the test <em>deterministic</em>
 * rather than probabilistic is the point — a plain "run it hot and hope"
 * stress test passes on a lucky interleaving. Two mechanisms are used:
 * a real overlap window (the stub sleeps while inside the critical region,
 * so a genuine concurrent entry is observed, not merely possible), and a
 * strict serialisation check (a depth counter that records the maximum
 * observed concurrency, which must be exactly 1).</p>
 */
public class OffsetCommitConcurrencyTest {

    /** Worker threads driving concurrent commits. Above the usual pool size. */
    private static final int THREADS = 8;

    /** Batches per thread. */
    private static final int BATCHES_PER_THREAD = 25;

    /** Overlap window held open inside the finish call, in milliseconds. */
    private static final long OVERLAP_WINDOW_MS = 2;

    private static final long TIMEOUT_SECONDS = 60;

    @BeforeEach
    public void resetSharedState() {
        // The maps are static, so residue from another test in the same JVM
        // would make a run order-dependent.
        DebeziumOffsetManagement.inFlightBatches = new ConcurrentHashMap<>();
        DebeziumOffsetManagement.completedBatches = new ConcurrentHashMap<>();
    }

    /**
     * Committer that reproduces {@code OffsetStorageWriter}'s single-flush rule
     * and records the maximum concurrency it ever observes.
     */
    private static final class SingleFlushCommitter
            implements DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> {

        private final AtomicInteger inFlush = new AtomicInteger(0);
        private final AtomicInteger maxConcurrent = new AtomicInteger(0);
        private final AtomicInteger finishCount = new AtomicInteger(0);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final boolean holdWindowOpen;

        private SingleFlushCommitter(boolean holdWindowOpen) {
            this.holdWindowOpen = holdWindowOpen;
        }

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record) {
            // No-op: this stub asserts on flush overlap, not on processed counts.
        }

        @Override
        public void markBatchFinished() {
            int depth = inFlush.incrementAndGet();
            maxConcurrent.accumulateAndGet(depth, Math::max);
            try {
                if (depth > 1) {
                    // Precisely the production symptom.
                    ConnectException e = new ConnectException(
                            "OffsetStorageWriter is already flushing");
                    failure.compareAndSet(null, e);
                    throw e;
                }
                if (holdWindowOpen) {
                    try {
                        // Hold the window open so a second thread that is NOT
                        // excluded actually lands inside it. Without this the
                        // critical section is too short to overlap reliably and
                        // a broken build could pass by luck.
                        Thread.sleep(OVERLAP_WINDOW_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            } finally {
                inFlush.decrementAndGet();
            }
            finishCount.incrementAndGet();
        }

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record,
                                  DebeziumEngine.Offsets sourceOffsets) {
            markProcessed(record);
        }

        @Override
        public DebeziumEngine.Offsets buildOffsets() {
            return null;
        }
    }

    /**
     * Minimal non-null {@link ChangeEvent}. acknowledgeRecords only proceeds
     * for records whose committer AND source record are both non-null.
     */
    private static ChangeEvent<SourceRecord, SourceRecord> dummyChangeEvent() {
        return new ChangeEvent<>() {
            @Override
            public SourceRecord key() {
                return null;
            }

            @Override
            public SourceRecord value() {
                return null;
            }

            @Override
            public String destination() {
                return null;
            }

            @Override
            public Integer partition() {
                return null;
            }
        };
    }

    /**
     * Builds a batch whose records all carry the same committer, with the last
     * one flagged as the batch terminator (the record that triggers the flush).
     */
    private static List<ClickHouseStruct> batch(
            DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> committer,
            long baseTs,
            int size) {
        List<ClickHouseStruct> records = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            ClickHouseStruct record = new ClickHouseStruct();
            record.setDebezium_ts_ms(baseTs + i);
            record.setCommitter(committer);
            record.setSourceRecord(dummyChangeEvent());
            record.setLastRecordInBatch(i == size - 1);
            records.add(record);
        }
        return records;
    }

    @Test
    @DisplayName("Concurrent acknowledgeRecords never opens two overlapping flushes")
    public void concurrentAcknowledgeIsSerialised() throws Exception {
        SingleFlushCommitter committer = new SingleFlushCommitter(true);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        try {
            for (int t = 0; t < THREADS; t++) {
                final int threadIndex = t;
                pool.submit(() -> {
                    try {
                        // Release all threads at once: the contention has to be
                        // simultaneous, not staggered by thread start-up.
                        startGate.await();
                        for (int b = 0; b < BATCHES_PER_THREAD; b++) {
                            long ts = 1_000_000L + threadIndex * 10_000L + b;
                            DebeziumOffsetManagement.acknowledgeRecords(
                                    batch(committer, ts, 3));
                        }
                    } catch (Throwable e) {
                        thrown.compareAndSet(null, e);
                    } finally {
                        done.countDown();
                    }
                });
            }

            startGate.countDown();
            assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Commit threads did not finish — the offset path deadlocked.");
        } finally {
            pool.shutdownNow();
        }

        assertNull(thrown.get(),
                "A commit thread threw. If this is 'OffsetStorageWriter is already "
                        + "flushing', the OFFSET_COMMIT_LOCK barrier no longer covers "
                        + "markBatchFinished() and concurrent batches corrupt the offset "
                        + "flush: " + thrown.get());
        assertNull(committer.failure.get(),
                "Two threads entered the offset flush concurrently: "
                        + committer.failure.get());
        assertEquals(1, committer.maxConcurrent.get(),
                "Maximum observed concurrency inside markBatchFinished() must be "
                        + "exactly 1. Observed " + committer.maxConcurrent.get()
                        + " — OffsetStorageWriter is single-flush, so any overlap is the "
                        + "production ConnectException.");
        assertEquals(THREADS * BATCHES_PER_THREAD, committer.finishCount.get(),
                "Every batch must be finished exactly once. A shortfall means "
                        + "offsets were never committed for some batches, which replays "
                        + "those records after a restart.");
    }

    @Test
    @DisplayName("markProcessed and markBatchFinished stay inside one critical section")
    public void processedAndFinishedShareOneCriticalSection() throws Exception {
        // Regression guard for the narrower half of the original bug: locking
        // only markBatchFinished() leaves markProcessed() outside the barrier,
        // so one thread can record offsets into the writer while another is
        // flushing it. The interleaving detector below fails if a markProcessed
        // from thread B lands between thread A's first markProcessed and its
        // markBatchFinished.
        AtomicBoolean interleaved = new AtomicBoolean(false);
        AtomicReference<Thread> owner = new AtomicReference<>();

        DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> committer =
                new DebeziumEngine.RecordCommitter<>() {
                    @Override
                    public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> r) {
                        Thread self = Thread.currentThread();
                        Thread current = owner.compareAndExchange(null, self);
                        if (current != null && current != self) {
                            interleaved.set(true);
                        }
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    @Override
                    public void markBatchFinished() {
                        // Section closes here; releasing the ownership marker.
                        owner.set(null);
                    }

                    @Override
                    public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> r,
                                              DebeziumEngine.Offsets o) {
                        markProcessed(r);
                    }

                    @Override
                    public DebeziumEngine.Offsets buildOffsets() {
                        return null;
                    }
                };

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(4);
        try {
            for (int t = 0; t < 4; t++) {
                final int threadIndex = t;
                pool.submit(() -> {
                    try {
                        startGate.await();
                        for (int b = 0; b < 10; b++) {
                            DebeziumOffsetManagement.acknowledgeRecords(
                                    batch(committer,
                                            2_000_000L + threadIndex * 1_000L + b * 10L,
                                            3));
                        }
                    } catch (Throwable ignored) {
                        // Assertion below is on the interleaving flag.
                    } finally {
                        done.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Commit threads did not finish.");
        } finally {
            pool.shutdownNow();
        }

        assertTrue(!interleaved.get(),
                "A second thread called markProcessed() while another thread's "
                        + "batch was still between its first markProcessed() and its "
                        + "markBatchFinished(). Both calls must sit inside the SAME "
                        + "OFFSET_COMMIT_LOCK critical section — Debezium builds a new "
                        + "RecordCommitter per batch, so the committer's own synchronized "
                        + "methods do not exclude across threads.");
    }

    @Test
    @DisplayName("Every in-flight batch is drained; none is stranded uncommitted")
    public void noBatchIsStrandedInFlight() throws Exception {
        SingleFlushCommitter committer = new SingleFlushCommitter(false);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        try {
            for (int t = 0; t < THREADS; t++) {
                final int threadIndex = t;
                pool.submit(() -> {
                    try {
                        startGate.await();
                        for (int b = 0; b < BATCHES_PER_THREAD; b++) {
                            long ts = 3_000_000L + threadIndex * 10_000L + b * 5L;
                            List<ClickHouseStruct> records = batch(committer, ts, 2);
                            DebeziumOffsetManagement.addToBatchTimestamps(records);
                            DebeziumOffsetManagement.checkIfBatchCanBeCommitted(records);
                        }
                    } catch (Throwable ignored) {
                        // Drain state is asserted below.
                    } finally {
                        done.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Batch threads did not finish.");
        } finally {
            pool.shutdownNow();
        }

        // A batch left in completedBatches after every producer has finished is
        // a batch whose offsets were never committed: on restart the connector
        // resumes before it and re-applies those rows.
        int stranded = DebeziumOffsetManagement.completedBatches.size();
        assertTrue(stranded == 0,
                "After all producers finished, " + stranded + " batch(es) remain in "
                        + "completedBatches with offsets never committed. Those records "
                        + "are replayed on restart.");

        assertEquals(0, DebeziumOffsetManagement.inFlightBatches.size(),
                "in-flight batches must all be drained once every batch has been "
                        + "processed; a leak here grows unbounded and eventually blocks "
                        + "every commit behind a phantom overlap.");
    }

    @Test
    @DisplayName("Overlap detection is symmetric under concurrent registration")
    public void overlapCheckIsStableUnderConcurrentRegistration() throws Exception {
        // checkIfThereAreInflightRequests iterates a shared ConcurrentHashMap
        // while other threads add and remove entries. It must never throw
        // (ConcurrentModificationException / NPE) — a throw here aborts the
        // batch and its offsets are lost.
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(6);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        try {
            for (int t = 0; t < 6; t++) {
                final int threadIndex = t;
                pool.submit(() -> {
                    try {
                        startGate.await();
                        SingleFlushCommitter c = new SingleFlushCommitter(false);
                        for (int b = 0; b < 100; b++) {
                            List<ClickHouseStruct> records =
                                    batch(c, 4_000_000L + threadIndex * 1_000L + b, 2);
                            DebeziumOffsetManagement.addToBatchTimestamps(records);
                            DebeziumOffsetManagement.checkIfThereAreInflightRequests(records);
                            Pair<Long, Long> key =
                                    DebeziumOffsetManagement
                                            .calculateMinMaxTimestampFromBatch(records);
                            DebeziumOffsetManagement.inFlightBatches.remove(key);
                        }
                    } catch (Throwable e) {
                        thrown.compareAndSet(null, e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Overlap-check threads did not finish.");
        } finally {
            pool.shutdownNow();
        }

        assertNull(thrown.get(),
                "Concurrent overlap checking threw " + thrown.get() + ". The scan "
                        + "runs while other threads register and retire batches, so it must "
                        + "tolerate concurrent mutation; a throw aborts the batch and drops "
                        + "its offset commit.");
    }
}

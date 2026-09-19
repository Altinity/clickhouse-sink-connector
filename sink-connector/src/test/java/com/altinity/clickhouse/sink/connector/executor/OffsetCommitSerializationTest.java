package com.altinity.clickhouse.sink.connector.executor;

import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Regression tests for the offset-commit serialization defect that stalled
 * txnrepo-sink-staging on 2026-09-10/11.
 *
 * <p>The deployed build serialized only {@code markBatchFinished()} while
 * leaving {@code markProcessed()} outside the lock. That is not sufficient:
 * {@code markProcessed()} mutates the OffsetStorageWriter's pending-offset
 * map, which is exactly the state {@code beginFlush()} snapshots. A
 * {@code markProcessed()} racing another thread's flush drives
 * {@code ConnectException: OffsetStorageWriter is already flushing}, and
 * because {@code EmbeddedEngine.commitOffsets} returns early without calling
 * {@code cancelFlush}, the {@code flushInProgress} semaphore leaks
 * permanently -- every later commit fails while ClickHouse writes keep
 * succeeding against a frozen binlog position.</p>
 *
 * <p>These tests fail against the partially-serialized variant and pass once
 * both calls share {@link DebeziumOffsetManagement}'s single lock.</p>
 */
public class OffsetCommitSerializationTest {

    /**
     * Committer that fails loudly if any two offset-mutating calls overlap in
     * time, mirroring the non-thread-safety of the real OffsetStorageWriter.
     *
     * <p>Each call holds "occupancy" for a short window so a genuinely
     * unserialized caller is observed rather than merely being possible.</p>
     */
    private static class OverlapDetectingCommitter
            implements DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> {

        private final AtomicInteger occupancy = new AtomicInteger(0);
        private final AtomicReference<String> overlap = new AtomicReference<>(null);

        final AtomicInteger markProcessedCalls = new AtomicInteger(0);
        final AtomicInteger markBatchFinishedCalls = new AtomicInteger(0);

        private void enter(String who) {
            if (occupancy.incrementAndGet() != 1) {
                overlap.compareAndSet(null, who);
            }
            try {
                // Widen the window so an unserialized caller actually collides
                // instead of merely being able to.
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            occupancy.decrementAndGet();
        }

        String detectedOverlap() {
            return overlap.get();
        }

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record) {
            markProcessedCalls.incrementAndGet();
            enter("markProcessed");
        }

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record,
                                  DebeziumEngine.Offsets sourceOffsets) {
            markProcessed(record);
        }

        @Override
        public void markBatchFinished() {
            markBatchFinishedCalls.incrementAndGet();
            enter("markBatchFinished");
        }

        @Override
        public DebeziumEngine.Offsets buildOffsets() {
            return (key, value) -> { };
        }
    }

    private static ChangeEvent<SourceRecord, SourceRecord> dummyChangeEvent() {
        return new ChangeEvent<SourceRecord, SourceRecord>() {
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
     * Debezium builds a NEW RecordCommitter per batch, so the committer's own
     * {@code synchronized} methods lock different monitors for different
     * batches. Distinct committers sharing one underlying writer must still be
     * mutually excluded by the connector's own lock.
     */
    @Test
    public void testConcurrentAcknowledgeRecordDoesNotOverlap() throws Exception {
        final int threads = 8;
        final int iterations = 25;

        // One detector shared by all threads: it stands in for the single
        // OffsetStorageWriter that every per-batch committer writes through.
        OverlapDetectingCommitter shared = new OverlapDetectingCommitter();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> failures = new ArrayList<>();
        List<Thread> workers = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        DebeziumOffsetManagement.acknowledgeRecord(
                                shared, dummyChangeEvent(), i % 3 == 0);
                    }
                } catch (Throwable e) {
                    synchronized (failures) {
                        failures.add(e);
                    }
                } finally {
                    done.countDown();
                }
            });
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }

        start.countDown();
        Assertions.assertTrue(done.await(60, TimeUnit.SECONDS),
                "offset acknowledgement threads did not finish -- possible deadlock");
        for (Thread worker : workers) {
            worker.join(TimeUnit.SECONDS.toMillis(10));
        }

        Assertions.assertTrue(failures.isEmpty(),
                "acknowledgeRecord threw: " + failures);
        Assertions.assertNull(shared.detectedOverlap(),
                "offset commits were not serialized -- concurrent "
                        + shared.detectedOverlap()
                        + " reached the OffsetStorageWriter. This is the "
                        + "condition that produces 'OffsetStorageWriter is "
                        + "already flushing'.");
        Assertions.assertEquals(threads * iterations,
                shared.markProcessedCalls.get(),
                "every record must be marked processed exactly once");
    }

    /**
     * The batch variant must serialize on the same lock, including its
     * {@code markProcessed()} calls.
     */
    @Test
    public void testAcknowledgeRecordSerializesAgainstBatchVariant() throws Exception {
        OverlapDetectingCommitter shared = new OverlapDetectingCommitter();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<Throwable> failures = new ArrayList<>();

        Runnable viaSingle = () -> {
            try {
                start.await();
                for (int i = 0; i < 50; i++) {
                    DebeziumOffsetManagement.acknowledgeRecord(
                            shared, dummyChangeEvent(), i % 5 == 0);
                }
            } catch (Throwable e) {
                synchronized (failures) {
                    failures.add(e);
                }
            } finally {
                done.countDown();
            }
        };

        Runnable viaPair = () -> {
            try {
                start.await();
                for (int i = 0; i < 50; i++) {
                    DebeziumOffsetManagement.acknowledgeRecords(
                            shared, dummyChangeEvent(), i % 5 == 0);
                }
            } catch (Throwable e) {
                synchronized (failures) {
                    failures.add(e);
                }
            } finally {
                done.countDown();
            }
        };

        Thread a = new Thread(viaSingle);
        Thread b = new Thread(viaPair);
        a.setDaemon(true);
        b.setDaemon(true);
        a.start();
        b.start();
        start.countDown();

        Assertions.assertTrue(done.await(60, TimeUnit.SECONDS),
                "offset acknowledgement threads did not finish -- possible deadlock");
        a.join(TimeUnit.SECONDS.toMillis(10));
        b.join(TimeUnit.SECONDS.toMillis(10));

        Assertions.assertTrue(failures.isEmpty(),
                "acknowledgement threw: " + failures);
        Assertions.assertNull(shared.detectedOverlap(),
                "the single-record and batch acknowledgement paths must share "
                        + "one lock; overlap seen in "
                        + shared.detectedOverlap());
    }

    /**
     * A null committer or record must be a no-op rather than an NPE: the
     * ClickHouseBatchWriter path skips such records, and a throw there would
     * abort acknowledgement for the rest of the batch.
     */
    @Test
    public void testAcknowledgeRecordIgnoresNulls() throws Exception {
        OverlapDetectingCommitter committer = new OverlapDetectingCommitter();

        DebeziumOffsetManagement.acknowledgeRecord(null, dummyChangeEvent(), true);
        DebeziumOffsetManagement.acknowledgeRecord(committer, null, true);

        Assertions.assertEquals(0, committer.markProcessedCalls.get());
        Assertions.assertEquals(0, committer.markBatchFinishedCalls.get());
    }
}

package com.altinity.clickhouse.sink.connector.executor;

import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link ClickHouseBatchExecutor#pause()} stops NEW batches from starting; it
 * does not stop a batch already inside a task body. Before this change the DDL
 * path called only {@code pause()} and then applied the DDL immediately, so a
 * batch still running kept writing records that had been read under the
 * PRE-ALTER schema against the POST-ALTER ClickHouse table.
 *
 * <p>For an {@code ALTER TABLE ... CHANGE COLUMN} (rename) that is silent
 * corruption: the buffered record carries the OLD field name while the table
 * now has the NEW column, so the writer finds no value and binds NULL over the
 * real one. Measured end to end on MySQL 8.0.36 -&gt; ClickHouse 24.8.14, 80
 * inserts issued across a rename: 3 rows arrived with the renamed column NULL
 * where MySQL had a value, and an UPDATE straddling the rename was lost. Row
 * counts matched exactly in both cases (80 = 80), which is why count-based
 * checksum jobs report such a table clean.</p>
 *
 * <p>{@link ClickHouseBatchExecutor#awaitQuiescent(long)} closes that window:
 * pause stops new batches, awaitQuiescent waits out the running ones, and only
 * then is the DDL applied.</p>
 */
public class ClickHouseBatchExecutorQuiescentTest {

    /**
     * With a batch in flight, awaitQuiescent must NOT return until it finishes.
     * This is the corruption window: returning early is what let a pre-ALTER
     * record land after the ALTER.
     */
    @Test
    public void testAwaitQuiescentWaitsForRunningBatch() throws Exception {
        ClickHouseBatchExecutor executor =
                new ClickHouseBatchExecutor(2, Executors.defaultThreadFactory());
        try {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean batchFinished = new AtomicBoolean(false);

            executor.submit(() -> {
                started.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                batchFinished.set(true);
            });

            Assert.assertTrue("batch did not start", started.await(10, TimeUnit.SECONDS));
            executor.pause();

            // The batch is still running, so a short wait must time out.
            Assert.assertFalse("awaitQuiescent returned while a batch was still running -- "
                            + "this is the window that corrupts a rename",
                    executor.awaitQuiescent(300));
            Assert.assertFalse("batch should still be running", batchFinished.get());

            release.countDown();

            Assert.assertTrue("awaitQuiescent did not return after the batch finished",
                    executor.awaitQuiescent(10_000));
            Assert.assertTrue("batch should have finished", batchFinished.get());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * With nothing running, awaitQuiescent returns immediately -- the DDL path
     * must not pay a delay on every DDL.
     */
    @Test
    public void testAwaitQuiescentReturnsImmediatelyWhenIdle() {
        ClickHouseBatchExecutor executor =
                new ClickHouseBatchExecutor(2, Executors.defaultThreadFactory());
        try {
            executor.pause();
            long start = System.currentTimeMillis();
            Assert.assertTrue("an idle executor is already quiescent",
                    executor.awaitQuiescent(5_000));
            Assert.assertTrue("awaitQuiescent should not block when idle",
                    System.currentTimeMillis() - start < 1_000);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * The counter must not leak when a batch throws, or the first failed batch
     * would wedge every later DDL until the timeout.
     */
    @Test
    public void testAwaitQuiescentAfterFailedBatch() throws Exception {
        ClickHouseBatchExecutor executor =
                new ClickHouseBatchExecutor(2, Executors.defaultThreadFactory());
        try {
            CountDownLatch done = new CountDownLatch(1);
            executor.submit(() -> {
                try {
                    throw new RuntimeException("batch blew up");
                } finally {
                    done.countDown();
                }
            });
            Assert.assertTrue("batch did not run", done.await(10, TimeUnit.SECONDS));
            executor.pause();
            Assert.assertTrue("a failed batch must not leave the executor permanently non-quiescent",
                    executor.awaitQuiescent(10_000));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * pause() must still do its own job: no NEW batch may start while paused.
     */
    @Test
    public void testPauseStillBlocksNewBatches() throws Exception {
        ClickHouseBatchExecutor executor =
                new ClickHouseBatchExecutor(2, Executors.defaultThreadFactory());
        try {
            executor.pause();
            AtomicBoolean ran = new AtomicBoolean(false);
            executor.submit(() -> ran.set(true));
            Thread.sleep(500);
            Assert.assertFalse("a new batch started while the executor was paused", ran.get());

            executor.resume();
            for (int i = 0; i < 100 && !ran.get(); i++) {
                Thread.sleep(50);
            }
            Assert.assertTrue("the batch did not run after resume", ran.get());
        } finally {
            executor.shutdownNow();
        }
    }
}

package com.altinity.clickhouse.debezium.embedded.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Failure mode FM-02.03-2 (spec 02.03 section 6): concurrent callers of the
 * process-wide version sequence. {@code nextSequenceNumber} is
 * {@code static synchronized}; this pins that callers racing on the same
 * source millisecond never receive the same version and that each caller sees
 * its own versions strictly increasing (spec 02.03 section 3.3).
 */
public class SequenceCounterConcurrencyTest {

    private static final long TS = 1_757_900_000_000L;
    private static final int THREADS = 8;
    private static final int CALLS_PER_THREAD = 25_000;

    @BeforeEach
    public void resetSequenceState() {
        VersionSequencer.sequenceNumber = VersionSequencer.SEQUENCE_START;
        VersionSequencer.sequenceAnchorTs = 0L;
        VersionSequencer.sequenceHighWaterPosition = null;
        VersionSequencer.sequenceHighWaterEffectiveTs = 0L;
        VersionSequencer.sequenceMaxSourceTs = 0L;
    }

    @Test
    @DisplayName("racing callers on one source millisecond never receive the same version")
    public void concurrentCallersNeverReceiveTheSameVersion() throws Exception {
        Set<Long> seen = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                results.add(pool.submit(() -> {
                    start.await();
                    long previous = Long.MIN_VALUE;
                    boolean increasing = true;
                    for (int i = 0; i < CALLS_PER_THREAD; i++) {
                        long v = VersionSequencer.nextSequenceNumber(TS, null);
                        if (v <= previous) {
                            increasing = false;
                        }
                        previous = v;
                        seen.add(v);
                    }
                    return increasing;
                }));
            }
            start.countDown();
            for (Future<Boolean> result : results) {
                assertTrue(result.get(60, TimeUnit.SECONDS), "each caller sees strictly increasing versions");
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(THREADS * CALLS_PER_THREAD, seen.size(),
                "every call received a distinct version: no duplicate _version under concurrency");
    }
}

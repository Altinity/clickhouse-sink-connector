package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.converters.LossyConversionReporter.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Spec 07.03 section 3.1.14: the reporter is lock-free (writer threads never serialize on one monitor), counts every
 * conversion exactly, writes exactly one first-occurrence WARN per (kind, column) under contention, rolls the summary
 * window over exactly once per interval, and {@code flush()} writes the trailing suppressed count so it is not lost.
 */
public class LossyConversionReporterConcurrencyTest {

    private static final int THREADS = 8;
    private static final int PER_THREAD = 2_000;

    @BeforeEach
    void reset() {
        LossyConversionReporter.resetForTests();
    }

    private static void runConcurrently(Callable<Void> body) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Void>> futures = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return body.call();
                }));
            }
            start.countDown();
            for (Future<Void> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("concurrent reports of one column count every conversion and write exactly one first-occurrence WARN")
    void concurrentReportsAreCountedExactly() throws Exception {
        runConcurrently(() -> {
            for (int i = 0; i < PER_THREAD; i++) {
                LossyConversionReporter.report(Kind.INT_WRAP, "db.t.c", "300", "44", 1_000L);
            }
            return null;
        });

        assertEquals((long) THREADS * PER_THREAD, LossyConversionReporter.count(Kind.INT_WRAP));
        assertEquals(1L, LossyConversionReporter.warnings(Kind.INT_WRAP),
                "one WARN for the first occurrence; the rest are suppressed within the window");
    }

    @Test
    @DisplayName("distinct columns each get their own first-occurrence WARN under contention")
    void distinctColumnsEachWarnOnce() throws Exception {
        runConcurrently(() -> {
            for (int i = 0; i < 50; i++) {
                LossyConversionReporter.report(Kind.DST_GAP, "db.t.c" + i, "s", "t", 1_000L);
            }
            return null;
        });

        assertEquals(50L, LossyConversionReporter.warnings(Kind.DST_GAP));
        assertEquals((long) THREADS * 50, LossyConversionReporter.count(Kind.DST_GAP));
    }

    @Test
    @DisplayName("the summary window rolls over exactly once for a burst of reports past the interval")
    void summaryRollsOverOnce() throws Exception {
        long t0 = 10_000L;
        LossyConversionReporter.report(Kind.BOOL_COLLAPSE, "db.t.b", "2", "true", t0);
        assertEquals(1L, LossyConversionReporter.warnings(Kind.BOOL_COLLAPSE));

        long later = t0 + LossyConversionReporter.SUMMARY_INTERVAL_MILLIS;
        runConcurrently(() -> {
            for (int i = 0; i < 200; i++) {
                LossyConversionReporter.report(Kind.BOOL_COLLAPSE, "db.t.b", "2", "true", later);
            }
            return null;
        });

        assertEquals(2L, LossyConversionReporter.warnings(Kind.BOOL_COLLAPSE),
                "first-occurrence WARN plus exactly one summary for the new window");
    }

    @Test
    @DisplayName("flush() writes the trailing suppressed count once, and a second flush has nothing left to write")
    void flushWritesTrailingSuppressedOnce() {
        long t0 = 5_000L;
        LossyConversionReporter.report(Kind.RANGE_CLAMP, "db.t.d", "s", "t", t0);
        LossyConversionReporter.report(Kind.RANGE_CLAMP, "db.t.d", "s", "t", t0 + 1);
        LossyConversionReporter.report(Kind.RANGE_CLAMP, "db.t.d", "s", "t", t0 + 2);
        assertEquals(1L, LossyConversionReporter.warnings(Kind.RANGE_CLAMP), "two conversions are still suppressed");

        LossyConversionReporter.flush(t0 + 3);
        assertEquals(2L, LossyConversionReporter.warnings(Kind.RANGE_CLAMP), "flush wrote the trailing summary");

        LossyConversionReporter.flush(t0 + 4);
        assertEquals(2L, LossyConversionReporter.warnings(Kind.RANGE_CLAMP), "nothing suppressed, nothing written");
        assertEquals(3L, LossyConversionReporter.count(Kind.RANGE_CLAMP), "flush never changes the totals");
    }

    @Test
    @DisplayName("flush() with nothing suppressed is a no-op for every kind")
    void flushWithNothingSuppressedIsNoOp() {
        LossyConversionReporter.report(Kind.ZERO_TIMESTAMP, "db.t.ts", "s", "t", 1L);
        LossyConversionReporter.flush();

        assertEquals(1L, LossyConversionReporter.warnings(Kind.ZERO_TIMESTAMP));
        for (Kind kind : Kind.values()) {
            if (kind != Kind.ZERO_TIMESTAMP) {
                assertEquals(0L, LossyConversionReporter.warnings(kind), kind.toString());
            }
        }
    }

    @Test
    @DisplayName("a flush racing concurrent reports loses no suppressed conversion: every one is in exactly one summary or still pending")
    void flushRacingReportsLosesNothing() throws Exception {
        LossyConversionReporter.report(Kind.DECIMAL_TRUNCATE, "db.t.m", "s", "t", 1L);
        runConcurrently(() -> {
            for (int i = 0; i < 500; i++) {
                LossyConversionReporter.report(Kind.DECIMAL_TRUNCATE, "db.t.m", "s", "t", 2L);
                if (i % 100 == 0) {
                    LossyConversionReporter.flush(3L);
                }
            }
            return null;
        });
        LossyConversionReporter.flush(4L);

        assertEquals(1L + (long) THREADS * 500, LossyConversionReporter.count(Kind.DECIMAL_TRUNCATE));
        long before = LossyConversionReporter.warnings(Kind.DECIMAL_TRUNCATE);
        LossyConversionReporter.flush(5L);
        assertEquals(before, LossyConversionReporter.warnings(Kind.DECIMAL_TRUNCATE),
                "after the final flush nothing is left pending");
    }
}

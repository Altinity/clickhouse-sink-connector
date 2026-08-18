package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@code _version} value scheme to the 2.8.0 formula.
 *
 * <p>{@code _version} decides which physical row survives a
 * ReplacingMergeTree merge, which makes the formula that produces it a
 * <b>data format</b>, not an implementation detail. A table that already
 * holds rows written by 2.8.0 and then receives rows from a connector using a
 * different formula ends up with two incomparable magnitudes in one column:
 * whichever scheme yields the larger number wins every merge regardless of
 * which change actually happened later, and the rows already written cannot
 * be undone by downgrading. That is why this is pinned by a test rather than
 * left to review.</p>
 *
 * <p>The 2.8.0 formula, from
 * {@code DebeziumChangeEventCapture.addVersion()} at tag {@code 2.8.0}:</p>
 * <pre>
 *     sequenceStartTime = first record's debezium_ts_ms
 *     diff = (record.debezium_ts_ms - sequenceStartTime) / 1000
 *     if (diff &gt; 1) { counter = SEQUENCE_START; sequenceStartTime = record.debezium_ts_ms }
 *     else           { counter++ }
 *     _version = record.debezium_ts_ms * 1_000_000 + counter
 * </pre>
 *
 * <p>Note the anchor is the <b>Debezium</b> timestamp, not the source commit
 * timestamp. Anchoring to {@code source.ts_ms} instead would be a strictly
 * better ordering property - it is stable across redelivery, which is the
 * root of issue #1346 - but it changes the emitted values, so it cannot be
 * done silently on a branch that must stay readable alongside 2.8.0 data.</p>
 */
public class Version280CompatibilityTest {

    /** Mirrors {@code DebeziumChangeEventCapture.SEQUENCE_START}. */
    private static final long SEQUENCE_START = 1000000000L;

    private static final long SEQUENCE_SCALE = 1000000L;

    /** 2024-01-01T00:00:00Z. */
    private static final long TS = 1704067200000L;

    private static ClickHouseStruct recordAt(long debeziumTsMs) {
        ClickHouseStruct record = new ClickHouseStruct();
        record.setDebezium_ts_ms(debeziumTsMs);
        record.setTs_ms(debeziumTsMs);
        return record;
    }

    /**
     * The 2.8.0 algorithm, reimplemented here from the tagged source so the
     * expected values are derived independently of the code under test.
     */
    private static List<Long> expected280(List<Long> timestamps) {
        List<Long> out = new ArrayList<>();
        long counter = SEQUENCE_START;
        long anchor = timestamps.get(0);
        for (long ts : timestamps) {
            int diff = (int) ((ts - anchor) / 1000);
            if (diff > 1) {
                counter = SEQUENCE_START;
                anchor = ts;
            } else {
                counter++;
            }
            out.add(ts * SEQUENCE_SCALE + counter);
        }
        return out;
    }

    @Test
    @DisplayName("the emitted _version matches the 2.8.0 formula exactly")
    public void matchesTwoEightZeroFormulaExactly() {
        List<Long> timestamps = Arrays.asList(TS, TS, TS + 500, TS + 900);
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        capture.seedSequenceAnchor(timestamps.get(0));

        List<Long> actual = new ArrayList<>();
        for (long ts : timestamps) {
            actual.add(capture.nextSequenceNumber(ts));
        }

        assertEquals(expected280(timestamps), actual,
                "the _version formula must remain bit-for-bit identical to 2.8.0; a "
                        + "different magnitude cannot coexist with 2.8.0-written rows in "
                        + "the same ReplacingMergeTree column");
    }

    @Test
    @DisplayName("the counter resets on a gap exactly as 2.8.0 did")
    public void resetsOnGapLikeTwoEightZero() {
        // A gap of more than one second must reset the counter to
        // SEQUENCE_START - not to some other floor, and not merely continue.
        List<Long> timestamps = Arrays.asList(TS, TS + 5000, TS + 5100);
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        capture.seedSequenceAnchor(timestamps.get(0));

        List<Long> actual = new ArrayList<>();
        for (long ts : timestamps) {
            actual.add(capture.nextSequenceNumber(ts));
        }

        assertEquals(expected280(timestamps), actual,
                "the gap-reset behaviour is part of the emitted value and must match 2.8.0");
        assertEquals((TS + 5000) * SEQUENCE_SCALE + SEQUENCE_START, actual.get(1),
                "the post-gap record must carry exactly SEQUENCE_START, as in 2.8.0");
    }

    @Test
    @DisplayName("a one-second step does NOT reset - the 2.8.0 boundary is diff > 1")
    public void oneSecondStepDoesNotReset() {
        // 2.8.0 resets on diff > 1, not diff >= 1. Tightening the boundary
        // would change the emitted values for every batch spanning one second.
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        capture.seedSequenceAnchor(TS);
        long first = capture.nextSequenceNumber(TS);
        long afterOneSecond = capture.nextSequenceNumber(TS + 1000);

        assertEquals(TS * SEQUENCE_SCALE + SEQUENCE_START + 1, first);
        assertEquals((TS + 1000) * SEQUENCE_SCALE + SEQUENCE_START + 2, afterOneSecond,
                "a one-second step must increment, not reset - 2.8.0's boundary is diff > 1");
    }

    @Test
    @DisplayName("addVersion() emits the same values as the live batch path")
    public void addVersionAgreesWithLivePath() {
        // The two paths must never drift into different schemes. addVersion()
        // is the historical entry point; nextSequenceNumber() is what the live
        // batch consumer calls.
        List<Long> timestamps = Arrays.asList(TS, TS, TS + 400);

        DebeziumChangeEventCapture viaHelper = new DebeziumChangeEventCapture();
        viaHelper.seedSequenceAnchor(timestamps.get(0));
        List<Long> helperValues = new ArrayList<>();
        for (long ts : timestamps) {
            helperValues.add(viaHelper.nextSequenceNumber(ts));
        }

        DebeziumChangeEventCapture viaAddVersion = new DebeziumChangeEventCapture();
        viaAddVersion.seedSequenceAnchor(timestamps.get(0));
        List<ClickHouseStruct> records = new ArrayList<>();
        for (long ts : timestamps) {
            records.add(recordAt(ts));
        }
        viaAddVersion.addVersion(records);

        List<Long> addVersionValues = new ArrayList<>();
        for (ClickHouseStruct r : records) {
            addVersionValues.add(r.getSequenceNumber());
        }

        assertEquals(helperValues, addVersionValues,
                "addVersion() and the live batch path must emit identical values, or the "
                        + "same table receives two different _version schemes");
    }

    @Test
    @DisplayName("addVersion() anchors on debezium_ts_ms, not source ts_ms")
    public void addVersionAnchorsOnDebeziumTimestamp() {
        // If this ever anchors on the source commit timestamp, every emitted
        // value shifts and 2.8.0 compatibility is silently broken.
        ClickHouseStruct record = new ClickHouseStruct();
        record.setDebezium_ts_ms(TS);
        record.setTs_ms(TS + 3_600_000L); // deliberately far from the Debezium clock

        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        capture.seedSequenceAnchor(TS);
        capture.addVersion(Collections.singletonList(record));

        assertEquals(TS * SEQUENCE_SCALE + SEQUENCE_START + 1, record.getSequenceNumber(),
                "the version must be derived from debezium_ts_ms, exactly as 2.8.0 did");
    }

    /**
     * The formula is preserved; the concurrency defect is not. Two threads
     * racing the shared counter must never be handed the same value, or the
     * ReplacingMergeTree collapses two distinct records into one row.
     */
    @Test
    @DisplayName("concurrent assignment never yields a duplicate _version")
    public void concurrentAssignmentIsCollisionFree() throws Exception {
        final int threads = 8;
        final int perThread = 400;
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        capture.seedSequenceAnchor(TS);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<List<Long>> results = Collections.synchronizedList(new ArrayList<>());
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    List<Long> mine = new ArrayList<>();
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            // All threads sit on the same millisecond, which is
                            // precisely where the unsynchronised counter collided.
                            mine.add(capture.nextSequenceNumber(TS));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    results.add(mine);
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS),
                    "workers did not finish in time");
        } finally {
            pool.shutdownNow();
        }

        Set<Long> unique = new HashSet<>();
        int total = 0;
        for (List<Long> chunk : results) {
            total += chunk.size();
            unique.addAll(chunk);
        }
        assertEquals(threads * perThread, total, "every worker must have completed");
        assertEquals(total, unique.size(),
                "every assigned _version must be unique; a duplicate lets the "
                        + "ReplacingMergeTree silently discard one of two distinct records");
    }
}

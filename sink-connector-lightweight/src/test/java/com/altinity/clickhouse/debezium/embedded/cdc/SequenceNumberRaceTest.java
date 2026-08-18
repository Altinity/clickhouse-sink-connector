package com.altinity.clickhouse.debezium.embedded.cdc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency contract for {@code _version} assignment.
 *
 * <p>{@code _version} is the value ReplacingMergeTree uses to decide which
 * physical row survives a merge. If two <em>different</em> records are handed
 * the <em>same</em> version, RMT keeps one and silently discards the other —
 * data loss with no error, no log line, and no way to detect it afterwards
 * except by counting rows against the source.</p>
 *
 * <p>{@code nextSequenceNumber(long)} is a check-and-act over two pieces of
 * shared state: the timestamp anchor is read to decide whether the counter
 * resets, and only then is the counter reset or incremented. An atomic counter
 * alone cannot make that pair consistent — two threads crossing the same
 * one-second boundary would both take the reset branch and both emit
 * {@code SEQUENCE_START}. Both windows must sit inside one critical section.
 * That is what these tests pin.</p>
 *
 * <h2>The uniqueness property this suite asserts, and why it is that one</h2>
 *
 * <p>Uniqueness holds <b>for a monotonically non-decreasing source clock</b>,
 * which is exactly what a MySQL binlog delivers: commit timestamps advance,
 * they do not rewind. It is <em>not</em> an unconditional property of the
 * formula, and the tests deliberately do not claim it is:</p>
 *
 * <pre>    _version = debezium_ts_ms * 1_000_000 + counter</pre>
 *
 * <p>{@code SEQUENCE_START} is 1e9 while the timestamp scale is 1e6, so the
 * counter's magnitude spans 1000 ms of timestamp band and adjacent bands
 * <b>overlap by design</b>. Feeding the same timestamps again from a second
 * independent stream re-enters the reset branch and reproduces values that
 * were already emitted. Verified single-threaded, with no concurrency
 * involved: replaying a timestamp stream reproduces prior values, while an
 * ascending stream never does. So a test asserting global uniqueness over
 * replayed per-thread streams would be asserting a property the pinned 2.8.0
 * format never had — it would fail against correct code and invite someone to
 * "fix" the formula, which is an irreversible data-format change (see
 * {@code Version280CompatibilityTest}).</p>
 *
 * <p>Each test therefore drives a <b>shared</b> clock across all threads,
 * mirroring one binlog feeding several batch threads, and asserts uniqueness
 * of what is emitted. Under that model any duplicate is a genuine lost update
 * in the read-modify-write, which is precisely the race being guarded.</p>
 */
public class SequenceNumberRaceTest {

    private static final int THREADS = 8;
    private static final int CALLS_PER_THREAD = 500;
    private static final long TIMEOUT_SECONDS = 60;

    /** A fixed base source-commit clock, for determinism. */
    private static final long BASE_TS = 1_700_000_000_000L;

    /**
     * Drives {@code nextSequenceNumber} concurrently and returns every emitted
     * value. The supplier receives (threadIndex, callIndex).
     */
    private static List<Long> runConcurrently(DebeziumChangeEventCapture capture,
                                              BiFunction<Integer, Integer, Long> tsForCall)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        List<List<Long>> perThread = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            perThread.add(new ArrayList<>(CALLS_PER_THREAD));
        }

        try {
            for (int t = 0; t < THREADS; t++) {
                final int threadIndex = t;
                final List<Long> sink = perThread.get(t);
                pool.submit(() -> {
                    try {
                        // Simultaneous release: staggered starts hide the race.
                        startGate.await();
                        for (int c = 0; c < CALLS_PER_THREAD; c++) {
                            sink.add(capture.nextSequenceNumber(
                                    tsForCall.apply(threadIndex, c)));
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
                    "Version-assignment threads did not finish — nextSequenceNumber "
                            + "deadlocked.");
        } finally {
            pool.shutdownNow();
        }

        assertNull(thrown.get(), "A version-assignment thread threw: " + thrown.get());

        List<Long> all = new ArrayList<>(THREADS * CALLS_PER_THREAD);
        for (List<Long> chunk : perThread) {
            all.addAll(chunk);
        }
        return all;
    }

    private static void assertAllUnique(List<Long> versions, String scenario) {
        Set<Long> seen = new HashSet<>();
        List<Long> duplicates = new ArrayList<>();
        for (Long v : versions) {
            if (!seen.add(v)) {
                duplicates.add(v);
            }
        }
        assertTrue(duplicates.isEmpty(),
                scenario + ": " + duplicates.size() + " duplicate _version value(s) "
                        + "emitted (first few: "
                        + duplicates.subList(0, Math.min(5, duplicates.size())) + "). "
                        + "The source clock in this scenario never rewinds, so every call "
                        + "must yield a fresh value; a duplicate is a lost update in the "
                        + "counter's read-modify-write. Two records sharing a _version means "
                        + "ReplacingMergeTree silently discards one on merge. Fix the "
                        + "critical section — do NOT change the formula, which is pinned to "
                        + "2.8.0 by Version280CompatibilityTest.");
        assertEquals(versions.size(), seen.size(),
                scenario + ": emitted count and distinct count disagree.");
    }

    @Test
    @DisplayName("Concurrent assignment at one timestamp emits no duplicate _version")
    public void noDuplicatesWithinOneTimestamp() throws Exception {
        // Every thread on the SAME timestamp: all calls take the increment
        // branch, isolating the incrementAndGet()-then-re-read window. This is
        // the purest lost-update detector — the clock cannot be blamed.
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();

        assertAllUnique(runConcurrently(capture, (t, c) -> BASE_TS),
                "constant timestamp");
    }

    @Test
    @DisplayName("Concurrent assignment on a shared advancing clock emits no duplicate _version")
    public void noDuplicatesOnSharedAdvancingClock() throws Exception {
        // One monotonically advancing clock shared by every thread: the real
        // shape of several batch threads consuming one binlog. Threads
        // interleave arbitrarily but the source time never rewinds.
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        AtomicLong clock = new AtomicLong(BASE_TS);

        assertAllUnique(runConcurrently(capture, (t, c) -> clock.getAndAdd(1L)),
                "shared advancing clock");
    }

    @Test
    @DisplayName("Concurrent assignment across reset boundaries emits no duplicate _version")
    public void noDuplicatesAcrossResetBoundary() throws Exception {
        // The counter resets when the clock advances more than one second past
        // the anchor. A shared clock jumping 5s per call makes every thread
        // race into the reset branch — the interleaving in which two batches
        // both emitted SEQUENCE_START.
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        AtomicLong clock = new AtomicLong(BASE_TS);

        assertAllUnique(runConcurrently(capture, (t, c) -> clock.getAndAdd(5_000L)),
                "reset boundary");
    }

    @Test
    @DisplayName("An out-of-order batch cannot drag the anchor backward")
    public void anchorNeverMovesBackward() {
        // Deterministic, single-threaded, and the important one: this is a
        // real defect found by the concurrent version of this test, reduced to
        // a form that cannot flake.
        //
        // The anchor is shared by all batch threads but each batch seeds it
        // from its OWN first record. A batch carrying an older timestamp can
        // therefore drag the anchor backward past a point another thread has
        // already passed. That re-arms the `diff > 1` reset branch for
        // timestamps already in use, so the counter is reset to the constant
        // SEQUENCE_START a second time and two different records at the same
        // source timestamp receive an IDENTICAL _version — ReplacingMergeTree
        // then silently discards one of them.
        //
        // No concurrency is needed to demonstrate it: seeding backward between
        // two assignments at the same later timestamp is sufficient. Measured
        // on the unguarded code, this loop produced 199 duplicate versions out
        // of 200 records.
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        final long staleTs = BASE_TS;
        final long currentTs = BASE_TS + 10_000L;

        List<Long> emitted = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            // A stale batch re-seeds the anchor backward while records keep
            // arriving at a later timestamp.
            capture.seedSequenceAnchor(staleTs);
            emitted.add(capture.nextSequenceNumber(currentTs));
        }

        assertAllUnique(emitted, "backward anchor seeding");

        // Ordering must hold too: the duplicate form of this bug also emitted a
        // LOWER value after a higher one, which lets a superseded row win.
        long previous = Long.MIN_VALUE;
        for (Long v : emitted) {
            assertTrue(v > previous,
                    "Version " + v + " did not exceed the previous value " + previous
                            + ". A backward anchor seed reset the counter, so a later record "
                            + "received a smaller _version than an earlier one and would lose "
                            + "the ReplacingMergeTree merge to a row it should supersede.");
            previous = v;
        }
    }

    @Test
    @DisplayName("Concurrent anchor seeding on an advancing clock emits no duplicate _version")
    public void anchorSeedingIsSerialisedWithAssignment() throws Exception {
        // The concurrent companion to the test above: seedSequenceAnchor() runs
        // once per batch while other batches assign versions. The clock is
        // shared and strictly advancing, so no batch seeds backward and every
        // emitted value must be distinct under any interleaving.
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        AtomicLong clock = new AtomicLong(BASE_TS);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        List<List<Long>> perThread = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            perThread.add(new ArrayList<>());
        }

        try {
            for (int t = 0; t < THREADS; t++) {
                final List<Long> sink = perThread.get(t);
                pool.submit(() -> {
                    try {
                        startGate.await();
                        for (int b = 0; b < 100; b++) {
                            // Real call order in the batch consumer: seed the
                            // shared anchor for the batch, then assign per record.
                            long batchTs = clock.getAndAdd(600L);
                            capture.seedSequenceAnchor(batchTs);
                            for (int i = 0; i < 5; i++) {
                                sink.add(capture.nextSequenceNumber(batchTs));
                            }
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
                    "Anchor-seeding threads did not finish.");
        } finally {
            pool.shutdownNow();
        }

        assertNull(thrown.get(), "An anchor-seeding thread threw: " + thrown.get());

        List<Long> all = new ArrayList<>();
        for (List<Long> chunk : perThread) {
            all.addAll(chunk);
        }
        assertAllUnique(all, "concurrent anchor seeding");
    }

    @Test
    @DisplayName("Versions increase monotonically for a non-decreasing source clock")
    public void versionsAreMonotonicForAdvancingClock() {
        // Single-threaded ordering contract. RMT keeps the LARGEST version, so
        // a later source commit must never produce a smaller value than an
        // earlier one — an inversion resurrects a superseded row, which is
        // corruption rather than loss.
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();

        long previous = Long.MIN_VALUE;
        for (int i = 0; i < 5_000; i++) {
            // Mixes the increment branch (small steps) and the reset branch
            // (a 5s jump every 100 calls) on one ascending clock.
            long ts = BASE_TS + i + (i / 100) * 5_000L;
            long version = capture.nextSequenceNumber(ts);

            assertTrue(version > previous,
                    "Version " + version + " did not exceed the previous value "
                            + previous + " at call " + i + " (ts=" + ts + "). The source clock "
                            + "only advances here, so versions must strictly increase; an "
                            + "inversion lets an older row outrank a newer one in the merge.");
            previous = version;
        }
    }

    @Test
    @DisplayName("A replayed timestamp stream reproduces prior versions — the #1346 shape")
    public void replayedStreamReproducesVersions() {
        // Documents the known limitation rather than asserting it away, so the
        // behaviour is visible and any future change to it is deliberate.
        //
        // Redelivery after an offset rewind replays source timestamps. Because
        // the reset branch re-seeds the counter to a constant, the same stream
        // yields the same values again. That is issue #1346: a replayed DELETE
        // can tie or outrank the INSERT that followed it. It is a property of
        // the pinned 2.8.0 FORMAT, reproducible with no concurrency at all —
        // NOT a race, and not fixable by locking. Fixing it means changing the
        // version scheme, which is an irreversible data-format change and needs
        // a migration plan, so it is deliberately out of scope here.
        DebeziumChangeEventCapture first = new DebeziumChangeEventCapture();
        DebeziumChangeEventCapture replay = new DebeziumChangeEventCapture();

        List<Long> original = new ArrayList<>();
        List<Long> replayed = new ArrayList<>();
        for (int c = 0; c < 50; c++) {
            long ts = BASE_TS + (long) c * 5_000L;
            original.add(first.nextSequenceNumber(ts));
            replayed.add(replay.nextSequenceNumber(ts));
        }

        assertEquals(original, replayed,
                "Replaying an identical source-timestamp stream must reproduce an "
                        + "identical version sequence. This determinism is what makes the "
                        + "format stable across a restart; if it ever diverges, a redelivered "
                        + "record would compete against its own earlier copy with a different "
                        + "version and the outcome of the merge would depend on timing.");
    }
}

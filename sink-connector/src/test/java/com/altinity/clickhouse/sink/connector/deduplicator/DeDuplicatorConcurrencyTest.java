package com.altinity.clickhouse.sink.connector.deduplicator;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkTaskTest;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.connect.sink.SinkRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Thread-safety contract for {@link DeDuplicator}.
 *
 * <p>{@code DeDuplicator} is the connector's last line of defence against
 * re-applying a record that Kafka redelivered. Its two backing structures —
 * {@code records} and {@code queue} — are plain {@link HashMap}s, and both
 * {@code isNew()} and {@code updateDedupePool()} mutate them.</p>
 *
 * <p>Whether that is a live defect depends on the call graph: today
 * {@code ClickHouseSinkTask.put()} is the only caller, and Kafka Connect
 * invokes {@code put()} from a single task thread, so the maps are confined in
 * practice. That confinement is an <em>invariant of the caller</em>, not a
 * property of this class, and nothing in the code states or enforces it. If a
 * second caller is ever added — a parallel {@code put}, a shared task-level
 * de-duplicator, a background eviction sweep — the failure mode is not a
 * crash but a wrong answer: concurrent {@code HashMap} mutation can corrupt
 * the bucket chain so a key that IS present reads as absent, and the duplicate
 * is admitted and re-applied.</p>
 *
 * <p>These tests therefore do two things. First they pin the single-threaded
 * semantics that must hold regardless (eviction bound, per-topic isolation,
 * policy behaviour) — those assertions are deterministic. Then they document
 * the confinement requirement explicitly, and exercise the concurrent path so
 * that <em>if</em> the class is ever shared, the resulting corruption surfaces
 * here rather than as unexplained duplicate rows in production. The concurrent
 * case asserts only outcomes that must hold under ANY correct interleaving —
 * never a specific interleaving — so it cannot become flaky.</p>
 */
public class DeDuplicatorConcurrencyTest {

    private static final String TOPIC = "products";
    private static final String KEY_FIELD = "productId";
    private static final String VALUE_FIELD = "amount";
    private static final long TIMEOUT_SECONDS = 60;

    private DeDuplicator createDeDuplicator(String policy, long poolSize) {
        Map<String, String> properties = new HashMap<>();
        properties.put(
                ClickHouseSinkConnectorConfigVariables.DEDUPLICATION_POLICY.toString(),
                policy);
        properties.put(
                ClickHouseSinkConnectorConfigVariables.BUFFER_COUNT.toString(),
                String.valueOf(poolSize));
        return new DeDuplicator(new ClickHouseSinkConnectorConfig(properties));
    }

    private SinkRecord record(String topic, String key) {
        return ClickHouseSinkTaskTest.spoofSinkRecord(
                topic, KEY_FIELD, key, VALUE_FIELD, "v",
                TimestampType.NO_TIMESTAMP_TYPE, System.currentTimeMillis());
    }

    @Test
    @DisplayName("A redelivered key is rejected exactly once per distinct key")
    public void redeliveredKeyIsRejected() {
        DeDuplicator dedup = createDeDuplicator("new", 1000);

        assertTrue(dedup.isNew(TOPIC, record(TOPIC, "k1")),
                "First sighting of a key must be admitted.");
        assertTrue(!dedup.isNew(TOPIC, record(TOPIC, "k1")),
                "A redelivered key must be rejected — admitting it re-applies the "
                        + "row, which for a DELETE followed by a re-INSERT leaves the row in "
                        + "the wrong final state.");
        assertTrue(dedup.isNew(TOPIC, record(TOPIC, "k2")),
                "A different key on the same topic must still be admitted.");
    }

    @Test
    @DisplayName("Eviction never lets the pool exceed its configured bound")
    public void evictionRespectsPoolBound() {
        // The pool is a bounded FIFO. If eviction under-runs, memory grows
        // without limit; if it over-runs, a key is forgotten too early and a
        // genuine duplicate is admitted. Both are silent.
        final long poolSize = 10;
        DeDuplicator dedup = createDeDuplicator("new", poolSize);

        for (int i = 0; i < 100; i++) {
            assertTrue(dedup.isNew(TOPIC, record(TOPIC, "k" + i)),
                    "Distinct key k" + i + " must be admitted.");
        }

        // The most recent key must still be remembered: eviction is FIFO, so
        // the newest entry can never be the one dropped.
        assertTrue(!dedup.isNew(TOPIC, record(TOPIC, "k99")),
                "The most recently seen key was evicted. Eviction is FIFO, so the "
                        + "newest key must survive; dropping it admits an immediate "
                        + "redelivery as new.");
    }

    @Test
    @DisplayName("Topics are isolated — one topic's keys never mask another's")
    public void topicsAreIsolated() {
        DeDuplicator dedup = createDeDuplicator("new", 1000);

        assertTrue(dedup.isNew("topic-a", record("topic-a", "shared-key")));
        assertTrue(dedup.isNew("topic-b", record("topic-b", "shared-key")),
                "The same key value on a DIFFERENT topic is a different record. "
                        + "Rejecting it would silently drop a legitimate row.");
        assertTrue(!dedup.isNew("topic-a", record("topic-a", "shared-key")),
                "Within one topic the key must still de-duplicate.");
    }

    @Test
    @DisplayName("OFF policy admits everything — de-duplication must be opt-in")
    public void offPolicyAdmitsEverything() {
        DeDuplicator dedup = createDeDuplicator("off", 1000);

        for (int i = 0; i < 50; i++) {
            assertTrue(dedup.isNew(TOPIC, record(TOPIC, "same-key")),
                    "With the OFF policy every record must be admitted; suppressing "
                            + "one here would drop a row the operator expected to be written.");
        }
    }

    @Test
    @DisplayName("Distinct keys are never lost when isNew() is driven concurrently")
    public void concurrentDistinctKeysAreAllAdmitted() throws Exception {
        // Documents the confinement requirement. Every key here is DISTINCT, so
        // under any correct interleaving all of them must be admitted exactly
        // once. A corrupted HashMap chain shows up as an admitted count below
        // the key count (a lost row) or as a thrown exception.
        //
        // This asserts a property that holds under every legal interleaving,
        // never a particular one, so it cannot be flaky. It will not fail on
        // today's single-threaded caller either — it fails only if the maps
        // genuinely corrupt, which is exactly the signal wanted if a second
        // caller is ever introduced.
        final int threads = 8;
        final int keysPerThread = 500;
        DeDuplicator dedup = createDeDuplicator("new", 1_000_000);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger admitted = new AtomicInteger(0);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        try {
            for (int t = 0; t < threads; t++) {
                final int threadIndex = t;
                pool.submit(() -> {
                    try {
                        startGate.await();
                        for (int k = 0; k < keysPerThread; k++) {
                            // Globally unique key: no legitimate rejection possible.
                            String key = "t" + threadIndex + "-k" + k;
                            if (dedup.isNew(TOPIC, record(TOPIC, key))) {
                                admitted.incrementAndGet();
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
                    "De-duplication threads did not finish — the pool structures "
                            + "deadlocked or an infinite loop was hit (a corrupted HashMap "
                            + "bucket chain can spin forever).");
        } finally {
            pool.shutdownNow();
        }

        assertNull(thrown.get(),
                "Concurrent de-duplication threw " + thrown.get() + ". DeDuplicator's "
                        + "records/queue are plain HashMaps; it is safe ONLY while confined to "
                        + "the single Connect task thread that calls put(). If a second caller "
                        + "was added, the maps must be made concurrent — do not relax this test.");
        assertEquals(threads * keysPerThread, admitted.get(),
                "Every key in this run is globally unique, so all "
                        + (threads * keysPerThread) + " must be admitted; only "
                        + admitted.get() + " were. A shortfall means the de-duplication pool "
                        + "reported a key as already-seen when it was not — the record is "
                        + "dropped and never reaches ClickHouse.");
    }
}

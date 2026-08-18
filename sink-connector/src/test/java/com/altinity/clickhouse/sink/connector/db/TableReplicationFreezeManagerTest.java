package com.altinity.clickhouse.sink.connector.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TableReplicationFreezeManager}, including the core
 * concurrency invariant that an insert thread blocks for the entire duration a
 * table is frozen by a DDL thread, and is released promptly on unfreeze.
 */
class TableReplicationFreezeManagerTest {

    private TableReplicationFreezeManager mgr;

    @BeforeEach
    void setUp() {
        mgr = TableReplicationFreezeManager.getInstance();
        mgr.clearAll();
    }

    @AfterEach
    void tearDown() {
        mgr.clearAll();
    }

    @Test
    @DisplayName("Unknown / never-frozen table is not frozen and awaitUnfrozen returns immediately")
    void unknownTableNotFrozen() {
        assertFalse(mgr.isFrozen("db.never"));
        assertTrue(mgr.awaitUnfrozen("db.never", 1000));
    }

    @Test
    @DisplayName("freeze then unfreeze toggles state")
    void freezeUnfreezeToggles() {
        mgr.freeze("db.t");
        assertTrue(mgr.isFrozen("db.t"));
        mgr.unfreeze("db.t");
        assertFalse(mgr.isFrozen("db.t"));
    }

    @Test
    @DisplayName("awaitUnfrozen blocks until the table is unfrozen by another thread")
    void awaitBlocksUntilUnfrozen() throws Exception {
        mgr.freeze("db.t");
        AtomicBoolean released = new AtomicBoolean(false);
        AtomicLong releasedAt = new AtomicLong(0);
        CountDownLatch started = new CountDownLatch(1);

        Thread insertThread = new Thread(() -> {
            started.countDown();
            boolean ok = mgr.awaitUnfrozen("db.t", 5000);
            released.set(ok);
            releasedAt.set(System.currentTimeMillis());
        });
        insertThread.start();

        assertTrue(started.await(2, TimeUnit.SECONDS));
        // Give the insert thread time to enter wait(); it must still be blocked.
        Thread.sleep(200);
        assertTrue(insertThread.isAlive(), "Insert thread must be blocked while frozen");
        assertFalse(released.get());

        long unfrozenAt = System.currentTimeMillis();
        mgr.unfreeze("db.t");
        insertThread.join(2000);

        assertFalse(insertThread.isAlive(), "Insert thread must wake after unfreeze");
        assertTrue(released.get(), "awaitUnfrozen should return true once unfrozen");
        assertTrue(releasedAt.get() >= unfrozenAt,
                "Insert thread should only be released at/after the unfreeze");
    }

    @Test
    @DisplayName("awaitUnfrozen returns false (fail-safe) if the freeze outlasts the timeout")
    void awaitTimesOutWhileFrozen() {
        mgr.freeze("db.stuck");
        long start = System.currentTimeMillis();
        boolean ok = mgr.awaitUnfrozen("db.stuck", 300);
        long elapsed = System.currentTimeMillis() - start;
        assertFalse(ok, "Should return false when still frozen at timeout");
        assertTrue(elapsed >= 250, "Should have waited ~the timeout");
        mgr.unfreeze("db.stuck");
    }

    @Test
    @DisplayName("Freeze is per-table: freezing A does not block inserts to B")
    void freezeIsPerTable() {
        mgr.freeze("db.A");
        assertTrue(mgr.isFrozen("db.A"));
        assertFalse(mgr.isFrozen("db.B"));
        // B was never frozen -> await returns immediately even while A is frozen.
        assertTrue(mgr.awaitUnfrozen("db.B", 1000));
        mgr.unfreeze("db.A");
    }

    @Test
    @DisplayName("Many insert threads all block during freeze and all release on unfreeze")
    void manyThreadsReleaseOnUnfreeze() throws Exception {
        final String table = "db.busy";
        final int threads = 16;
        mgr.freeze(table);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch allStarted = new CountDownLatch(threads);
        CountDownLatch allReleased = new CountDownLatch(threads);
        AtomicInteger releasedWhileFrozen = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                allStarted.countDown();
                boolean ok = mgr.awaitUnfrozen(table, 5000);
                if (ok && mgr.isFrozen(table)) {
                    // Released but the table is still frozen -> invariant broken.
                    releasedWhileFrozen.incrementAndGet();
                }
                allReleased.countDown();
            });
        }

        assertTrue(allStarted.await(2, TimeUnit.SECONDS));
        Thread.sleep(200);
        // None should have finished yet.
        assertEquals(threads, allReleased.getCount(),
                "No insert thread may proceed while the table is frozen");

        mgr.unfreeze(table);
        assertTrue(allReleased.await(5, TimeUnit.SECONDS),
                "All insert threads must release after unfreeze");
        assertEquals(0, releasedWhileFrozen.get(),
                "No thread may observe itself released while still frozen");
        pool.shutdownNow();
    }
}

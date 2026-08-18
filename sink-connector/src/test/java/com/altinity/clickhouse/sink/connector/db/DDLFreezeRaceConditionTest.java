package com.altinity.clickhouse.sink.connector.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end concurrency test that reproduces the GitHub issue #1222 /
 * {@code price_usd} data-loss race and proves the per-table replication freeze
 * fixes it.
 *
 * <p><b>The model.</b> A "destination schema" set of columns is shared between:
 * <ul>
 *   <li>a DDL thread that runs {@code ADD COLUMN price_usd}: it sleeps to model
 *       the ALTER + {@code system.columns} propagation delay, then adds the
 *       column to the destination schema and invalidates the cache; and</li>
 *   <li>N insert threads that, for each row, read the destination schema (the
 *       "cache") and record whether {@code price_usd} would be written.</li>
 * </ul>
 *
 * <p>Every row's source event <i>contains</i> {@code price_usd} from the start
 * (the column was added on the source first). The destination is missing it
 * until the DDL thread finishes propagating. An insert that reads the
 * destination schema before propagation would omit the column = data loss.</p>
 *
 * <p><b>Without the freeze</b> the inserts that race ahead of propagation drop
 * the column. <b>With the freeze</b> every insert blocks until the DDL thread
 * unfreezes (after propagation + invalidation), so none drop it. The test
 * asserts both halves so the freeze is shown to be both necessary and
 * sufficient.</p>
 */
class DDLFreezeRaceConditionTest {

    private static final String TABLE = "trading_db.partner_liquidation_trade";
    private static final String NEW_COL = "price_usd";

    private TableReplicationFreezeManager freeze;

    /** Models the destination ClickHouse schema (what the cache would rebuild from). */
    private final Set<String> destinationSchema = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void setUp() {
        freeze = TableReplicationFreezeManager.getInstance();
        freeze.clearAll();
        destinationSchema.clear();
        // Initial destination schema: everything EXCEPT the not-yet-propagated column.
        destinationSchema.add("id");
        destinationSchema.add("price");
        destinationSchema.add("quantity");
    }

    @AfterEach
    void tearDown() {
        freeze.clearAll();
    }

    /**
     * Simulates one insert: reads the current destination schema and reports
     * whether the new column would be persisted for this row.
     */
    private boolean wouldPersistNewColumn() {
        // The insert path iterates the destination column set; if the column
        // isn't there, the source value is silently dropped.
        return destinationSchema.contains(NEW_COL);
    }

    @Test
    @DisplayName("REGRESSION GUARD: without the freeze, concurrent inserts drop the new column")
    void withoutFreezeDataIsLost() throws Exception {
        int insertThreads = 12;
        int rowsPerThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(insertThreads + 1);
        CountDownLatch ready = new CountDownLatch(insertThreads + 1);
        AtomicInteger droppedRows = new AtomicInteger(0);
        AtomicBoolean go = new AtomicBoolean(false);

        // DDL thread: no freeze. Delay models ALTER + system.columns propagation.
        pool.submit(() -> {
            ready.countDown();
            while (!go.get()) { Thread.onSpinWait(); }
            try {
                Thread.sleep(50); // propagation window during which inserts race
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            destinationSchema.add(NEW_COL);
        });

        for (int t = 0; t < insertThreads; t++) {
            pool.submit(() -> {
                ready.countDown();
                while (!go.get()) { Thread.onSpinWait(); }
                for (int r = 0; r < rowsPerThread; r++) {
                    if (!wouldPersistNewColumn()) {
                        droppedRows.incrementAndGet();
                    }
                    try {
                        Thread.sleep(0, 200_000); // ~0.2ms per row, spreads across the window
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        go.set(true);
        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));

        assertTrue(droppedRows.get() > 0,
                "Without the freeze, some inserts must have raced ahead of propagation "
                        + "and dropped the new column (reproduces issue #1222). dropped="
                        + droppedRows.get());
    }

    @Test
    @DisplayName("FIX: with the per-table freeze, zero inserts drop the new column")
    void withFreezeNoDataIsLost() throws Exception {
        int insertThreads = 12;
        int rowsPerThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(insertThreads + 1);
        CountDownLatch ready = new CountDownLatch(insertThreads + 1);
        AtomicInteger droppedRows = new AtomicInteger(0);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean go = new AtomicBoolean(false);
        // Debezium delivers the ALTER event before the subsequent INSERT events
        // for the same table, so the DDL freeze is always established before the
        // connector processes the post-ALTER inserts. This latch models that
        // ordering: insert threads do not begin until the freeze is in place.
        CountDownLatch frozen = new CountDownLatch(1);

        // DDL thread: freeze BEFORE the ALTER, propagate, invalidate, then unfreeze.
        pool.submit(() -> {
            ready.countDown();
            while (!go.get()) { Thread.onSpinWait(); }
            freeze.freeze(TABLE);
            frozen.countDown();
            try {
                Thread.sleep(50); // ALTER + propagation, while inserts are frozen
                destinationSchema.add(NEW_COL);
                CacheInvalidationManager.getInstance().invalidateTable(TABLE);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                freeze.unfreeze(TABLE);
            }
        });

        for (int t = 0; t < insertThreads; t++) {
            pool.submit(() -> {
                ready.countDown();
                while (!go.get()) { Thread.onSpinWait(); }
                try {
                    // Post-ALTER inserts are processed only after the freeze is set.
                    frozen.await();
                    for (int r = 0; r < rowsPerThread; r++) {
                        // Each batch waits for the table to be unfrozen first.
                        boolean unfrozen = freeze.awaitUnfrozen(TABLE, 5000);
                        if (!unfrozen) {
                            failure.compareAndSet(null,
                                    new AssertionError("await timed out while frozen"));
                            return;
                        }
                        if (!wouldPersistNewColumn()) {
                            droppedRows.incrementAndGet();
                        }
                        Thread.sleep(0, 200_000);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        assertTrue(ready.await(3, TimeUnit.SECONDS));
        go.set(true);
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        assertEquals(null, failure.get(), "No insert thread should fail");
        assertEquals(0, droppedRows.get(),
                "With the freeze, NO insert may drop the new column. dropped=" + droppedRows.get());
        // Sanity: the column did get added.
        assertTrue(destinationSchema.contains(NEW_COL));
    }

    @Test
    @DisplayName("Repeated freeze/unfreeze cycles never leak a frozen state")
    void repeatedCyclesAreClean() {
        for (int i = 0; i < 100; i++) {
            freeze.freeze(TABLE);
            assertTrue(freeze.isFrozen(TABLE));
            freeze.unfreeze(TABLE);
            assertTrue(freeze.awaitUnfrozen(TABLE, 100));
        }
    }
}

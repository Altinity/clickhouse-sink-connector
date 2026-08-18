package com.altinity.clickhouse.sink.connector.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the per-table schema cache TTL added to
 * {@link CacheInvalidationManager}. The TTL ensures the metadata cache is never
 * kept forever, so any missed schema change self-heals within one TTL window.
 */
class CacheInvalidationManagerTtlTest {

    private CacheInvalidationManager mgr;
    private long originalTtl;

    @BeforeEach
    void setUp() {
        mgr = CacheInvalidationManager.getInstance();
        originalTtl = mgr.getCacheTtlMs();
        mgr.clearAll();
    }

    @AfterEach
    void tearDown() {
        mgr.clearAll();
        mgr.setCacheTtlMs(originalTtl);
    }

    @Test
    @DisplayName("Default TTL is one hour")
    void defaultTtlIsOneHour() {
        assertEquals(60L * 60L * 1000L, CacheInvalidationManager.DEFAULT_CACHE_TTL_MS);
    }

    @Test
    @DisplayName("Never-built table is treated as expired (forces first build)")
    void unbuiltTableIsExpired() {
        mgr.setCacheTtlMs(60_000);
        assertTrue(mgr.isCacheExpired("db.fresh"));
    }

    @Test
    @DisplayName("Freshly built table is not expired before TTL elapses")
    void freshlyBuiltNotExpired() {
        mgr.setCacheTtlMs(60_000);
        mgr.markCacheBuilt("db.t");
        assertFalse(mgr.isCacheExpired("db.t"));
    }

    @Test
    @DisplayName("Cache expires once the TTL elapses")
    void expiresAfterTtl() throws Exception {
        mgr.setCacheTtlMs(150);
        mgr.markCacheBuilt("db.t");
        assertFalse(mgr.isCacheExpired("db.t"));
        Thread.sleep(250);
        assertTrue(mgr.isCacheExpired("db.t"), "Cache should be expired after the TTL window");
    }

    @Test
    @DisplayName("Rebuild resets the TTL clock")
    void rebuildResetsClock() throws Exception {
        mgr.setCacheTtlMs(300);
        mgr.markCacheBuilt("db.t");
        Thread.sleep(200);
        // Rebuild before expiry -> clock resets.
        mgr.markCacheBuilt("db.t");
        Thread.sleep(200);
        assertFalse(mgr.isCacheExpired("db.t"), "Total 400ms but reset at 200ms -> not expired");
    }

    @Test
    @DisplayName("TTL <= 0 disables expiry (DDL-triggered invalidation still works)")
    void ttlDisabled() {
        mgr.setCacheTtlMs(0);
        // Never-built normally counts as expired, but expiry is disabled.
        assertFalse(mgr.isCacheExpired("db.t"));
        // DDL invalidation path is unaffected. shouldInvalidate() was replaced
        // by a monotonic generation counter (the old remove-on-read Set let the
        // FIRST worker thread consume the signal, leaving every other thread
        // serving a stale writer), so the equivalent assertion is that the
        // generation advances.
        long before = mgr.currentGeneration("db.t");
        mgr.invalidateTable("db.t");
        assertNotEquals(before, mgr.currentGeneration("db.t"));
    }

    @Test
    @DisplayName("DDL invalidation and TTL are independent signals")
    void invalidationIndependentOfTtl() {
        mgr.setCacheTtlMs(60_000);
        mgr.markCacheBuilt("db.t");
        assertFalse(mgr.isCacheExpired("db.t"));
        long before = mgr.currentGeneration("db.t");
        mgr.invalidateTable("db.t");
        assertNotEquals(before, mgr.currentGeneration("db.t"),
                "DDL invalidation fires even when TTL is not expired");
    }
}

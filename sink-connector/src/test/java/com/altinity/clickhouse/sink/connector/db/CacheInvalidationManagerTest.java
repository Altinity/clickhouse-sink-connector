package com.altinity.clickhouse.sink.connector.db;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for {@link CacheInvalidationManager}.
 *
 * <p>The connector runs a pool of worker threads, each holding its own
 * topicToDbWriterMap cache. The previous remove-on-read implementation let the
 * FIRST reader consume the invalidation signal, so every other thread kept
 * serving a DbWriter built against the pre-DDL column list. Those writers build
 * the INSERT column list from a snapshot taken at construction, so the newly
 * added column was silently dropped from every subsequent INSERT and ClickHouse
 * diverged from MySQL permanently.</p>
 */
public class CacheInvalidationManagerTest {

    private static final String TABLE = "testdb.orders";

    @BeforeEach
    public void reset() {
        CacheInvalidationManager.getInstance().clearAll();
    }

    @Test
    public void generationStartsAtZeroForUnknownTable() {
        Assertions.assertEquals(0L,
                CacheInvalidationManager.getInstance().currentGeneration(TABLE));
    }

    @Test
    public void invalidationIsVisibleToEveryConsumerNotJustTheFirst() {
        CacheInvalidationManager manager = CacheInvalidationManager.getInstance();

        // Two worker threads each cached a DbWriter at generation 0.
        long threadOneCachedAt = manager.currentGeneration(TABLE);
        long threadTwoCachedAt = manager.currentGeneration(TABLE);

        // ALTER TABLE ... ADD COLUMN arrives.
        manager.invalidateTable(TABLE);

        long generationAfterDdl = manager.currentGeneration(TABLE);

        // BOTH threads must observe that their cached writer is stale. Under the
        // old remove-on-read behaviour only the first caller saw the signal.
        Assertions.assertNotEquals(threadOneCachedAt, generationAfterDdl,
                "first worker thread must rebuild its DbWriter after DDL");
        Assertions.assertNotEquals(threadTwoCachedAt, generationAfterDdl,
                "second worker thread must ALSO rebuild its DbWriter after DDL");
    }

    @Test
    public void generationIsStableWhenNoDdlOccurs() {
        CacheInvalidationManager manager = CacheInvalidationManager.getInstance();
        manager.invalidateTable(TABLE);

        long cachedAt = manager.currentGeneration(TABLE);

        // No further DDL: repeated reads must not invalidate the cache, otherwise
        // every insert would rebuild the writer and re-query the schema.
        Assertions.assertEquals(cachedAt, manager.currentGeneration(TABLE));
        Assertions.assertEquals(cachedAt, manager.currentGeneration(TABLE));
    }

    @Test
    public void successiveDdlsProduceDistinctGenerations() {
        CacheInvalidationManager manager = CacheInvalidationManager.getInstance();

        manager.invalidateTable(TABLE);
        long afterFirst = manager.currentGeneration(TABLE);
        manager.invalidateTable(TABLE);
        long afterSecond = manager.currentGeneration(TABLE);

        Assertions.assertNotEquals(afterFirst, afterSecond,
                "a second DDL must invalidate caches rebuilt after the first");
    }

    @Test
    public void tablesAreTrackedIndependently() {
        CacheInvalidationManager manager = CacheInvalidationManager.getInstance();

        long otherCachedAt = manager.currentGeneration("testdb.customers");
        manager.invalidateTable(TABLE);

        Assertions.assertEquals(otherCachedAt,
                manager.currentGeneration("testdb.customers"),
                "DDL on one table must not invalidate caches for another");
    }

    @Test
    public void nullAndEmptyNamesAreIgnored() {
        CacheInvalidationManager manager = CacheInvalidationManager.getInstance();

        manager.invalidateTable(null);
        manager.invalidateTable("");

        Assertions.assertEquals(0L, manager.currentGeneration(null));
        Assertions.assertEquals(0L, manager.currentGeneration(""));
        Assertions.assertEquals(0, manager.pendingInvalidations());
    }
}

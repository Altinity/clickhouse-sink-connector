package com.altinity.clickhouse.sink.connector.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 08.02 section 3 and its Failure Modes section: the version counter
 * arithmetic of {@link CacheInvalidationManager}, independent of the
 * proven-absent tracking of spec 08.03.
 *
 * <p>A writer rebuilds when {@code getVersion(table)} differs from the value it
 * cached (ClickHouseBatchRunnable#getDbWriterForTable compares with
 * {@code ==}). The fold {@code globalEpoch + tableVersion} is only safe if no
 * sequence of invalidations can ever bring a table's version back to a value a
 * stale writer cached (an ABA): the stale writer would then keep binding
 * against a schema a DDL changed. Both terms only ever increase, so the sum is
 * strictly monotone per invalidation that touches the table -- pinned here
 * over a long random interleaving.</p>
 */
public class CacheInvalidationVersionArithmeticTest {

    private final CacheInvalidationManager manager = CacheInvalidationManager.getInstance();

    @BeforeEach
    public void reset() {
        manager.clearAll();
    }

    @Test
    @DisplayName("getVersion is strictly monotone for every invalidation that reaches the table (no ABA)")
    public void versionIsStrictlyMonotoneUnderAnyInterleaving() {
        String[] tables = {"db.a", "db.b", "db.c"};
        long[] last = new long[tables.length];
        for (int i = 0; i < tables.length; i++) {
            last[i] = manager.getVersion(tables[i]);
        }
        Random random = new Random(0x1515L);
        for (int step = 0; step < 10_000; step++) {
            boolean global = random.nextInt(5) == 0;
            int target = random.nextInt(tables.length);
            if (global) {
                manager.invalidateAll();
            } else {
                manager.invalidateTable(tables[target]);
            }
            for (int i = 0; i < tables.length; i++) {
                long now = manager.getVersion(tables[i]);
                boolean reached = global || i == target;
                if (reached) {
                    assertTrue(now > last[i], "step " + step + ": an invalidation that reaches "
                            + tables[i] + " must move its version strictly up (" + last[i] + " -> " + now
                            + "); a repeat of an older value lets a stale writer pass the == check");
                } else {
                    assertEquals(last[i], now, "step " + step + ": invalidating another table must "
                            + "not move " + tables[i] + " (that would only cost a rebuild, but pins scope)");
                }
                last[i] = now;
            }
        }
    }

    @Test
    @DisplayName("invalidateAll moves the version of a table that has no entry of its own")
    public void invalidateAllReachesATableThatWasNeverInvalidated() {
        long before = manager.getVersion("db.never_seen");
        assertEquals(0L, before, "a table never invalidated reads version 0");
        manager.invalidateAll();
        assertEquals(1L, manager.getVersion("db.never_seen"),
                "the epoch is folded into every table's version, so a global sweep reaches it");
        assertEquals(0, manager.pendingInvalidations(),
                "a global sweep allocates no per-table entry: bookkeeping stays bounded by the tables DDL touched");
    }

    @Test
    @DisplayName("a table's own invalidations and the epoch add up")
    public void tableVersionAndEpochAreSummed() {
        manager.invalidateTable("db.t");
        manager.invalidateTable("db.t");
        manager.invalidateAll();
        assertEquals(3L, manager.getVersion("db.t"));
        assertEquals(1L, manager.getVersion("db.other"));
    }

    @Test
    @DisplayName("null and empty table names read 0 and are never recorded")
    public void nullAndEmptyNamesReadZeroAndAreInert() {
        manager.invalidateTable(null);
        manager.invalidateTable("");
        assertEquals(0, manager.pendingInvalidations());
        manager.invalidateAll();
        assertEquals(0L, manager.getVersion(null), "a null name is not a table and never reports staleness");
        assertEquals(0L, manager.getVersion(""));
    }
}

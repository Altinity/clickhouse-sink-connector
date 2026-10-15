package com.altinity.clickhouse.sink.connector.db;

import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Guards the proven-absent bookkeeping that stops a MATERIALIZED (or ALIAS)
 * column from driving an unbounded {@code system.columns} re-read loop.
 *
 * <p>{@code GroupInsertQueryWithBatchRecords#refreshIfRecordHasUnknownColumn}
 * treats "the record carries a column the cached map does not have" as proof
 * the cache is stale, and re-reads table metadata. That inference is wrong for
 * a column ClickHouse computes: {@code DBMetadata#getColumnsDataTypesForTable}
 * filters {@code MATERIALIZED} and {@code ALIAS} columns out by design (they
 * cannot be bound in an INSERT), so the re-read returns a map that still lacks
 * the column and the next record repeats the whole cycle -- while each
 * iteration also bumps the table's invalidation version, forcing every cached
 * writer to rebuild.</p>
 *
 * <p>The fix records that a fresh read proved the column absent, scoped to the
 * table version the proof was taken at, so the probe costs one metadata query
 * per computed column per DDL generation instead of one per record.</p>
 */
public class CacheInvalidationProvenAbsentTest {

    private static final String TABLE = "sales.orders";

    private CacheInvalidationManager manager;

    @BeforeEach
    public void reset() {
        manager = CacheInvalidationManager.getInstance();
        manager.clearAll();
    }

    /** A column not yet probed must not be reported as proven absent. */
    @Test
    public void testUnprobedColumnIsNotProvenAbsent() {
        Assert.assertFalse(manager.isColumnProvenAbsent(TABLE, "total_with_tax"));
    }

    /**
     * The whole point: once a fresh read has proven the column absent, the
     * caller must be told so, so it stops re-reading metadata per record.
     */
    @Test
    public void testProvenAbsentColumnIsRemembered() {
        manager.markColumnProvenAbsent(TABLE, "total_with_tax");
        Assert.assertTrue(manager.isColumnProvenAbsent(TABLE, "total_with_tax"));
    }

    /** Column names are compared case-insensitively, as they are everywhere else. */
    @Test
    public void testProvenAbsentIsCaseInsensitive() {
        manager.markColumnProvenAbsent(TABLE, "Total_With_Tax");
        Assert.assertTrue(manager.isColumnProvenAbsent(TABLE, "total_with_tax"));
        Assert.assertTrue(manager.isColumnProvenAbsent(TABLE, "TOTAL_WITH_TAX"));
    }

    /** The proof is scoped to its table; a sibling table is unaffected. */
    @Test
    public void testProvenAbsentIsScopedToItsTable() {
        manager.markColumnProvenAbsent(TABLE, "total_with_tax");
        Assert.assertFalse(manager.isColumnProvenAbsent(
                "archive.orders", "total_with_tax"));
    }

    /**
     * A real DDL bumps the table version, which must discard the proof: the
     * column may have been redefined as an ordinary one that a re-read would
     * now find. Caching the "absent" answer across a schema change would
     * reintroduce the silent column-drop this mechanism exists to prevent.
     */
    @Test
    public void testDdlInvalidatesTheProof() {
        manager.markColumnProvenAbsent(TABLE, "total_with_tax");
        Assert.assertTrue(manager.isColumnProvenAbsent(TABLE, "total_with_tax"));

        manager.invalidateTable(TABLE);

        Assert.assertFalse("a DDL must force the column to be probed again",
                manager.isColumnProvenAbsent(TABLE, "total_with_tax"));
    }

    /** invalidateAll() is the fail-safe sweep and must discard proofs too. */
    @Test
    public void testInvalidateAllInvalidatesTheProof() {
        manager.markColumnProvenAbsent(TABLE, "total_with_tax");
        Assert.assertTrue(manager.isColumnProvenAbsent(TABLE, "total_with_tax"));

        manager.invalidateAll();

        Assert.assertFalse("a global sweep must force the column to be probed again",
                manager.isColumnProvenAbsent(TABLE, "total_with_tax"));
    }

    /**
     * After the DDL that discarded a proof, a fresh proof taken at the new
     * version must stick -- otherwise the storm simply resumes post-DDL.
     */
    @Test
    public void testProofRetakenAfterDdlSticks() {
        manager.markColumnProvenAbsent(TABLE, "total_with_tax");
        manager.invalidateTable(TABLE);
        Assert.assertFalse(manager.isColumnProvenAbsent(TABLE, "total_with_tax"));

        manager.markColumnProvenAbsent(TABLE, "total_with_tax");
        Assert.assertTrue(manager.isColumnProvenAbsent(TABLE, "total_with_tax"));
    }

    /** Null and empty inputs are inert rather than throwing on the hot path. */
    @Test
    public void testNullAndEmptyInputsAreInert() {
        manager.markColumnProvenAbsent(null, "c");
        manager.markColumnProvenAbsent(TABLE, null);
        manager.markColumnProvenAbsent("", "c");
        manager.markColumnProvenAbsent(TABLE, "");

        Assert.assertFalse(manager.isColumnProvenAbsent(null, "c"));
        Assert.assertFalse(manager.isColumnProvenAbsent(TABLE, null));
        Assert.assertFalse(manager.isColumnProvenAbsent("", "c"));
        Assert.assertFalse(manager.isColumnProvenAbsent(TABLE, ""));
    }
}

package com.altinity.clickhouse.sink.connector.db.batch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PreparedStatementFieldMapper#REPORTED_NULL_SCHEMA_MISMATCH_COLUMNS} is a
 * {@code static}, process-lifetime, JVM-wide dedup cache keyed by every distinct
 * {@code database.table.column} that has ever been reported as a NULL-into-a-
 * non-nullable-column schema mismatch (Spec 07.07 section 3.2.3). Nothing else in
 * the connector clears it, so without a cap it grows by one entry per distinct
 * mismatched column for as long as the JVM runs -- memory proportional to DDL /
 * schema-drift volume, never bounded in bytes, exactly the class of structure the
 * memory-bound duty (2026-09-26) requires every such cache to cap.
 *
 * <p>This test adds more distinct keys than the cap allows, directly (the
 * field is package-private so the test does not need a full insert path) and
 * asserts the set never exceeds
 * {@link PreparedStatementFieldMapper#MAX_REPORTED_NULL_SCHEMA_MISMATCH_COLUMNS}
 * and evicts the oldest entry first (LRU by insertion order).</p>
 */
public class NullSchemaMismatchBoundTest {

    @AfterEach
    void clearSharedState() {
        PreparedStatementFieldMapper.REPORTED_NULL_SCHEMA_MISMATCH_COLUMNS.clear();
    }

    @Test
    @DisplayName("REPORTED_NULL_SCHEMA_MISMATCH_COLUMNS never exceeds its cap")
    void doesNotGrowPastTheCap() {
        int cap = PreparedStatementFieldMapper.MAX_REPORTED_NULL_SCHEMA_MISMATCH_COLUMNS;
        int distinctColumnsSeen = cap + 500;

        for (int i = 0; i < distinctColumnsSeen; i++) {
            PreparedStatementFieldMapper.REPORTED_NULL_SCHEMA_MISMATCH_COLUMNS.add("db.table.col_" + i);
        }

        assertTrue(PreparedStatementFieldMapper.REPORTED_NULL_SCHEMA_MISMATCH_COLUMNS.size() <= cap,
                "the set must never hold more than " + cap + " entries, held "
                        + PreparedStatementFieldMapper.REPORTED_NULL_SCHEMA_MISMATCH_COLUMNS.size());
    }

    @Test
    @DisplayName("the oldest reported column is evicted first once the cap is exceeded")
    void evictsOldestFirst() {
        int cap = PreparedStatementFieldMapper.MAX_REPORTED_NULL_SCHEMA_MISMATCH_COLUMNS;

        String firstKey = "db.table.col_first";
        PreparedStatementFieldMapper.REPORTED_NULL_SCHEMA_MISMATCH_COLUMNS.add(firstKey);
        for (int i = 0; i < cap; i++) {
            PreparedStatementFieldMapper.REPORTED_NULL_SCHEMA_MISMATCH_COLUMNS.add("db.table.col_" + i);
        }

        assertFalse(PreparedStatementFieldMapper.REPORTED_NULL_SCHEMA_MISMATCH_COLUMNS.contains(firstKey),
                "the first-inserted key should have been evicted once the cap was exceeded");
    }
}

package com.altinity.clickhouse.sink.connector.db.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Distinguishes the two very different reasons a column can be missing from
 * the prepared-statement index map.
 *
 * <p>{@code QueryFormatter} deliberately hardcodes the bitemporal metadata
 * columns as SQL literals and records no bind index for them, so their absence
 * is by design. Every other missing column is a real defect: the mapper
 * {@code continue}s past it, and the downstream handlers are guarded by the
 * same map, so nothing ever binds the value and it is silently dropped from
 * the INSERT.</p>
 *
 * <p>Logging both cases at ERROR made the real defect invisible -- a
 * production history-mode connector emitted ~22,800 of these lines, all for
 * the by-design columns, on a ten-second heartbeat.</p>
 */
public class PreparedStatementFieldMapperUnboundColumnTest {

    @Test
    @DisplayName("Bitemporal metadata columns are unbound by design")
    public void testMetadataColumnsAreByDesign() {
        assertTrue(PreparedStatementFieldMapper.isUnboundByDesign("_version"));
        assertTrue(PreparedStatementFieldMapper.isUnboundByDesign("is_deleted"));
        assertTrue(PreparedStatementFieldMapper.isUnboundByDesign("_operation"));
        assertTrue(PreparedStatementFieldMapper.isUnboundByDesign("_sign"));
    }

    @Test
    @DisplayName("Matching is case-insensitive, as the column lookup is")
    public void testCaseInsensitive() {
        assertTrue(PreparedStatementFieldMapper.isUnboundByDesign("_VERSION"));
        assertTrue(PreparedStatementFieldMapper.isUnboundByDesign("Is_Deleted"));
        assertTrue(PreparedStatementFieldMapper.isUnboundByDesign("_OpErAtIoN"));
    }

    @Test
    @DisplayName("A real data column is NOT by design -- its value would be silently dropped")
    public void testDataColumnIsNotByDesign() {
        assertFalse(PreparedStatementFieldMapper.isUnboundByDesign("comment"),
                "'comment' is a source data column; a missing placeholder means the value is never written");
        assertFalse(PreparedStatementFieldMapper.isUnboundByDesign("id"));
        assertFalse(PreparedStatementFieldMapper.isUnboundByDesign("appkey"));
    }

    @Test
    @DisplayName("The temporal columns ARE bound as parameters and must stay classified as real")
    public void testTemporalColumnsAreBoundParameters() {
        // _valid_from / _valid_to are bound via toDateTime(?, tz) in the
        // second SELECT, so they DO get an index. If they ever go missing that
        // is a genuine defect and must not be silenced.
        assertFalse(PreparedStatementFieldMapper.isUnboundByDesign("_valid_from"));
        assertFalse(PreparedStatementFieldMapper.isUnboundByDesign("_valid_to"));
    }

    @Test
    @DisplayName("Null and empty names are not by design")
    public void testNullSafety() {
        assertFalse(PreparedStatementFieldMapper.isUnboundByDesign(null));
        assertFalse(PreparedStatementFieldMapper.isUnboundByDesign(""));
    }
}

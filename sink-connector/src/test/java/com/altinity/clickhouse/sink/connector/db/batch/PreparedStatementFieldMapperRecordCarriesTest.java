package com.altinity.clickhouse.sink.connector.db.batch;

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Separates the two reasons a column can lack a bind placeholder.
 *
 * <p>A column the change event does not carry is left out of the INSERT on
 * purpose, so ClickHouse applies its DEFAULT -- that is correct behaviour for a
 * pre-ALTER record, or for a NULL column the source event omits. A column the
 * event DOES carry but that has no placeholder is a real defect: nothing binds
 * it and the value never reaches ClickHouse.</p>
 *
 * <p>Logging both at ERROR made the harmful case invisible. A history-mode run
 * emitted 15 errors for a {@code comment} column that was simply NULL at the
 * source, which is exactly the noise that hides a genuine drop.</p>
 */
public class PreparedStatementFieldMapperRecordCarriesTest {

    private List<Field> fields(String... names) {
        List<Field> out = new ArrayList<>();
        int i = 0;
        for (String n : names) {
            out.add(new Field(n, i++, Schema.OPTIONAL_STRING_SCHEMA));
        }
        return out;
    }

    @Test
    @DisplayName("A column the record carries is reported as carried")
    public void testCarried() {
        List<Field> f = fields("id", "appkey", "comment");
        assertTrue(PreparedStatementFieldMapper.recordCarries(f, "comment"));
        assertTrue(PreparedStatementFieldMapper.recordCarries(f, "id"));
    }

    @Test
    @DisplayName("A column absent from the record is not carried -- DEFAULT applies, not an error")
    public void testAbsent() {
        List<Field> f = fields("id", "appkey");
        assertFalse(PreparedStatementFieldMapper.recordCarries(f, "comment"),
                "comment is NULL at source and omitted from the event; ClickHouse DEFAULT applies");
        assertFalse(PreparedStatementFieldMapper.recordCarries(f, "added_by_later_alter"));
    }

    @Test
    @DisplayName("Matching is case-insensitive, as the column lookup is")
    public void testCaseInsensitive() {
        List<Field> f = fields("Comment", "ID");
        assertTrue(PreparedStatementFieldMapper.recordCarries(f, "comment"));
        assertTrue(PreparedStatementFieldMapper.recordCarries(f, "id"));
    }

    @Test
    @DisplayName("Null and empty inputs are safe")
    public void testNullSafety() {
        assertFalse(PreparedStatementFieldMapper.recordCarries(null, "comment"));
        assertFalse(PreparedStatementFieldMapper.recordCarries(fields("id"), null));
        assertFalse(PreparedStatementFieldMapper.recordCarries(new ArrayList<>(), "id"));
        assertFalse(PreparedStatementFieldMapper.recordCarries(Arrays.asList((Field) null), "id"),
                "a null entry must not throw");
    }

    @Test
    @DisplayName("The by-design metadata columns remain classified separately")
    public void testByDesignStillDistinct() {
        // These are never carried by a source record, but they are also never
        // an error: QueryFormatter emits them as SQL literals.
        List<Field> f = fields("id");
        assertFalse(PreparedStatementFieldMapper.recordCarries(f, "_version"));
        assertTrue(PreparedStatementFieldMapper.isUnboundByDesign("_version"),
                "_version must be caught by the by-design branch before the carried check");
        assertTrue(PreparedStatementFieldMapper.isUnboundByDesign("_operation"));
    }
}

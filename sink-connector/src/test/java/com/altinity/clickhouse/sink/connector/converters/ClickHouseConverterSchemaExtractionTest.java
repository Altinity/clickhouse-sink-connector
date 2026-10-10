package com.altinity.clickhouse.sink.connector.converters;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Spec 10.04 section 3.9: a record whose own schema cannot be read is a
 * genuine failure, not a legitimate no-row case (a heartbeat / tombstone /
 * transaction-metadata record with no value schema, or a row with no
 * populated after/before image). {@code extractDebeziumSchema} must let such
 * a failure propagate instead of catching it and returning {@code null}: a
 * source column the caller never saw would otherwise be written to
 * ClickHouse without a value -- divergence, not a condition to log and
 * continue past.
 */
public class ClickHouseConverterSchemaExtractionTest {

    /** A record whose value schema cannot be read. */
    private static SourceRecord unreadableRecord() {
        return new SourceRecord(null, null, "pg.public.orders", null, null) {
            @Override
            public Schema valueSchema() {
                throw new IllegalStateException("corrupt envelope");
            }
        };
    }

    @Test
    @DisplayName("10.04 s3.9: an unreadable record schema propagates instead of being caught and logged")
    public void unreadableSchemaPropagates() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> ClickHouseConverter.extractDebeziumSchema(unreadableRecord()));
        assertEquals("corrupt envelope", thrown.getMessage());
    }

    @Test
    @DisplayName("10.04 s3.9: a null value schema (control record) is a legitimate no-row case, returns null")
    public void nullValueSchemaReturnsNullWithoutThrowing() {
        SourceRecord record = new SourceRecord(null, null, "pg.public.orders", null, null, null, null);
        assertSame(null, ClickHouseConverter.extractDebeziumSchema(record));
    }
}

package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import io.debezium.engine.ChangeEvent;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Spec 03.01 section 3.4: the error logger must not throw for a record that
 * carries no Debezium source record.
 *
 * <p>Only the embedded runtime attaches a {@code ChangeEvent} to the batch
 * record; a record built from a Kafka {@code SinkRecord} has none. With
 * {@code error.logging.enable=true} the Kafka-mode runnable therefore threw
 * {@code NullPointerException} from inside {@code logErrorToClickHouse} --
 * replacing the ClickHouse error that was being reported and skipping its
 * classification.</p>
 */
public class ClickHouseBatchRunnableErrorLoggerTest {

    @Test
    @DisplayName("A Kafka-mode record (no source record attached) yields null, not an exception")
    public void sourceRecordIsNullForKafkaModeRecord() {
        ClickHouseStruct kafkaModeRecord = new ClickHouseStruct(
                5L, "db1.orders", null, 0, System.currentTimeMillis(),
                null, null, null, ClickHouseConverter.CDC_OPERATION.CREATE);

        assertNull(ClickHouseBatchRunnable.sourceRecordOrNull(kafkaModeRecord));
        assertNull(ClickHouseBatchRunnable.sourceRecordOrNull(null));
    }

    @Test
    @DisplayName("An embedded-runtime record yields the SourceRecord behind its ChangeEvent")
    public void sourceRecordIsUnwrappedWhenPresent() {
        SourceRecord source = new SourceRecord(Collections.singletonMap("server", "db1"),
                Collections.singletonMap("pos", 42L), "db1.orders", null, null);
        ChangeEvent<SourceRecord, SourceRecord> event = new ChangeEvent<SourceRecord, SourceRecord>() {
            @Override
            public SourceRecord key() {
                return null;
            }

            @Override
            public SourceRecord value() {
                return source;
            }

            @Override
            public String destination() {
                return source.topic();
            }

            @Override
            public Integer partition() {
                return null;
            }
        };
        ClickHouseStruct record = new ClickHouseStruct();
        record.setSourceRecord(event);

        assertSame(source, ClickHouseBatchRunnable.sourceRecordOrNull(record));
    }
}

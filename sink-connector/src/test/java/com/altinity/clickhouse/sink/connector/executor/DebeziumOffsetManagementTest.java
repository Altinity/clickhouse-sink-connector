package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.executor.DebeziumOffsetManagement;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.Assert;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class DebeziumOffsetManagementTest {

    /** Collects everything the class under test logs during one call. */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

        CapturingAppender() {
            super("capture-offset-management", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    /**
     * Spec 03.06 section 3.3 line 4: the per-unit "BATCH marked as processed"
     * line is INFO by design -- it is how an operator sees from the log that
     * offsets are being acknowledged. A revision moved it to DEBUG for volume;
     * the operators reversed that.
     */
    @Test
    @DisplayName("Acknowledging a unit logs the 'BATCH marked as processed' line at INFO, nothing at WARN or above")
    public void acknowledgementIsLoggedAtInfo() throws InterruptedException {
        OffsetTestSupport.RecordingCommitter committer = new OffsetTestSupport.RecordingCommitter();
        List<ClickHouseStruct> unit = OffsetTestSupport.unit(committer, 1L, "orders");

        Logger coreLogger = (Logger) LogManager.getLogger(DebeziumOffsetManagement.class);
        Level savedLevel = coreLogger.getLevel();
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        coreLogger.addAppender(appender);
        Configurator.setLevel(coreLogger.getName(), Level.INFO);
        try {
            DebeziumOffsetManagement.acknowledgeRecords(unit);
        } finally {
            Configurator.setLevel(coreLogger.getName(), savedLevel);
            coreLogger.removeAppender(appender);
            appender.stop();
        }

        Assert.assertEquals("the unit's offset is still acknowledged", 1, committer.batchesFinished);
        List<LogEvent> events = new ArrayList<>(appender.events);
        List<String> all = events.stream()
                .map(e -> e.getLevel() + ": " + e.getMessage().getFormattedMessage())
                .collect(Collectors.toList());
        Assertions.assertTrue(events.stream().anyMatch(e -> e.getLevel() == Level.INFO
                        && e.getMessage().getFormattedMessage().contains("BATCH marked as processed")),
                "the acknowledgement line must be logged at INFO: " + all);
        List<String> warnAndAbove = events.stream()
                .filter(e -> e.getLevel().isMoreSpecificThan(Level.WARN))
                .map(e -> e.getLevel() + ": " + e.getMessage().getFormattedMessage())
                .collect(Collectors.toList());
        Assertions.assertEquals(Collections.emptyList(), warnAndAbove,
                "an acknowledged unit must not log at WARN or above (spec 03.06 section 3.3)");
    }

    /**
     * Fake {@link DebeziumEngine.RecordCommitter} that records how many times
     * its methods were invoked and, optionally, throws a supplied error from
     * {@code markBatchFinished()} so we can verify that exceptions propagate.
     */
    private static class FakeRecordCommitter
            implements DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> {

        int markBatchFinishedCalls = 0;
        int markProcessedCalls = 0;

        private final RuntimeException errorToThrow;

        FakeRecordCommitter() {
            this(null);
        }

        FakeRecordCommitter(RuntimeException errorToThrow) {
            this.errorToThrow = errorToThrow;
        }

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record) {
            markProcessedCalls++;
        }

        @Override
        public void markBatchFinished() {
            markBatchFinishedCalls++;
            if (errorToThrow != null) {
                throw errorToThrow;
            }
        }

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record,
                                  DebeziumEngine.Offsets sourceOffsets) {
            markProcessedCalls++;
        }

        @Override
        public DebeziumEngine.Offsets buildOffsets() {
            return (key, value) -> { };
        }
    }

    /**
     * Minimal non-null {@link ChangeEvent} so acknowledgeRecords proceeds to
     * mark the record processed and finish the batch.
     */
    private static ChangeEvent<SourceRecord, SourceRecord> dummyChangeEvent() {
        return new ChangeEvent<SourceRecord, SourceRecord>() {
            @Override
            public SourceRecord key() {
                return null;
            }

            @Override
            public SourceRecord value() {
                return null;
            }

            @Override
            public String destination() {
                return null;
            }

            @Override
            public Integer partition() {
                return null;
            }
        };
    }

    @Test
    public void testAcknowledgeMarksBatchFinishedOnce() throws InterruptedException {
        // Happy path: the last record in the batch is processed and the batch
        // is finished exactly once (serialized on OFFSET_COMMIT_LOCK).
        FakeRecordCommitter committer = new FakeRecordCommitter();

        DebeziumOffsetManagement.acknowledgeRecords(committer, dummyChangeEvent(), true);

        Assert.assertEquals(1, committer.markProcessedCalls);
        Assert.assertEquals(1, committer.markBatchFinishedCalls);
    }

    @Test
    public void testAcknowledgeDoesNotFinishBatchWhenNotLastRecord() throws InterruptedException {
        // Not the last record: process it but do not finish/flush the batch.
        FakeRecordCommitter committer = new FakeRecordCommitter();

        DebeziumOffsetManagement.acknowledgeRecords(committer, dummyChangeEvent(), false);

        Assert.assertEquals(1, committer.markProcessedCalls);
        Assert.assertEquals(0, committer.markBatchFinishedCalls);
    }

    @Test
    public void testAcknowledgePropagatesCommitError() {
        // We no longer inspect the exception message: any error from
        // markBatchFinished propagates and is handled by the caller's
        // existing retriable-error path.
        RuntimeException error = new ConnectException("OffsetStorageWriter is already flushing");
        FakeRecordCommitter committer = new FakeRecordCommitter(error);

        Assertions.assertThrows(ConnectException.class, () ->
                DebeziumOffsetManagement.acknowledgeRecords(committer, dummyChangeEvent(), true));

        Assert.assertEquals(1, committer.markBatchFinishedCalls);
    }

    public static Struct getKafkaStruct() {
        Schema kafkaConnectSchema = SchemaBuilder
                .struct()
                .field("first_name", Schema.STRING_SCHEMA)
                .field("last_name", Schema.STRING_SCHEMA)
                .field("quantity", Schema.INT32_SCHEMA)
                .field("amount", Schema.FLOAT64_SCHEMA)
                .field("employed", Schema.BOOLEAN_SCHEMA)
                .build();

        Struct kafkaConnectStruct = new Struct(kafkaConnectSchema);
        kafkaConnectStruct.put("first_name", "John");
        kafkaConnectStruct.put("last_name", "Doe");
        kafkaConnectStruct.put("quantity", 100);
        kafkaConnectStruct.put("amount", 23.223);
        kafkaConnectStruct.put("employed", true);


        return kafkaConnectStruct;
    }
}

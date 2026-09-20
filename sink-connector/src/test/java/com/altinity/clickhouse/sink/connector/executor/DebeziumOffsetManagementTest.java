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
import org.junit.Assert;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

public class DebeziumOffsetManagementTest {

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

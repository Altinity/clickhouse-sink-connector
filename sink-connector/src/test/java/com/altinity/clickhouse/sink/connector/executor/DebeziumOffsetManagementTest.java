package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.executor.DebeziumOffsetManagement;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.Assert;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DebeziumOffsetManagementTest {

    /**
     * Fake {@link DebeziumEngine.RecordCommitter} whose {@code markBatchFinished()}
     * throws a configurable number of "OffsetStorageWriter is already flushing"
     * errors before succeeding, and records how many times it was invoked.
     */
    private static class FakeRecordCommitter
            implements DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> {

        int markBatchFinishedCalls = 0;
        int markProcessedCalls = 0;

        private int flushingErrorsToThrow;
        private final boolean alwaysThrowFlushing;
        private final RuntimeException nonFlushingError;

        FakeRecordCommitter(int flushingErrorsToThrow, boolean alwaysThrowFlushing,
                            RuntimeException nonFlushingError) {
            this.flushingErrorsToThrow = flushingErrorsToThrow;
            this.alwaysThrowFlushing = alwaysThrowFlushing;
            this.nonFlushingError = nonFlushingError;
        }

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record) {
            markProcessedCalls++;
        }

        @Override
        public void markBatchFinished() {
            markBatchFinishedCalls++;
            if (nonFlushingError != null) {
                throw nonFlushingError;
            }
            if (alwaysThrowFlushing || flushingErrorsToThrow > 0) {
                flushingErrorsToThrow--;
                throw new ConnectException("OffsetStorageWriter is already flushing");
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
    public void testAcknowledgeRetriesWhenAlreadyFlushing() throws InterruptedException {
        // First two flushes collide with an in-progress flush, then it succeeds.
        FakeRecordCommitter committer = new FakeRecordCommitter(2, false, null);

        DebeziumOffsetManagement.acknowledgeRecords(committer, dummyChangeEvent(), true);

        // 2 failed attempts + 1 successful attempt.
        Assert.assertEquals(3, committer.markBatchFinishedCalls);
        Assert.assertEquals(1, committer.markProcessedCalls);
    }

    @Test
    public void testAcknowledgePersistentFlushingIsDowngraded() throws InterruptedException {
        // OffsetStorageWriter is always busy flushing.
        FakeRecordCommitter committer = new FakeRecordCommitter(0, true, null);

        // Should NOT throw: the collision is downgraded and the offsets will be
        // committed on a subsequent flush.
        DebeziumOffsetManagement.acknowledgeRecords(committer, dummyChangeEvent(), true);

        // Bounded retry: markBatchFinished attempted MAX_FLUSH_RETRIES (5) times.
        Assert.assertEquals(5, committer.markBatchFinishedCalls);
    }

    @Test
    public void testAcknowledgePropagatesNonFlushingError() {
        // A genuine ConnectException (not the flushing collision) must surface.
        RuntimeException realError = new ConnectException("Some real offset error");
        FakeRecordCommitter committer = new FakeRecordCommitter(0, false, realError);

        Assertions.assertThrows(ConnectException.class, () ->
                DebeziumOffsetManagement.acknowledgeRecords(committer, dummyChangeEvent(), true));

        // No retry for a non-flushing error.
        Assert.assertEquals(1, committer.markBatchFinishedCalls);
    }

    // Test function to validate the isWithinRange function
    @Test
    public void testIsWithinRange() {

        // Min and Max values for this batch - 3 and 433
        List<ClickHouseStruct> clickHouseStructs = new ArrayList<>();
        ClickHouseStruct ch1 = new ClickHouseStruct(10, "SERVER5432.test.customers", getKafkaStruct(), 2, 21L, null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch1.setDebezium_ts_ms(21L);

        ClickHouseStruct ch4 = new ClickHouseStruct(1000, "SERVER5432.test.customers", getKafkaStruct(), 2, 433L, null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch4.setDebezium_ts_ms(433L);

        ClickHouseStruct ch2 = new ClickHouseStruct(8, "SERVER5432.test.customers", getKafkaStruct(), 2, 22L ,null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch2.setDebezium_ts_ms(22L);

        ClickHouseStruct ch6 = new ClickHouseStruct(1000, "SERVER5432.test.customers", getKafkaStruct(), 2, 3L, null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch6.setDebezium_ts_ms(3L);

        ClickHouseStruct ch3 = new ClickHouseStruct(1000, "SERVER5432.test.customers", getKafkaStruct(), 2, 33L, null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch3.setDebezium_ts_ms(33L);
        clickHouseStructs.add(ch1);
        clickHouseStructs.add(ch2);
        clickHouseStructs.add(ch3);

        clickHouseStructs.add(ch4);
        clickHouseStructs.add(ch6);

        // Batch 2 - Min and Max values for this batch - 1001 and 2001
        List<ClickHouseStruct> clickHouseStructs1 = new ArrayList<>();
        ClickHouseStruct ch5 = new ClickHouseStruct(10, "SERVER5432.test.customers", getKafkaStruct(), 2, 21L, null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch5.setDebezium_ts_ms(1001L);

        ClickHouseStruct ch7 = new ClickHouseStruct(10, "SERVER5432.test.customers", getKafkaStruct(), 2, 21L, null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch7.setDebezium_ts_ms(2001L);
        clickHouseStructs1.add(ch5);
        clickHouseStructs1.add(ch7);

        DebeziumOffsetManagement.addToBatchTimestamps(clickHouseStructs);
        DebeziumOffsetManagement.addToBatchTimestamps(clickHouseStructs1);

        // Batch 3 - Min and Max values for this batch - 501 and 1000
        List<ClickHouseStruct> clickHouseStructs2 = new ArrayList<>();
        ClickHouseStruct ch8 = new ClickHouseStruct(10, "SERVER5432.test.customers", getKafkaStruct(), 2, 21L, null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch8.setDebezium_ts_ms(501L);

        ClickHouseStruct ch9 = new ClickHouseStruct(10, "SERVER5432.test.customers", getKafkaStruct(), 2, 21L, null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch9.setDebezium_ts_ms(1000L);
        clickHouseStructs2.add(ch8);
        clickHouseStructs2.add(ch9);

        boolean result = DebeziumOffsetManagement.checkIfThereAreInflightRequests(clickHouseStructs2);
        Assert.assertTrue(result);

        // Batch 4 - Min and Max values for this batch - 1 and 2
        List<ClickHouseStruct> clickHouseStructs3 = new ArrayList<>();
        ClickHouseStruct ch10 = new ClickHouseStruct(10, "SERVER5432.test.customers", getKafkaStruct(), 2, 21L, null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch10.setDebezium_ts_ms(1L);

        ClickHouseStruct ch11 = new ClickHouseStruct(10, "SERVER5432.test.customers", getKafkaStruct(), 2, 21L, null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch11.setDebezium_ts_ms(2L);
        clickHouseStructs3.add(ch10);
        clickHouseStructs3.add(ch11);

        boolean result1 = DebeziumOffsetManagement.checkIfThereAreInflightRequests(clickHouseStructs3);
        Assert.assertFalse(result1);


    }

    @Test
    public void testCalculateMinMaxTimestampFromBatch() {
        // Test to validate DebeziumOffsetManagement calculateMinMaxTimestampFromBatch function
        // Create batch timestamps map.
        Map<Pair<Long, Long>, List<ClickHouseStruct>> batchTimestamps = new HashMap();
        List<ClickHouseStruct> clickHouseStructs = new ArrayList<>();
        ClickHouseStruct ch1 = new ClickHouseStruct(10, "SERVER5432.test.customers", getKafkaStruct(), 2, 21L, null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch1.setDebezium_ts_ms(21L);

        ClickHouseStruct ch4 = new ClickHouseStruct(1000, "SERVER5432.test.customers", getKafkaStruct(), 2, 433L, null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch4.setDebezium_ts_ms(433L);

        ClickHouseStruct ch2 = new ClickHouseStruct(8, "SERVER5432.test.customers", getKafkaStruct(), 2, 22L ,null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch2.setDebezium_ts_ms(22L);

        ClickHouseStruct ch6 = new ClickHouseStruct(1000, "SERVER5432.test.customers", getKafkaStruct(), 2, 3L, null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch6.setDebezium_ts_ms(3L);

        ClickHouseStruct ch3 = new ClickHouseStruct(1000, "SERVER5432.test.customers", getKafkaStruct(), 2, 33L, null, getKafkaStruct(), null, ClickHouseConverter.CDC_OPERATION.CREATE);
        ch3.setDebezium_ts_ms(33L);

        clickHouseStructs.add(ch1);
        clickHouseStructs.add(ch2);
        clickHouseStructs.add(ch3);

        clickHouseStructs.add(ch4);
        clickHouseStructs.add(ch6);


        Pair<Long, Long> result = DebeziumOffsetManagement.calculateMinMaxTimestampFromBatch(clickHouseStructs);
        Assert.assertTrue(result.getLeft() == 3L);
        Assert.assertTrue(result.getRight() == 433L);

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

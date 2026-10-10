package com.altinity.clickhouse.sink.connector;

import com.altinity.clickhouse.sink.connector.deduplicator.DeDuplicator;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kafka-mode task liveness (spec 03.01 section 3.4) and the rule that only a
 * true Kafka tombstone may be skipped by {@code put()} (spec 10.04 section
 * 3.5).
 *
 * <p><b>Liveness.</b> {@code ScheduledThreadPoolExecutor} cancels a periodic
 * task whose run throws and calls nobody. The batch runnable rethrows on a
 * FATAL ClickHouse classification, after which nothing drained the queue --
 * but {@code put()} kept enqueueing and {@code preCommit()} kept answering
 * from a frozen watermark, so the task consumed Kafka forever and replicated
 * nothing, with no error after the first one.</p>
 */
public class ClickHouseSinkTaskTest {
    private static AtomicLong spoofedRecordOffset = new AtomicLong();

    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);

    @AfterEach
    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Function to create spoofed sink record.
     * @param topic
     * @param keyField
     * @param key
     * @param valueField
     * @param value
     * @param timestampType
     * @param timestamp
     * @return
     */
    public static SinkRecord spoofSinkRecord(String topic, String keyField, String key,
                                             String valueField, String value,
                                             TimestampType timestampType, Long timestamp) {
        Schema basicKeySchema = null;
        Struct basicKey = null;
        if (keyField != null) {
            basicKeySchema = SchemaBuilder
                    .struct()
                    .field(keyField, Schema.STRING_SCHEMA)
                    .build();
            basicKey = new Struct(basicKeySchema);
            basicKey.put(keyField, key);
        }

        Schema basicValueSchema = null;
        Struct basicValue = null;
        if (valueField != null) {
            basicValueSchema = SchemaBuilder
                    .struct()
                    .field(valueField, Schema.STRING_SCHEMA)
                    .build();
            basicValue = new Struct(basicValueSchema);
            basicValue.put(valueField, value);
        }

        return new SinkRecord(topic, 0, basicKeySchema, basicKey,
                basicValueSchema, basicValue, spoofedRecordOffset.getAndIncrement(), timestamp, timestampType);
    }

    private static final String TOPIC = "db1.orders";
    private static final Schema ROW = SchemaBuilder.struct().field("id", Schema.INT32_SCHEMA).build();
    private static final Schema ENVELOPE = SchemaBuilder.struct()
            .field("op", Schema.STRING_SCHEMA)
            .field("before", SchemaBuilder.struct().field("id", Schema.INT32_SCHEMA).optional().build())
            .field("after", ROW)
            .build();

    /** A Debezium INSERT envelope: {@code op = 'c'} with an after image. */
    private static SinkRecord debeziumInsert(int id, long offset) {
        Struct value = new Struct(ENVELOPE).put("op", "c").put("after", new Struct(ROW).put("id", id));
        return new SinkRecord(TOPIC, 0, null, null, ENVELOPE, value, offset);
    }

    /** A Kafka tombstone: key present, value and value schema null. */
    private static SinkRecord tombstone(long offset) {
        return new SinkRecord(TOPIC, 0, Schema.STRING_SCHEMA, "k", null, null, offset);
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        ClickHouseSinkConnectorConfig.setDefaultValues(props);
        return new ClickHouseSinkConnectorConfig(props);
    }

    private static ClickHouseSinkTask taskWith(ScheduledFuture<?> runnableFuture,
                                              LinkedBlockingQueue<List<ClickHouseStruct>> queue) {
        ClickHouseSinkTask task = new ClickHouseSinkTask();
        ClickHouseSinkConnectorConfig config = config();
        task.attachForTest(config, queue, new ConcurrentHashMap<>(), new DeDuplicator(config),
                runnableFuture);
        return task;
    }

    /** Exactly what ClickHouseBatchRunnable#run does on a FATAL code, then waits for the future. */
    private ScheduledFuture<?> deadRunnable() throws Exception {
        CountDownLatch ticked = new CountDownLatch(1);
        ScheduledFuture<?> future = executor.scheduleAtFixedRate(() -> {
            ticked.countDown();
            throw new RuntimeException("Fatal ClickHouse error, stopping task",
                    new RuntimeException("Code: 60. DB::Exception: Table db1.orders does not exist"));
        }, 0, 10, TimeUnit.MILLISECONDS);
        assertTrue(ticked.await(10, TimeUnit.SECONDS));
        long deadline = System.currentTimeMillis() + 10_000;
        while (!future.isDone() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(future.isDone(), "the runnable's task must have terminated");
        return future;
    }

    private ScheduledFuture<?> liveRunnable() {
        return executor.scheduleAtFixedRate(() -> { }, 0, 10, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("put() fails the task with the runnable's cause once the runnable is dead")
    public void deadRunnableFailsPut() throws Exception {
        LinkedBlockingQueue<List<ClickHouseStruct>> queue = new LinkedBlockingQueue<>();
        ClickHouseSinkTask task = taskWith(deadRunnable(), queue);

        ConnectException thrown = assertThrows(ConnectException.class,
                () -> task.put(Collections.singletonList(debeziumInsert(1, 5))),
                "a dead runnable can never write what put() accepts; the task must fail, not stall");

        assertNotNull(thrown.getCause(), "the runnable's own failure must be the cause");
        assertTrue(String.valueOf(thrown.getCause().getMessage()).contains("Fatal ClickHouse error"),
                "cause was: " + thrown.getCause());
        assertTrue(thrown.getMessage().contains("dead"), "message was: " + thrown.getMessage());
        assertTrue(queue.isEmpty(), "nothing may be enqueued for a dead runnable");
    }

    @Test
    @DisplayName("preCommit() fails the task once the runnable is dead instead of answering from a frozen watermark")
    public void deadRunnableFailsPreCommit() throws Exception {
        ClickHouseSinkTask task = taskWith(deadRunnable(), new LinkedBlockingQueue<>());
        Map<TopicPartition, OffsetAndMetadata> current = new HashMap<>();
        current.put(new TopicPartition(TOPIC, 0), new OffsetAndMetadata(6));

        ConnectException thrown = assertThrows(ConnectException.class, () -> task.preCommit(current));
        assertNotNull(thrown.getCause());
    }

    @Test
    @DisplayName("A live runnable: records are converted and enqueued, preCommit holds at the durable watermark")
    public void liveRunnableAcceptsRecords() throws Exception {
        LinkedBlockingQueue<List<ClickHouseStruct>> queue = new LinkedBlockingQueue<>();
        ClickHouseSinkTask task = taskWith(liveRunnable(), queue);

        task.put(Arrays.asList(debeziumInsert(1, 5), debeziumInsert(2, 6)));

        assertEquals(1, queue.size(), "one put() is one handoff unit");
        List<ClickHouseStruct> batch = queue.take();
        assertEquals(2, batch.size());
        assertEquals(5L, batch.get(0).getKafkaOffset());
        assertEquals(6L, batch.get(1).getKafkaOffset());

        Map<TopicPartition, OffsetAndMetadata> current = new HashMap<>();
        TopicPartition tp = new TopicPartition(TOPIC, 0);
        current.put(tp, new OffsetAndMetadata(7));
        // Nothing durably inserted yet: the watermark was seeded at firstOffset - 1 = 4,
        // so the task may commit at most 5 (the resume point), never 7.
        assertEquals(5L, task.preCommit(current).get(tp).offset());
    }

    @Test
    @DisplayName("A Kafka tombstone (null value) is skipped; the records around it are kept")
    public void tombstoneIsDroppedQuietly() throws Exception {
        LinkedBlockingQueue<List<ClickHouseStruct>> queue = new LinkedBlockingQueue<>();
        ClickHouseSinkTask task = taskWith(liveRunnable(), queue);

        task.put(Arrays.asList(debeziumInsert(1, 7), tombstone(8), debeziumInsert(2, 9)));

        List<ClickHouseStruct> batch = queue.take();
        assertEquals(2, batch.size(), "the tombstone carries no change event and is not a record");
        assertEquals(7L, batch.get(0).getKafkaOffset());
        assertEquals(9L, batch.get(1).getKafkaOffset());
    }

    @Test
    @DisplayName("A non-null value that does not convert fails the task instead of being dropped")
    public void unconvertibleRecordIsLoud() throws Exception {
        LinkedBlockingQueue<List<ClickHouseStruct>> queue = new LinkedBlockingQueue<>();
        ClickHouseSinkTask task = taskWith(liveRunnable(), queue);
        // A STRUCT value with no Debezium envelope: no 'op', no images.
        SinkRecord notAnEnvelope = spoofSinkRecord(TOPIC, null, null, "v", "x",
                TimestampType.NO_TIMESTAMP_TYPE, null);

        DataException thrown = assertThrows(DataException.class,
                () -> task.put(Collections.singletonList(notAnEnvelope)),
                "dropping a record that is not a tombstone loses a change while the offset advances");

        assertTrue(thrown.getMessage().contains(TOPIC), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("offset " + notAnEnvelope.kafkaOffset()), thrown.getMessage());
        assertTrue(queue.isEmpty(), "nothing may be enqueued from a failed put()");
    }
}

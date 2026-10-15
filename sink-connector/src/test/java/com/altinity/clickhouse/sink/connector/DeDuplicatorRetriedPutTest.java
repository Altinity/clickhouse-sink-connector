package com.altinity.clickhouse.sink.connector;

import com.altinity.clickhouse.sink.connector.deduplicator.DeDuplicator;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.RetriableException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Spec 10.05 section 6: {@code deduplication.policy} and a {@code put()} that
 * Kafka Connect retries.
 *
 * <p>{@code ClickHouseSinkTask.put} records every record's identity in the
 * de-duplication pool ({@code isNew}) BEFORE it hands the batch to the writer
 * queue. When the handoff is interrupted, {@code put} throws
 * {@link RetriableException}, and Kafka Connect's contract for that exception
 * is to call {@code put} again with the same records. The retried call finds
 * every identity already in the pool, drops each record as a "duplicate
 * delivery", and hands off an empty batch: the records were never queued, and
 * the next batch that is written moves the durable watermark
 * ({@code preCommit}) past them.</p>
 */
public class DeDuplicatorRetriedPutTest {

    private static final String TOPIC = "db1.orders";
    private static final Schema ROW = SchemaBuilder.struct().field("id", Schema.INT32_SCHEMA).build();
    private static final Schema ENVELOPE = SchemaBuilder.struct()
            .field("op", Schema.STRING_SCHEMA)
            .field("before", SchemaBuilder.struct().field("id", Schema.INT32_SCHEMA).optional().build())
            .field("after", ROW)
            .build();

    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);

    @AfterEach
    public void shutdown() {
        executor.shutdownNow();
    }

    private static SinkRecord insert(int id, long offset) {
        Struct value = new Struct(ENVELOPE).put("op", "c").put("after", new Struct(ROW).put("id", id));
        return new SinkRecord(TOPIC, 0, null, null, ENVELOPE, value, offset);
    }

    /** A writer queue whose first handoff is interrupted, as a task stop or a thread interrupt would. */
    private static final class InterruptedOnceQueue extends LinkedBlockingQueue<List<ClickHouseStruct>> {
        private boolean interrupted = false;

        @Override
        public void put(List<ClickHouseStruct> batch) throws InterruptedException {
            if (!interrupted) {
                interrupted = true;
                throw new InterruptedException("handoff interrupted");
            }
            super.put(batch);
        }
    }

    private ClickHouseSinkTask task(String policy, LinkedBlockingQueue<List<ClickHouseStruct>> queue) {
        Map<String, String> props = new HashMap<>();
        ClickHouseSinkConnectorConfig.setDefaultValues(props);
        props.put(ClickHouseSinkConnectorConfigVariables.DEDUPLICATION_POLICY.toString(), policy);
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(props);
        ScheduledFuture<?> live = executor.scheduleAtFixedRate(() -> { }, 0, 10, TimeUnit.MILLISECONDS);
        ClickHouseSinkTask task = new ClickHouseSinkTask();
        task.attachForTest(config, queue, new ConcurrentHashMap<>(), new DeDuplicator(config), live);
        return task;
    }

    private static void putTwice(ClickHouseSinkTask task, List<SinkRecord> records) {
        assertThrows(RetriableException.class, () -> task.put(records),
                "an interrupted handoff is reported as retriable, so Connect redelivers the batch");
        task.put(records);
    }

    @Test
    @DisplayName("policy off (the default): the retried put hands off every record")
    public void retriedPutHandsOffEveryRecordWhenDeduplicationIsOff() throws Exception {
        InterruptedOnceQueue queue = new InterruptedOnceQueue();
        putTwice(task("off", queue), Arrays.asList(insert(1, 5), insert(2, 6)));
        assertEquals(1, queue.size());
        assertEquals(2, queue.take().size());
    }

    @Test
    @Disabled("DEFECT FM-10.05-2: with deduplication.policy=old|new the identities are pooled before the "
            + "handoff, so the put Connect retries after a RetriableException drops every record as a duplicate "
            + "and hands off an empty batch; the records are lost once a later batch advances preCommit")
    @DisplayName("policy new: the retried put still hands off the records its failed attempt never queued")
    public void retriedPutIsNotDroppedAsDuplicate() throws Exception {
        InterruptedOnceQueue queue = new InterruptedOnceQueue();
        putTwice(task("new", queue), Arrays.asList(insert(1, 5), insert(2, 6)));
        assertEquals(1, queue.size());
        assertEquals(2, queue.take().size(), "the records of the failed put were never queued; dropping "
                + "them on the retry as duplicates loses them");
    }
}

package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A worker whose scheduled task has died must stop the engine LOUDLY on the
 * next source batch (spec 03.01 section 3.3).
 *
 * <p><b>The defect.</b> {@code ClickHouseBatchRunnable#run} rethrows on a
 * FATAL classification. {@code ScheduledThreadPoolExecutor} then cancels that
 * task's future and does nothing else, and nothing inspected the futures. The
 * worker's batch stayed registered (so no control-record offset could ever be
 * committed again), every later batch stayed parked, the dead worker's queue
 * filled to {@code sink.connector.max.queue.size}, and the Debezium thread
 * blocked in {@code put}: replication stopped with no error after the first
 * one.</p>
 */
public class WorkerDeathIsLoudTest {

    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);

    @AfterEach
    public void shutdown() {
        executor.shutdownNow();
    }

    /** Records what the engine's committer was asked to do. */
    private static final class RecordingCommitter
            implements DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> {

        private final List<ChangeEvent<SourceRecord, SourceRecord>> processed = new ArrayList<>();
        private int batchesFinished = 0;

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record) {
            processed.add(record);
        }

        @Override
        public void markBatchFinished() {
            batchesFinished++;
        }

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record,
                                  DebeziumEngine.Offsets sourceOffsets) {
            processed.add(record);
        }

        @Override
        public DebeziumEngine.Offsets buildOffsets() {
            return (key, value) -> { };
        }
    }

    /** A heartbeat exactly as Debezium emits one: a value with no {@code op}. */
    private static ChangeEvent<SourceRecord, SourceRecord> heartbeat() {
        Schema valueSchema = SchemaBuilder.struct()
                .name("io.debezium.connector.common.Heartbeat")
                .field("ts_ms", Schema.INT64_SCHEMA)
                .build();
        Struct value = new Struct(valueSchema);
        value.put("ts_ms", 1788182208680L);
        Map<String, Object> partition = new LinkedHashMap<>();
        partition.put("server", "sink-connector-manager");
        Map<String, Object> offset = new LinkedHashMap<>();
        offset.put("lsn", 74130877960L);
        SourceRecord record = new SourceRecord(partition, offset,
                "__debezium-heartbeat.sink-connector-manager", 0,
                null, null, valueSchema, value);
        return new ChangeEvent<SourceRecord, SourceRecord>() {
            @Override
            public SourceRecord key() {
                return null;
            }

            @Override
            public SourceRecord value() {
                return record;
            }

            @Override
            public String destination() {
                return record.topic();
            }

            @Override
            public Integer partition() {
                return null;
            }
        };
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        ClickHouseSinkConnectorConfig.setDefaultValues(props);
        return new ClickHouseSinkConnectorConfig(props);
    }

    private static void awaitDone(ScheduledFuture<?> future) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!future.isDone() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(future.isDone(), "the worker task must have terminated");
    }

    @Test
    @DisplayName("A worker killed by a FATAL classification fails the next source batch with its cause")
    public void deadWorkerFailsTheNextBatchLoudly() throws Exception {
        CountDownLatch ticked = new CountDownLatch(1);
        // Exactly what ClickHouseBatchRunnable#run does on a FATAL code.
        ScheduledFuture<?> worker = executor.scheduleAtFixedRate(() -> {
            ticked.countDown();
            throw new RuntimeException("Fatal ClickHouse error, stopping task",
                    new RuntimeException("Code: 60. DB::Exception: Table db.orders does not exist"));
        }, 0, 10, TimeUnit.MILLISECONDS);
        assertTrue(ticked.await(10, TimeUnit.SECONDS));
        awaitDone(worker);

        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        capture.workerFutures.add(worker);
        RecordingCommitter committer = new RecordingCommitter();
        DebeziumRecordParserService parser = new SourceRecordParserService();

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                capture.handleChangeEventBatch(Collections.singletonList(heartbeat()), committer,
                        new Properties(), parser, config()),
                "a dead worker must stop the engine, not leave a silently stalled pipeline");

        assertNotNull(thrown.getCause(), "the worker's own failure must be the cause");
        assertTrue(String.valueOf(thrown.getCause().getMessage()).contains("Fatal ClickHouse error"),
                "cause was: " + thrown.getCause());
        assertTrue(thrown.getMessage().contains("dead"), "message was: " + thrown.getMessage());
        assertTrue(committer.processed.isEmpty(),
                "nothing may be committed once a worker is dead");
        assertEquals(0, committer.batchesFinished);
    }

    @Test
    @DisplayName("Live workers do not interfere with the batch handler")
    public void liveWorkersDoNotInterfere() throws Exception {
        ScheduledFuture<?> worker = executor.scheduleAtFixedRate(() -> { }, 0, 10, TimeUnit.MILLISECONDS);

        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        capture.workerFutures.add(worker);
        RecordingCommitter committer = new RecordingCommitter();
        DebeziumRecordParserService parser = new SourceRecordParserService();

        capture.handleChangeEventBatch(Collections.singletonList(heartbeat()), committer,
                new Properties(), parser, config());

        assertEquals(1, committer.processed.size(), "the heartbeat is committed as before");
        assertEquals(1, committer.batchesFinished);
    }
}

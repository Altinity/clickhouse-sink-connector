package com.altinity.clickhouse.debezium.embedded.cdc;

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

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 03.01 section 6, FM-03.01-1: a worker killed by an {@link Error}
 * (OutOfMemoryError, StackOverflowError, a linkage error) rather than by the
 * FATAL rethrow. {@code ClickHouseBatchRunnable.run} catches {@code Exception}
 * only, so the Error ends the periodic task without any log line from the
 * worker; the next source batch -- a heartbeat on an idle source -- must stop
 * the engine with the Error as the cause, and the completion callback must
 * treat it as terminal (no in-process retry on the same, dead pool).
 */
public class WorkerErrorDeathIsLoudTest {

    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);

    @AfterEach
    public void shutdown() {
        executor.shutdownNow();
    }

    private static final class NoopCommitter
            implements DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> {
        int calls = 0;

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record) {
            calls++;
        }

        @Override
        public void markBatchFinished() {
            calls++;
        }

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record, DebeziumEngine.Offsets offsets) {
            calls++;
        }

        @Override
        public DebeziumEngine.Offsets buildOffsets() {
            return (key, value) -> { };
        }
    }

    private static ChangeEvent<SourceRecord, SourceRecord> heartbeat() {
        Schema valueSchema = SchemaBuilder.struct()
                .name("io.debezium.connector.common.Heartbeat")
                .field("ts_ms", Schema.INT64_SCHEMA)
                .build();
        Struct value = new Struct(valueSchema).put("ts_ms", 1788182208680L);
        Map<String, Object> partition = new LinkedHashMap<>();
        partition.put("server", "sink-connector");
        Map<String, Object> offset = new LinkedHashMap<>();
        offset.put("pos", 4L);
        SourceRecord record = new SourceRecord(partition, offset, "__debezium-heartbeat.sink-connector", 0,
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

    @Test
    @DisplayName("A worker killed by OutOfMemoryError stops the engine on the next heartbeat, and the stop is terminal")
    public void aWorkerKilledByAnErrorStopsTheEngineOnTheNextHeartbeat() throws Exception {
        ScheduledFuture<?> worker = executor.scheduleAtFixedRate(() -> {
            throw new OutOfMemoryError("simulated: Java heap space");
        }, 0, 10, TimeUnit.MILLISECONDS);
        long deadline = System.currentTimeMillis() + 10_000L;
        while (!worker.isDone() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(worker.isDone(), "the Error must end the periodic task");

        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        capture.workerFutures.add(worker);
        Map<String, String> props = new HashMap<>();
        ClickHouseSinkConnectorConfig.setDefaultValues(props);
        NoopCommitter committer = new NoopCommitter();

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                capture.handleChangeEventBatch(Collections.singletonList(heartbeat()), committer,
                        new Properties(), new SourceRecordParserService(), new ClickHouseSinkConnectorConfig(props)));
        assertTrue(thrown.getCause() instanceof OutOfMemoryError, "the Error is the engine stop's cause: " + thrown);
        assertTrue(thrown.getMessage().contains("is dead"), thrown.getMessage());
        assertTrue(committer.calls == 0, "nothing is committed once a worker is dead");
        assertTrue(capture.hasDeadWorker(),
                "the completion callback reads the same futures and makes the failure terminal (no retry on this pool)");
    }
}

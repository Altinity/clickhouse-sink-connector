package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ZoneFixtures;
import com.altinity.clickhouse.sink.connector.common.Metrics;
import com.altinity.clickhouse.sink.connector.common.MetricsConstants;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import io.prometheus.client.Collector;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code replication_log_only} skips the table write, and the metrics (partition
 * offset, source and Debezium lag, binlog position) were only ever published by
 * that write: with the mode on, replication lag and progress were blind.
 * {@code processBatch} now publishes them for such a batch through
 * {@code updateMetricsForBatchWithoutTableWrite}.
 *
 * <p>The branch itself sits behind the replication-history write, which needs a
 * ClickHouse server, so the publishing step is exercised directly.</p>
 */
public class ClickHouseBatchRunnableLogOnlyMetricsTest {

    private static final String TOPIC = "SERVER5432.testdb.orders";

    private static final Schema ROW_SCHEMA = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .build();

    @AfterEach
    public void stopMetrics() {
        Metrics.stop();
    }

    /** A worker that opens no connection: this test never reaches ClickHouse. */
    private static final class OfflineWorker extends ClickHouseBatchRunnable {

        OfflineWorker(ClickHouseSinkConnectorConfig config) {
            super(new LinkedBlockingQueue<List<ClickHouseStruct>>(), config, new HashMap<>());
        }

        @Override
        Connection openConnection(String jdbcUrl, String databaseName) {
            return null;
        }
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        props.put("clickhouse.server.url", "127.0.0.1");
        props.put("clickhouse.server.port", "1");
        props.put("clickhouse.server.user", "default");
        props.put("clickhouse.server.password", "");
        props.put("clickhouse.server.database", "testdb");
        props.put("auto.create.tables", "false");
        props.put("single.threaded", "true");
        props.put("connection.pool.disable", "true");
        props.put("errors.max.retries", "1");
        props.put("connector.class",
                "com.altinity.clickhouse.sink.connector.ClickHouseSinkConnector");
        ClickHouseSinkConnectorConfig.setDefaultValues(props);
        return new ClickHouseSinkConnectorConfig(ZoneFixtures.withZones(props));
    }

    private static ClickHouseStruct record(long offset, long tsMs) {
        ClickHouseStruct record = new ClickHouseStruct(offset, TOPIC, null, 3, tsMs, null,
                new Struct(ROW_SCHEMA).put("id", (int) offset), null,
                ClickHouseConverter.CDC_OPERATION.CREATE);
        record.setDatabase("testdb");
        record.setDebezium_ts_ms(tsMs);
        return record;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** The values of every sample of the given Prometheus metric whose labels include {@code label}. */
    private static List<Double> samples(String metricName, String label) {
        List<Double> out = new ArrayList<>();
        Enumeration<Collector.MetricFamilySamples> families =
                Metrics.meterRegistry().getPrometheusRegistry().metricFamilySamples();
        while (families.hasMoreElements()) {
            Collector.MetricFamilySamples family = families.nextElement();
            if (!metricName.equals(family.name)) {
                continue;
            }
            for (Collector.MetricFamilySamples.Sample sample : family.samples) {
                int i = sample.labelValues.indexOf(label);
                if (i >= 0) {
                    out.add(sample.value);
                }
            }
        }
        return out;
    }

    @Test
    @DisplayName("A batch that skips the table write still publishes its partition offset and lag")
    public void batchWithoutTableWritePublishesOffsetAndLag() throws IOException {
        Metrics.initialize("true", String.valueOf(freePort()));
        assertNotNull(Metrics.meterRegistry(), "precondition: metrics are enabled");
        OfflineWorker worker = new OfflineWorker(config());
        long oneMinuteAgo = System.currentTimeMillis() - 60_000L;
        List<ClickHouseStruct> batch = new ArrayList<>();
        batch.add(record(10L, oneMinuteAgo));
        batch.add(record(20L, oneMinuteAgo));

        worker.updateMetricsForBatchWithoutTableWrite(batch);

        List<Double> offsets = samples(MetricsConstants.CLICKHOUSE_SINK_PARTITION_OFFSET, TOPIC);
        assertEquals(1, offsets.size(), "one partition-offset series for the topic: " + offsets);
        assertEquals(20.0, offsets.get(0), "the highest offset of the batch");

        List<Double> lag = samples(MetricsConstants.CLICKHOUSE_DB_SINK_LAG, TOPIC);
        assertEquals(1, lag.size(), "one source-lag series for the topic: " + lag);
        assertFalse(lag.get(0) < 60_000.0,
                "the lag must be at least the age of the records (60 s), got " + lag.get(0));
    }

    @Test
    @DisplayName("Publishing the metrics of a batch never fails the batch, whether or not metrics are enabled")
    public void publishingNeverFailsTheBatch() {
        OfflineWorker worker = new OfflineWorker(config());
        List<ClickHouseStruct> batch = new ArrayList<>();
        batch.add(record(1L, System.currentTimeMillis()));

        assertDoesNotThrow(() -> worker.updateMetricsForBatchWithoutTableWrite(batch));
    }
}

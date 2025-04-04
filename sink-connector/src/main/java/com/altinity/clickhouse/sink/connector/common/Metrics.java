/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.altinity.clickhouse.sink.connector.common;

import com.altinity.clickhouse.sink.connector.model.BlockMetaData;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Gauge;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * Metrics class using the io.dropwizard library for timer and memory usage statistics.
 * <p>
 * This class provides functionality to collect various metrics such as
 * memory usage, garbage collection, thread counts, and other system metrics.
 * It exposes the metrics using Prometheus.
 * </p>
 */
public class Metrics {

    /**
     * Logger instance used for logging metrics related messages.
     */
    private static final Logger log = LogManager.getLogger(Metrics.class);

    /**
     * Metric registry for collecting metrics.
     */
    private static MetricRegistry registry = null;

    /**
     * CollectorRegistry for Prometheus metrics collection.
     */
    private static CollectorRegistry collectorRegistry;

    /**
     * MeterRegistry for Prometheus metrics.
     */
    private static PrometheusMeterRegistry meterRegistry;

    /**
     * Counter for tracking the number of records processed by the ClickHouse sink.
     */
    private static Counter.Builder clickHouseSinkRecordsCounter;

    /**
     * Counter for tracking the number of records by topic.
     */
    private static Counter.Builder topicsNumRecordsCounter;

    /**
     * Counter for tracking the number of error records by topic.
     */
    private static Counter.Builder topicsErrorRecordsCounter;

    /**
     * Gauge for tracking the maximum binlog position processed by the ClickHouse sink.
     */
    private static Gauge maxBinLogPositionCounter;

    /**
     * Counter for tracking DDL operations.
     */
    private static Counter.Builder ddlProcessingCounter;

    /**
     * Gauge for tracking the uptime of the connector.
     */
    private static Gauge upTimeCounter;

    /**
     * Gauge for tracking the partition offsets.
     */
    private static Gauge partitionOffsetCounter;

    /**
     * Gauge for tracking the lag between the source database and ClickHouse insertion time.
     */
    private static Gauge sourceToCHLagCounter;

    /**
     * Gauge for tracking the lag between Debezium and ClickHouse insertion time.
     */
    private static Gauge debeziumToCHLagCounter;

    /**
     * Gauge for tracking the GTID value.
     */
    private static Gauge gtidCounter;

    /**
     * HTTP server used to expose Prometheus metrics.
     */
    private static HttpServer server;

    /**
     * Flag indicating whether metrics collection is enabled.
     */
    private static boolean enableMetrics = false;

    /**
     * Timestamp indicating the connector start time.
     */
    private static long connectorStartTimeMs = -1;

    /**
     * Port number for the Prometheus metrics server.
     */
    private static int port = 8084;

    /**
     * Initializes the metrics based on configuration parameters.
     *
     * @param enableFlag  the flag to enable or disable metrics collection.
     * @param metricsPort the port for Prometheus to expose metrics.
     */
    public static void initialize(String enableFlag, String metricsPort) {

        connectorStartTimeMs = System.currentTimeMillis();

        parseConfiguration(enableFlag, metricsPort);

        if (enableMetrics) {
            collectorRegistry = new CollectorRegistry();
            meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT, collectorRegistry, Clock.SYSTEM);

            // Bind JVM system metrics
            new JvmMemoryMetrics().bindTo(meterRegistry);
            new JvmGcMetrics().bindTo(meterRegistry);
            new ProcessorMetrics().bindTo(meterRegistry);
            new JvmThreadMetrics().bindTo(meterRegistry);

            exposePrometheusPort(meterRegistry);
            registerMetrics(collectorRegistry);
        }
    }

    /**
     * Parses the configuration for enabling metrics and setting the metrics port.
     *
     * @param enableFlag  the flag to enable or disable metrics.
     * @param metricsPort the port to expose metrics on.
     */
    private static void parseConfiguration(String enableFlag, String metricsPort) {
        if (enableFlag != null) {
            try {
                enableMetrics = Boolean.parseBoolean(enableFlag);
                log.info("METRICS enabled: " + enableMetrics);
            } catch (Exception e) {
                log.error("Exception parsing Metrics flag", e);
            }
        }
        if (metricsPort != null) {
            log.info("METRICS server started, Port: " + metricsPort);
            try {
                port = Integer.parseInt(metricsPort);
            } catch (NumberFormatException ne) {
                log.error("Error parsing metrics port", ne);
            }
        }
    }

    /**
     * Registers the Prometheus metrics.
     *
     * @param collectorRegistry the Prometheus collector registry.
     */
    private static void registerMetrics(CollectorRegistry collectorRegistry) {
        Map<String, String> metricsToHelp = MetricsConstants.getMetricsToHelpMap();

        clickHouseSinkRecordsCounter = Counter.builder(MetricsConstants.CLICKHOUSE_SINK_RECORDS)
                .description(metricsToHelp.get(MetricsConstants.CLICKHOUSE_SINK_RECORDS));

        maxBinLogPositionCounter = Gauge.build().labelNames("file").name(MetricsConstants.CLICKHOUSE_SINK_BINLOG_POS)
                .help(metricsToHelp.get(MetricsConstants.CLICKHOUSE_SINK_BINLOG_POS))
                .register(collectorRegistry);

        upTimeCounter = Gauge.build().name(MetricsConstants.CLICKHOUSE_SINK_CONNECTOR_UPTIME)
                .help(metricsToHelp.get(MetricsConstants.CLICKHOUSE_SINK_CONNECTOR_UPTIME))
                .register(collectorRegistry);

        gtidCounter = Gauge.build().name(MetricsConstants.CLICKHOUSE_SINK_GTID)
                .help(metricsToHelp.get(MetricsConstants.CLICKHOUSE_SINK_GTID))
                .register(collectorRegistry);

        partitionOffsetCounter = Gauge.build().
                labelNames(MetricsConstants.TOPIC, MetricsConstants.PARTITION)
                .name(MetricsConstants.CLICKHOUSE_SINK_PARTITION_OFFSET)
                .help(metricsToHelp.get(MetricsConstants.CLICKHOUSE_SINK_PARTITION_OFFSET))
                .register(collectorRegistry);

        sourceToCHLagCounter = Gauge.build().
                labelNames(MetricsConstants.TOPIC).
                name(MetricsConstants.CLICKHOUSE_DB_SINK_LAG)
                .help(metricsToHelp.get(MetricsConstants.CLICKHOUSE_DB_SINK_LAG))
                .register(collectorRegistry);

        debeziumToCHLagCounter = Gauge.build().labelNames(MetricsConstants.TOPIC)
                .name(MetricsConstants.CLICKHOUSE_DEBEZIUM_SINK_LAG)
                .help(metricsToHelp.get(MetricsConstants.CLICKHOUSE_DEBEZIUM_SINK_LAG))
                .register(collectorRegistry);

        topicsNumRecordsCounter = Counter.builder(MetricsConstants.CLICKHOUSE_NUM_RECORDS_BY_TOPIC)
                .description(metricsToHelp.get(MetricsConstants.CLICKHOUSE_NUM_RECORDS_BY_TOPIC));

        topicsErrorRecordsCounter = Counter.builder(MetricsConstants.CLICKHOUSE_NUM_ERROR_RECORDS_BY_TOPIC)
                .description(metricsToHelp.get(MetricsConstants.CLICKHOUSE_NUM_ERROR_RECORDS_BY_TOPIC));

        ddlProcessingCounter = Counter.builder(MetricsConstants.CLICKHOUSE_SINK_DDL)
                .description(metricsToHelp.get(MetricsConstants.CLICKHOUSE_SINK_DDL));
    }

    /**
     * Exposes Prometheus metrics on the specified port.
     *
     * @param prometheusMeterRegistry the Prometheus meter registry.
     */
    private static void exposePrometheusPort(PrometheusMeterRegistry prometheusMeterRegistry) {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/metrics", httpExchange -> {
                String response = prometheusMeterRegistry.scrape();
                httpExchange.sendResponseHeaders(200, response.getBytes().length);
                try (OutputStream os = httpExchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            });

            new Thread(server::start).start();
        } catch (IOException e) {
            log.error("Cannot start HTTP server for Prometheus on /metrics", e);
        }
    }

    /**
     * Stops the metrics server.
     */
    public static void stop() {
        if (server != null) {
            server.stop(0);
        }
        connectorStartTimeMs = -1;
    }

    /**
     * Returns the MetricRegistry used for collecting metrics.
     *
     * @return the MetricRegistry instance.
     */
    public static MetricRegistry registry() {
        return registry;
    }

    /**
     * Updates the metrics with data from the BlockMetaData.
     *
     * @param bmd the BlockMetaData object containing metrics data.
     */
    public static void updateMetrics(BlockMetaData bmd) {
        if (!enableMetrics) {
            return;
        }
        maxBinLogPositionCounter.labels(bmd.getBinLogFile()).set(bmd.getBinLogPosition());
        gtidCounter.set(bmd.getTransactionId());

        HashMap<String, MutablePair<Integer, Long>> partitionToOffsetMap = bmd.getPartitionToOffsetMap();
        if (!partitionToOffsetMap.isEmpty()) {
            for (Map.Entry<String, MutablePair<Integer, Long>> entry : partitionToOffsetMap.entrySet()) {
                MutablePair<Integer, Long> mp = entry.getValue();
                partitionOffsetCounter.labels(entry.getKey(), Integer.toString(mp.left))
                        .set(mp.right);
            }
        }

        // Db Source to CH lag
        if (!bmd.getSourceToCHLag().isEmpty()) {
            for (Map.Entry<String, Long> entry : bmd.getSourceToCHLag().entrySet()) {
                sourceToCHLagCounter.labels(entry.getKey()).set(entry.getValue());
            }
        }

        // Debezium to CH Lag
        if (!bmd.getDebeziumToCHLag().isEmpty()) {
            for (Map.Entry<String, Long> entry : bmd.getDebeziumToCHLag().entrySet()) {
                debeziumToCHLagCounter.labels(entry.getKey()).set(entry.getValue());
            }
        }

        upTimeCounter.set(System.currentTimeMillis() - connectorStartTimeMs);
    }

    /**
     * Returns the PrometheusMeterRegistry instance.
     *
     * @return the PrometheusMeterRegistry instance.
     */
    public static PrometheusMeterRegistry meterRegistry() {
        return meterRegistry;
    }

    /**
     * Creates a timer with the given metric name.
     *
     * @param first the first part of the metric name.
     * @param keys  additional keys for the metric name.
     * @return a Timer instance.
     */
    public static Timer timer(String first, String... keys) {
        return registry.timer(MetricRegistry.name(first, keys));
    }

    /**
     * Updates the number of records counter for the specified topic.
     *
     * @param topicName the topic name.
     * @param numRecords the number of records.
     */
    public static void updateCounters(String topicName, int numRecords) {
        if (enableMetrics) {
            topicsNumRecordsCounter
                    .tag("topic", topicName).register(Metrics.meterRegistry()).increment(numRecords);
        }
    }

    /**
     * Updates the error records counter for the specified topic.
     *
     * @param topicName the topic name.
     * @param numRecords the number of error records.
     */
    public static void updateErrorCounters(String topicName, int numRecords) {
        if (enableMetrics) {
            topicsErrorRecordsCounter
                    .tag("topic", topicName).register(Metrics.meterRegistry()).increment(numRecords);
        }
    }

    /**
     * Updates the DDL metrics for the specified DDL operation.
     *
     * @param ddl the DDL operation.
     * @param timestamp the timestamp of the operation.
     * @param timeTaken the time taken to process the DDL.
     * @param failed indicates if the operation failed.
     */
    public static void updateDdlMetrics(String ddl, long timestamp, long timeTaken, boolean failed) {
        if (enableMetrics) {
            ddlProcessingCounter
                    .tag("ddl", ddl).tag("fail", String.valueOf(failed)).tag("timestamp", String.valueOf(timestamp)).register(Metrics.meterRegistry()).increment(timeTaken);
        }
    }
}

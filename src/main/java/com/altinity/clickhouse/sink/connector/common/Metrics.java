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
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * Metrics class using io.dropwizard library
 * for timer and MemoryUsageStats.
 */
public class Metrics {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(Metrics.class);
    private static MetricRegistry registry = null;
    private static CollectorRegistry collectorRegistry;
    private static PrometheusMeterRegistry meterRegistry;
    private static Counter.Builder clickHouseSinkRecordsCounter;
    private static Counter.Builder topicsNumRecordsCounter;
    private static Counter.Builder topicsErrorRecordsCounter;
    private static Gauge maxBinLogPositionCounter;

    private static Gauge upTimeCounter;
    private static Gauge partitionOffsetCounter;

    // Lag between source database and ClickHouse Insertion time.
    private static Gauge sourceToCHLagCounter;

    // Lag between Debezium and ClickHouse Insertion time.
    private static Gauge debeziumToCHLagCounter;
    private static Gauge gtidCounter;
    private static HttpServer server;
    private static boolean enableMetrics = false;

    private static long connectorStartTimeMs = -1;

    private static int port = 8084;

    /**
     * Initialize metrics based on configuration parameter.
     * @param enableFlag
     * @param metricsPort
     */
    public static void initialize(String enableFlag, String metricsPort) {

        connectorStartTimeMs = System.currentTimeMillis();

        // Register reporters here.
//        reporter = ConsoleReporter.forRegistry(registry)
//                .convertRatesTo(TimeUnit.SECONDS)
//                .convertDurationsTo(TimeUnit.SECONDS)
//                .build();
//        reporter.start(1, TimeUnit.MINUTES);

        //            registry = new MetricRegistry();
//            registry.register("memory", new MemoryUsageGaugeSet());
//            registry.register("jvm.thread-states",new ThreadStatesGaugeSet());
//            registry.register("jvm.garbage-collector",new GarbageCollectorMetricSet());
        parseConfiguration(enableFlag, metricsPort);

        if(enableMetrics) {


            collectorRegistry = new CollectorRegistry();
            meterRegistry =
                    new PrometheusMeterRegistry(PrometheusConfig.DEFAULT, collectorRegistry, Clock.SYSTEM);

            new JvmMemoryMetrics().bindTo(meterRegistry);
            new JvmGcMetrics().bindTo(meterRegistry);
            new ProcessorMetrics().bindTo(meterRegistry);
            new JvmThreadMetrics().bindTo(meterRegistry);

            exposePrometheusPort(meterRegistry);
            registerMetrics(collectorRegistry);
        }
    }

    /**
     * Function to parse sink connector configuration.
     * @param enableFlag
     * @param metricsPort
     */
    private static void parseConfiguration(String enableFlag, String metricsPort) {
        if(enableFlag != null) {
            try {
                enableMetrics = Boolean.parseBoolean(enableFlag);
                log.info("METRICS enabled: " + enableMetrics);
            } catch(Exception e) {
                log.error("Exception parsing Metrics flag", e);
            }
        }
        if(metricsPort != null) {
            log.info("METRICS server started, Port: "+ metricsPort);
            try {
                port = Integer.parseInt(metricsPort);
            } catch(NumberFormatException ne) {
                log.error("Error parsing metrics port", ne);
            }
        }
    }

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

    }

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
            throw new RuntimeException(e);
        }
    }

    public static void stop() {
        if(server != null) {
            server.stop(0);
        }
        connectorStartTimeMs = -1;
    }
    public static MetricRegistry registry() {
        return registry;
    }

    public static void updateMetrics(BlockMetaData bmd) {
        if(!enableMetrics) {
            return;
        }
        maxBinLogPositionCounter.labels(bmd.getBinLogFile()).set(bmd.getBinLogPosition());
                //tag("partition", Integer.toString(bmd.getPartition())).

        gtidCounter.set(bmd.getTransactionId());
                //tag("partition", Integer.toString(bmd.getPartition())).
                //register(Metrics.meterRegistry()).increment(bmd.getTransactionId());

        HashMap<String, MutablePair<Integer, Long>> partitionToOffsetMap = bmd.getPartitionToOffsetMap();

        if(!partitionToOffsetMap.isEmpty()) {
            for(Map.Entry<String, MutablePair<Integer, Long>> entry : partitionToOffsetMap.entrySet()) {
                MutablePair<Integer, Long> mp = entry.getValue();
                partitionOffsetCounter.labels(entry.getKey(), Integer.toString(mp.left))
                        .set(mp.right);
            }
        }
        // Db Source to CH lag
        if(!bmd.getSourceToCHLag().isEmpty()) {
            for(Map.Entry<String, Long> entry: bmd.getSourceToCHLag().entrySet()) {
                sourceToCHLagCounter.labels(entry.getKey()).set(entry.getValue());
            }
        }

        // Debezium to CH Lag.
        if(!bmd.getDebeziumToCHLag().isEmpty()) {
            for(Map.Entry<String, Long> entry: bmd.getDebeziumToCHLag().entrySet()) {
                debeziumToCHLagCounter.labels(entry.getKey()).set(entry.getValue());
            }
        }

        upTimeCounter.set(System.currentTimeMillis() - connectorStartTimeMs);
    }


    public static PrometheusMeterRegistry meterRegistry(){ return meterRegistry;}


    public static Timer timer(String first, String... keys) {
        return registry.timer(MetricRegistry.name(first, keys));
    }

    /**
     * Update the num of record counter
     * @param topicName
     * @param numRecords
     */
    public static void updateCounters(String topicName, int numRecords) {
        if(enableMetrics) {
            topicsNumRecordsCounter
                    .tag("topic", topicName).register(Metrics.meterRegistry()).increment(numRecords);
        }
    }

    public static void updateErrorCounters(String topicName, int numRecords) {
        if(enableMetrics) {
            topicsErrorRecordsCounter
                    .tag("topic", topicName).register(Metrics.meterRegistry()).increment(numRecords);
        }
    }
}

package com.altinity.clickhouse.sink.connector.common;

import com.altinity.clickhouse.sink.connector.model.BlockMetaData;
import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Counter;
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

    private static ConsoleReporter reporter = null;

    private static CollectorRegistry collectorRegistry;

    private static PrometheusMeterRegistry meterRegistry;

    private static Gauge clickHouseSinkRecordsGauge;

    private static Counter.Builder clickHouseSinkRecordsCounter;

    private static Counter.Builder minSourceLagCounter;

    private static Counter.Builder maxSourceLagCounter;

    private static Counter.Builder minConsumerLagCounter;

    private static Counter.Builder maxConsumerLagCounter;


    private static Counter.Builder topicsNumRecordsCounter;

    private static Gauge maxBinLogPositionCounter;

    private static Gauge partitionOffsetCounter;

    private static Gauge gtidCounter;

    private static HttpServer server;

    private static boolean enableMetrics = false;

    private static int port = 8084;

    public static void initialize(String enableFlag, String metricsPort) {
        registry = new MetricRegistry();
        // registry.register("memory", new MemoryUsageGaugeSet());

        // Register reporters here.
//        reporter = ConsoleReporter.forRegistry(registry)
//                .convertRatesTo(TimeUnit.SECONDS)
//                .convertDurationsTo(TimeUnit.SECONDS)
//                .build();
//        reporter.start(1, TimeUnit.MINUTES);

        parseConfiguration(enableFlag, metricsPort);

        if(enableMetrics) {
            collectorRegistry = new CollectorRegistry();
            meterRegistry =
                    new PrometheusMeterRegistry(PrometheusConfig.DEFAULT, collectorRegistry, Clock.SYSTEM);


            exposePrometheusPort(meterRegistry);
            registerMetrics(collectorRegistry);
        }
    }

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

        clickHouseSinkRecordsCounter = Counter.builder("clickhouse.sink.records");

        minSourceLagCounter = Counter.builder("clickhouse.source.lag.min");
        maxSourceLagCounter = Counter.builder("clickhouse.source.lag.max");

        minConsumerLagCounter = Counter.builder("clickhouse.consumer.lag.min");
        maxConsumerLagCounter = Counter.builder("clickhouse.consumer.lag.max");

        maxBinLogPositionCounter = Gauge.build().name("clickhouse_sink_binlog_pos").help("Bin Log Position").register(collectorRegistry);
        gtidCounter = Gauge.build().name("clickhouse_sink_gtid").help("GTID Transaction Id").register(collectorRegistry);

        partitionOffsetCounter = Gauge.build().
        labelNames("Topic", "Partition").
                name("clickhouse_sink_partition_offset").help("Kafka partition Offset").register(collectorRegistry);

        topicsNumRecordsCounter = Counter.builder("clickhouse.topics.num.records");

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
    }
    public static MetricRegistry registry() {
        return registry;
    }

    public static Counter.Builder getClickHouseSinkRecordsCounter() { return clickHouseSinkRecordsCounter;}


    public static void updateMetrics(BlockMetaData bmd) {
        if(!enableMetrics) {
            return;
        }
        maxBinLogPositionCounter.set(bmd.getBinLogPosition());
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
    }

    public static void updateSinkRecordsCounter(String blockUUid, Long taskId, String topicName, String tableName,
                                                HashMap<Integer, MutablePair<Long, Long>> partitionToOffsetMap,
                                                int numRecords, long minSourceLag, long maxSourceLag,
                                                long minConsumerLag, long maxConsumerLag) {
        if(enableMetrics) {
            for(Map.Entry<Integer, MutablePair<Long, Long>> entry: partitionToOffsetMap.entrySet()) {

                MutablePair<Long, Long> offsetTuple = entry.getValue();
                long minOffset = offsetTuple.left;
                long maxOffset = offsetTuple.right;
                long totalRecords = maxOffset - minOffset;

                Metrics.getClickHouseSinkRecordsCounter()
                        .tag("taskId", taskId.toString())
                        .tag("UUID", blockUUid)
                        .tag("topic", topicName)
                        .tag("table", tableName)
                        .tag("minOffset", Long.toString(minOffset))
                        .tag("maxOffset", Long.toString(maxOffset))
                        .tag("partition", Integer.toString(entry.getKey()))
                        .tag("totalRecords", Long.toString(totalRecords))

                        .register(Metrics.meterRegistry()).increment(totalRecords);


                minSourceLagCounter.register(Metrics.meterRegistry()).increment(minSourceLag);
                maxSourceLagCounter.register(Metrics.meterRegistry()).increment(maxSourceLag);

                minConsumerLagCounter.register(Metrics.meterRegistry()).increment(minConsumerLag);
                maxConsumerLagCounter.register(Metrics.meterRegistry()).increment(maxConsumerLag);

            }
        }
    }

    public static Gauge getClickHouseSinkRecordsGauge(){return clickHouseSinkRecordsGauge;}

    public static PrometheusMeterRegistry meterRegistry(){ return meterRegistry;}


    public static Timer timer(String first, String... keys) {
        return registry.timer(MetricRegistry.name(first, keys));
    }

    public static void updateCounters(String topicName, int numRecords) {
        if(enableMetrics) {
            topicsNumRecordsCounter
                    .tag("topic", topicName).register(Metrics.meterRegistry()).increment(numRecords);
        }
    }
}

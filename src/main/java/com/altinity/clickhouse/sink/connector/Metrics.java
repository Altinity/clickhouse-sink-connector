package com.altinity.clickhouse.sink.connector;

import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.codahale.metrics.jvm.MemoryUsageGaugeSet;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

/**
 * Metrics class using io.dropwizard library
 * for timer and MemoryUsageStats.
 */
public class Metrics {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(Metrics.class);
    private static MetricRegistry registry = null;

    private static ConsoleReporter reporter = null;

    private static PrometheusMeterRegistry meterRegistry;

    private static Gauge clickHouseSinkRecordsGauge;

    private static Counter.Builder clickHouseSinkRecordsCounter;
    private static HttpServer server;

    private static boolean enableMetrics = false;

    private static int port = 8084;

    public static void initialize(String enableFlag, String metricsPort) {
        registry = new MetricRegistry();
        registry.register("memory", new MemoryUsageGaugeSet());

        // Register reporters here.
//        reporter = ConsoleReporter.forRegistry(registry)
//                .convertRatesTo(TimeUnit.SECONDS)
//                .convertDurationsTo(TimeUnit.SECONDS)
//                .build();
//        reporter.start(1, TimeUnit.MINUTES);

        parseConfiguration(enableFlag, metricsPort);

        if(enableMetrics == true) {
            meterRegistry =
                    new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);


            exposePrometheusPort(meterRegistry);
            registerMetrics();
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

    private static void registerMetrics() {

        clickHouseSinkRecordsCounter = Counter.builder("clickhouse.sink.records");

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

    public static void updateSinkRecordsCounter(String blockUUid, String topicName, String tableName,
                                                String minOffset, String maxOffset, int numRecords) {
        if(enableMetrics == false) {
            Metrics.getClickHouseSinkRecordsCounter()
                    .tag("UUID", blockUUid)
                    .tag("topic", topicName)
                    .tag("table", tableName)
                    .tag("minOffset", minOffset)
                    .tag("maxOffset", maxOffset)

                    .register(Metrics.meterRegistry()).increment(numRecords);
        }
    }

    public static Gauge getClickHouseSinkRecordsGauge(){return clickHouseSinkRecordsGauge;}

    public static PrometheusMeterRegistry meterRegistry(){ return meterRegistry;}


    public static Timer timer(String first, String... keys) {
        return registry.timer(MetricRegistry.name(first, keys));
    }

}

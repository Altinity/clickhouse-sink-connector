package com.altinity.clickhouse.sink.connector;

import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.codahale.metrics.jvm.MemoryUsageGaugeSet;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * Metrics class using io.dropwizard library
 * for timer and MemoryUsageStats.
 */
public class Metrics {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(Metrics.class);
    private static MetricRegistry registry = null;

    private static ConsoleReporter reporter = null;

    private static PrometheusMeterRegistry meterRegistry;

    private static HttpServer server;
    public static void initialize() {
        registry = new MetricRegistry();
        registry.register("memory", new MemoryUsageGaugeSet());

        // Register reporters here.
        reporter = ConsoleReporter.forRegistry(registry)
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.SECONDS)
                .build();
        reporter.start(1, TimeUnit.MINUTES);


        meterRegistry =
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);


        exposePrometheusPort(meterRegistry);
    }

    private static void exposePrometheusPort(PrometheusMeterRegistry prometheusMeterRegistry) {


        try {
            server = HttpServer.create(new InetSocketAddress(8084), 0);
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

    public static PrometheusMeterRegistry meterRegistry(){ return meterRegistry;}


    public static Timer timer(String first, String... keys) {
        return registry.timer(MetricRegistry.name(first, keys));
    }

}

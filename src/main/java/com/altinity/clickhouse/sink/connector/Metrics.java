package com.altinity.clickhouse.sink.connector;

import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.codahale.metrics.jvm.MemoryUsageGaugeSet;
import org.slf4j.Logger;

import java.util.concurrent.TimeUnit;

/**
 * Metrics class using io.dropwizard library
 * for timer and MemoryUsageStats.
 */
public class Metrics {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(Metrics.class);
    private static final MetricRegistry registry;

    private static final ConsoleReporter reporter;

    static {
        registry = new MetricRegistry();
        registry.register("memory", new MemoryUsageGaugeSet());

        // Register reporters here.
        reporter = ConsoleReporter.forRegistry(registry)
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.SECONDS)
                .build();
        reporter.start(1, TimeUnit.MINUTES);

    }

    public static MetricRegistry registry() {
        return registry;
    }

    public static Timer timer(String first, String... keys) {
        return registry.timer(MetricRegistry.name(first, keys));
    }

}

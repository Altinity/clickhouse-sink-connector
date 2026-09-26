package com.altinity.clickhouse.sink.connector.common;

import com.altinity.clickhouse.sink.connector.model.BlockMetaData;
import io.micrometer.core.instrument.Meter;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.Collector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The metrics registry must not grow with DDL volume, binlog rotations or
 * engine restarts (spec 10.03 §3.4).
 *
 * <p><b>The defects.</b> (1) The DDL counter carried the DDL text and the
 * wall-clock timestamp as tags, so every DDL event registered a new series
 * that was never removed; a source refreshing its views thousands of times a
 * day grew the registry -- and every scrape -- without bound. (2) The binlog
 * position gauge kept one child per binlog file since start. (3) Every engine
 * restart built a new registry and never closed the old one, whose
 * {@code JvmGcMetrics} listeners kept it, and everything in it, reachable.</p>
 */
public class MetricsLifecycleTest {

    @AfterEach
    public void stopMetrics() {
        Metrics.stop();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void start() throws IOException {
        Metrics.initialize("true", String.valueOf(freePort()));
    }

    private static List<Meter> metersNamed(String name) {
        List<Meter> out = new ArrayList<>();
        for (Meter m : Metrics.meterRegistry().getMeters()) {
            if (name.equals(m.getId().getName())) {
                out.add(m);
            }
        }
        return out;
    }

    /** Label values of the given Prometheus metric's children, via the registry the gauge was registered in. */
    private static List<String> childLabels(String metricName, String label) {
        List<String> out = new ArrayList<>();
        Enumeration<Collector.MetricFamilySamples> families =
                Metrics.meterRegistry().getPrometheusRegistry().metricFamilySamples();
        while (families.hasMoreElements()) {
            Collector.MetricFamilySamples family = families.nextElement();
            if (!metricName.equals(family.name)) {
                continue;
            }
            for (Collector.MetricFamilySamples.Sample sample : family.samples) {
                int i = sample.labelNames.indexOf(label);
                if (i >= 0) {
                    out.add(sample.labelValues.get(i));
                }
            }
        }
        return out;
    }

    @Test
    @DisplayName("three DDL events with distinct statements and timestamps are one series per fail value, not one per event")
    public void ddlCounterHasFixedCardinality() throws IOException {
        start();
        Metrics.updateDdlMetrics("CREATE OR REPLACE VIEW v1 AS SELECT 1", 1_000L, 5, false);
        Metrics.updateDdlMetrics("CREATE OR REPLACE VIEW v2 AS SELECT 2", 2_000L, 7, false);
        Metrics.updateDdlMetrics("ALTER TABLE t ADD COLUMN c Int32", 3_000L, 9, false);
        assertEquals(1, metersNamed(MetricsConstants.CLICKHOUSE_SINK_DDL).size(),
                "distinct statements and timestamps share one fail=false series");

        Metrics.updateDdlMetrics("ALTER TABLE nope MODIFY COLUMN c String", 4_000L, 11, true);
        List<Meter> meters = metersNamed(MetricsConstants.CLICKHOUSE_SINK_DDL);
        assertEquals(2, meters.size(), "fail=true adds the second and last series");
        for (Meter m : meters) {
            assertTrue(m.getId().getTag("ddl") == null, "the DDL text is not a tag");
            assertTrue(m.getId().getTag("timestamp") == null, "the timestamp is not a tag");
            assertTrue(m.getId().getTag("fail") != null, "fail is the only tag");
        }
    }

    @Test
    @DisplayName("the binlog position gauge keeps one child across a file rotation")
    public void binlogPositionKeepsOneChildAcrossRotation() throws IOException {
        start();
        BlockMetaData first = new BlockMetaData();
        first.setBinLogFile("binary.000001");
        first.setBinLogPosition(10L);
        Metrics.updateMetrics(first);

        BlockMetaData second = new BlockMetaData();
        second.setBinLogFile("binary.000002");
        second.setBinLogPosition(20L);
        Metrics.updateMetrics(second);

        List<String> files = childLabels(MetricsConstants.CLICKHOUSE_SINK_BINLOG_POS, "file");
        assertEquals(1, files.size(), "one child, not one per rotation: " + files);
        assertEquals("binary.000002", files.get(0), "the child is the live file");

        // Same file again: still one child, position updated.
        second.setBinLogPosition(30L);
        Metrics.updateMetrics(second);
        assertEquals(1, childLabels(MetricsConstants.CLICKHOUSE_SINK_BINLOG_POS, "file").size());
    }

    @Test
    @DisplayName("stop() closes the registry, and a second initialize() closes the previous registry before opening a new one")
    public void registryIsReleasedOnStopAndOnReinitialize() throws IOException {
        start();
        PrometheusMeterRegistry first = Metrics.meterRegistry();
        assertTrue(Metrics.isRegistryOpen());
        assertFalse(first.isClosed());

        // An in-process engine restart initializes again without stopping.
        start();
        PrometheusMeterRegistry second = Metrics.meterRegistry();
        assertNotSame(first, second, "a restart gets a fresh registry");
        assertTrue(first.isClosed(), "the previous registry was closed, not leaked");
        assertTrue(Metrics.isRegistryOpen());

        Metrics.stop();
        assertTrue(second.isClosed(), "stop() closes the current registry");
        assertFalse(Metrics.isRegistryOpen());
    }
}

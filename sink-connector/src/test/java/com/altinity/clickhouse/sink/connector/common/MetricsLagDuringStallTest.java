package com.altinity.clickhouse.sink.connector.common;

import com.altinity.clickhouse.sink.connector.model.BlockMetaData;
import io.prometheus.client.Collector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Enumeration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 10.03 section 6: the Prometheus lag gauge during a writer stall.
 *
 * <p>{@code clickhouse_sink_db_lag} is set only by {@code Metrics.updateMetrics},
 * which runs after a batch was written. While every worker retries a failing
 * batch (TOO_MANY_PARTS, a missing column, ClickHouse down -- spec 10.02 retries
 * without a cap) nothing is written, the gauge keeps the lag of the last
 * successful batch, and an alert on it -- the alert {@code doc/Monitoring.md}
 * recommends -- never fires. A lag metric must grow with the wall clock while
 * nothing is written.</p>
 */
public class MetricsLagDuringStallTest {

    private static final String TOPIC = "db.orders";

    @AfterEach
    public void stopMetrics() {
        Metrics.stop();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static double lagSample() {
        Enumeration<Collector.MetricFamilySamples> families =
                Metrics.meterRegistry().getPrometheusRegistry().metricFamilySamples();
        while (families.hasMoreElements()) {
            Collector.MetricFamilySamples family = families.nextElement();
            if (!MetricsConstants.CLICKHOUSE_DB_SINK_LAG.equals(family.name)) {
                continue;
            }
            for (Collector.MetricFamilySamples.Sample sample : family.samples) {
                int i = sample.labelNames.indexOf(MetricsConstants.TOPIC);
                if (i >= 0 && TOPIC.equals(sample.labelValues.get(i))) {
                    return sample.value;
                }
            }
        }
        throw new AssertionError("no " + MetricsConstants.CLICKHOUSE_DB_SINK_LAG + " sample for " + TOPIC);
    }

    private static void writeBatchWithLag(long lagMs) {
        BlockMetaData bmd = new BlockMetaData();
        bmd.getSourceToCHLag().put(TOPIC, lagMs);
        Metrics.updateMetrics(bmd);
    }

    @Test
    @DisplayName("the lag gauge reports the lag of the last written batch (pinned)")
    public void lagGaugeReportsTheLastWrittenBatch() throws IOException {
        Metrics.initialize("true", String.valueOf(freePort()));
        writeBatchWithLag(1000L);
        assertEquals(1000.0, lagSample(), 0.0);
    }

    @Test
    @Disabled("DEFECT FM-10.03-2: clickhouse_sink_db_lag is set only when a batch is written, so during a "
            + "writer stall it freezes at the last successful batch's lag and a threshold alert on it never fires")
    @DisplayName("the lag gauge keeps growing while nothing is written")
    public void lagGaugeGrowsWhileNothingIsWritten() throws Exception {
        Metrics.initialize("true", String.valueOf(freePort()));
        writeBatchWithLag(1000L);

        // The writers stall: no batch is written for 1.5 s.
        Thread.sleep(1500L);

        assertTrue(lagSample() >= 2000.0, "the lag gauge still reads " + lagSample() + " ms after 1.5 s "
                + "without a write: a stalled replica reports the lag it had when it stopped");
    }
}

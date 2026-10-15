package com.altinity.clickhouse.debezium.embedded.cdc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 10.03 section 6: {@code Seconds_Behind_Source} as served by the REST
 * {@code /status} endpoint (and therefore by {@code sink-connector-client
 * show_replica_status}).
 *
 * <p>The value is {@code ReplicationStatusSingleton.getReplicationLag() / 1000},
 * a number the Debezium thread stores when it CONVERTS a row
 * ({@code now - ts_ms} at that instant, DebeziumChangeEventCapture). It is not
 * recomputed when read, and it is written at capture, not when the row reaches
 * ClickHouse or its offset is committed. When the writers stall, the reader
 * keeps converting until the handoff cap pauses it, and from then on the value
 * is frozen at the small lag of the last captured row.</p>
 */
public class StatusLagDuringStallTest {

    @AfterEach
    public void reset() {
        ReplicationStatusSingleton.getInstance().setReplicationStatus(new ReplicationStatus());
    }

    @Test
    @DisplayName("the /status lag is the value stored at the last capture (pinned)")
    public void statusLagIsTheStoredCaptureValue() {
        ReplicationStatusSingleton rss = ReplicationStatusSingleton.getInstance();
        rss.setReplicationLag(1000L);
        assertEquals(1000L, rss.getReplicationLag());
    }

    @Test
    @Disabled("DEFECT FM-10.03-4: /status Seconds_Behind_Source is stored at capture time and never recomputed, "
            + "so it freezes at the last captured row's lag while the writers stall or the reader is paused")
    @DisplayName("the /status lag grows with the clock while nothing new is captured")
    public void statusLagGrowsWhileNothingIsCaptured() throws Exception {
        ReplicationStatusSingleton rss = ReplicationStatusSingleton.getInstance();
        long ts = System.currentTimeMillis() - 1000L;
        rss.setLastRecordTimestamp(ts);
        rss.setReplicationLag(1000L);

        Thread.sleep(1200L);

        assertTrue(rss.getReplicationLag() >= 2000L, "Seconds_Behind_Source still reports "
                + rss.getReplicationLag() + " ms, 1.2 s after the last capture: a stalled connector reports "
                + "the lag it had when it stopped");
    }
}

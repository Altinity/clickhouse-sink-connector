package com.altinity.clickhouse.sink.connector.executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A batch handed to the asynchronous consumers must count as unwritten from
 * the instant it is handed off, not from the instant a consumer gets round to
 * registering it.
 *
 * <p>{@code ClickHouseBatchRunnable} polls a batch off the handoff queue and
 * only registers it in {@code inFlightBatches} once it reaches
 * {@code processBatch} -- after the replication-history write. Between the
 * poll and that registration the batch is in NEITHER the queue nor the map.
 * A quiescence check evaluated in that window sees both empty and concludes
 * nothing is outstanding.</p>
 *
 * <p>That matters because the control-record offset commit added for #1379
 * uses exactly this predicate to decide whether committing a heartbeat is
 * safe. A heartbeat carries the connector's CURRENT position, so committing
 * one while a polled-but-unwritten batch exists advances the offset past
 * those rows and loses them on a crash -- the failure mode of #1285. The
 * queue-and-map checks alone cannot close the window; the handoff counter
 * can, because the PRODUCER increments it before the batch is visible to any
 * consumer at all.</p>
 */
public class HandedOffBatchVisibilityTest {

    /**
     * The maps and the counter are static, and surefire shares one JVM across
     * the module, so another test in this class -- or
     * {@code DebeziumOffsetManagementTest}, which calls
     * {@code addToBatchTimestamps} and leaves entries behind -- can otherwise
     * poison these assertions. Clear all three so each case starts genuinely
     * empty rather than depending on execution order.
     */
    @BeforeEach
    public void reset() {
        DebeziumOffsetManagement.inFlightBatches.clear();
        DebeziumOffsetManagement.completedBatches.clear();
        for (int i = 0; i < 1024 && DebeziumOffsetManagement.hasUnwrittenBatches(); i++) {
            DebeziumOffsetManagement.batchHandoffFailed();
        }
        assertFalse("test isolation: the pipeline must start with nothing outstanding",
                DebeziumOffsetManagement.hasUnwrittenBatches());
    }

    @Test
    @DisplayName("A handed-off batch reads as unwritten even before a consumer registers it (#1379)")
    public void handedOffBatchIsVisibleBeforeConsumerRegistersIt() {
        // Producer hands a batch off. No consumer has touched it yet, so it
        // is in no map -- exactly the window the race lives in.
        DebeziumOffsetManagement.batchHandedOff();

        assertTrue("a batch handed to the consumers must read as unwritten immediately; "
                        + "otherwise a control-record offset can be committed past rows that "
                        + "are polled but not yet written (#1285 data loss)",
                DebeziumOffsetManagement.hasUnwrittenBatches());

        // Acknowledged -> no longer blocks a control-record commit.
        DebeziumOffsetManagement.batchHandoffFailed();
        assertFalse("an acknowledged batch must stop blocking the commit",
                DebeziumOffsetManagement.hasUnwrittenBatches());
    }

    @Test
    @DisplayName("Every routed group is counted, so siblings still block the commit")
    public void eachRoutedGroupCountsSeparately() {
        // Hash routing splits one list into N independently-acknowledged
        // groups. Counting the list once would under-count and let a commit
        // through while sibling groups were still unwritten.
        DebeziumOffsetManagement.batchHandedOff();
        DebeziumOffsetManagement.batchHandedOff();
        DebeziumOffsetManagement.batchHandedOff();

        DebeziumOffsetManagement.batchHandoffFailed();
        assertTrue("two routed groups are still outstanding",
                DebeziumOffsetManagement.hasUnwrittenBatches());

        DebeziumOffsetManagement.batchHandoffFailed();
        assertTrue("one routed group is still outstanding",
                DebeziumOffsetManagement.hasUnwrittenBatches());

        DebeziumOffsetManagement.batchHandoffFailed();
        assertFalse("all routed groups acknowledged", DebeziumOffsetManagement.hasUnwrittenBatches());
    }

    @Test
    @DisplayName("The counter never goes negative, so a double release cannot fake quiescence")
    public void counterFloorsAtZero() {
        // A batch parked in completedBatches and later retried can reach the
        // acknowledgement path more than once. Going negative would mask a
        // genuinely outstanding batch -- the one unsafe direction.
        DebeziumOffsetManagement.batchHandoffFailed();
        DebeziumOffsetManagement.batchHandoffFailed();

        DebeziumOffsetManagement.batchHandedOff();
        assertTrue("an outstanding batch must still be visible after spurious releases",
                DebeziumOffsetManagement.hasUnwrittenBatches());

        DebeziumOffsetManagement.batchHandoffFailed();
        assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches());
    }
}

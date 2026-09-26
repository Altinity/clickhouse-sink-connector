package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.executor.OffsetTestSupport.RecordingCommitter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.altinity.clickhouse.sink.connector.executor.OffsetTestSupport.record;
import static com.altinity.clickhouse.sink.connector.executor.OffsetTestSupport.unit;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A batch handed to the asynchronous consumers must count as unwritten from
 * the instant it is handed off, not from the instant a consumer gets round to
 * picking it up.
 *
 * <p>{@code ClickHouseBatchRunnable} polls a batch off the handoff queue and
 * only reaches its write after the replication-history step; a quiescence
 * check evaluated in that window would otherwise see an empty queue and no
 * in-flight batch and conclude nothing is outstanding. That matters because
 * the control-record offset commit (#1379) uses exactly this predicate to
 * decide whether committing a heartbeat is safe: a heartbeat carries the
 * connector's CURRENT position, so committing one while a polled-but-unwritten
 * batch exists advances the offset past those rows and loses them on a crash
 * (#1285). The handoff sequence closes the window because the PRODUCER
 * registers it before the batch is visible to any consumer at all.</p>
 */
public class HandedOffBatchVisibilityTest {

    /**
     * The FIFO state is static and surefire shares one JVM across the module,
     * so clear it so each case starts genuinely empty rather than depending on
     * execution order.
     */
    @BeforeEach
    public void reset() {
        OffsetTestSupport.resetFifo();
        assertFalse("test isolation: the pipeline must start with nothing outstanding",
                DebeziumOffsetManagement.hasUnwrittenBatches());
    }

    @Test
    @DisplayName("A handed-off batch reads as unwritten before any consumer touches it (#1379)")
    public void handedOffBatchIsVisibleBeforeConsumerPicksItUp() throws Exception {
        RecordingCommitter committer = new RecordingCommitter();
        List<ClickHouseStruct> batch = unit(committer, 100L, "r1");

        // Producer hands the batch off. No consumer has touched it yet --
        // exactly the window the race lives in.
        DebeziumOffsetManagement.registerHandoff(batch, Collections.singletonList(batch));

        assertTrue("a batch handed to the consumers must read as unwritten immediately; "
                        + "otherwise a control-record offset can be committed past rows that "
                        + "are polled but not yet written (#1285 data loss)",
                DebeziumOffsetManagement.hasUnwrittenBatches());

        // Written and acknowledged -> no longer blocks a control-record commit.
        DebeziumOffsetManagement.checkIfBatchCanBeCommitted(batch);
        assertFalse("an acknowledged batch must stop blocking the commit",
                DebeziumOffsetManagement.hasUnwrittenBatches());
    }

    @Test
    @DisplayName("Every routed group is counted: the unit blocks the commit until ALL its groups are written")
    public void eachRoutedGroupCountsSeparately() throws Exception {
        RecordingCommitter committer = new RecordingCommitter();
        // Hash routing splits one list into N independently-written groups.
        ClickHouseStruct a = record("a", 100L, committer);
        ClickHouseStruct b = record("b", 100L, committer);
        ClickHouseStruct c = record("c", 100L, committer);
        c.setLastRecordInBatch(true);
        List<ClickHouseStruct> ga = new ArrayList<>(Collections.singletonList(a));
        List<ClickHouseStruct> gb = new ArrayList<>(Collections.singletonList(b));
        List<ClickHouseStruct> gc = new ArrayList<>(Collections.singletonList(c));
        DebeziumOffsetManagement.registerHandoff(Arrays.asList(a, b, c), Arrays.asList(ga, gb, gc));

        DebeziumOffsetManagement.checkIfBatchCanBeCommitted(ga);
        assertTrue("two routed groups are still unwritten",
                DebeziumOffsetManagement.hasUnwrittenBatches());

        DebeziumOffsetManagement.checkIfBatchCanBeCommitted(gb);
        assertTrue("one routed group is still unwritten",
                DebeziumOffsetManagement.hasUnwrittenBatches());
        assertTrue("nothing may be staged before the whole unit is written",
                committer.processed.isEmpty());

        DebeziumOffsetManagement.checkIfBatchCanBeCommitted(gc);
        assertFalse("all routed groups written and the unit acknowledged",
                DebeziumOffsetManagement.hasUnwrittenBatches());
    }

    @Test
    @DisplayName("A written-but-parked batch still counts as outstanding")
    public void parkedBatchStillBlocksTheCommit() throws Exception {
        RecordingCommitter committer = new RecordingCommitter();
        List<ClickHouseStruct> older = unit(committer, 100L, "o1");
        List<ClickHouseStruct> younger = unit(committer, 100L, "y1");
        DebeziumOffsetManagement.registerHandoff(older, Collections.singletonList(older));
        DebeziumOffsetManagement.registerHandoff(younger, Collections.singletonList(younger));

        // The younger one is written first and parked behind the older one.
        assertFalse(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(younger));
        assertTrue("a parked batch has no acknowledged offset yet, so the pipeline is not quiescent",
                DebeziumOffsetManagement.hasUnwrittenBatches());

        assertTrue(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(older));
        assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches());
    }
}

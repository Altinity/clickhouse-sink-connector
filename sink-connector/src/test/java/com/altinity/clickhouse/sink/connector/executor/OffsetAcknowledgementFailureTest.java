package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failure mode FM-09.01-4 (spec 09.01 section 6): the offset commit races a
 * failure -- the committer throws while the drain acknowledges the head unit
 * (Debezium's {@code "Timed out while waiting for committing task offset"}, a
 * JDBC offset store that cannot be reached). The worker has already dropped the
 * written batch (WRITTEN-ONCE), so the acknowledgement is not retried by the
 * worker. What must hold: the unit stays outstanding (no control-record commit
 * and no younger unit can pass it, spec 09.04) and the next drain -- the next
 * unit any worker writes -- acknowledges it again, in handoff order, before the
 * younger one.
 */
public class OffsetAcknowledgementFailureTest {

    /** A committer whose first {@code markBatchFinished} fails like a timed-out offset flush. */
    static final class FailingOnceCommitter
            implements DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> {
        final List<String> processed = new ArrayList<>();
        int finished = 0;
        int failuresLeft = 1;

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record) {
            processed.add(record.destination());
        }

        @Override
        public void markBatchFinished() {
            if (failuresLeft > 0) {
                failuresLeft--;
                throw new IllegalStateException("Timed out while waiting for committing task offset (simulated)");
            }
            finished++;
        }

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record,
                                  DebeziumEngine.Offsets sourceOffsets) {
            markProcessed(record);
        }

        @Override
        public DebeziumEngine.Offsets buildOffsets() {
            return (key, value) -> { };
        }
    }

    @BeforeEach
    public void reset() {
        OffsetTestSupport.resetFifo();
    }

    private static List<ClickHouseStruct> unit(FailingOnceCommitter committer, String... names) {
        List<ClickHouseStruct> batch = new ArrayList<>();
        for (String name : names) {
            ClickHouseStruct s = new ClickHouseStruct();
            s.setTopic("srv.db." + name);
            s.setDebezium_ts_ms(100L);
            s.setCommitter(committer);
            s.setSourceRecord(OffsetTestSupport.event(name));
            batch.add(s);
        }
        batch.get(batch.size() - 1).setLastRecordInBatch(true);
        return batch;
    }

    @Test
    @DisplayName("a committer failure leaves the unit outstanding and the next drain acknowledges it first")
    public void failedAcknowledgementIsRetriedByTheNextDrainInHandoffOrder() throws Exception {
        FailingOnceCommitter committer = new FailingOnceCommitter();
        List<ClickHouseStruct> a = unit(committer, "a1", "a2");
        long seqA = DebeziumOffsetManagement.registerHandoff(a, Collections.singletonList(a));

        assertThrows(IllegalStateException.class, () -> DebeziumOffsetManagement.checkIfBatchCanBeCommitted(a),
                "the commit error propagates to the worker (spec 09.02)");
        assertTrue(DebeziumOffsetManagement.hasUnwrittenBatches(),
                "the unit whose commit failed is still outstanding: no control-record offset may pass it");
        assertTrue(DebeziumOffsetManagement.completedUnits.containsKey(seqA),
                "the written unit is parked for the next drain, never handed back to a worker");
        assertEquals(0, committer.finished);

        List<ClickHouseStruct> b = unit(committer, "b1");
        DebeziumOffsetManagement.registerHandoff(b, Collections.singletonList(b));
        assertTrue(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(b),
                "the next written unit drains the FIFO");

        assertEquals(Arrays.asList("a1", "a2", "a1", "a2", "b1"), committer.processed,
                "A is staged again, in order, before B: the durable position never passes A");
        assertEquals(2, committer.finished, "one successful markBatchFinished per unit");
        assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches());
    }
}

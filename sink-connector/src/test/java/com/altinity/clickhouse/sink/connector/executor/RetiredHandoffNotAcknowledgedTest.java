package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A unit handed off by an engine that has since stopped is RETIRED by
 * {@code reset()}: a worker that writes one of its groups afterwards is
 * answered "not acknowledged" and moves on -- the stopped engine's committer
 * is never invoked (spec 09.01 section 3.8 item 5).
 *
 * <p><b>The defect.</b> The engine's completion-callback retry recreates the
 * engine on the same instance and keeps the worker pool, and the pool keeps
 * the stopped engine's batches. The engine's offset store closes with the
 * engine, so the first batch a worker finished after the stop was
 * acknowledged through a committer whose store was gone:
 * {@code JdbcOffsetBackingStore.set} threw {@code NullPointerException}
 * ("this.executor is null") inside {@code OffsetStorageWriter.doFlush}, which
 * left the writer "already flushing" for good; the worker's next
 * acknowledgement met that state, the worker died on it, and every recreated
 * engine failed on "Sink worker 1 of 10 is dead" until the process exited.
 * Observed twice in one day on one deployment.</p>
 *
 * <p><b>The rule.</b> {@code reset()} records the retirement watermark
 * (every sequence assigned so far); {@code registerHandoff} stamps the
 * sequence on the rows; a written group with no unit whose stamp is below
 * the watermark is retired -- {@code false}, one INFO line, no committer
 * call. Everything else is unchanged: a stamp at or above the watermark, or
 * an unstamped batch carrying a committer, is still the loud
 * {@code IllegalStateException}; an unstamped batch without a committer (the
 * Kafka Connect path) is still {@code true}.</p>
 */
public class RetiredHandoffNotAcknowledgedTest {

    /**
     * A committer whose engine has stopped: its offset store is closed, so
     * every call throws exactly what Debezium's JDBC offset store threw in
     * production.
     */
    private static final class ClosedStoreCommitter
            implements DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> {
        int calls = 0;

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record) {
            calls++;
            throw new NullPointerException("Cannot invoke \"java.util.concurrent.ExecutorService"
                    + ".submit(java.util.concurrent.Callable)\" because \"this.executor\" is null");
        }

        @Override
        public void markBatchFinished() {
            calls++;
            throw new NullPointerException("Cannot invoke \"java.util.concurrent.ExecutorService"
                    + ".submit(java.util.concurrent.Callable)\" because \"this.executor\" is null");
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
    @AfterEach
    public void quiescent() {
        OffsetTestSupport.resetFifo();
    }

    private static List<ClickHouseStruct> unit(
            DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> committer,
            String... names) {
        List<ClickHouseStruct> b = new ArrayList<>();
        for (String n : names) {
            ClickHouseStruct s = new ClickHouseStruct();
            s.setTopic("srv.db." + n);
            s.setDebezium_ts_ms(1L);
            s.setCommitter(committer);
            s.setSourceRecord(OffsetTestSupport.event(n));
            b.add(s);
        }
        b.get(b.size() - 1).setLastRecordInBatch(true);
        return b;
    }

    @Test
    @DisplayName("A group written after its engine stopped is not acknowledged and not an error: "
            + "the closed-store committer is never called")
    public void writtenAfterRetirementIsNotAcknowledgedAndNotAnError() throws InterruptedException {
        ClosedStoreCommitter committer = new ClosedStoreCommitter();
        List<ClickHouseStruct> unit = unit(committer, "orders", "orders");
        long sequence = DebeziumOffsetManagement.registerHandoff(unit, Collections.singletonList(unit));
        for (ClickHouseStruct record : unit) {
            assertEquals(sequence, record.getHandoffSequence(),
                    "the handoff sequence is stamped on every row of the unit");
        }
        assertFalse(DebeziumOffsetManagement.isRetired(unit), "a unit of a live engine is not retired");

        // The engine stops: everything it handed off is retired (what the
        // completion callback and connectorStopped do).
        assertEquals(1, DebeziumOffsetManagement.reset());
        assertTrue(DebeziumOffsetManagement.isRetired(unit));

        // A worker finishes writing the group afterwards.
        boolean acknowledged = DebeziumOffsetManagement.checkIfBatchCanBeCommitted(unit);

        assertFalse(acknowledged,
                "a retired unit is never acknowledged; the restarted engine redelivers it");
        assertEquals(0, committer.calls,
                "the stopped engine's committer must not be invoked: its store is closed (NPE), and "
                        + "the failed flush would leave the OffsetStorageWriter 'already flushing' for good");
        assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches(),
                "nothing is left outstanding to park the next engine's units behind");
    }

    @Test
    @DisplayName("A unit handed off after the retirement is acknowledged normally: the watermark is exclusive")
    public void unitHandedOffAfterRetirementIsAcknowledged() throws InterruptedException {
        ClosedStoreCommitter dead = new ClosedStoreCommitter();
        List<ClickHouseStruct> old = unit(dead, "old");
        DebeziumOffsetManagement.registerHandoff(old, Collections.singletonList(old));
        DebeziumOffsetManagement.reset();

        OffsetTestSupport.RecordingCommitter live = new OffsetTestSupport.RecordingCommitter();
        List<ClickHouseStruct> fresh = OffsetTestSupport.unit(live, 2L, "fresh");
        DebeziumOffsetManagement.registerHandoff(fresh, Collections.singletonList(fresh));
        assertFalse(DebeziumOffsetManagement.isRetired(fresh),
                "a sequence assigned after the reset is not retired by it");

        assertTrue(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(fresh));
        assertEquals(1, live.batchesFinished, "the new engine's unit is acknowledged at once");
        assertEquals(0, dead.calls);

        assertFalse(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(old),
                "the old unit, reported later still, is just retired");
        assertEquals(0, dead.calls);
    }

    @Test
    @DisplayName("A committer-bearing batch that was never handed off is still rejected loudly: "
            + "retirement does not hide a producer bug")
    public void neverHandedOffIsStillRejected() {
        ClosedStoreCommitter dead = new ClosedStoreCommitter();
        List<ClickHouseStruct> old = unit(dead, "old");
        DebeziumOffsetManagement.registerHandoff(old, Collections.singletonList(old));
        DebeziumOffsetManagement.reset();

        List<ClickHouseStruct> unregistered =
                OffsetTestSupport.unit(new OffsetTestSupport.RecordingCommitter(), 3L, "orphan");
        assertEquals(-1L, DebeziumOffsetManagement.handoffSequenceOf(unregistered));
        assertFalse(DebeziumOffsetManagement.isRetired(unregistered));
        assertThrows(IllegalStateException.class,
                () -> DebeziumOffsetManagement.checkIfBatchCanBeCommitted(unregistered));
    }

    @Test
    @DisplayName("A batch without a committer and without a handoff (the Kafka Connect path) is unchanged: true")
    public void kafkaConnectPathIsUnchanged() throws InterruptedException {
        DebeziumOffsetManagement.reset();
        ClickHouseStruct s = new ClickHouseStruct();
        s.setTopic("srv.db.kafka");
        List<ClickHouseStruct> batch = Collections.singletonList(s);
        assertFalse(DebeziumOffsetManagement.isRetired(batch));
        assertTrue(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(batch));
    }
}

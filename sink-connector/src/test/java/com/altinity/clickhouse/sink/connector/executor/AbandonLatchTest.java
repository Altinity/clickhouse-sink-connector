package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 09.01 section 3.6 / spec 09.04 FM-09.04-4: a {@code reset()} that
 * abandons units leaves the FIFO reading as EMPTY while the abandoned units'
 * rows are still unaccounted for, so the FIFO's emptiness alone is not proof
 * that the connector's current position is safe to commit.
 *
 * <p><b>The defect.</b> {@code hasUnwrittenBatches()} is exactly
 * {@code !outstandingSequences.isEmpty()}, and {@code reset()} clears that set
 * without any of the abandoned units being acknowledged. The instant the FIFO
 * was emptied the pipeline read as quiescent, so the next heartbeat -- whose
 * offset is the connector's CURRENT position, past the abandoned rows --
 * committed it. A restart after that commit resumed after rows that never
 * reached ClickHouse. On 2026-11-10 one deployment logged 23 such resets in
 * five hours while a missing target table forced the handoff hard cap.</p>
 *
 * <p><b>The rule.</b> The latch is a predicate of its own, not part of
 * {@code hasUnwrittenBatches()}: the stop-drain, the DDL-drain wait and the
 * start-time refusal must keep seeing an abandoned FIFO as empty (a latch
 * there hangs the drain and refuses the restart that recovers from the
 * abandonment). Only the control-record commit consumes it.</p>
 */
public class AbandonLatchTest {

    @BeforeEach
    @AfterEach
    public void quiescent() {
        OffsetTestSupport.resetFifo();
    }

    @Test
    @DisplayName("A reset that abandons a unit arms the latch, and the FIFO still reports itself empty")
    public void aResetThatAbandonsAUnitArmsTheLatch() {
        OffsetTestSupport.RecordingCommitter committer = new OffsetTestSupport.RecordingCommitter();
        List<ClickHouseStruct> unit = OffsetTestSupport.unit(committer, 1L, "orders");
        DebeziumOffsetManagement.registerHandoff(unit, Collections.singletonList(unit));

        assertFalse(DebeziumOffsetManagement.hasUnprovenAbandonment(),
                "a live engine's outstanding unit is not an abandonment");
        assertTrue(DebeziumOffsetManagement.hasUnwrittenBatches(),
                "the handed-off unit is outstanding");

        assertEquals(1, DebeziumOffsetManagement.reset());

        assertTrue(DebeziumOffsetManagement.hasUnprovenAbandonment(),
                "the abandoned unit's rows are unaccounted for, so the latch is armed");
        assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches(),
                "the FIFO itself is empty -- which is exactly why the latch has to be a separate "
                        + "predicate: the drain and setup() must still see an empty FIFO");
    }

    @Test
    @DisplayName("A reset with nothing outstanding arms nothing")
    public void aResetWithNothingOutstandingArmsNothing() {
        assertEquals(0, DebeziumOffsetManagement.reset());
        assertFalse(DebeziumOffsetManagement.hasUnprovenAbandonment(),
                "nothing was abandoned, so there is nothing to prove");
    }

    @Test
    @DisplayName("Acknowledging a unit assigned after the reset clears the latch")
    public void acknowledgingAPostResetUnitClearsTheLatch() throws InterruptedException {
        List<ClickHouseStruct> old = OffsetTestSupport.unit(
                new OffsetTestSupport.RecordingCommitter(), 1L, "old");
        DebeziumOffsetManagement.registerHandoff(old, Collections.singletonList(old));
        DebeziumOffsetManagement.reset();
        assertTrue(DebeziumOffsetManagement.hasUnprovenAbandonment(), "armed by the abandonment");

        OffsetTestSupport.RecordingCommitter live = new OffsetTestSupport.RecordingCommitter();
        List<ClickHouseStruct> fresh = OffsetTestSupport.unit(live, 2L, "fresh");
        DebeziumOffsetManagement.registerHandoff(fresh, Collections.singletonList(fresh));

        assertTrue(DebeziumOffsetManagement.hasUnprovenAbandonment(),
                "a handoff is a read, not a write: the latch stays armed until something is durably "
                        + "written and acknowledged");

        assertTrue(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(fresh),
                "the post-reset unit is written and acknowledged");
        assertFalse(DebeziumOffsetManagement.hasUnprovenAbandonment(),
                "the redelivered stream reached the FIFO's head, so the current position is safe again");
    }

    @Test
    @DisplayName("Acknowledging a unit that predates the reset does not clear the latch")
    public void acknowledgingAPreResetUnitDoesNotClearTheLatch() throws InterruptedException {
        OffsetTestSupport.RecordingCommitter committer = new OffsetTestSupport.RecordingCommitter();
        List<ClickHouseStruct> unit = OffsetTestSupport.unit(committer, 1L, "orders");
        DebeziumOffsetManagement.registerHandoff(unit, Collections.singletonList(unit));
        DebeziumOffsetManagement.reset();

        // A worker that still holds the retired unit reports it written later.
        assertFalse(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(unit),
                "a retired unit is never acknowledged");
        assertTrue(DebeziumOffsetManagement.hasUnprovenAbandonment(),
                "a retired unit's write proves nothing about the redelivered stream: the latch stays "
                        + "armed until a unit assigned AFTER the reset is acknowledged");
    }

    @Test
    @DisplayName("The first unit assigned AFTER the reset clears the latch: the boundary is inclusive")
    public void theFirstUnitAssignedAfterTheResetClearsTheLatch() throws InterruptedException {
        // Two units are outstanding when the reset runs, so the retirement
        // watermark is one past the second one -- and the FIRST unit of the new
        // engine is stamped with exactly that value, not a greater one.
        List<ClickHouseStruct> first = OffsetTestSupport.unit(
                new OffsetTestSupport.RecordingCommitter(), 1L, "first");
        DebeziumOffsetManagement.registerHandoff(first, Collections.singletonList(first));
        List<ClickHouseStruct> second = OffsetTestSupport.unit(
                new OffsetTestSupport.RecordingCommitter(), 2L, "second");
        DebeziumOffsetManagement.registerHandoff(second, Collections.singletonList(second));

        // The counter is process-wide and is never reset (sequences stay unique
        // for the life of the JVM), so the assertion is relative.
        long watermark = DebeziumOffsetManagement.handoffSequenceOf(second) + 1;
        assertEquals(DebeziumOffsetManagement.handoffSequenceOf(first) + 1,
                DebeziumOffsetManagement.handoffSequenceOf(second), "sequences are consecutive");

        assertEquals(2, DebeziumOffsetManagement.reset());
        assertTrue(DebeziumOffsetManagement.hasUnprovenAbandonment(), "both units were abandoned");

        OffsetTestSupport.RecordingCommitter live = new OffsetTestSupport.RecordingCommitter();
        List<ClickHouseStruct> boundary = OffsetTestSupport.unit(live, 3L, "boundary");
        DebeziumOffsetManagement.registerHandoff(boundary, Collections.singletonList(boundary));
        assertEquals(watermark, DebeziumOffsetManagement.handoffSequenceOf(boundary),
                "the new engine's first unit carries the watermark's own value, not a greater one");

        assertTrue(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(boundary),
                "a unit assigned at the watermark belongs to the new engine");
        assertFalse(DebeziumOffsetManagement.hasUnprovenAbandonment(),
                "the boundary comparison is inclusive (seq >= watermark), so this unit clears the "
                        + "latch: a strict '>' would leave the connector unable to commit forever");
    }
}

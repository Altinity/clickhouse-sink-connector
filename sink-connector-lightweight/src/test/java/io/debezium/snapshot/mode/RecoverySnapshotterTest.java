package io.debezium.snapshot.mode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Recovery must preserve the schema belonging to the retained offset (spec 01.11). */
class RecoverySnapshotterTest {
    @Test
    void onlyCompletedOffsetsWithRestoredTablesAvoidTheCurrentSchemaSnapshot() {
        for (boolean offset : new boolean[] {false, true}) {
            for (boolean running : new boolean[] {false, true}) {
                for (boolean restored : new boolean[] {false, true}) {
                    assertEquals(!(offset && !running && restored),
                            RecoverySchemaPolicy.needsSnapshot(offset, running, restored));
                }
            }
        }
    }

    @Test
    void missingAndInProgressOffsetsKeepNativeRecoveryBehavior() {
        HistoryPreservingRecoverySnapshotter snapshotter = new HistoryPreservingRecoverySnapshotter();
        assertEquals("history_preserving_recovery", snapshotter.name());
        assertTrue(snapshotter.shouldSnapshotSchema(false, false));
        assertTrue(snapshotter.shouldSnapshotSchema(true, true));
        assertFalse(snapshotter.shouldSnapshotData(true, false));
        assertTrue(snapshotter.shouldSnapshotOnSchemaError());
        assertTrue(snapshotter.shouldStream());
        assertEquals("recovery", new RecoverySnapshotter().name(), "built-in recovery is not replaced");
        assertTrue(new RecoverySnapshotter().shouldSnapshotSchema(true, false));
    }

    @Test
    void missingSchemaContextFailsRatherThanPretendingHistoryWasRestored() {
        HistoryPreservingRecoverySnapshotter snapshotter = new HistoryPreservingRecoverySnapshotter();
        assertThrows(NullPointerException.class, () -> snapshotter.shouldSnapshotSchema(true, false));
    }
}

package io.debezium.snapshot.mode;

/** Keep the schema of a completed checkpoint when history restored it (spec 01.11). */
final class RecoverySchemaPolicy {
    private RecoverySchemaPolicy() {
    }

    static boolean needsSnapshot(boolean offsetExists, boolean snapshotInProgress, boolean restoredTables) {
        return !offsetExists || snapshotInProgress || !restoredTables;
    }
}

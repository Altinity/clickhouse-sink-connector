package io.debezium.snapshot.mode;

import io.debezium.bean.StandardBeanNames;
import io.debezium.relational.HistorizedRelationalDatabaseSchema;
import io.debezium.schema.DatabaseSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opt-in recovery retaining history for an older offset (spec 01.11).
 *
 * <p>Extends the pinned Debezium 3.1.3.Final recovery snapshotter without
 * changing built-in recovery. A caller deliberately replacing history or
 * adding capture scope keeps the original mode. This mode retains definitions
 * already restored for the checkpoint, before any current-schema snapshot
 * can replace them. Missing or already-incorrect history is not repaired.</p>
 */
public class HistoryPreservingRecoverySnapshotter extends RecoverySnapshotter {
    private static final Logger LOGGER = LoggerFactory.getLogger(HistoryPreservingRecoverySnapshotter.class);

    @Override
    public String name() {
        return "history_preserving_recovery";
    }

    @Override
    public boolean shouldSnapshotSchema(boolean offsetExists, boolean snapshotInProgress) {
        boolean restoredTables = false;
        if (offsetExists && !snapshotInProgress) {
            DatabaseSchema<?> schema = beanRegistry.lookupByName(StandardBeanNames.DATABASE_SCHEMA, DatabaseSchema.class);
            restoredTables = schema instanceof HistorizedRelationalDatabaseSchema historical
                    && historical.tableInformationComplete();
        }
        boolean snapshot = RecoverySchemaPolicy.needsSnapshot(offsetExists, snapshotInProgress, restoredTables);
        if (!snapshot) {
            LOGGER.warn("Recovery is retaining the schema restored for the committed offset instead of "
                    + "replacing it with the current source schema. This does not repair missing or "
                    + "already-incorrect history (spec 01.11).");
        }
        return snapshot;
    }
}

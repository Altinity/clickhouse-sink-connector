package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the #1379 offset-progress hardening.
 *
 * <p>Every batch handed to the writers must carry exactly one terminal record so
 * {@code DebeziumOffsetManagement.acknowledgeRecords} calls
 * {@code markBatchFinished()} and the handoff's offset is flushed. When the
 * Debezium batch ends with a control record (heartbeat / transaction boundary) or
 * is split ahead of a DDL, the last handed-off row is not the last Debezium record
 * and would carry {@code isLastRecordInBatch() == false}; without a terminal
 * marker the offset commit is deferred to a later heartbeat, delaying (on an idle
 * source, stranding) snapshot completion. {@code markTerminalRecord} guarantees
 * the marker regardless of Debezium batch composition.</p>
 */
public class SnapshotOffsetProgressTest {

    private static ClickHouseStruct row() {
        return new ClickHouseStruct();
    }

    @Test
    @DisplayName("markTerminalRecord flags exactly the last row of the handed-off batch")
    public void marksExactlyTheLastRow() {
        List<ClickHouseStruct> batch = new ArrayList<>();
        ClickHouseStruct r0 = row();
        ClickHouseStruct r1 = row();
        ClickHouseStruct r2 = row();
        batch.add(r0);
        batch.add(r1);
        batch.add(r2);

        // Precondition: nothing flagged (mirrors a batch whose Debezium-last
        // record was a heartbeat, so no row was marked terminal).
        assertFalse(r0.isLastRecordInBatch());
        assertFalse(r1.isLastRecordInBatch());
        assertFalse(r2.isLastRecordInBatch());

        DebeziumChangeEventCapture.markTerminalRecord(batch);

        assertTrue(r2.isLastRecordInBatch(),
                "the last handed-off row must be the batch terminal so markBatchFinished() runs "
                        + "and the offset is flushed");
        assertFalse(r0.isLastRecordInBatch(), "only the last row may be the terminal");
        assertFalse(r1.isLastRecordInBatch(), "only the last row may be the terminal");
    }

    @Test
    @DisplayName("markTerminalRecord is a safe no-op on empty/null batches")
    public void safeOnEmptyOrNull() {
        assertDoesNotThrow(() -> DebeziumChangeEventCapture.markTerminalRecord(new ArrayList<>()));
        assertDoesNotThrow(() -> DebeziumChangeEventCapture.markTerminalRecord(null));
    }
}

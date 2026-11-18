package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.executor.DebeziumOffsetManagement;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 09.04 FM-09.04-4, at the GATE: a {@code reset()} that abandons a unit
 * leaves the FIFO empty while the abandoned unit's rows are unaccounted for,
 * and the FIFO's emptiness alone is not proof that the control record's
 * offset -- the connector's CURRENT position, past those rows -- is safe to
 * commit.
 *
 * <p>This lives in the lightweight module because the gate,
 * {@code DebeziumChangeEventCapture.commitControlRecordOffset}, is
 * package-private and only reachable from here. The latch's own transitions
 * are covered in the {@code sink-connector} module's {@code AbandonLatchTest};
 * this class proves the gate consumes the latch. Against 2.11.0, where the
 * gate was {@code !hasUnwrittenBatches()} alone,
 * {@link #aResetThatAbandonsAUnitWithholdsTheControlRecordCommit()} fails at
 * its first assertion: the FIFO read as quiescent and the heartbeat committed
 * past rows that never reached ClickHouse.</p>
 */
public class AbandonLatchGateTest {

    /** Records what the engine's committer was asked to do. */
    private static final class RecordingCommitter
            implements DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> {

        private final List<ChangeEvent<SourceRecord, SourceRecord>> processed = new ArrayList<>();
        private int batchesFinished = 0;

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record) {
            processed.add(record);
        }

        @Override
        public void markBatchFinished() {
            batchesFinished++;
        }

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record,
                                  DebeziumEngine.Offsets sourceOffsets) {
            processed.add(record);
        }

        @Override
        public DebeziumEngine.Offsets buildOffsets() {
            return (key, value) -> { };
        }
    }

    private static ChangeEvent<SourceRecord, SourceRecord> changeEvent(SourceRecord record) {
        return new ChangeEvent<SourceRecord, SourceRecord>() {
            @Override
            public SourceRecord key() {
                return null;
            }

            @Override
            public SourceRecord value() {
                return record;
            }

            @Override
            public String destination() {
                return record == null ? "db1.shop.orders" : record.topic();
            }

            @Override
            public Integer partition() {
                return null;
            }
        };
    }

    /** A heartbeat exactly as Debezium emits one: {@code ts_ms} only, no {@code op}. */
    private static ChangeEvent<SourceRecord, SourceRecord> heartbeat(long lsn) {
        Schema valueSchema = SchemaBuilder.struct()
                .name("io.debezium.connector.common.Heartbeat")
                .field("ts_ms", Schema.INT64_SCHEMA)
                .build();
        Struct value = new Struct(valueSchema);
        value.put("ts_ms", 1788182208680L);

        Map<String, Object> partition = new LinkedHashMap<>();
        partition.put("server", "sink-connector-manager");
        Map<String, Object> offset = new LinkedHashMap<>();
        offset.put("lsn", lsn);
        offset.put("txId", 3322L);
        offset.put("ts_usec", 1788182207848583L);

        return changeEvent(new SourceRecord(partition, offset,
                "__debezium-heartbeat.sink-connector-manager", 0,
                null, null, valueSchema, value));
    }

    /** A handed-off unit whose terminal record is wired to {@code committer}. */
    private static List<ClickHouseStruct> unit(RecordingCommitter committer) {
        ClickHouseStruct s = new ClickHouseStruct();
        s.setTopic("db1.shop.orders");
        s.setCommitter(committer);
        s.setSourceRecord(changeEvent(null));
        s.setLastRecordInBatch(true);
        return Collections.singletonList(s);
    }

    /**
     * The latch is static state and only an acknowledgement clears it, so a
     * test that armed it releases it the way production does: by handing off a
     * unit after the reset and letting it be written and acknowledged.
     */
    @AfterEach
    public void releaseTheLatch() throws Exception {
        if (!DebeziumOffsetManagement.hasUnprovenAbandonment()) {
            return;
        }
        RecordingCommitter committer = new RecordingCommitter();
        List<ClickHouseStruct> fresh = unit(committer);
        DebeziumOffsetManagement.registerHandoff(fresh, Collections.singletonList(fresh));
        assertTrue(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(fresh),
                "the post-reset unit is written and acknowledged, so it clears the latch");
    }

    @Test
    @DisplayName("A reset that abandons a unit withholds the control-record commit until a post-reset unit is written")
    public void aResetThatAbandonsAUnitWithholdsTheControlRecordCommit() throws Exception {
        // The replaced engine handed this off and no worker ever wrote it.
        List<ClickHouseStruct> abandoned = unit(new RecordingCommitter());
        DebeziumOffsetManagement.registerHandoff(abandoned, Collections.singletonList(abandoned));
        assertTrue(DebeziumOffsetManagement.hasUnwrittenBatches(), "sanity: the unit is outstanding");

        assertEquals(1, DebeziumOffsetManagement.reset(), "reset() abandons the outstanding unit");
        assertTrue(DebeziumOffsetManagement.hasUnprovenAbandonment(),
                "the abandoned unit's rows are unaccounted for");
        assertFalse(DebeziumOffsetManagement.hasUnwrittenBatches(),
                "the FIFO is empty -- which is exactly why the FIFO's emptiness cannot be the gate");

        // The replacement engine's heartbeat carries the connector's CURRENT
        // position, which is past the rows the abandoned unit never wrote.
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        RecordingCommitter committer = new RecordingCommitter();
        boolean committed = capture.commitControlRecordOffset(heartbeat(74130877960L), committer, false);

        assertFalse(committed, "an empty FIFO over an unproven abandonment must not commit the current "
                + "position: a restart after that commit resumes past rows that never reached ClickHouse");
        assertTrue(committer.processed.isEmpty(), "nothing may be staged with the engine");
        assertEquals(0, committer.batchesFinished);

        // The redelivered stream reaches the FIFO's head and is durably written.
        RecordingCommitter freshCommitter = new RecordingCommitter();
        List<ClickHouseStruct> fresh = unit(freshCommitter);
        DebeziumOffsetManagement.registerHandoff(fresh, Collections.singletonList(fresh));
        assertTrue(DebeziumOffsetManagement.checkIfBatchCanBeCommitted(fresh));
        assertFalse(DebeziumOffsetManagement.hasUnprovenAbandonment(),
                "a unit assigned after the reset proves the position is safe again");

        RecordingCommitter nextCommitter = new RecordingCommitter();
        assertTrue(capture.commitControlRecordOffset(heartbeat(74130877990L), nextCommitter, false),
                "with the latch released the next control record commits again");
        assertEquals(1, nextCommitter.processed.size());
        assertEquals(1, nextCommitter.batchesFinished);
    }

    @Test
    @DisplayName("A reset with nothing outstanding leaves the control-record commit open")
    public void aResetWithNothingOutstandingLeavesTheCommitOpen() throws Exception {
        assertEquals(0, DebeziumOffsetManagement.reset(), "nothing to abandon");
        assertFalse(DebeziumOffsetManagement.hasUnprovenAbandonment(),
                "a reset that abandons nothing must not arm the latch -- every quiet restart would "
                        + "otherwise stop the offset until the next write");

        RecordingCommitter committer = new RecordingCommitter();
        boolean committed = new DebeziumChangeEventCapture()
                .commitControlRecordOffset(heartbeat(74130877960L), committer, false);

        assertTrue(committed, "an idle pipeline with no abandonment commits its heartbeat");
        assertEquals(1, committer.processed.size());
        assertEquals(1, committer.batchesFinished);
    }

    @Test
    @DisplayName("A batch whose rows were handed off is still withheld whatever the latch says")
    public void handedOffRowsStillWithholdTheCommit() throws Exception {
        assertFalse(DebeziumOffsetManagement.hasUnprovenAbandonment(), "sanity: nothing armed");
        RecordingCommitter committer = new RecordingCommitter();

        boolean committed = new DebeziumChangeEventCapture()
                .commitControlRecordOffset(heartbeat(74130877960L), committer, true);

        assertFalse(committed, "a control record never commits ahead of rows from its own batch");
        assertTrue(committer.processed.isEmpty());
        assertEquals(0, committer.batchesFinished);
    }
}

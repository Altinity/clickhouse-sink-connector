package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression test for issue #1379 ("Initial Snapshot never finishes").
 *
 * <p>A Debezium batch carries more than row changes. A heartbeat has a Struct
 * value and no {@code op} field, so
 * {@link SourceRecordParserService#parse} returns null for it -- that null is
 * contract, not failure. Such a record was then dropped outright: never
 * {@code markProcessed}, never {@code markBatchFinished}.</p>
 *
 * <p>That is what stranded the end-of-snapshot state. Debezium marks the
 * snapshot complete AFTER the last snapshot row is emitted, so every snapshot
 * ROW still carries {@code snapshot=INITIAL, snapshot_completed=false}. The
 * completed state rides only on post-snapshot records, and on an idle source
 * those are exclusively heartbeats. Dropping them pinned
 * {@code replica_source_info} at {@code snapshot_completed=false} forever:
 * the status view never showed the snapshot finishing, and on restart
 * Debezium's {@code InitialSnapshotter} saw a snapshot still in progress and
 * re-ran the whole thing.</p>
 *
 * <p>Note this is NOT the defect fixed in #1416. That fix stopped a
 * NullPointerException and turned the drop into a clean, logged skip; the
 * record is still dropped, and the offset it carries is still never
 * committed. The reporter confirmed the symptom persists on 2.10.2, which
 * carries #1416.</p>
 *
 * <p>The offset must be committed only when nothing is in flight -- a
 * heartbeat carries the connector's CURRENT position, so committing it while
 * a row is unwritten would advance the offset past that row and lose it on a
 * crash (issue #1285). Both directions are asserted here.</p>
 */
public class ControlRecordOffsetCommitTest {

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
            return new DebeziumEngine.Offsets() {
                @Override
                public void set(String key, Object value) {
                }
            };
        }
    }

    /**
     * A heartbeat exactly as Debezium emits one: the documented heartbeat
     * value schema, a single {@code ts_ms} field, and no {@code op}. Its
     * source offset is the connector's post-snapshot position -- the offset
     * whose loss is the defect.
     */
    private static ChangeEvent<SourceRecord, SourceRecord> heartbeat(long lsn) {
        Schema valueSchema = SchemaBuilder.struct()
                .name("io.debezium.connector.common.Heartbeat")
                .field("ts_ms", Schema.INT64_SCHEMA)
                .build();
        Struct value = new Struct(valueSchema);
        value.put("ts_ms", 1788182208680L);

        Map<String, Object> partition = new LinkedHashMap<>();
        partition.put("server", "sink-connector-manager");

        // Post-snapshot offsets carry no "snapshot"/"snapshot_completed"
        // keys at all -- PostgresOffsetContext#getOffset omits them once
        // postSnapshotCompletion has cleared the snapshot type. Committing
        // this is precisely what lets a restart skip the snapshot.
        Map<String, Object> offset = new LinkedHashMap<>();
        offset.put("lsn", lsn);
        offset.put("txId", 3322L);
        offset.put("ts_usec", 1788182207848583L);

        SourceRecord record = new SourceRecord(partition, offset,
                "__debezium-heartbeat.sink-connector-manager", 0,
                null, null, valueSchema, value);
        return changeEvent(record);
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
                return record.topic();
            }

            @Override
            public Integer partition() {
                return null;
            }
        };
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        ClickHouseSinkConnectorConfig.setDefaultValues(props);
        return new ClickHouseSinkConnectorConfig(props);
    }

    /**
     * Drives the real batch handler, so this exercises the production path
     * rather than a restatement of it.
     */
    private static RecordingCommitter runBatch(DebeziumChangeEventCapture capture,
                                               List<ChangeEvent<SourceRecord, SourceRecord>> batch)
            throws Exception {
        RecordingCommitter committer = new RecordingCommitter();
        DebeziumRecordParserService parser = new SourceRecordParserService();
        capture.handleChangeEventBatch(batch, committer, new Properties(), parser, config());
        return committer;
    }

    @Test
    @DisplayName("A post-snapshot heartbeat has its offset committed, so the snapshot is recorded as finished (#1379)")
    public void heartbeatOnlyBatchCommitsItsOffset() throws Exception {
        ChangeEvent<SourceRecord, SourceRecord> hb = heartbeat(74130877960L);

        RecordingCommitter committer = runBatch(new DebeziumChangeEventCapture(),
                Collections.singletonList(hb));

        // Before the fix both of these were zero: the record was skipped
        // outright, so the post-snapshot offset never reached the offset
        // writer and replica_source_info stayed on snapshot_completed=false.
        assertEquals("the heartbeat's offset must be staged with the engine; "
                        + "issue #1379 dropped it, leaving the stored offset pinned at "
                        + "snapshot=INITIAL/snapshot_completed=false forever",
                1, committer.processed.size());
        assertEquals("the batch must be finished so the engine is asked to flush the offset",
                1, committer.batchesFinished);

        // The committed offset must be the post-snapshot one, i.e. the one
        // that carries no snapshot keys. That absence is what makes
        // InitialSnapshotter#shouldSnapshotData return false on restart.
        SourceRecord committed = committer.processed.get(0).value();
        assertNotNull(committed.sourceOffset());
        assertEquals(74130877960L, committed.sourceOffset().get("lsn"));
        assertFalse("a committed post-snapshot offset must not re-assert an in-progress snapshot",
                committed.sourceOffset().containsKey("snapshot_completed"));
    }

    @Test
    @DisplayName("A heartbeat is not committed while a row from the same batch is still unwritten")
    public void heartbeatIsNotCommittedAheadOfUnwrittenRows() throws Exception {
        // A row that parses, so it is handed to the writers and is still
        // unwritten when the trailing heartbeat is considered. Committing the
        // heartbeat here would advance the offset past that row (issue #1285).
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        RecordingCommitter committer = new RecordingCommitter();

        boolean committed = capture.commitControlRecordOffset(
                heartbeat(74130877960L), committer, true);

        assertFalse("a control record must never be committed while a row is unwritten",
                committed);
        assertTrue("nothing may be staged with the engine in that case",
                committer.processed.isEmpty());
        assertEquals(0, committer.batchesFinished);
    }

    @Test
    @DisplayName("A batch with no control record commits nothing extra")
    public void noControlRecordCommitsNothing() throws Exception {
        RecordingCommitter committer = new RecordingCommitter();

        boolean committed = new DebeziumChangeEventCapture()
                .commitControlRecordOffset(null, committer, false);

        assertFalse(committed);
        assertTrue(committer.processed.isEmpty());
        assertEquals(0, committer.batchesFinished);
    }

    @Test
    @DisplayName("An empty batch is a no-op")
    public void emptyBatchIsANoOp() throws Exception {
        RecordingCommitter committer = runBatch(new DebeziumChangeEventCapture(),
                Collections.emptyList());

        assertTrue(committer.processed.isEmpty());
        assertEquals(0, committer.batchesFinished);
    }
}

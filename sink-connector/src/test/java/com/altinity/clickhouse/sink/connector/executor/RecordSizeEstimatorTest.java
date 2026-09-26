package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.executor.OffsetTestSupport.RecordingCommitter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import io.debezium.engine.ChangeEvent;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-row heap estimate behind the handoff byte cap and the INSERT byte
 * chunking (spec 01.05 §3.4 item 7).
 *
 * <p>The estimate is deterministic and grows with payload: a row carrying a
 * megabyte BLOB must cost about a megabyte times the retention factor, a
 * narrow row must cost little, and a row with no envelope at all must still
 * cost its fixed overhead so that a cap in bytes can never be satisfied by
 * zero-cost rows.</p>
 */
public class RecordSizeEstimatorTest {

    private static final Schema ROW = SchemaBuilder.struct().name("row")
            .field("id", Schema.INT64_SCHEMA)
            .field("name", Schema.OPTIONAL_STRING_SCHEMA)
            .field("payload", Schema.OPTIONAL_BYTES_SCHEMA)
            .build();

    private static final Schema ENVELOPE = SchemaBuilder.struct().name("envelope")
            .field("before", ROW)
            .field("after", ROW)
            .field("op", Schema.STRING_SCHEMA)
            .build();

    private static Struct row(long id, String name, byte[] payload) {
        Struct s = new Struct(ROW).put("id", id);
        if (name != null) {
            s.put("name", name);
        }
        if (payload != null) {
            s.put("payload", ByteBuffer.wrap(payload));
        }
        return s;
    }

    private static ClickHouseStruct rowWithEnvelope(Struct before, Struct after) {
        Struct envelope = new Struct(ENVELOPE).put("before", before).put("after", after).put("op", "u");
        SourceRecord source = new SourceRecord(Collections.emptyMap(), Collections.emptyMap(),
                "srv.db.t", ENVELOPE, envelope);
        ClickHouseStruct record = new ClickHouseStruct();
        record.setTopic("srv.db.t");
        record.setSourceRecord(new ChangeEvent<SourceRecord, SourceRecord>() {
            @Override
            public SourceRecord key() {
                return null;
            }

            @Override
            public SourceRecord value() {
                return source;
            }

            @Override
            public String destination() {
                return "srv.db.t";
            }

            @Override
            public Integer partition() {
                return null;
            }
        });
        return record;
    }

    @Test
    @DisplayName("payload bytes follow the value: strings by length, bytes by length, structs by their fields")
    public void payloadBytesFollowTheValue() {
        assertEquals(0L, RecordSizeEstimator.payloadBytes(null));
        assertEquals(40L + 2L * 5, RecordSizeEstimator.payloadBytes("hello"));
        assertEquals(16L + 1024, RecordSizeEstimator.payloadBytes(new byte[1024]));
        assertEquals(16L + 1024, RecordSizeEstimator.payloadBytes(ByteBuffer.wrap(new byte[1024])));
        assertEquals(16L, RecordSizeEstimator.payloadBytes(42L), "a boxed scalar is one object");

        long narrow = RecordSizeEstimator.payloadBytes(row(1L, "ab", null));
        long wide = RecordSizeEstimator.payloadBytes(row(1L, "ab", new byte[1 << 20]));
        assertTrue(narrow < 200, "a narrow row is a few objects: " + narrow);
        assertTrue(wide >= narrow + (1 << 20), "a megabyte BLOB adds a megabyte: " + wide);
    }

    @Test
    @DisplayName("a row's estimate is the envelope payload times the retention factor plus the fixed overhead")
    public void estimateScalesTheEnvelope() {
        ClickHouseStruct narrow = rowWithEnvelope(row(1L, "ab", null), row(1L, "ab", null));
        ClickHouseStruct wide = rowWithEnvelope(row(1L, "ab", new byte[1 << 20]), row(1L, "ab", new byte[1 << 20]));
        long narrowBytes = RecordSizeEstimator.estimate(narrow);
        long wideBytes = RecordSizeEstimator.estimate(wide);
        assertTrue(narrowBytes >= RecordSizeEstimator.PER_RECORD_OVERHEAD_BYTES, "never below the overhead");
        assertTrue(narrowBytes < 4_096, "a narrow UPDATE is a few KiB at most: " + narrowBytes);
        // Two megabyte images, scaled: at least 2 MiB x factor.
        assertTrue(wideBytes >= 2L * (1 << 20) * RecordSizeEstimator.RETENTION_FACTOR,
                "both images of a wide UPDATE are charged and scaled: " + wideBytes);
    }

    @Test
    @DisplayName("a row without an envelope costs its fixed overhead, never zero")
    public void rowWithoutEnvelopeCostsTheOverhead() {
        RecordingCommitter committer = new RecordingCommitter();
        ClickHouseStruct record = OffsetTestSupport.record("r", 1L, committer);
        assertEquals(RecordSizeEstimator.PER_RECORD_OVERHEAD_BYTES, RecordSizeEstimator.estimate(record));
        assertEquals(0L, RecordSizeEstimator.estimate(null));
    }

    @Test
    @DisplayName("a group is sampled once, every row is stamped with the per-row share, and the total is per-row times size")
    public void groupIsSampledOnceAndStamped() {
        ClickHouseStruct first = rowWithEnvelope(row(1L, "ab", new byte[4096]), row(1L, "ab", new byte[4096]));
        ClickHouseStruct second = rowWithEnvelope(row(2L, "ab", null), row(2L, "ab", null));
        List<ClickHouseStruct> group = Arrays.asList(first, second);
        long perRow = RecordSizeEstimator.estimate(first);

        long total = RecordSizeEstimator.estimateGroup(group);

        assertEquals(perRow * 2, total, "the first row's estimate is charged to every row of the group");
        assertEquals(perRow, first.getEstimatedBytes());
        assertEquals(perRow, second.getEstimatedBytes(), "stamped from the sample, not measured individually");
        assertEquals(0L, RecordSizeEstimator.estimateGroup(Collections.emptyList()));
        assertEquals(0L, RecordSizeEstimator.estimateGroup(null));
    }
}

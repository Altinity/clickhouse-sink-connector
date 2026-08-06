package com.altinity.clickhouse.debezium.embedded.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pins the redelivery-stable version assignment for issue #1346
 * (bulk DELETE + re-INSERT loses rows after an offset rewind) while keeping the
 * emitted {@code _version} in the exact 2.8.0 numeric domain
 * ({@code ts_ms * 1_000_000 + counter}), so both upgrade AND downgrade remain safe.
 */
public class SourceTsVersionAnchorTest {

    private static final long TS = 1_754_000_000_000L; // fixed source commit ts (ms)

    @BeforeEach
    public void resetSequenceState() {
        DebeziumChangeEventCapture.sequenceNumber = DebeziumChangeEventCapture.SEQUENCE_START;
        DebeziumChangeEventCapture.sequenceAnchorTs = 0L;
    }

    private ClickHouseStruct recordAt(long sourceTsMs, long processingTsMs) {
        ClickHouseStruct record = new ClickHouseStruct();
        record.setTs_ms(sourceTsMs);
        record.setDebezium_ts_ms(processingTsMs);
        return record;
    }

    private List<Long> versionsOf(List<ClickHouseStruct> records) {
        List<Long> out = new ArrayList<>();
        for (ClickHouseStruct r : records) {
            out.add(r.getSequenceNumber());
        }
        return out;
    }

    @Test
    @DisplayName("the emitted _version stays in the 2.8.0 domain: ts_ms * 1e6 + counter")
    public void staysInTwoEightZeroDomain() {
        List<ClickHouseStruct> batch = Arrays.asList(
                recordAt(TS, TS + 5),
                recordAt(TS, TS + 6),
                recordAt(TS + 500, TS + 7));
        DebeziumChangeEventCapture.addVersion(batch);

        long base = TS * 1_000_000L + DebeziumChangeEventCapture.SEQUENCE_START;
        assertEquals(base + 1, batch.get(0).getSequenceNumber());
        assertEquals(base + 2, batch.get(1).getSequenceNumber());
        assertEquals((TS + 500) * 1_000_000L + DebeziumChangeEventCapture.SEQUENCE_START + 3,
                batch.get(2).getSequenceNumber(),
                "the formula must remain ts_ms * 1_000_000 + counter, bit-compatible with "
                        + "2.8.0-written values in the same ReplacingMergeTree column");
    }

    @Test
    @DisplayName("#1346: a re-delivered DELETE keeps its original version and cannot "
            + "out-rank the later re-INSERT")
    public void redeliveredDeleteCannotOutrankReinsert() {
        // First delivery: DELETE committed at TS, re-INSERT committed at TS + 3000.
        List<ClickHouseStruct> first = Arrays.asList(
                recordAt(TS, TS + 10),          // DELETE
                recordAt(TS + 3000, TS + 12));  // re-INSERT (later commit)
        DebeziumChangeEventCapture.addVersion(first);
        long deleteV1 = first.get(0).getSequenceNumber();
        long reinsertV = first.get(1).getSequenceNumber();
        assertTrue(deleteV1 < reinsertV, "sanity: in-order delivery ranks re-INSERT higher");

        // Redelivery after an offset rewind: ONLY the DELETE is re-processed, much
        // later in wall-clock time (higher processing ts). With the historical
        // processing-time anchor it would now receive a HIGHER version than the
        // re-INSERT and the row would be permanently stuck is_deleted=1.
        List<ClickHouseStruct> redelivery = Arrays.asList(
                recordAt(TS, TS + 60_000));      // same source commit ts, much later
        DebeziumChangeEventCapture.addVersion(redelivery);
        long deleteV2 = redelivery.get(0).getSequenceNumber();

        assertTrue(deleteV2 < reinsertV,
                "a re-delivered DELETE must keep ranking BELOW the later re-INSERT - "
                        + "its version is anchored to the source commit timestamp, which is "
                        + "identical on every redelivery (issue #1346)");
    }

    @Test
    @DisplayName("the intra-second counter is kept across binlog rotation "
            + "(keyed on the source clock only, never on file/position)")
    public void counterKeptAcrossBinlogRotation() {
        // Two batches, same source second - as delivered on either side of a binary
        // log rotation. Nothing about the rotation (file name change, position reset)
        // may reset the counter: only the source clock advancing can.
        List<ClickHouseStruct> beforeRotation = Arrays.asList(
                recordAt(TS, TS + 1), recordAt(TS, TS + 2));
        DebeziumChangeEventCapture.addVersion(beforeRotation);

        List<ClickHouseStruct> afterRotation = Arrays.asList(
                recordAt(TS, TS + 500), recordAt(TS + 200, TS + 501));
        DebeziumChangeEventCapture.addVersion(afterRotation);

        List<Long> all = versionsOf(beforeRotation);
        all.addAll(versionsOf(afterRotation));
        for (int i = 1; i < all.size(); i++) {
            assertTrue(all.get(i - 1) < all.get(i),
                    "versions must stay strictly increasing across the rotation boundary; "
                            + "a counter reset would emit a duplicate or inverted _version");
        }
        long expectedLast = (TS + 200) * 1_000_000L
                + DebeziumChangeEventCapture.SEQUENCE_START + 4;
        assertEquals(expectedLast, all.get(3).longValue(),
                "the counter must have kept incrementing (…+4), not reset to SEQUENCE_START");
    }

    @Test
    @DisplayName("the counter resets only when the source clock advances by more than one second")
    public void counterResetsOnlyOnSourceClockAdvance() {
        List<ClickHouseStruct> batch = Arrays.asList(
                recordAt(TS, TS + 1),
                recordAt(TS + 5000, TS + 2)); // source clock jumped 5s
        DebeziumChangeEventCapture.addVersion(batch);

        assertEquals((TS + 5000) * 1_000_000L + DebeziumChangeEventCapture.SEQUENCE_START,
                batch.get(1).getSequenceNumber(),
                "a >1s source-clock advance resets the counter to SEQUENCE_START, "
                        + "exactly as 2.8.0 did");
    }

    @Test
    @DisplayName("an older re-delivered timestamp never moves the anchor backward")
    public void anchorNeverMovesBackward() {
        List<ClickHouseStruct> current = Arrays.asList(recordAt(TS + 10_000, TS + 20));
        DebeziumChangeEventCapture.addVersion(current);

        // Redelivered event with an older source ts must not re-arm the counter reset.
        List<ClickHouseStruct> redelivered = Arrays.asList(recordAt(TS, TS + 30));
        DebeziumChangeEventCapture.addVersion(redelivered);

        List<ClickHouseStruct> next = Arrays.asList(recordAt(TS + 10_500, TS + 40));
        DebeziumChangeEventCapture.addVersion(next);

        assertEquals((TS + 10_500) * 1_000_000L
                        + DebeziumChangeEventCapture.SEQUENCE_START + 3,
                next.get(0).getSequenceNumber(),
                "the counter must have continued (…+3) - an old redelivered timestamp "
                        + "re-arming the reset is the duplicate-_version race");
    }

    @Test
    @DisplayName("records without a source timestamp fall back to the processing timestamp")
    public void fallsBackToProcessingTimestamp() {
        ClickHouseStruct noSourceTs = new ClickHouseStruct();
        noSourceTs.setDebezium_ts_ms(TS + 42);
        DebeziumChangeEventCapture.addVersion(Arrays.asList(noSourceTs));

        assertEquals((TS + 42) * 1_000_000L + DebeziumChangeEventCapture.SEQUENCE_START + 1,
                noSourceTs.getSequenceNumber(),
                "records lacking source.ts_ms (e.g. some snapshot records) must keep the "
                        + "historical processing-time anchor rather than emitting version 0");
    }
}

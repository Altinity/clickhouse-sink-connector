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

    /**
     * The sequence anchor and counter are per-instance state guarded by one
     * lock (they used to be two public statics, which is the duplicate-version
     * race this suite pins). A fresh instance per test is therefore the
     * equivalent of the old static reset.
     */
    private DebeziumChangeEventCapture capture;

    @BeforeEach
    public void resetSequenceState() {
        capture = new DebeziumChangeEventCapture();
        capture.resetSequenceStateForTest();
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
        capture.addVersion(batch);

        // The very first batch after start/resume is seeded at
        // SEQUENCE_START_INITIAL (500m) - still inside the 2.8.0 domain.
        long base = TS * 1_000_000L + DebeziumChangeEventCapture.SEQUENCE_START_INITIAL;
        assertEquals(base + 1, batch.get(0).getSequenceNumber());
        assertEquals(base + 2, batch.get(1).getSequenceNumber());
        assertEquals((TS + 500) * 1_000_000L
                        + DebeziumChangeEventCapture.SEQUENCE_START_INITIAL + 3,
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
        capture.addVersion(first);
        long deleteV1 = first.get(0).getSequenceNumber();
        long reinsertV = first.get(1).getSequenceNumber();
        assertTrue(deleteV1 < reinsertV, "sanity: in-order delivery ranks re-INSERT higher");

        // Redelivery after an offset rewind: ONLY the DELETE is re-processed, much
        // later in wall-clock time (higher processing ts). With the historical
        // processing-time anchor it would now receive a HIGHER version than the
        // re-INSERT and the row would be permanently stuck is_deleted=1.
        List<ClickHouseStruct> redelivery = Arrays.asList(
                recordAt(TS, TS + 60_000));      // same source commit ts, much later
        capture.addVersion(redelivery);
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
        capture.addVersion(beforeRotation);

        List<ClickHouseStruct> afterRotation = Arrays.asList(
                recordAt(TS, TS + 500), recordAt(TS + 200, TS + 501));
        capture.addVersion(afterRotation);

        List<Long> all = versionsOf(beforeRotation);
        all.addAll(versionsOf(afterRotation));
        for (int i = 1; i < all.size(); i++) {
            assertTrue(all.get(i - 1) < all.get(i),
                    "versions must stay strictly increasing across the rotation boundary; "
                            + "a counter reset would emit a duplicate or inverted _version");
        }
        long expectedLast = (TS + 200) * 1_000_000L
                + DebeziumChangeEventCapture.SEQUENCE_START_INITIAL + 4;
        assertEquals(expectedLast, all.get(3).longValue(),
                "the counter must have kept incrementing (…+4), not reset — neither by the "
                        + "rotation nor by the batch boundary");
    }

    @Test
    @DisplayName("the counter resets only when the source clock advances by more than one second")
    public void counterResetsOnlyOnSourceClockAdvance() {
        List<ClickHouseStruct> batch = Arrays.asList(
                recordAt(TS, TS + 1),
                recordAt(TS + 5000, TS + 2)); // source clock jumped 5s
        capture.addVersion(batch);

        assertEquals((TS + 5000) * 1_000_000L + DebeziumChangeEventCapture.SEQUENCE_START,
                batch.get(1).getSequenceNumber(),
                "a >1s source-clock advance resets the counter to SEQUENCE_START, "
                        + "exactly as 2.8.0 did");
    }

    @Test
    @DisplayName("an older re-delivered timestamp never moves the anchor backward")
    public void anchorNeverMovesBackward() {
        List<ClickHouseStruct> current = Arrays.asList(recordAt(TS + 10_000, TS + 20));
        capture.addVersion(current);

        // Redelivered event with an older source ts must not re-arm the counter reset.
        List<ClickHouseStruct> redelivered = Arrays.asList(recordAt(TS, TS + 30));
        capture.addVersion(redelivered);

        List<ClickHouseStruct> next = Arrays.asList(recordAt(TS + 10_500, TS + 40));
        capture.addVersion(next);

        assertEquals((TS + 10_500) * 1_000_000L
                        + DebeziumChangeEventCapture.SEQUENCE_START_INITIAL + 3,
                next.get(0).getSequenceNumber(),
                "the counter must have continued (…+3) - an old redelivered timestamp "
                        + "re-arming the reset is the duplicate-_version race");
    }

    @Test
    @DisplayName("records without a source timestamp fall back to the processing timestamp")
    public void fallsBackToProcessingTimestamp() {
        ClickHouseStruct noSourceTs = new ClickHouseStruct();
        noSourceTs.setDebezium_ts_ms(TS + 42);
        capture.addVersion(Arrays.asList(noSourceTs));

        assertEquals((TS + 42) * 1_000_000L
                        + DebeziumChangeEventCapture.SEQUENCE_START_INITIAL + 1,
                noSourceTs.getSequenceNumber(),
                "records lacking source.ts_ms (e.g. some snapshot records) must keep the "
                        + "historical processing-time anchor rather than emitting version 0");
    }

    @Test
    @DisplayName("first batch after resume is seeded at SEQUENCE_START_INITIAL (500m), "
            + "so re-published events rank below pre-restart writes of the same second")
    public void resumeSeedsCounterAtInitial() {
        // Pre-restart run: two events written with counters in the 1000m range.
        List<ClickHouseStruct> preRestart = Arrays.asList(
                recordAt(TS, TS + 1), recordAt(TS, TS + 2));
        capture.addVersion(preRestart);
        // Escape the initial domain: >1s source advance resets to SEQUENCE_START.
        List<ClickHouseStruct> normalDomain = Arrays.asList(recordAt(TS + 5000, TS + 3));
        capture.addVersion(normalDomain);
        long preRestartVersion = normalDomain.get(0).getSequenceNumber();
        assertEquals((TS + 5000) * 1_000_000L + DebeziumChangeEventCapture.SEQUENCE_START,
                preRestartVersion, "sanity: steady-state counters live in the 1000m range");

        // Simulated restart: sequence state is re-initialized exactly as a new JVM would.
        capture.resetSequenceStateForTest();

        // Resume re-publishes the TS+5000 event (same source commit ts).
        List<ClickHouseStruct> republished = Arrays.asList(recordAt(TS + 5000, TS + 90_000));
        capture.addVersion(republished);
        long republishedVersion = republished.get(0).getSequenceNumber();

        assertEquals((TS + 5000) * 1_000_000L
                        + DebeziumChangeEventCapture.SEQUENCE_START_INITIAL + 1,
                republishedVersion,
                "the first post-resume counter must start from SEQUENCE_START_INITIAL (500m)");
        assertTrue(republishedVersion < preRestartVersion,
                "a re-published event must rank strictly BELOW the pre-restart write of the "
                        + "same source second - re-publication must never supersede "
                        + "already-written rows");
    }

    @Test
    @DisplayName("the 500m initial domain is left on the first >1s source-clock advance")
    public void initialSeedEscapesToNormalDomainAfterOneSecond() {
        List<ClickHouseStruct> first = Arrays.asList(recordAt(TS, TS + 1));
        capture.addVersion(first);
        assertEquals(TS * 1_000_000L
                        + DebeziumChangeEventCapture.SEQUENCE_START_INITIAL + 1,
                first.get(0).getSequenceNumber(),
                "first post-start record is in the 500m domain");

        List<ClickHouseStruct> later = Arrays.asList(recordAt(TS + 2001, TS + 5));
        capture.addVersion(later);
        assertEquals((TS + 2001) * 1_000_000L + DebeziumChangeEventCapture.SEQUENCE_START,
                later.get(0).getSequenceNumber(),
                "the first >1s source-clock advance must reset to SEQUENCE_START (1000m), "
                        + "leaving the initial domain for steady-state operation");
    }

    @Test
    @DisplayName("re-publication after resume preserves DELETE < re-INSERT ordering "
            + "and the counter survives a binlog rotation inside the re-published range")
    public void republicationPreservesOrderAcrossRotation() {
        // Original run: DELETE at TS, re-INSERT at TS+3000 (nightly refresh pattern).
        List<ClickHouseStruct> original = Arrays.asList(
                recordAt(TS, TS + 10),          // DELETE
                recordAt(TS + 3000, TS + 12));  // re-INSERT
        capture.addVersion(original);
        long originalDelete = original.get(0).getSequenceNumber();
        long originalReinsert = original.get(1).getSequenceNumber();
        assertTrue(originalDelete < originalReinsert);

        // Crash + resume: sequence state re-initialized; the binlog also rotated
        // between the DELETE and the re-INSERT. Both events are re-published.
        capture.resetSequenceStateForTest();

        List<ClickHouseStruct> republishedDelete = Arrays.asList(
                recordAt(TS, TS + 120_000));            // re-published DELETE (old file)
        capture.addVersion(republishedDelete);
        List<ClickHouseStruct> republishedReinsert = Arrays.asList(
                recordAt(TS + 3000, TS + 120_001));     // re-published re-INSERT (new file)
        capture.addVersion(republishedReinsert);

        long replayDelete = republishedDelete.get(0).getSequenceNumber();
        long replayReinsert = republishedReinsert.get(0).getSequenceNumber();

        assertTrue(replayDelete < replayReinsert,
                "re-published DELETE must still rank below the re-published re-INSERT "
                        + "(source-commit ordering is delivery-independent)");
        assertTrue(replayDelete < originalReinsert,
                "the re-published DELETE must rank below the ORIGINAL re-INSERT already in "
                        + "ClickHouse - otherwise the #1346 stuck-delete returns on resume");
        assertTrue(replayReinsert <= originalReinsert,
                "a re-published event must never out-rank the original write of the same "
                        + "source commit (500m seed < 1000m steady-state counter)");
    }
}

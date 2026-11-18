package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.SourcePosition;
import io.debezium.engine.ChangeEvent;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Restart-offset ordering must survive a wrapped event header without guessing wraps. */
public class BinlogOffsetVersionTest {
    private static final long TS = 1_750_000_000_000L;

    @BeforeEach
    public void resetSequenceState() {
        VersionSequencer.sequenceNumber = VersionSequencer.SEQUENCE_START;
        VersionSequencer.sequenceAnchorTs = 0L;
        VersionSequencer.sequenceHighWaterPosition = null;
        VersionSequencer.sequenceHighWaterEffectiveTs = 0L;
        VersionSequencer.sequenceMaxSourceTs = 0L;
    }

    private static ChangeEvent<SourceRecord, SourceRecord> event(long header, long anchor, long ordinal, long row) {
        Schema sourceSchema = SchemaBuilder.struct().field("file", Schema.STRING_SCHEMA)
                .field("pos", Schema.INT64_SCHEMA).field("row", Schema.INT32_SCHEMA).build();
        Struct source = new Struct(sourceSchema).put("file", "mysql-bin.000010")
                .put("pos", header).put("row", (int) row - 1);
        Schema schema = SchemaBuilder.struct().field("source", sourceSchema).build();
        Map<String, Object> offset = new HashMap<>(Map.of("file", "mysql-bin.000010", "pos", anchor, "row", row));
        // BinlogOffsetContext omits event=0, including inner rows of a compressed payload.
        if (ordinal != 0) {
            offset.put("event", ordinal);
        }
        SourceRecord record = new SourceRecord(Collections.singletonMap("server", "example"), offset,
                "example.events", schema, new Struct(schema).put("source", source));
        return new ChangeEvent<SourceRecord, SourceRecord>() {
            public SourceRecord key() { return null; }
            public SourceRecord value() { return record; }
            public String destination() { return record.topic(); }
            public Integer partition() { return null; }
        };
    }

    private static SourcePosition position(long header, long anchor, long ordinal, long row) {
        return ClickHouseStruct.getSourcePositionFromChangeEvent(event(header, anchor, ordinal, row));
    }

    private static long version(long ts, SourcePosition position) {
        return VersionSequencer.nextSequenceNumber(ts, position);
    }

    @Test
    public void wrappedHeaderWithOlderStatementTimeStillAdvancesTheVersion() {
        SourcePosition first = position(900_000_100L, 900_000_000L, 4, 1);
        SourcePosition second = position(900_000_300L, 900_000_000L, 6, 1);
        SourcePosition wrapped = position(700_000_000L, 900_000_000L, 500_004, 1);
        long insert = version(TS, first);
        long update = version(TS + 10_000, second);
        long lateUpdate = version(TS + 5_000, wrapped);
        assertTrue(update > insert);
        assertTrue(lateUpdate > update, "a later event must beat the earlier image, despite header wrap");
        assertEquals(wrapped, VersionSequencer.sequenceHighWaterPosition);
        long replay = version(TS, position(900_000_100L, 900_000_000L, 4, 1));
        assertTrue(replay < lateUpdate, "an earlier event in the same transaction is still replay");
        assertEquals(wrapped, VersionSequencer.sequenceHighWaterPosition);
    }

    @Test
    public void laterTransactionAnchorDominatesItsResetEventOrdinal() {
        long first = version(TS + 10_000, position(1000, 900, 500, 8));
        long later = version(TS, position(2000, 1900, 2, 1));
        assertTrue(later > first, "a later nonwrapped BEGIN is new even when event and row reset");
        assertTrue(version(TS, position(1000, 900, 500, 8)) < later);
    }

    @Test
    public void compressedPayloadRowsShareFloorButNeverReuseAVersion() {
        version(TS + 10_000, position(800, 700, 4, 1));
        long insert = version(TS, position(1000, 900, 0, 8));
        long update = version(TS, position(1000, 900, 0, 1));
        long delete = version(TS, position(1000, 900, 0, 1));
        assertTrue(update > insert, "inner statements keep the outer event ordinal and reset row");
        assertTrue(delete > update, "equal cursor does not mean an equal row image or cached version");
        assertEquals(TS + 10_000, VersionSequencer.sequenceHighWaterEffectiveTs);
    }

    @Test
    public void reconstructedCursorAfterRestartUsesExistingDurableFloor() {
        long before = version(TS + 10_000, position(900_000_300L, 900_000_000L, 6, 1));
        SourcePosition cursor = position(700_000_000L, 900_000_000L, 500_004, 1);
        resetSequenceState();
        VersionSequencer.seedVersionFloor(before);
        assertEquals(cursor, position(700_000_000L, 900_000_000L, 500_004, 1));
        assertTrue(version(TS, cursor) > before);
        assertEquals(cursor, VersionSequencer.sequenceHighWaterPosition);
    }

    private static long assignedVersion(long ts, long physicalPosition, long ordinal, boolean parsedBatch) {
        long header = physicalPosition & 0xffff_ffffL;
        ChangeEvent<SourceRecord, SourceRecord> change = event(header, 1024L, ordinal, 1);
        if (!parsedBatch) {
            return version(ts, ClickHouseStruct.getSourcePositionFromChangeEvent(change));
        }
        ClickHouseStruct record = new ClickHouseStruct();
        record.setFile("mysql-bin.000010");
        record.setPos(header);
        record.setRow(0);
        record.setSourceRecord(change);
        record.setTs_ms(ts);
        VersionSequencer.addVersion(Collections.singletonList(record));
        return record.getSequenceNumber();
    }

    @Test
    public void signedAndUnsignedBoundariesKeepBothVersionPathsMonotonic() {
        for (boolean parsedBatch : new boolean[] {false, true}) {
            for (long boundary : new long[] {1L << 31, 1L << 32, 1L << 33}) {
                resetSequenceState();
                long before = assignedVersion(TS, boundary - 2, 1, parsedBatch);
                long highTimestamp = assignedVersion(TS + 10_000, boundary - 1, 2, parsedBatch);
                long atBoundary = assignedVersion(TS + 5_000, boundary, 3, parsedBatch);
                long afterBoundary = assignedVersion(TS, boundary + 1, 4, parsedBatch);
                String context = "boundary=" + boundary + ", parsedBatch=" + parsedBatch;
                assertTrue(highTimestamp > before, context);
                assertTrue(atBoundary > highTimestamp, context + ": exact boundary, including header zero");
                assertTrue(afterBoundary > atBoundary, context + ": first byte after boundary");
            }
        }
    }

    @Test
    public void olderNullImagesCannotDisplaceCorrectedValueAfterHeaderWrap() {
        for (boolean parsedBatch : new boolean[] {false, true}) {
            for (long boundary : new long[] {1L << 32, 1L << 33}) {
                for (int replayIndex : new int[] {0, 1}) {
                    resetSequenceState();
                    long[] times = {TS, TS + 10_000};
                    long[] physicalPositions = {boundary - 4, boundary - 3};
                    long winnerVersion = Long.MIN_VALUE;
                    String winnerValue = null;
                    for (int i = 0; i < times.length; i++) {
                        winnerVersion = Math.max(winnerVersion,
                                assignedVersion(times[i], physicalPositions[i], i + 1, parsedBatch));
                    }
                    // A different key raises the shared floor before the late update of this key.
                    assignedVersion(TS + 20_000, boundary - 2, 3, parsedBatch);
                    long corrected = assignedVersion(TS + 5_000, boundary + 1, 4, parsedBatch);
                    if (corrected > winnerVersion) {
                        winnerVersion = corrected;
                        winnerValue = "corrected";
                    }
                    long replayed = assignedVersion(times[replayIndex], physicalPositions[replayIndex],
                            replayIndex + 1, parsedBatch);
                    if (replayed > winnerVersion) {
                        winnerVersion = replayed;
                        winnerValue = null;
                    }
                    assertEquals(corrected, winnerVersion, "the corrected image must have the greatest version");
                    assertEquals("corrected", winnerValue, "replaying an older NULL must not restore NULL");
                }
            }
        }
    }

    @Test
    public void fixedSeedSequenceSurvivesRepeatedHeaderWrapsAndOlderEventReplay() {
        for (boolean parsedBatch : new boolean[] {false, true}) {
            resetSequenceState();
            Random random = new Random(0x5eedL);
            long previous = assignedVersion(TS + 30_000, 1025, 1, parsedBatch);
            for (int ordinal = 2; ordinal <= 256; ordinal++) {
                long physicalPosition = 1024L + ((long) ordinal << 27);
                long current = assignedVersion(TS + random.nextInt(30_000), physicalPosition, ordinal, parsedBatch);
                assertTrue(current > previous, "ordinal=" + ordinal + ", parsedBatch=" + parsedBatch);
                if (ordinal % 16 == 0) {
                    int earlierOrdinal = ordinal / 2;
                    long replay = assignedVersion(TS - 10_000,
                            1024L + ((long) earlierOrdinal << 27), earlierOrdinal, parsedBatch);
                    assertTrue(replay < current, "older event remains replay after repeated wraps");
                }
                previous = current;
            }
        }
    }

    @Test
    public void mixedCoordinateKindsDoNotClampPossibleReplayOrReplaceTheMark() {
        SourcePosition offset = position(1000, 900, 4, 1);
        long first = version(TS + 10_000, offset);
        SourcePosition legacy = SourcePosition.ofBinlog("mysql-bin.000010", 1100L, 0);
        assertTrue(version(TS, legacy) < first);
        assertEquals(offset, VersionSequencer.sequenceHighWaterPosition);

        resetSequenceState();
        long legacyFirst = version(TS + 10_000, legacy);
        assertTrue(version(TS, position(2000, 1900, 4, 1)) < legacyFirst);
        assertEquals(legacy, VersionSequencer.sequenceHighWaterPosition);
    }
}

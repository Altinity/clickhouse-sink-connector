package com.altinity.clickhouse.debezium.embedded.cdc;

import static org.junit.jupiter.api.Assertions.*;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.model.SourcePosition;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Engine recreation resets the position gate only after successful floor seeding. */
class EngineEpochVersionTest {
    private static final long TS = 1_757_900_000_000L;
    private static final String FILE = "mysql-bin.000500";
    private static final long MULTIPLIER = VersionHighWaterMark.VERSION_MULTIPLIER;

    @BeforeEach
    void reset() {
        VersionSequencer.sequenceNumber = VersionSequencer.SEQUENCE_START;
        VersionSequencer.sequenceAnchorTs = 0;
        VersionSequencer.sequenceHighWaterPosition = null;
        VersionSequencer.sequenceHighWaterEffectiveTs = 0;
        VersionSequencer.sequenceMaxSourceTs = 0;
    }

    private long version(long ts, String file, long pos) {
        return VersionSequencer.nextSequenceNumber(ts, SourcePosition.ofBinlog(file, pos, 0));
    }

    private static Properties properties() {
        Properties props = new Properties();
        props.setProperty(ClickHouseSinkConnectorConfigVariables.OFFSET_STORAGE_TABLE_NAME.toString(),
                "offsets.epoch_test");
        return props;
    }

    static void seedEngine(long horizon) {
        VersionHighWaterMarkTest.FakeClickHouse db = new VersionHighWaterMarkTest.FakeClickHouse();
        db.answer("max(`high_water_version`)", new Object[] {Long.toString(horizon)});
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        capture.systemDbConnection = db.connection();
        capture.seedVersionFloorFromDurableMark(properties(), null);
        assertNull(VersionSequencer.sequenceHighWaterPosition);
        assertEquals(0, VersionSequencer.sequenceHighWaterEffectiveTs);
        assertEquals(2, db.executed.size(), "seed reads bookkeeping only; never scan target tables");
    }

    @Test
    void replayConvergesForNeverPartlyAndFullyWrittenSuffixes() {
        // Enumerate both events' write status; old workers may finish after restart.
        for (int stored = 0; stored < 4; stored++) {
            reset();
            version(TS - 10_000, FILE, 50);
            long earlier = version(TS + 110, FILE, 100);
            long later = version(TS + 100, FILE, 200);
            long lastAssigned = version(TS + 120, FILE, 300);
            assertTrue(later > earlier);
            long horizon = lastAssigned + VersionHighWaterMark.HORIZON_HEADROOM_MS * MULTIPLIER;
            seedEngine(horizon);
            long earlierAgain = version(TS + 110, FILE, 100);
            long laterAgain = version(TS + 100, FILE, 200);
            assertTrue(earlierAgain > horizon);
            assertTrue(laterAgain > earlierAgain, "late commit must win in the replay");
            long retained = Math.max((stored & 1) != 0 ? earlier : 0,
                    (stored & 2) != 0 ? later : 0);
            assertTrue(laterAgain > retained);
            assertTrue(laterAgain > Math.max(earlier, later), "late old-worker writes cannot win");
        }
    }

    @Test
    void deleteThenReinsertStillEndsAtTheReinsertAfterReplay() {
        version(TS - 10_000, FILE, 50);
        long delete = version(TS + 110, FILE, 100);
        long reinsert = version(TS + 100, FILE, 200);
        version(TS + 120, FILE, 300);
        seedEngine(reinsert + VersionHighWaterMark.HORIZON_HEADROOM_MS * MULTIPLIER);
        long deleteAgain = version(TS + 110, FILE, 100);
        long reinsertAgain = version(TS + 100, FILE, 200);
        assertTrue(deleteAgain > delete);
        assertTrue(reinsertAgain > Math.max(deleteAgain, reinsert));
    }

    @Test
    void lowerNumberedSameBasenameIsNewAfterAnEngineBoundary() {
        version(TS - 10_000, FILE, 100);
        long old = version(TS, FILE, 900);
        seedEngine(old + VersionHighWaterMark.HORIZON_HEADROOM_MS * MULTIPLIER);
        assertTrue(version(TS - 5, "mysql-bin.000020", 4) > old);
    }

    @Test
    void epochKeepsCounterAnchorAndTheHigherExistingFloor() throws ReflectiveOperationException {
        long assigned = version(TS, FILE, 900);
        long counter = VersionSequencer.sequenceNumber;
        long anchor = VersionSequencer.sequenceAnchorTs;
        long floor = VersionSequencer.sequenceMaxSourceTs;
        java.lang.reflect.Field maximum = VersionSequencer.class.getDeclaredField("sequenceMaxAssignedVersion");
        maximum.setAccessible(true);
        long assignedMaximum = maximum.getLong(null);
        seedEngine(assigned - 10_000 * MULTIPLIER);
        assertEquals(floor, VersionSequencer.sequenceMaxSourceTs);
        assertEquals(counter, VersionSequencer.sequenceNumber);
        assertEquals(anchor, VersionSequencer.sequenceAnchorTs);
        assertEquals(assignedMaximum, maximum.getLong(null));
    }

    @Test
    void invalidFloorDoesNotClearThePositionGate() {
        version(TS, FILE, 900);
        SourcePosition mark = VersionSequencer.sequenceHighWaterPosition;
        long floor = VersionSequencer.sequenceMaxSourceTs;
        for (long invalid : new long[] {0L, -1L}) {
            assertThrows(IllegalArgumentException.class, () -> VersionSequencer.beginEngineRun(invalid));
            assertSame(mark, VersionSequencer.sequenceHighWaterPosition);
            assertEquals(floor, VersionSequencer.sequenceMaxSourceTs);
        }
    }

    @Test
    void unreadableSeedRefusesStartupWithoutClearingThePositionGate() {
        version(TS, FILE, 900);
        SourcePosition mark = VersionSequencer.sequenceHighWaterPosition;
        long floor = VersionSequencer.sequenceMaxSourceTs;
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> capture.seedVersionFloorFromDurableMark(properties(), null));
        assertTrue(failure.getMessage().contains("refusing to start an unseeded engine"));
        assertNotNull(failure.getCause());
        assertSame(mark, VersionSequencer.sequenceHighWaterPosition);
        assertEquals(floor, VersionSequencer.sequenceMaxSourceTs);
    }

    @Test
    void missingOffsetTableRefusesStartupWithoutClearingThePositionGate() {
        version(TS, FILE, 900);
        SourcePosition mark = VersionSequencer.sequenceHighWaterPosition;
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        assertThrows(IllegalStateException.class,
                () -> capture.seedVersionFloorFromDurableMark(new Properties(), null));
        assertSame(mark, VersionSequencer.sequenceHighWaterPosition);
    }
}

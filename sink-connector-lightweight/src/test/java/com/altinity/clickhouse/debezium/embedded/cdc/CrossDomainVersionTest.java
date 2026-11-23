package com.altinity.clickhouse.debezium.embedded.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.altinity.clickhouse.sink.connector.common.SnowFlakeId;
import com.altinity.clickhouse.sink.connector.db.batch.ReplicationHistoryHandler;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.SourcePosition;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

/**
 * The cross-domain rules of the version sequence (spec 02.01 section 3.1.2, spec
 * 02.02 section 3.5.1): GTID versions stay in commit order inside one effective
 * millisecond, rows without a GTID are carried above earlier GTID versions, the
 * rows of one transaction still share one version, redeliveries are untouched
 * (spec 02.04), and a durable mark in the snowflake domain seeds both floors.
 */
public class CrossDomainVersionTest {

    private static final long TS = 1_757_900_000_000L;
    private static final long M = VersionHighWaterMark.VERSION_MULTIPLIER;

    @BeforeEach
    @AfterEach
    public void resetSequenceState() {
        new GtidVersionTieBreakTest().resetSequenceState();
    }

    private static ClickHouseStruct at(long sourceTsMs, Long gtid, String file, long pos) {
        ClickHouseStruct record = new ClickHouseStruct();
        record.setTs_ms(sourceTsMs);
        record.setDebezium_ts_ms(sourceTsMs + 250);
        if (gtid != null) {
            record.setGtid(gtid);
        }
        record.setFile(file);
        record.setPos(pos);
        record.setRow(0);
        return record;
    }

    private static ClickHouseStruct at(long sourceTsMs, Long gtid, long pos) {
        return at(sourceTsMs, gtid, "mysql-bin.000001", pos);
    }

    private static long versionOf(ClickHouseStruct record) {
        VersionSequencer.addVersion(Arrays.asList(record));
        record.calculateVersion(true);
        return record.getVersion();
    }

    @Test
    @DisplayName("rows of one GTID transaction at successive positions share one version and are not bumped")
    public void rowsOfOneTransactionShareTheirVersion() {
        long first = versionOf(at(TS, 900_000L, 100));
        ClickHouseStruct secondRecord = at(TS, 900_000L, 200);
        long second = versionOf(secondRecord);

        assertEquals(first, second, "same transaction, same millisecond: the tie rule of spec 02.01 section 3.1.1");
        assertEquals(TS, secondRecord.getVersionTs(), "no bump for the same transaction");
    }

    @Test
    @DisplayName("alternating server UUIDs inside one clamped millisecond keep strictly increasing versions")
    public void alternatingUuidsKeepCommitOrder() {
        VersionSequencer.raiseVersionFloor(TS + 5_000);
        long previous = 0L;
        long[] gtids = {900_000L, 7L, 900_001L, 8L, 900_002L, 9L};
        for (int i = 0; i < gtids.length; i++) {
            long v = versionOf(at(TS, gtids[i], 100 + i));
            assertTrue(v > previous, "transaction " + i + " (gtid " + gtids[i] + ") ranks above its predecessor");
            previous = v;
        }
        assertEquals(TS + 5_000, VersionSequencer.sequenceMaxSourceTs, "the bumps do not raise the floor");
    }

    @Test
    @DisplayName("a bumped GTID millisecond stays inside the sequence value the durable horizon covers")
    public void bumpedMillisecondIsCovered() {
        versionOf(at(TS, 900_000L, 100));
        ClickHouseStruct bumped = at(TS - 3, 7L, 200);
        versionOf(bumped);

        assertEquals(TS + 1, bumped.getVersionTs());
        assertTrue(VersionHighWaterMark.sequenceFloor(bumped.getSequenceNumber()) > bumped.getVersionTs(),
                "a restart seeded from a horizon covering this row floors above its snowflake millisecond");
    }

    @Test
    @DisplayName("GTID -> no GTID -> GTID keeps commit order across both version domains")
    public void domainsInterleaveInCommitOrder() {
        long gtidRow = versionOf(at(TS, 500L, 100));
        ClickHouseStruct anonymousRecord = at(TS + 60_000, null, 200);
        long anonymous = versionOf(anonymousRecord);
        long nextAnonymous = versionOf(at(TS + 60_001, null, 300));
        ClickHouseStruct laterGtidRecord = at(TS + 60_002, 501L, 400);
        long laterGtid = versionOf(laterGtidRecord);
        long lastAnonymous = versionOf(at(TS + 60_003, null, 500));

        assertTrue(anonymous > gtidRow, "carried above the snowflake domain");
        assertTrue(nextAnonymous > anonymous);
        assertTrue(laterGtid > nextAnonymous);
        assertTrue(lastAnonymous > laterGtid);
        assertTrue(laterGtidRecord.getSequenceNumber() >= laterGtid,
                "after the merge the covered sequence value is at least the GTID version");
        assertEquals(anonymous, ReplicationHistoryHandler.historyVersion(anonymousRecord),
                "history mode: the carried version is its own history version, not a refused counter");
    }

    @Test
    @DisplayName("a pure GTID stream keeps its sequence values in the sequence domain")
    public void pureGtidStreamIsUnchanged() {
        ClickHouseStruct t2 = at(TS + 110, 200L, 100);
        ClickHouseStruct t1 = at(TS + 100, 201L, 200);
        versionOf(t2);
        versionOf(t1);
        assertTrue(t1.getSequenceNumber() < SnowFlakeId.generate(TS, 0L, false),
                "no carry into the snowflake range without a row lacking a GTID");
        assertTrue(VersionHighWaterMark.isPlausibleFloor(VersionHighWaterMark.sequenceFloor(t1.getSequenceNumber()),
                TS), "the horizon stays a plausible sequence-domain mark");
    }

    @Test
    @DisplayName("a GTID redelivery keeps its source-timestamp version (spec 02.04)")
    public void gtidRedeliveryIsNotBumped() {
        ClickHouseStruct original = at(TS + 100, 7L, 100);
        versionOf(original);
        versionOf(at(TS + 200, 900_000L, 300));
        ClickHouseStruct replay = at(TS + 100, 7L, 100);
        long replayed = versionOf(replay);

        assertEquals(TS + 100, replay.getVersionTs(), "at or below the mark: raw timestamp, no bump");
        assertEquals(SnowFlakeId.generate(TS + 100, 7L, false), replayed);
        assertEquals(original.getVersion(), replayed, "identical on redelivery");
    }

    @Test
    @DisplayName("after an in-run log restart the new log's later events stay first deliveries")
    public void eventsAfterALogRestartAreFirstDeliveries() {
        versionOf(at(TS - 10_000, null, "mysql-bin.000500", 100));
        long last = versionOf(at(TS, null, "mysql-bin.000500", 900));
        long first = versionOf(at(TS - 5, null, "mysql-bin.000020", 4));
        long next = versionOf(at(TS - 4, null, "mysql-bin.000020", 120));

        assertTrue(first > last);
        assertTrue(next > first);
        assertEquals(SourcePosition.ofBinlog("mysql-bin.000020", 120L, 0), VersionSequencer.sequenceHighWaterPosition);
        long replay = versionOf(at(TS - 5, null, "mysql-bin.000020", 4));
        assertTrue(replay < next, "an earlier position in the same file is still a redelivery");
    }

    @Test
    @DisplayName("a durable mark in the snowflake domain seeds the millisecond floor and the version floor")
    public void snowflakeDomainMarkSeedsBothFloors() throws Exception {
        long now = TS + 3_600_000L;
        long horizon = SnowFlakeId.generate(TS, 500L, false) + 1 + VersionHighWaterMark.HORIZON_HEADROOM_MS * M;
        VersionHighWaterMarkTest.FakeClickHouse fake = new VersionHighWaterMarkTest.FakeClickHouse();
        fake.answer("SELECT max(`high_water_version`)", new Object[] {String.valueOf(horizon)});

        VersionHighWaterMark.Seed seed = new VersionHighWaterMark(fake::connection,
                "offsets_db.replica_source_info", () -> now, 2, 0L).seedFloor();

        assertTrue(seed.fromMark, seed.source);
        assertEquals(SnowFlakeId.floorAbove(horizon), seed.floorMs,
                "the millisecond floor is the snowflake decoding, not a sequence decoding years ahead");
        assertTrue(seed.floorMs < now + VersionHighWaterMark.PLAUSIBLE_FUTURE_MS);
        assertEquals(horizon, VersionSequencer.seededVersionFloor);

        VersionSequencer.beginEngineRun(seed.floorMs);
        long anonymous = versionOf(at(TS + 1_000, null, 100));
        ClickHouseStruct gtidRecord = at(TS + 1_000, 501L, 200);
        long gtid = versionOf(gtidRecord);
        assertTrue(anonymous > horizon, "a row without a GTID ranks above every pre-restart version");
        assertTrue(gtid > anonymous, "commit order across the domains");
        assertTrue(gtidRecord.getVersionTs() < now + VersionHighWaterMark.PLAUSIBLE_FUTURE_MS,
                "the GTID timestamp stays near the clock: no runaway after the merge");
    }

    @Test
    @DisplayName("an ordinary mark does not set a version floor")
    public void sequenceDomainMarkSetsNoVersionFloor() throws Exception {
        long now = TS + 3_600_000L;
        VersionHighWaterMarkTest.FakeClickHouse fake = new VersionHighWaterMarkTest.FakeClickHouse();
        fake.answer("SELECT max(`high_water_version`)", new Object[] {String.valueOf(TS * M + 1_000_000_000L)});

        VersionHighWaterMark.Seed seed = new VersionHighWaterMark(fake::connection,
                "offsets_db.replica_source_info", () -> now, 2, 0L).seedFloor();

        assertEquals(TS + 1_001, seed.floorMs);
        assertEquals(0L, VersionSequencer.seededVersionFloor);
    }
}

package com.altinity.clickhouse.debezium.embedded.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

/**
 * Failure modes of the GTID version path (spec 02.01 section 7):
 *
 * <ul>
 *   <li>FM-02.01-1: rows without a GTID (anonymous transactions under
 *   {@code gtid_mode=ON_PERMISSIVE}/{@code OFF_PERMISSIVE}, GTIDs switched off,
 *   a failover to a replica without GTIDs) take the sequence path, whose values
 *   (~1.79e18) lie below every snowflake value (~2.1e18) written for the same keys
 *   while GTIDs were on.</li>
 *   <li>FM-02.01-2: inside one effective millisecond the snowflake orders rows by
 *   the low 22 bits of the GTID transaction number. That is commit order only for
 *   one server UUID and between two wraps of the 22-bit field; a clamp at the floor
 *   (a late commit, a restart on a lagging source) puts many transactions into one
 *   millisecond.</li>
 * </ul>
 */
public class GtidVersionTieBreakTest {

    private static final long TS = 1_757_900_000_000L;

    @BeforeEach
    public void resetSequenceState() {
        VersionSequencer.sequenceNumber = VersionSequencer.SEQUENCE_START;
        VersionSequencer.sequenceAnchorTs = 0L;
        VersionSequencer.sequenceHighWaterPosition = null;
        VersionSequencer.sequenceHighWaterEffectiveTs = 0L;
        VersionSequencer.sequenceMaxSourceTs = 0L;
    }

    private static ClickHouseStruct at(long sourceTsMs, Long gtid, long pos) {
        ClickHouseStruct record = new ClickHouseStruct();
        record.setTs_ms(sourceTsMs);
        record.setDebezium_ts_ms(sourceTsMs + 250);
        if (gtid != null) {
            record.setGtid(gtid);
        }
        record.setFile("mysql-bin.000001");
        record.setPos(pos);
        record.setRow(0);
        return record;
    }

    /** Dispatch-loop assignment, then the version precedence with snowflake.id=true (the default). */
    private static long versionOf(ClickHouseStruct record) {
        VersionSequencer.addVersion(Arrays.asList(record));
        record.calculateVersion(true);
        return record.getVersion();
    }

    @Test
    @Disabled("DEFECT FM-02.01-1: a row without a GTID is versioned in the sequence domain, below every "
            + "snowflake version written while GTIDs were on, so it can never supersede them")
    @DisplayName("a later row without a GTID must rank above an earlier GTID row of the same key")
    public void laterRowWithoutGtidRanksAboveEarlierGtidRow() {
        long withGtid = versionOf(at(TS, 500L, 100));
        long anonymous = versionOf(at(TS + 60_000, null, 200));

        assertTrue(anonymous > withGtid,
                "the anonymous transaction committed a minute later but was versioned " + anonymous
                        + " < " + withGtid + " (sequence domain below snowflake domain): the key stays "
                        + "frozen at its GTID-era value under FINAL");
    }

    @Test
    @Disabled("DEFECT FM-02.01-2: inside one clamped millisecond the snowflake compares the low 22 bits "
            + "of the GTID transaction number, which wrap every 4 194 304 transactions")
    @DisplayName("a GTID transaction number wrap inside one clamped millisecond keeps commit order")
    public void gtidWrapInsideAClampedMillisecondKeepsCommitOrder() {
        // Restart on a lagging source: the floor is seeded ahead of the source clock.
        VersionSequencer.raiseVersionFloor(TS + 5_000);
        long before = versionOf(at(TS, (1L << 22) - 1, 100));
        long after = versionOf(at(TS + 1, 1L << 22, 200));

        assertEquals(TS + 5_000, VersionSequencer.sequenceMaxSourceTs, "both rows were clamped");
        assertTrue(after > before,
                "transaction 4194304 committed after 4194303 but was versioned " + after + " < " + before
                        + ": its low 22 bits are 0");
    }

    @Test
    @Disabled("DEFECT FM-02.01-2: inside one clamped millisecond transactions of different server UUIDs "
            + "are ordered by their unrelated transaction numbers")
    @DisplayName("after a failover, the new primary's transaction clamped into the old one's millisecond keeps commit order")
    public void newServerUuidInsideAClampedMillisecondKeepsCommitOrder() {
        // The new primary's binlog: the last transaction replicated from the old
        // primary (uuidA:900000), then its own first transaction (uuidB:7), whose
        // statement time is 3 ms behind (clock skew between the hosts).
        long replicatedFromOldPrimary = versionOf(at(TS, 900_000L, 100));
        long firstLocal = versionOf(at(TS - 3, 7L, 200));

        assertTrue(firstLocal > replicatedFromOldPrimary,
                "uuidB:7 committed after uuidA:900000 but, clamped into the same millisecond, was versioned "
                        + firstLocal + " < " + replicatedFromOldPrimary);
    }
}

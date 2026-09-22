package com.altinity.clickhouse.debezium.embedded.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.altinity.clickhouse.sink.connector.common.SnowFlakeId;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

/**
 * The late-commit inversion on the GTID path (spec 01.04 §4.2, 02.01 §3.1).
 *
 * <p>With a GTID present {@code ClickHouseStruct.calculateVersion} builds the
 * {@code _version} with {@code SnowFlakeId.generate(ts, gtid)}, in which the
 * timestamp dominates the transaction number. {@code source.ts_ms} is the
 * STATEMENT time, so a long transaction {@code T1} that started first but
 * committed last reaches the binlog after {@code T2} with an older timestamp
 * and a higher GTID transaction number: {@code T2 (ts 110, gtid 200, pos 100)}
 * then {@code T1 (ts 100, gtid 201, pos 200)}. Versioned on the raw
 * {@code ts_ms}, {@code T1} ranked below {@code T2} and ReplacingMergeTree kept
 * {@code T2}'s row -- the newest committed state of the key was discarded.</p>
 *
 * <p>The fix feeds the clamped {@code effectiveTs} of the version sequence
 * (the same floor the no-GTID path uses) into the snowflake, via
 * {@code ClickHouseStruct.versionTs}. The snowflake encoding is unchanged, so
 * the values stay in the domain older releases wrote.</p>
 */
public class GtidLateCommitVersionTest {

    /** A fixed source millisecond; the scenario's ts 100 / 110 are offsets from it. */
    private static final long TS = 1_757_900_000_000L;

    @BeforeEach
    public void resetSequenceState() {
        DebeziumChangeEventCapture.sequenceNumber = DebeziumChangeEventCapture.SEQUENCE_START;
        DebeziumChangeEventCapture.sequenceAnchorTs = 0L;
        DebeziumChangeEventCapture.sequenceHighWaterPosition = null;
        DebeziumChangeEventCapture.sequenceMaxSourceTs = 0L;
    }

    private static ClickHouseStruct at(long sourceTsMs, long gtid, long pos) {
        ClickHouseStruct record = new ClickHouseStruct();
        record.setTs_ms(sourceTsMs);
        record.setDebezium_ts_ms(sourceTsMs + 250);
        record.setGtid(gtid);
        record.setFile("mysql-bin.000001");
        record.setPos(pos);
        record.setRow(0);
        return record;
    }

    /** Runs the record through the dispatch-loop assignment, then the version precedence. */
    private static long versionOf(ClickHouseStruct record) {
        DebeziumChangeEventCapture.addVersion(Arrays.asList(record));
        record.calculateVersion(true);
        return record.getVersion();
    }

    @Test
    @DisplayName("T2 (ts 110, gtid 200, pos 100) then T1 (ts 100, gtid 201, pos 200): version(T1) > version(T2)")
    public void lateCommittingTransactionOutranksEarlierCommitUnderGtid() {
        ClickHouseStruct t2 = at(TS + 110, 200L, 100L);
        ClickHouseStruct t1 = at(TS + 100, 201L, 200L);

        long v2 = versionOf(t2);
        long v1 = versionOf(t1);

        assertTrue(v1 > v2,
                "T1 committed after T2 (higher binlog position, higher GTID) and is the newest "
                        + "state of the key; versioned on the raw statement time it was "
                        + v1 + " < " + v2 + " and ReplacingMergeTree kept T2's row");
    }

    @Test
    @DisplayName("the GTID version is SnowFlakeId(effectiveTs, gtid): same encoding, floored timestamp")
    public void gtidVersionStaysInTheSnowflakeDomain() {
        ClickHouseStruct t2 = at(TS + 110, 200L, 100L);
        ClickHouseStruct t1 = at(TS + 100, 201L, 200L);
        long v2 = versionOf(t2);
        long v1 = versionOf(t1);

        assertEquals(TS + 110, t2.getVersionTs(), "T2 is not clamped: its own timestamp is the floor");
        assertEquals(TS + 110, t1.getVersionTs(), "T1 is clamped up to the floor set by T2");
        assertEquals(SnowFlakeId.generate(TS + 110, 200L, false), v2,
                "the encoding is the unchanged snowflake over the effective timestamp");
        assertEquals(SnowFlakeId.generate(TS + 110, 201L, false), v1,
                "T1 shares T2's timestamp field and wins on the transaction number");
    }
}

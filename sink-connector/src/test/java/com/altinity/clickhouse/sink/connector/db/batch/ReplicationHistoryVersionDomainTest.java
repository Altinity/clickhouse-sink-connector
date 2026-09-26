package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.common.SnowFlakeId;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 12.03 section 3.5.1 (S10): the history version domain.
 *
 * <p>Every row of an SCD2 table carries {@code SnowFlakeId.generate(ts_ms, d, false)}
 * of the event's ordering key -- a strictly monotone re-encoding of the standard
 * version, and the domain releases up to 2.11.0 already used for the UPDATE and
 * DELETE rows of a history table. The tests below pin the encoding on each source
 * path, the refusal of an unencodable event, the INSERT-path binding in history
 * mode, and the two compatibility facts the upgrade / downgrade suite relies on:
 * a 2.11.0 open row is superseded by the fixed connector's next event, and a fixed
 * connector's open row is superseded by a 2.11.0 connector's next event.</p>
 */
public class ReplicationHistoryVersionDomainTest {

    /** 2026-09-26T14:00:00Z, the millisecond the end-to-end run wrote its legacy rows at. */
    private static final long TS_MS = 1790431200000L;

    private static ClickHouseStruct record() {
        ClickHouseStruct record = new ClickHouseStruct();
        record.setTopic("SERVER5432.test.u_basic");
        record.setKafkaOffset(11L);
        record.setTs_ms(TS_MS);
        record.setTsSec(TS_MS / 1000);
        return record;
    }

    @Test
    public void gtidSourceIsTheStandardSnowflakeVersionItself() {
        ClickHouseStruct record = record();
        record.setGtid(777L);
        record.calculateVersion(true);

        assertEquals(record.getVersion(), ReplicationHistoryHandler.historyVersion(record),
                "with snowflake.id=true the standard version is already in the history domain");
        assertEquals(SnowFlakeId.generate(TS_MS, 777L, false), ReplicationHistoryHandler.historyVersion(record));
    }

    @Test
    public void gtidSourceUsesTheFlooredVersionTimestampWhenTheDispatchLoopSetOne() {
        ClickHouseStruct record = record();
        record.setGtid(777L);
        record.setVersionTs(TS_MS + 5000L);   // the commit-order floor of spec 02.02
        record.calculateVersion(true);

        assertEquals(SnowFlakeId.generate(TS_MS + 5000L, 777L, false),
                ReplicationHistoryHandler.historyVersion(record));
    }

    @Test
    public void rawGtidVersionIsReEncoded() {
        ClickHouseStruct record = record();
        record.setGtid(777L);
        record.calculateVersion(false);
        assertEquals(777L, record.getVersion(), "snowflake.id=false: the standard version is the raw GTID");

        assertEquals(SnowFlakeId.generate(TS_MS, 777L, false), ReplicationHistoryHandler.historyVersion(record),
                "the history rows still carry the snowflake domain 2.11.0 wrote");
    }

    @Test
    public void sequenceSourceEncodesItsMillisecondSlotAndCounter() {
        ClickHouseStruct record = record();
        record.setSequenceNumber(TS_MS * 1_000_000L + 42L);   // effectiveTs * 10^6 + counter
        record.calculateVersion(true);
        assertEquals(TS_MS * 1_000_000L + 42L, record.getVersion());

        assertEquals(SnowFlakeId.generate(TS_MS, 42L, false), ReplicationHistoryHandler.historyVersion(record));
    }

    @Test
    public void sequenceEncodingIsStrictlyMonotoneLikeTheSequence() {
        ClickHouseStruct a = record();
        a.setSequenceNumber(TS_MS * 1_000_000L + 1L);
        a.calculateVersion(true);
        ClickHouseStruct b = record();
        b.setSequenceNumber(TS_MS * 1_000_000L + 2L);          // same millisecond, next counter
        b.calculateVersion(true);
        ClickHouseStruct c = record();
        c.setSequenceNumber((TS_MS + 1) * 1_000_000L + 1L);    // next millisecond, counter reset
        c.calculateVersion(true);

        long va = ReplicationHistoryHandler.historyVersion(a);
        long vb = ReplicationHistoryHandler.historyVersion(b);
        long vc = ReplicationHistoryHandler.historyVersion(c);
        assertTrue(va < vb && vb < vc, "order preserved: " + va + " < " + vb + " < " + vc);
    }

    @Test
    public void fallbackSourcesUseTheLowBitsOfTheStandardVersion() {
        ClickHouseStruct record = record();   // no GTID, no sequence number, no LSN: Kafka-offset fallback
        record.calculateVersion(true);
        long standard = record.getVersion();
        assertTrue(standard > 0);

        assertEquals(SnowFlakeId.generate(TS_MS, standard & ((1L << SnowFlakeId.GTID_FIELD_BITS) - 1), false),
                ReplicationHistoryHandler.historyVersion(record));
    }

    @Test
    public void underivableOrUnencodableVersionsAreRefused() {
        ClickHouseStruct sentinel = record();
        assertThrows(IllegalStateException.class, () -> ReplicationHistoryHandler.historyVersion(sentinel),
                "-1 is never encoded");

        ClickHouseStruct beforeEpoch = record();
        beforeEpoch.setTs_ms(SnowFlakeId.SNOWFLAKE_EPOCH);    // not after the epoch
        beforeEpoch.setSequenceNumber(SnowFlakeId.SNOWFLAKE_EPOCH * 1_000_000L + 1L);
        beforeEpoch.calculateVersion(true);
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> ReplicationHistoryHandler.historyVersion(beforeEpoch));
        assertTrue(refused.getMessage().contains("snowflake epoch"), refused.getMessage());
    }

    /**
     * Upgrade safety: an open row or delete marker a 2.11.0 connector left at
     * {@code (k, S)} carries {@code SnowFlakeId(ts_old, gtid) + 1} -- with the
     * all-ones discriminator on a GTID-less source. The fixed connector's next
     * event, one second later on the sequence path, must rank at least as high
     * (the convergence hypothesis "the visible open row has version <= V"), while
     * the RAW sequence number -- what the connector would have bound without the
     * history domain -- ranks below it and would have frozen the key.
     */
    @Test
    public void legacyOpenRowIsSupersededOnUpgrade() {
        long legacyOpenRow = SnowFlakeId.generate(TS_MS, -1L, false) + 1;   // 2.11.0, no GTID: gtid = -1

        ClickHouseStruct next = record();
        next.setTs_ms(TS_MS + 1000L);
        next.setSequenceNumber((TS_MS + 1000L) * 1_000_000L + 1L);
        next.calculateVersion(true);

        assertTrue(ReplicationHistoryHandler.historyVersion(next) >= legacyOpenRow,
                "history version " + ReplicationHistoryHandler.historyVersion(next) + " must supersede " + legacyOpenRow);
        assertTrue(next.getVersion() < legacyOpenRow,
                "the raw sequence " + next.getVersion() + " would have lost to the 2.11.0 row " + legacyOpenRow);
    }

    /** Downgrade safety: a 2.11.0 connector's next event outranks the fixed connector's open row. */
    @Test
    public void fixedOpenRowIsSupersededOnDowngrade() {
        ClickHouseStruct fixed = record();
        fixed.setSequenceNumber(TS_MS * 1_000_000L + 999_999L);
        fixed.calculateVersion(true);
        long fixedOpenRow = ReplicationHistoryHandler.historyVersion(fixed);

        long legacyNext = SnowFlakeId.generate(TS_MS + 1000L, -1L, false) + 1;   // 2.11.0 writes SnowFlakeId(ts_ms, gtid) + 1
        assertTrue(legacyNext > fixedOpenRow, legacyNext + " > " + fixedOpenRow);
    }

    /** The INSERT path binds the history version in history mode, and the standard version otherwise. */
    @Test
    public void insertPathBindsTheHistoryVersionInHistoryMode() throws Exception {
        ClickHouseStruct record = record();
        record.setSequenceNumber(TS_MS * 1_000_000L + 7L);

        AtomicLong bound = new AtomicLong(-1L);
        bindVersion(record, recordingStatement(bound), true);
        assertEquals(SnowFlakeId.generate(TS_MS, 7L, false), bound.get(), "history mode: the history domain");

        AtomicLong standardBound = new AtomicLong(-1L);
        bindVersion(record, recordingStatement(standardBound), false);
        assertEquals(TS_MS * 1_000_000L + 7L, standardBound.get(), "standard mode: the standard version, unchanged");
    }

    private static PreparedStatement recordingStatement(AtomicLong boundVersion) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "setLong":
                    boundVersion.set((Long) args[1]);
                    return null;
                case "toString":
                    return "RecordingPreparedStatement";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return null;
            }
        };
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, h);
    }

    /** Drives the real bind path ({@code handleVersionColumn} is private). */
    private static void bindVersion(ClickHouseStruct record, PreparedStatement ps, boolean historyMode) throws Exception {
        Map<String, Integer> columnNameToIndexMap = new HashMap<>();
        columnNameToIndexMap.put("_version", 1);
        Map<String, String> columnNameToDataTypeMap = new HashMap<>();
        columnNameToDataTypeMap.put("_version", "UInt64");
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString(), String.valueOf(historyMode));

        Method method = PreparedStatementFieldMapper.class.getDeclaredMethod(
                "handleVersionColumn", Map.class, PreparedStatement.class, ClickHouseStruct.class,
                ClickHouseSinkConnectorConfig.class, Map.class, DBMetadata.TABLE_ENGINE.class);
        method.setAccessible(true);
        try {
            method.invoke(new PreparedStatementFieldMapper("is_deleted", true, null, "_version", "test_db", ZoneId.of("UTC")),
                    columnNameToIndexMap, ps, record, new ClickHouseSinkConnectorConfig(props),
                    columnNameToDataTypeMap, DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }
}

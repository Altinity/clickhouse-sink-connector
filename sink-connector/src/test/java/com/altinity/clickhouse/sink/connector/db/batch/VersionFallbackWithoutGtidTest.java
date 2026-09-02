package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.common.SnowFlakeId;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.junit.jupiter.api.DisplayName;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #1213: the {@code _version} column is filled with an unreadable value
 * on a MySQL source that has GTID disabled.
 *
 * <p>Chain: {@code ClickHouseStruct.version} is initialised to the
 * {@code UNINITIALIZED_VALUE} sentinel {@code -1}. {@code calculateVersion()}
 * only assigns a version from a GTID, a sequence number or an LSN; when a
 * MySQL source runs without GTID none of the three is present on the Kafka
 * Connect path (nothing in {@code sink-connector/src/main} ever calls
 * {@code setSequenceNumber} -- only the lightweight
 * {@code DebeziumChangeEventCapture} does), so the method falls through and
 * leaves the sentinel in place. The bind site then executes
 * {@code ps.setLong(index, -1)} into a {@code UInt64} column, which stores
 * <b>18446744073709551615</b> -- the value the reporter sees, and the value
 * the JDBC driver then refuses to read back
 * ({@code ArithmeticException: integer overflow: 18446744073709551615 cannot
 * be presented as long}).
 *
 * <p>The unreadable cell is only the visible symptom. UInt64 max is the
 * largest representable version, so under ReplacingMergeTree such a row wins
 * every future deduplication permanently: every later UPDATE and DELETE for
 * that key is discarded on merge. That is silent, unrecoverable corruption,
 * which is why a record that cannot produce a version must fail loudly rather
 * than be written.
 *
 * <p>The fallback asserted here reuses the connector's existing, already
 * shipping version formula: {@code SnowFlakeId.generate(ts_ms, ordering)},
 * substituting the Kafka offset for the absent GTID. Both inputs ARE populated
 * on the affected path -- {@code ts_ms} from {@code source.ts_ms} and the
 * offset from {@code record.kafkaOffset()} -- so no new versioning scheme is
 * invented and no working deployment changes: a source that supplies a GTID, a
 * sequence number or an LSN still takes its existing branch, which the
 * regression tests at the bottom of this class pin.
 */
public class VersionFallbackWithoutGtidTest {

    private static final String VERSION_COLUMN = "_version";

    /** What setLong(-1) actually stores in a UInt64 column. */
    private static final String UINT64_MAX = "18446744073709551615";

    /** A realistic MySQL source commit timestamp (2026-01-01T00:00:00Z). */
    private static final long SOURCE_TS_MS = 1767225600000L;

    private static PreparedStatementFieldMapper mapper() {
        return new PreparedStatementFieldMapper(
                "is_deleted", true, null, VERSION_COLUMN, "test_db", ZoneId.of("UTC"));
    }

    /**
     * A PreparedStatement stand-in that records the value bound to the version
     * column. Mockito is not on the test classpath, so this uses a JDK proxy in
     * the same style as ClosedConnectionRefreshTest.
     */
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
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, h);
    }

    /**
     * Drives the real bind path for the version column. handleVersionColumn is
     * private, so it is reached reflectively -- the point of the test is to
     * observe what actually reaches the driver, not to re-implement it.
     */
    private static void bindVersion(ClickHouseStruct record, PreparedStatement ps) throws Exception {
        Map<String, Integer> columnNameToIndexMap = new HashMap<>();
        columnNameToIndexMap.put(VERSION_COLUMN, 1);
        Map<String, String> columnNameToDataTypeMap = new HashMap<>();
        columnNameToDataTypeMap.put(VERSION_COLUMN, "UInt64");

        Method method = PreparedStatementFieldMapper.class.getDeclaredMethod(
                "handleVersionColumn",
                Map.class, PreparedStatement.class, ClickHouseStruct.class,
                ClickHouseSinkConnectorConfig.class, Map.class,
                DBMetadata.TABLE_ENGINE.class);
        method.setAccessible(true);
        try {
            method.invoke(mapper(), columnNameToIndexMap, ps, record,
                    new ClickHouseSinkConnectorConfig(new HashMap<>()),
                    columnNameToDataTypeMap,
                    DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }

    /** The exact record shape produced by MySQL-without-GTID on the Kafka Connect path. */
    private static ClickHouseStruct mysqlRecordWithoutGtid(long kafkaOffset) {
        ClickHouseStruct record = new ClickHouseStruct();
        record.setTopic("SERVER5432.test.employees");
        record.setKafkaOffset(kafkaOffset);
        record.setTs_ms(SOURCE_TS_MS);
        // No GTID (disabled on the source), no sequence number (never set on
        // this path) and no LSN (not a Postgres source).
        return record;
    }

    @Test
    @DisplayName("#1213: a MySQL record without GTID must not leave the -1 sentinel as its version")
    public void calculateVersionMustNotFallThroughToSentinel() {
        ClickHouseStruct record = mysqlRecordWithoutGtid(300023L);

        record.calculateVersion(true);

        assertNotEquals(-1L, record.getVersion(),
                "calculateVersion() fell through and left the uninitialized sentinel. "
                        + "Bound with setLong into a UInt64 column that stores " + UINT64_MAX
                        + ", which no later row can ever supersede under ReplacingMergeTree.");
        assertTrue(record.getVersion() > 0,
                "the derived version must be a usable positive ordering value; got "
                        + record.getVersion());
    }

    @Test
    @DisplayName("#1213: the value reaching the driver must not be UInt64 max")
    public void bindMustNotWriteUint64Max() throws Exception {
        AtomicLong bound = new AtomicLong(Long.MIN_VALUE);
        bindVersion(mysqlRecordWithoutGtid(300023L), recordingStatement(bound));

        assertNotEquals(Long.MIN_VALUE, bound.get(), "no version was bound at all");
        assertNotEquals(UINT64_MAX, Long.toUnsignedString(bound.get()),
                "the bind wrote the -1 sentinel, stored as UInt64 " + UINT64_MAX
                        + " -- exactly the value reported in issue #1213, and a version "
                        + "that permanently wins every ReplacingMergeTree merge");
        assertTrue(bound.get() > 0,
                "expected a positive version; got " + bound.get()
                        + " (unsigned " + Long.toUnsignedString(bound.get()) + ")");
    }

    @Test
    @DisplayName("#1213: the fallback reuses the existing SnowFlakeId formula with the Kafka offset")
    public void fallbackUsesSnowflakeIdWithKafkaOffset() {
        long kafkaOffset = 300023L;
        ClickHouseStruct record = mysqlRecordWithoutGtid(kafkaOffset);

        record.calculateVersion(true);

        assertEquals(SnowFlakeId.generate(SOURCE_TS_MS, kafkaOffset, false), record.getVersion(),
                "the fallback must substitute the Kafka offset into the connector's existing "
                        + "SnowFlakeId(timestamp, ordering) formula rather than invent a scheme");
    }

    @Test
    @DisplayName("#1213: versions stay strictly increasing with the Kafka offset")
    public void fallbackVersionsAreMonotonicWithinASecond() {
        ClickHouseStruct first = mysqlRecordWithoutGtid(300023L);
        ClickHouseStruct second = mysqlRecordWithoutGtid(300024L);

        first.calculateVersion(true);
        second.calculateVersion(true);

        assertTrue(second.getVersion() > first.getVersion(),
                "two changes committed in the same millisecond must still be ordered by their "
                        + "Kafka offset, otherwise ReplacingMergeTree picks between them arbitrarily; "
                        + "got " + first.getVersion() + " then " + second.getVersion());
    }

    @Test
    @DisplayName("#1213: a record with no derivable version at all fails loudly, it is never written")
    public void unresolvableVersionThrowsInsteadOfBinding() {
        // No ordering key AND no source timestamp: nothing can be derived.
        ClickHouseStruct record = new ClickHouseStruct();
        record.setTopic("SERVER5432.test.employees");
        record.setKafkaOffset(4242L);

        AtomicLong bound = new AtomicLong(Long.MIN_VALUE);
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> bindVersion(record, recordingStatement(bound)),
                "with no derivable version the batch must fail loudly; binding the sentinel "
                        + "writes UInt64 " + UINT64_MAX + ", which is unrecoverable corruption");

        assertEquals(Long.MIN_VALUE, bound.get(),
                "nothing may be bound once the version is known to be underivable");
        assertTrue(thrown.getMessage() != null && thrown.getMessage().contains(UINT64_MAX),
                "the error must name the value that would have been stored so an operator can "
                        + "recognise it in the table; got: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("SERVER5432.test.employees"),
                "the error must identify the offending record; got: " + thrown.getMessage());
    }

    // ---------------------------------------------------------------------
    // Regression guards: existing, working deployments must be untouched.
    // These pass both before and after the fix -- they pin that the new
    // fallback is reachable ONLY from the branch that is currently corrupt.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("a GTID source keeps its existing version, snowflake mode")
    public void gtidSnowflakeVersionUnchanged() {
        ClickHouseStruct record = mysqlRecordWithoutGtid(300023L);
        record.setGtid(2179558590L);

        record.calculateVersion(true);

        assertEquals(SnowFlakeId.generate(SOURCE_TS_MS, 2179558590L, false), record.getVersion(),
                "a GTID source must still take the GTID branch");
    }

    @Test
    @DisplayName("a GTID source keeps its existing version, plain mode")
    public void gtidPlainVersionUnchanged() {
        ClickHouseStruct record = mysqlRecordWithoutGtid(300023L);
        record.setGtid(2179558590L);

        record.calculateVersion(false);

        assertEquals(2179558590L, record.getVersion(),
                "with snowflake.id disabled the raw GTID must still be used verbatim");
    }

    @Test
    @DisplayName("the lightweight sequence-number path is bit-for-bit unchanged")
    public void sequenceNumberVersionUnchanged() {
        long sequenceNumber = SOURCE_TS_MS * 1_000_000L + 7L;
        ClickHouseStruct record = mysqlRecordWithoutGtid(300023L);
        record.setSequenceNumber(sequenceNumber);

        record.calculateVersion(true);

        assertEquals(sequenceNumber, record.getVersion(),
                "the lightweight connector always sets a sequence number; its 2.8.0-compatible "
                        + "version domain must not move");
    }

    @Test
    @DisplayName("a Postgres LSN source is unchanged")
    public void lsnVersionUnchanged() {
        ClickHouseStruct record = mysqlRecordWithoutGtid(300023L);
        record.setLsn(987654321L);

        record.calculateVersion(true);

        assertEquals(987654321L, record.getVersion(),
                "an LSN source must still take the LSN branch");
    }
}

package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test: the {@code -1} uninitialized {@code _version} sentinel must
 * never be written to a ReplacingMergeTree table.
 *
 * <p>{@code ClickHouseStruct.getVersion()} starts at -1 and
 * {@code calculateVersion()} is a no-op when the record carries no usable
 * source ordering key - no GTID, no binlog position, no sequence number and no
 * LSN. The sentinel then survives to the bind site.</p>
 *
 * <p>{@code _version} is declared {@code UInt64} in ClickHouse and the value is
 * bound with {@code PreparedStatement.setLong}, so the driver reinterprets the
 * 64 bits unsigned: -1 is stored as <b>18446744073709551615</b>, the largest
 * representable version. Verified against a live ClickHouse:
 * {@code SELECT reinterpretAsUInt64(toInt64(-1))} returns 18446744073709551615.
 * A row carrying that value can never be superseded - the ReplacingMergeTree
 * freezes on it and every later UPDATE and DELETE for the same key is silently
 * discarded on merge. That is unbounded, undetectable data loss, so the batch
 * must fail loudly instead.</p>
 */
public class VersionSentinelGuardTest {

    private static final String VERSION_COLUMN = "_version";

    private static PreparedStatementFieldMapper mapperWithVersionColumn() {
        return new PreparedStatementFieldMapper(
                "is_deleted",
                true,
                null,
                VERSION_COLUMN,
                "test_db",
                ZoneId.of("UTC"));
    }

    /**
     * Invokes the private bind path directly. A {@code null} PreparedStatement
     * is deliberate: the guard must reject the record before any bind is
     * attempted, so reaching the driver at all is itself a failure. If the
     * guard were removed, this call would NPE on {@code ps.setLong(...)}
     * rather than throwing IllegalStateException - a distinguishable outcome
     * that the assertions below rely on.
     */
    private static void bindVersion(PreparedStatementFieldMapper mapper,
                                    ClickHouseStruct record) throws Exception {
        Map<String, Integer> columnNameToIndexMap = new HashMap<>();
        columnNameToIndexMap.put(VERSION_COLUMN, 1);
        Map<String, String> columnNameToDataTypeMap = new HashMap<>();
        columnNameToDataTypeMap.put(VERSION_COLUMN, "UInt64");

        Method method = PreparedStatementFieldMapper.class.getDeclaredMethod(
                "handleVersionColumn",
                Map.class,
                PreparedStatement.class,
                ClickHouseStruct.class,
                ClickHouseSinkConnectorConfig.class,
                Map.class,
                DBMetadata.TABLE_ENGINE.class);
        method.setAccessible(true);
        try {
            method.invoke(mapper,
                    columnNameToIndexMap,
                    (PreparedStatement) null,
                    record,
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

    /**
     * A record with no GTID, no binlog position, no sequence number and no LSN
     * cannot produce a version. Writing it would store UInt64 max.
     */
    @Test
    @DisplayName("a record with no usable ordering key is rejected, not written as UInt64 max")
    public void rejectsUncalculableVersion() throws Exception {
        ClickHouseStruct record = new ClickHouseStruct();
        record.setTopic("test_topic");
        record.setKafkaOffset(4242L);

        // Precondition: the scenario really does leave the sentinel in place.
        record.calculateVersion(false);
        assertEquals(-1L, record.getVersion(),
                "precondition: calculateVersion() must be a no-op without an ordering key");

        PreparedStatementFieldMapper mapper = mapperWithVersionColumn();
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> bindVersion(mapper, record),
                "binding an uncalculated _version must fail loudly; storing the -1 sentinel "
                        + "writes UInt64 max, which no later row can ever supersede");

        assertNotNull(thrown.getMessage());
        assertTrue(thrown.getMessage().contains("18446744073709551615"),
                "the error must state the value that would have been stored so an operator "
                        + "can recognise it in the table; got: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("test_topic"),
                "the error must identify the offending record; got: " + thrown.getMessage());
    }

    /**
     * The guard must not fire for well-formed records. A record carrying a
     * sequence number produces a real version and binds normally - here that
     * means reaching {@code ps.setLong} on the null statement, i.e. a
     * NullPointerException rather than IllegalStateException.
     */
    @Test
    @DisplayName("a record with a sequence number binds normally and is not rejected")
    public void allowsCalculableVersion() {
        ClickHouseStruct record = new ClickHouseStruct();
        record.setTopic("test_topic");
        record.setTs_ms(1704067200000L);
        record.setSequenceNumber(1704067200000L * 1_000_000L + 1L);

        PreparedStatementFieldMapper mapper = mapperWithVersionColumn();
        Exception thrown = assertThrows(Exception.class, () -> bindVersion(mapper, record));
        assertInstanceOf(NullPointerException.class, thrown,
                "a calculable version must proceed to the bind (NPE on the null "
                        + "PreparedStatement), not be rejected by the sentinel guard; got: "
                        + thrown.getClass().getName() + ": " + thrown.getMessage());
    }

    /**
     * An LSN-only record (a Postgres source) is also calculable and must pass.
     */
    @Test
    @DisplayName("an LSN-only record binds normally and is not rejected")
    public void allowsLsnOnlyVersion() {
        ClickHouseStruct record = new ClickHouseStruct();
        record.setTopic("test_topic");
        record.setLsn(987654321L);

        PreparedStatementFieldMapper mapper = mapperWithVersionColumn();
        Exception thrown = assertThrows(Exception.class, () -> bindVersion(mapper, record));
        assertInstanceOf(NullPointerException.class, thrown,
                "an LSN-derived version must proceed to the bind, not be rejected; got: "
                        + thrown.getClass().getName());
    }

    /**
     * A GTID-only record (no binlog position) is also calculable and must pass.
     */
    @Test
    @DisplayName("a GTID-only record binds normally and is not rejected")
    public void allowsGtidOnlyVersion() {
        ClickHouseStruct record = new ClickHouseStruct();
        record.setTopic("test_topic");
        record.setGtid(987654321L);

        PreparedStatementFieldMapper mapper = mapperWithVersionColumn();
        Exception thrown = assertThrows(Exception.class, () -> bindVersion(mapper, record));
        assertInstanceOf(NullPointerException.class, thrown,
                "a GTID-derived version must proceed to the bind, not be rejected; got: "
                        + thrown.getClass().getName());
    }
}

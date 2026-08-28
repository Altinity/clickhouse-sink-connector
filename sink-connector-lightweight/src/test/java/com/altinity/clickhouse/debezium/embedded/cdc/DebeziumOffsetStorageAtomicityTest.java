package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import io.debezium.storage.jdbc.offset.JdbcOffsetBackingStoreConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for issue #1297 -- the offset update deleted the stored
 * offset row before writing its replacement, so a crash in between left the
 * connector with no offset at all.
 *
 * <p>ClickHouse has no transactions on this path. The offset-storage JDBC URL
 * sets jdbc_ignore_unsupported_values=true precisely so that
 * setAutoCommit(false) is silently ignored rather than throwing (see
 * DebeziumChangeEventCapture and SinkConnectorDataSource), so the delete and
 * the insert cannot be made one atomic unit. The window is real and cannot be
 * closed -- it can only be pointed the other way, so that what survives a
 * crash is a stale offset rather than no offset.
 *
 * <p>Losing the offset entirely is the expensive outcome: the connector
 * restarts with nothing to resume from. A superseded row left behind is
 * harmless, because the read is ordered by record_insert_ts and the next
 * update clears it.
 */
public class DebeziumOffsetStorageAtomicityTest {

    private static final String OFFSET_TABLE =
            "altinity_sink_connector.replica_source_info";

    private static final String OFFSET_TABLE_KEY =
            JdbcOffsetBackingStoreConfig.OFFSET_STORAGE_PREFIX
                    + JdbcOffsetBackingStoreConfig.PROP_TABLE_NAME.name();

    /** A stored offset in the shape the connector writes and reads back. */
    private static final String STORED_OFFSET =
            "{\"transaction_id\":null,\"ts_sec\":1687278006,"
                    + "\"file\":\"mysql-bin.000003\",\"pos\":1156385,"
                    + "\"row\":1,\"server_id\":266,\"event\":2}";

    private static Properties props() {
        Properties props = new Properties();
        props.setProperty(OFFSET_TABLE_KEY, OFFSET_TABLE);
        props.setProperty("name", "engine");
        return props;
    }

    private static ClickHouseSinkConnectorConfig config() {
        return new ClickHouseSinkConnectorConfig(new HashMap<>());
    }

    /**
     * The real storage object with only the offset read stubbed out, so the
     * delete and insert statements under test are the production ones.
     */
    private static class ReadStubbedStorage extends DebeziumOffsetStorage {
        @Override
        public String getDebeziumStorageStatusQuery(Properties props,
                                                    Connection connection) {
            return STORED_OFFSET;
        }
    }

    private static DebeziumJdbcStorageOperations opsWith(
            DebeziumOffsetStorage storage) {
        DebeziumJdbcStorageOperations ops = new DebeziumJdbcStorageOperations();
        ops.debeziumOffsetStorage = storage;
        return ops;
    }

    @Test
    @DisplayName("#1297 the new offset row is written before the old one is removed")
    void offsetUpdate_insertsBeforeDeleting() throws Exception {
        OffsetStatementRecorder recorder = new OffsetStatementRecorder();

        opsWith(new ReadStubbedStorage()).updateDebeziumStorageStatus(
                recorder.connection(), config(), props(),
                "mysql-bin.000004", "1157000", null);

        int insertAt = recorder.indexOf("insert into");
        int deleteAt = recorder.indexOf("delete from");

        assertTrue(insertAt >= 0, "an offset row must be written, saw: "
                + recorder.summary());
        assertTrue(deleteAt >= 0, "superseded rows must be removed, saw: "
                + recorder.summary());
        assertTrue(insertAt < deleteAt,
                "the replacement offset must be written BEFORE the old one is "
                        + "removed, otherwise a crash in between loses the "
                        + "offset entirely. Order was: " + recorder.summary());
    }

    /**
     * The PostgreSQL overload carries the same delete/insert pair and must be
     * ordered the same way. Fixing only the binlog path would leave every
     * PostgreSQL deployment exposed.
     */
    @Test
    @DisplayName("#1297 the LSN overload is ordered the same way")
    void lsnOffsetUpdate_insertsBeforeDeleting() throws Exception {
        OffsetStatementRecorder recorder = new OffsetStatementRecorder();

        opsWith(new ReadStubbedStorage()).updateDebeziumStorageStatus(
                recorder.connection(), config(), props(), "0/1A2B3C4");

        int insertAt = recorder.indexOf("insert into");
        int deleteAt = recorder.indexOf("delete from");

        assertTrue(insertAt >= 0 && deleteAt >= 0,
                "both statements must be issued, saw: " + recorder.summary());
        assertTrue(insertAt < deleteAt,
                "the LSN overload must also insert before deleting. Order was: "
                        + recorder.summary());
    }

    /**
     * The defect made concrete: abort at the point where the second statement
     * would run, and check what a restart would find.
     *
     * <p>The abort is an unchecked exception on purpose.
     * DBMetadata.executeSystemQuery wraps its work in a retry loop that
     * swallows and retries SQLException, so a SQLException here would simulate
     * a transient error the connector recovers from -- not the crash this test
     * is about.
     */
    @Test
    @DisplayName("#1297 a crash between the two statements leaves an offset behind")
    void crashBetweenStatements_leavesAnOffsetRow() {
        OffsetStatementRecorder recorder = new OffsetStatementRecorder();
        DebeziumJdbcStorageOperations ops = opsWith(new ReadStubbedStorage() {
            @Override
            public void deleteSupersededOffsetRows(String offsetKey,
                                                   String keepId,
                                                   Properties props,
                                                   Connection connection) {
                throw new IllegalStateException("simulated crash");
            }
        });

        assertThrows(IllegalStateException.class, () ->
                ops.updateDebeziumStorageStatus(recorder.connection(), config(),
                        props(), "mysql-bin.000004", "1157000", null));

        assertTrue(recorder.indexOf("insert into") >= 0,
                "a crash during the update must still leave an offset row to "
                        + "resume from; nothing was written before the crash, "
                        + "so the connector would restart with no offset at "
                        + "all. Statements issued: " + recorder.summary());
    }

    /**
     * Control: writing without deleting is not the fix either.
     *
     * <p>The offset table is a ReplacingMergeTree whose sorting key is id, and
     * id is a fresh UUID on every insert rather than the offset key, so rows
     * for one connector never collapse. The delete has to stay -- it just has
     * to run second, and it must spare the row just written.
     */
    @Test
    @DisplayName("#1297 control -- the delete still runs, and spares the new row")
    void offsetUpdate_stillRemovesSupersededRows() throws Exception {
        OffsetStatementRecorder recorder = new OffsetStatementRecorder();

        opsWith(new ReadStubbedStorage()).updateDebeziumStorageStatus(
                recorder.connection(), config(), props(),
                "mysql-bin.000004", "1157000", null);

        OffsetStatementRecorder.Recorded insert = recorder.first("insert into");
        OffsetStatementRecorder.Recorded delete = recorder.first("delete from");

        String insertedId = insert.boundValues.get(0);
        assertNotNull(insertedId, "the insert must report the id it generated");

        assertTrue(delete.sql.contains("offset_key=?"),
                "the offset key stays a bind parameter, was: " + delete.sql);
        assertTrue(delete.sql.contains("id!=?"),
                "the delete must exclude the row just inserted, was: "
                        + delete.sql);
        assertTrue(delete.boundValues.contains(insertedId),
                "the excluded id must be the one just written (" + insertedId
                        + "), bound values were: " + delete.boundValues);
        assertEquals(2, recorder.statements.size(),
                "exactly one insert and one delete, saw: "
                        + recorder.summary());
    }
}

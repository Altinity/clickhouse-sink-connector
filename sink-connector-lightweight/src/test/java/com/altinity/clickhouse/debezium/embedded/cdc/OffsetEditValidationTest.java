package com.altinity.clickhouse.debezium.embedded.cdc;

import io.debezium.storage.jdbc.offset.JdbcOffsetBackingStoreConfig;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REST {@code /binlog} and {@code /lsn} offset edits are validated, and the
 * stored offset is read as the NEWEST row (spec 09.03 §3.2, §3.4).
 *
 * <p><b>The defects.</b> {@code updateBinLogInformation} overlaid whatever it
 * was given onto the stored offset JSON: the position was stored as a
 * string (Debezium then failed at startup on anything non-numeric), a new
 * file/position left the old {@code gtids} in place (which Debezium prefers,
 * so the edit was silently ignored) and left the old {@code row}/{@code event}
 * skip counters in place (so rows of the first event at the new position were
 * silently skipped), and an empty request was accepted. The LSN form
 * {@code X/Y} was parsed as {@code Y} alone. The base row was read with a
 * plain {@code select offset_val ... where offset_key=...}, which on a
 * ReplacingMergeTree with unmerged parts returns an arbitrary one of several
 * rows, so the edit could be applied on top of a stale checkpoint.</p>
 */
public class OffsetEditValidationTest {

    private static final String BASE = "{\"transaction_id\":null,\"ts_sec\":1687278006,"
            + "\"file\":\"mysql-bin.000003\",\"pos\":1156385,"
            + "\"gtids\":\"30fd82c7-0f86-11ee-9e3b-0242c0a86002:1-2442\","
            + "\"row\":1,\"server_id\":266,\"event\":2}";

    private static JSONObject parse(String json) throws Exception {
        return (JSONObject) new JSONParser().parse(json);
    }

    @Test
    @DisplayName("A binlog file + position edit stores the position as a number and drops the stored GTID set and skip counters")
    public void filePositionEditIsNumericAndDropsGtidsAndSkipCounters() throws Exception {
        String updated = new DebeziumOffsetStorage().updateBinLogInformation(
                BASE, "mysql-bin.000009", "4", null);
        JSONObject json = parse(updated);

        assertEquals("mysql-bin.000009", json.get("file"));
        assertEquals(4L, json.get("pos"), "pos must be a JSON number (Debezium reads it as a long)");
        assertFalse(json.containsKey("gtids"),
                "a file/position edit must drop the stored GTID set: Debezium resumes from the GTID "
                        + "set when one is present, so leaving it in place silently ignores the edit");
        assertEquals(0L, json.get("row"),
                "the row skip counter belongs to the OLD position; kept, it skips rows of the first "
                        + "event at the new position");
        assertEquals(0L, json.get("event"), "the event skip counter must be reset too");
        assertEquals(266L, json.get("server_id"), "unrelated fields are preserved");
        assertEquals(1687278006L, json.get("ts_sec"));
    }

    @Test
    @DisplayName("A GTID-only edit replaces the GTID set and keeps the stored file/position")
    public void gtidOnlyEditKeepsFilePosition() throws Exception {
        String updated = new DebeziumOffsetStorage().updateBinLogInformation(
                BASE, null, null, "30fd82c7-0f86-11ee-9e3b-0242c0a86002:1-3000");
        JSONObject json = parse(updated);

        assertEquals("30fd82c7-0f86-11ee-9e3b-0242c0a86002:1-3000", json.get("gtids"));
        assertEquals("mysql-bin.000003", json.get("file"));
        assertEquals(1156385L, json.get("pos"));
    }

    @Test
    @DisplayName("A file + position + GTID edit stores all three")
    public void fileAndGtidEditStoresAll() throws Exception {
        JSONObject json = parse(new DebeziumOffsetStorage().updateBinLogInformation(
                BASE, "mysql-bin.000009", "120", "30fd82c7-0f86-11ee-9e3b-0242c0a86002:1-3000"));
        assertEquals("mysql-bin.000009", json.get("file"));
        assertEquals(120L, json.get("pos"));
        assertEquals("30fd82c7-0f86-11ee-9e3b-0242c0a86002:1-3000", json.get("gtids"));
    }

    @Test
    @DisplayName("A non-numeric or negative position is refused")
    public void nonNumericPositionIsRefused() {
        DebeziumOffsetStorage storage = new DebeziumOffsetStorage();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> storage.updateBinLogInformation(BASE, "mysql-bin.000009", "12a", null),
                "pre-fix the string was stored verbatim and Debezium failed at the next start");
        assertTrue(ex.getMessage().contains("binlog_position"), ex.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> storage.updateBinLogInformation(BASE, "mysql-bin.000009", "-1", null));
    }

    @Test
    @DisplayName("A file without a position, or a position without a file, is refused")
    public void halfACoordinateIsRefused() {
        DebeziumOffsetStorage storage = new DebeziumOffsetStorage();
        assertThrows(IllegalArgumentException.class,
                () -> storage.updateBinLogInformation(BASE, "mysql-bin.000009", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> storage.updateBinLogInformation(BASE, null, "4", null));
    }

    @Test
    @DisplayName("An edit that names nothing is refused instead of rewriting the stored offset with itself")
    public void emptyEditIsRefused() {
        DebeziumOffsetStorage storage = new DebeziumOffsetStorage();
        assertThrows(IllegalArgumentException.class,
                () -> storage.updateBinLogInformation(BASE, "", "", ""));
        assertThrows(IllegalArgumentException.class,
                () -> storage.updateBinLogInformation(null, null, null, null));
    }

    @Test
    @DisplayName("With no stored offset a file + position edit creates a complete row")
    public void editWithoutStoredOffsetCreatesRow() throws Exception {
        JSONObject json = parse(new DebeziumOffsetStorage().updateBinLogInformation(
                null, "mysql-bin.000001", "4", null));
        assertEquals("mysql-bin.000001", json.get("file"));
        assertEquals(4L, json.get("pos"));
        assertTrue(json.containsKey("ts_sec"));
        assertFalse(json.containsKey("gtids"));
    }

    @Test
    @DisplayName("A PostgreSQL LSN in X/Y form is (X << 32) | Y, not Y alone")
    public void lsnHighWordIsHonoured() throws Exception {
        DebeziumOffsetStorage storage = new DebeziumOffsetStorage();
        String base = "{\"transaction_id\":null,\"lsn_proc\":27485360,\"messageType\":\"UPDATE\","
                + "\"lsn\":27485360,\"txId\":743,\"ts_usec\":1687876724804733}";

        JSONObject json = parse(storage.updateLsnInformation(base, "1/AF00"));
        long expected = (1L << 32) | 0xAF00L;
        assertEquals(expected, json.get("lsn"),
                "pre-fix the high word was discarded, positioning the connector 4 GiB of WAL too early");
        assertEquals(expected, json.get("lsn_proc"));

        assertEquals(27496352L, parse(storage.updateLsnInformation(base, "0/1A38FA0")).get("lsn"));
        assertEquals(27496352L, parse(storage.updateLsnInformation(base, "27496352")).get("lsn"));
    }

    @Test
    @DisplayName("A malformed or missing LSN is refused with a message naming the field")
    public void malformedLsnIsRefused() {
        DebeziumOffsetStorage storage = new DebeziumOffsetStorage();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> storage.updateLsnInformation("{}", "garbage"));
        assertTrue(ex.getMessage().contains("lsn"), ex.getMessage());
        assertThrows(IllegalArgumentException.class, () -> storage.updateLsnInformation("{}", null));
        assertThrows(IllegalArgumentException.class, () -> storage.updateLsnInformation("{}", "1/2/3"));
    }

    private static Properties offsetProps(String ddl) {
        Properties props = new Properties();
        props.setProperty(JdbcOffsetBackingStoreConfig.OFFSET_STORAGE_PREFIX
                + JdbcOffsetBackingStoreConfig.PROP_TABLE_NAME.name(), "db1.replica_source_info");
        props.setProperty("name", "orders");
        if (ddl != null) {
            props.setProperty(JdbcOffsetBackingStoreConfig.OFFSET_STORAGE_PREFIX
                    + JdbcOffsetBackingStoreConfig.PROP_TABLE_DDL.name(), ddl);
        }
        return props;
    }

    @Test
    @DisplayName("The stored offset is read as the newest row: FINAL, ORDER BY record_insert_ts DESC, record_insert_seq DESC, LIMIT 1")
    public void offsetReadTakesTheNewestRow() {
        String sql = new DebeziumOffsetStorage().offsetValueQuery(offsetProps(
                "CREATE TABLE if not exists %s (`id` String, `offset_key` String, `offset_val` String, "
                        + "`record_insert_ts` DateTime, `record_insert_seq` UInt64) "
                        + "ENGINE = ReplacingMergeTree ORDER BY offset_key"));
        String upper = sql.toUpperCase();

        assertTrue(upper.contains(" FINAL"), "unmerged parts hold several rows per key: " + sql);
        assertTrue(upper.contains("ORDER BY RECORD_INSERT_TS DESC, RECORD_INSERT_SEQ DESC"),
                "the newest checkpoint must win: " + sql);
        assertTrue(upper.contains("LIMIT 1"), sql);
        assertTrue(sql.contains("db1.replica_source_info"), sql);
        assertTrue(sql.contains(new DebeziumOffsetStorage().getOffsetKey(offsetProps(null))), sql);
    }

    @Test
    @DisplayName("A KeeperMap offset table (no FINAL support) is read with the same ordering and no FINAL")
    public void keeperMapOffsetReadHasNoFinal() {
        String sql = new DebeziumOffsetStorage().offsetValueQuery(offsetProps(
                "CREATE TABLE if not exists %s ON CLUSTER c (`id` String, `offset_key` String, "
                        + "`offset_val` String, `record_insert_ts` DateTime, `record_insert_seq` UInt64) "
                        + "ENGINE = KeeperMap('/asc_offsets', 10) PRIMARY KEY offset_key"));
        String upper = sql.toUpperCase();

        assertFalse(upper.contains(" FINAL"), "KeeperMap rejects FINAL: " + sql);
        assertTrue(upper.contains("ORDER BY RECORD_INSERT_TS DESC, RECORD_INSERT_SEQ DESC"), sql);
        assertTrue(upper.contains("LIMIT 1"), sql);
    }
}

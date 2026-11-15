package com.altinity.clickhouse.sink.connector.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.debezium.engine.ChangeEvent;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SourcePosition} orders change events in COMMIT order - the order the
 * transaction log delivers them - so the version sequence can tell a first delivery
 * from a redelivery.
 */
public class SourcePositionTest {

    @Test
    @DisplayName("within one binlog file positions order by pos, then by row")
    public void ordersByPosThenRow() {
        SourcePosition a = SourcePosition.ofBinlog("mysql-bin.000010", 100L, 0);
        SourcePosition b = SourcePosition.ofBinlog("mysql-bin.000010", 100L, 1);
        SourcePosition c = SourcePosition.ofBinlog("mysql-bin.000010", 250L, 0);

        assertTrue(a.compareTo(b) < 0, "row 1 of the same event follows row 0");
        assertTrue(b.compareTo(c) < 0, "a later event follows every row of the earlier one");
        assertTrue(c.compareTo(a) > 0);
        assertEquals(0, a.compareTo(SourcePosition.ofBinlog("mysql-bin.000010", 100L, 0)));
    }

    @Test
    @DisplayName("a binary log rotation resets pos: the new file always orders after the old one")
    public void rotationOrdersByFileNumber() {
        SourcePosition lastOfOldFile = SourcePosition.ofBinlog("mysql-bin.000010", 1_073_741_824L, 3);
        SourcePosition firstOfNewFile = SourcePosition.ofBinlog("mysql-bin.000011", 4L, 0);

        assertTrue(firstOfNewFile.compareTo(lastOfOldFile) > 0,
                "mysql-bin.000011:4 committed after mysql-bin.000010:1073741824");
    }

    @Test
    @DisplayName("the file number is compared numerically, not lexically")
    public void fileNumberIsNumeric() {
        // Six digits overflow into seven: a lexical compare would put 1000000 before 999999.
        SourcePosition sixDigits = SourcePosition.ofBinlog("mysql-bin.999999", 500L, 0);
        SourcePosition sevenDigits = SourcePosition.ofBinlog("mysql-bin.1000000", 4L, 0);

        assertTrue(sevenDigits.compareTo(sixDigits) > 0);
        assertEquals(999_999L, SourcePosition.binlogFileSequence("mysql-bin.999999"));
        assertEquals(1_000_000L, SourcePosition.binlogFileSequence("mysql-bin.1000000"));
        assertEquals(7L, SourcePosition.binlogFileSequence("binlog.000007"));
    }

    @Test
    @DisplayName("a file name without a numeric suffix still compares deterministically")
    public void nonNumericFileNamesFallBackToLexicalOrder() {
        assertEquals(-1L, SourcePosition.binlogFileSequence("mysql-bin"));
        assertEquals(-1L, SourcePosition.binlogFileSequence("mysql-bin."));
        assertEquals(-1L, SourcePosition.binlogFileSequence("mysql-bin.abc"));

        SourcePosition a = SourcePosition.ofBinlog("log-a", 10L, 0);
        SourcePosition b = SourcePosition.ofBinlog("log-b", 5L, 0);
        assertTrue(a.compareTo(b) < 0);
        assertTrue(b.compareTo(a) > 0);
    }

    @Test
    @DisplayName("the order stays transitive when numeric and non-numeric suffixes are mixed")
    public void mixedSuffixesKeepATransitiveOrder() {
        // Numeric 20 < numeric 100; the non-numeric name shares the prefix and sorts
        // after both. A lexical fallback alone would give a < b, b < c and a > c.
        SourcePosition a = SourcePosition.ofBinlog("log.20", 1L, 0);
        SourcePosition b = SourcePosition.ofBinlog("log.100", 1L, 0);
        SourcePosition c = SourcePosition.ofBinlog("log.15a", 1L, 0);

        assertTrue(a.compareTo(b) < 0, "a < b");
        assertTrue(b.compareTo(c) < 0, "b < c");
        assertTrue(a.compareTo(c) < 0, "a < c (transitivity)");

        // Different prefixes group first, whatever the suffixes.
        SourcePosition d = SourcePosition.ofBinlog("alpha.999999", 1L, 0);
        assertTrue(d.compareTo(a) < 0);
        assertTrue(d.compareTo(c) < 0);
    }

    @Test
    @DisplayName("records without binlog coordinates have no position")
    public void missingCoordinatesYieldNoPosition() {
        assertNull(SourcePosition.ofBinlog(null, 10L, 0));
        assertNull(SourcePosition.ofBinlog("", 10L, 0));
        assertNull(SourcePosition.ofBinlog("mysql-bin.000001", null, 0));
        assertNull(SourcePosition.ofBinlog("mysql-bin.000001", 0L, 0),
                "pos 0 is the ClickHouseStruct default, i.e. never populated from a record");
        assertNull(SourcePosition.ofLsn(null));
        assertNull(SourcePosition.ofLsn(-1L));
        assertNull(SourcePosition.ofLsn(0L));
    }

    @Test
    @DisplayName("a null row is treated as row 0")
    public void nullRowIsRowZero() {
        assertEquals(0, SourcePosition.ofBinlog("mysql-bin.000001", 10L, null)
                .compareTo(SourcePosition.ofBinlog("mysql-bin.000001", 10L, 0)));
    }

    @Test
    @DisplayName("PostgreSQL LSNs order numerically")
    public void lsnOrdersNumerically() {
        SourcePosition low = SourcePosition.ofLsn(27_485_360L);
        SourcePosition high = SourcePosition.ofLsn(27_496_352L);
        assertNotNull(low);
        assertTrue(low.compareTo(high) < 0);
        assertTrue(high.compareTo(low) > 0);
        assertEquals(0, low.compareTo(SourcePosition.ofLsn(27_485_360L)));
    }

    @Test
    @DisplayName("equals and hashCode agree with compareTo")
    public void equalsAgreesWithCompareTo() {
        SourcePosition a = SourcePosition.ofBinlog("mysql-bin.000010", 100L, 2);
        SourcePosition same = SourcePosition.ofBinlog("mysql-bin.000010", 100L, 2);
        SourcePosition other = SourcePosition.ofBinlog("mysql-bin.000010", 100L, 3);

        assertEquals(a, same);
        assertEquals(a.hashCode(), same.hashCode());
        assertNotEquals(a, other);
        assertEquals("mysql-bin.000010:100:2", a.toString());
        assertEquals("lsn=42", SourcePosition.ofLsn(42L).toString());
    }

    private static Struct mysqlSource(String file, long pos, int row) {
        Schema schema = SchemaBuilder.struct()
                .field(SinkRecordColumns.TS_MS, Schema.INT64_SCHEMA)
                .field(SinkRecordColumns.BINLOG_FILE, Schema.STRING_SCHEMA)
                .field(SinkRecordColumns.BINLOG_POS, Schema.INT64_SCHEMA)
                .field(SinkRecordColumns.ROW, Schema.INT32_SCHEMA)
                .build();
        return new Struct(schema)
                .put(SinkRecordColumns.TS_MS, 1_757_900_000_000L)
                .put(SinkRecordColumns.BINLOG_FILE, file)
                .put(SinkRecordColumns.BINLOG_POS, pos)
                .put(SinkRecordColumns.ROW, row);
    }

    @Test
    @DisplayName("the position is read from a MySQL source struct (file, pos, row)")
    public void readsMySqlSourceStruct() {
        SourcePosition position = ClickHouseStruct.sourcePositionOf(mysqlSource("mysql-bin.000123", 4_567L, 3));
        assertNotNull(position);
        assertEquals(SourcePosition.ofBinlog("mysql-bin.000123", 4_567L, 3), position);
    }

    @Test
    @DisplayName("the position is read from a PostgreSQL source struct (lsn)")
    public void readsPostgresSourceStruct() {
        Schema schema = SchemaBuilder.struct()
                .field(SinkRecordColumns.TS_MS, Schema.INT64_SCHEMA)
                .field(SinkRecordColumns.LSN, Schema.OPTIONAL_INT64_SCHEMA)
                .build();
        Struct source = new Struct(schema)
                .put(SinkRecordColumns.TS_MS, 1_757_900_000_000L)
                .put(SinkRecordColumns.LSN, 27_496_352L);

        assertEquals(SourcePosition.ofLsn(27_496_352L), ClickHouseStruct.sourcePositionOf(source));
    }

    @Test
    @DisplayName("a source struct without coordinates (heartbeat, MongoDB) yields no position")
    public void sourceWithoutCoordinatesYieldsNoPosition() {
        Schema schema = SchemaBuilder.struct()
                .field(SinkRecordColumns.TS_MS, Schema.INT64_SCHEMA)
                .build();
        Struct source = new Struct(schema).put(SinkRecordColumns.TS_MS, 1_757_900_000_000L);

        assertNull(ClickHouseStruct.sourcePositionOf(source));
        assertNull(ClickHouseStruct.getSourcePositionFromChangeEvent(null));
    }

    @Test
    @DisplayName("ClickHouseStruct exposes the position captured from its source fields")
    public void structExposesItsPosition() {
        ClickHouseStruct record = new ClickHouseStruct();
        assertNull(record.getSourcePosition(), "a bare struct has no coordinates");

        record.setFile("mysql-bin.000009");
        record.setPos(777L);
        record.setRow(1);
        assertEquals(SourcePosition.ofBinlog("mysql-bin.000009", 777L, 1), record.getSourcePosition());

        ClickHouseStruct pg = new ClickHouseStruct();
        pg.setLsn(99L);
        assertEquals(SourcePosition.ofLsn(99L), pg.getSourcePosition());
    }

    @Test
    public void restartCursorOrdersEventsDespiteWrappedHeaderPositions() {
        SourcePosition first = ClickHouseStruct.getSourcePositionFromChangeEvent(
                binlogEvent(900_000_100L, 900_000_000L, 4L, 1L));
        SourcePosition second = ClickHouseStruct.getSourcePositionFromChangeEvent(
                binlogEvent(900_000_300L, 900_000_000L, 6L, 1L));
        SourcePosition wrapped = ClickHouseStruct.getSourcePositionFromChangeEvent(
                binlogEvent(700_000_000L, 900_000_000L, 500_004L, 1L));
        assertTrue(first.compareTo(second) < 0);
        assertTrue(second.compareTo(wrapped) < 0, "offset event ordinal survives header wrap");
        assertFalse(first.sameLogPosition(second), "an anchor contains multiple events");
        SourcePosition replay = ClickHouseStruct.getSourcePositionFromChangeEvent(
                binlogEvent(700_000_000L, 900_000_000L, 500_004L, 1L));
        assertEquals(wrapped, replay, "restart reconstruction needs no wrap state");
        assertEquals(wrapped.hashCode(), replay.hashCode());
        assertNotEquals(first, second);
    }

    @Test
    public void instanceAndStaticExtractorsUseTheSameRestartCursor() {
        ChangeEvent<SourceRecord, SourceRecord> event =
                binlogEvent(700_000_000L, 900_000_000L, 500_004L, 1L);
        ClickHouseStruct record = new ClickHouseStruct();
        record.setFile("mysql-bin.000010");
        record.setPos(700_000_000L);
        record.setRow(0);
        record.setSourceRecord(event);
        assertEquals(ClickHouseStruct.getSourcePositionFromChangeEvent(event), record.getSourcePosition());
        assertNotEquals(SourcePosition.ofBinlog("mysql-bin.000010", 700_000_000L, 0),
                record.getSourcePosition(), "a restart anchor is not a header coordinate");
    }

    @Test
    public void offsetRowsResetOnlyWithinAnOffsetEvent() {
        SourcePosition row = ClickHouseStruct.getSourcePositionFromChangeEvent(
                binlogEvent(1000L, 900L, 4L, 8L));
        SourcePosition reset = ClickHouseStruct.getSourcePositionFromChangeEvent(
                binlogEvent(1000L, 900L, 4L, 1L));
        SourcePosition next = ClickHouseStruct.getSourcePositionFromChangeEvent(
                binlogEvent(1000L, 900L, 6L, 1L));
        assertTrue(row.sameLogPosition(reset), "compressed payload keeps the event ordinal");
        assertFalse(row.sameLogPosition(next));
        assertTrue(row.compareTo(next) < 0);
    }

    private static ChangeEvent<SourceRecord, SourceRecord> binlogEvent(
            long headerPos, long anchor, long event, long row) {
        return changeEvent(mysqlSource("mysql-bin.000010", headerPos, 0),
                Map.of("file", "mysql-bin.000010", "pos", anchor, "event", event, "row", row));
    }

    private static ChangeEvent<SourceRecord, SourceRecord> changeEvent(Struct source, Map<String, ?> offset) {
        Schema schema = SchemaBuilder.struct().field(SinkRecordColumns.SOURCE, source.schema()).build();
        SourceRecord record = new SourceRecord(Collections.singletonMap("server", "example"), offset,
                "example.events", schema, new Struct(schema).put(SinkRecordColumns.SOURCE, source));
        return new ChangeEvent<SourceRecord, SourceRecord>() {
            public SourceRecord key() { return null; }
            public SourceRecord value() { return record; }
            public String destination() { return record.topic(); }
            public Integer partition() { return null; }
        };
    }

    @Test
    public void invalidOrAbsentOffsetsKeepTheExistingEnvelopeFallback() {
        Struct source = mysqlSource("mysql-bin.000010", 1234L, 3);
        SourcePosition expected = SourcePosition.ofBinlog("mysql-bin.000010", 1234L, 3);
        assertFallback(source, null, expected);
        assertFallback(source, Collections.emptyMap(), expected);
        Map<String, Object> valid = Map.of("file", "mysql-bin.000010", "pos", 900L, "event", 4L, "row", 1L);
        for (String key : valid.keySet()) {
            Map<String, Object> missing = new HashMap<>(valid);
            missing.remove(key);
            if (key.equals("file") || key.equals("pos")) {
                assertFallback(source, missing, expected);
            }
            for (Object invalid : new Object[] {key.equals("file") ? "" : "4", -1L, 1.5, null}) {
                Map<String, Object> malformed = new HashMap<>(valid);
                malformed.put(key, invalid);
                assertFallback(source, malformed, expected);
            }
        }
        assertFallback(source, Map.of("file", "", "pos", 900L), expected);
        assertFallback(source, Map.of("file", "mysql-bin.000010", "pos", 0L), expected);
    }

    private static void assertFallback(Struct source, Map<String, ?> offset, SourcePosition expected) {
        ChangeEvent<SourceRecord, SourceRecord> event = changeEvent(source, offset);
        assertEquals(expected, ClickHouseStruct.getSourcePositionFromChangeEvent(event));
        ClickHouseStruct record = new ClickHouseStruct();
        record.setFile("mysql-bin.000010");
        record.setPos(1234L);
        record.setRow(3);
        record.setSourceRecord(event);
        assertEquals(expected, record.getSourcePosition());
    }

    @Test
    public void omittedOffsetComponentsMeanZeroAndIntegralTypesAreAccepted() {
        SourcePosition omitted = ClickHouseStruct.getSourcePositionFromChangeEvent(changeEvent(
                mysqlSource("mysql-bin.000010", 1000L, 0), Map.of("file", "mysql-bin.000010", "pos", 900)));
        SourcePosition zero = ClickHouseStruct.getSourcePositionFromChangeEvent(binlogEvent(1000, 900, 0, 0));
        assertEquals(zero, omitted);
        assertEquals(zero.hashCode(), omitted.hashCode());
        SourcePosition later = ClickHouseStruct.getSourcePositionFromChangeEvent(changeEvent(
                mysqlSource("mysql-bin.000010", 1000L, 0),
                Map.of("file", "mysql-bin.000010", "pos", 900L, "event", 1, "row", 1)));
        assertTrue(later.compareTo(zero) > 0);
        assertFalse(later.sameLogPosition(zero));
        assertFalse(later.sameCoordinateKind(SourcePosition.ofBinlog("mysql-bin.000010", 900L, 0)));
    }

    @Test
    public void offsetMetadataDoesNotReplaceAPostgresLsn() {
        Schema schema = SchemaBuilder.struct().field(SinkRecordColumns.LSN, Schema.INT64_SCHEMA).build();
        ChangeEvent<SourceRecord, SourceRecord> event = changeEvent(
                new Struct(schema).put(SinkRecordColumns.LSN, 42L),
                Map.of("file", "unrelated.000001", "pos", 900L));
        assertEquals(SourcePosition.ofLsn(42L), ClickHouseStruct.getSourcePositionFromChangeEvent(event));
        ClickHouseStruct record = new ClickHouseStruct();
        record.setLsn(42L);
        record.setSourceRecord(event);
        assertEquals(SourcePosition.ofLsn(42L), record.getSourcePosition());
    }

    @Test
    public void mixedCoordinateKindsStillHaveATransitiveTotalOrder() {
        SourcePosition low = ClickHouseStruct.getSourcePositionFromChangeEvent(binlogEvent(1000, 900, 4, 1));
        SourcePosition high = ClickHouseStruct.getSourcePositionFromChangeEvent(binlogEvent(1100, 900, 6, 1));
        SourcePosition legacy = SourcePosition.ofBinlog("mysql-bin.000010", 10_000L, 0);
        assertTrue(legacy.compareTo(low) < 0);
        assertTrue(low.compareTo(high) < 0);
        assertTrue(legacy.compareTo(high) < 0);
        SourcePosition rotated = ClickHouseStruct.getSourcePositionFromChangeEvent(changeEvent(
                mysqlSource("mysql-bin.000011", 500L, 0),
                Map.of("file", "mysql-bin.000011", "pos", 400L, "event", 1L, "row", 1L)));
        assertTrue(rotated.compareTo(high) > 0);
    }

    /**
     * Positions are comparable only within one binary log. Across a basename
     * change (log_bin reconfigured, a failover to a differently named log, or a
     * RESET MASTER with the engine re-created in the same JVM) the prefix order
     * is just string order, so the version sequence must not use compareTo to
     * tell a first delivery from a redelivery; sameLog says whether it may
     * (spec 01.02 section 3.1.1).
     */
    @Test
    @DisplayName("sameLog is equality of the file prefix: true across a rotation, false across a basename change")
    public void sameLogPositionIgnoresTheRowIndex() {
        SourcePosition first = SourcePosition.ofBinlog("mysql-bin.000123", 900L, 0);
        SourcePosition laterRow = SourcePosition.ofBinlog("mysql-bin.000123", 900L, 7);
        SourcePosition restartedRow = SourcePosition.ofBinlog("mysql-bin.000123", 900L, 0);
        SourcePosition nextEvent = SourcePosition.ofBinlog("mysql-bin.000123", 950L, 0);
        SourcePosition nextFile = SourcePosition.ofBinlog("mysql-bin.000124", 900L, 0);
        SourcePosition renamed = SourcePosition.ofBinlog("binlog.000123", 900L, 0);

        assertTrue(first.sameLogPosition(laterRow), "a higher row index is the same event");
        assertTrue(laterRow.sameLogPosition(restartedRow), "a row index that restarted at 0 is the same event");
        assertTrue(laterRow.compareTo(restartedRow) > 0,
                "sanity: by compareTo the restarted row ranks BELOW, which is why compareTo alone "
                        + "would call the rest of a compressed transaction a redelivery");
        assertFalse(first.sameLogPosition(nextEvent), "another byte position is another event");
        assertFalse(first.sameLogPosition(nextFile), "the same offset in the next file is another event");
        assertFalse(first.sameLogPosition(renamed), "another log is never the same event");
        assertTrue(SourcePosition.ofLsn(10L).sameLogPosition(SourcePosition.ofLsn(10L)));
        assertFalse(SourcePosition.ofLsn(10L).sameLogPosition(SourcePosition.ofLsn(11L)));
    }

    @Test
    public void sameLogIsByFilePrefix() {
        SourcePosition oldLog = SourcePosition.ofBinlog("mysql-bin.000123", 900L, 0);
        SourcePosition rotated = SourcePosition.ofBinlog("mysql-bin.000124", 4L, 0);
        SourcePosition renamed = SourcePosition.ofBinlog("binlog.000001", 4L, 0);

        assertTrue(oldLog.sameLog(rotated), "a rotation keeps the basename: the same log");
        assertTrue(rotated.sameLog(oldLog));
        assertTrue(oldLog.sameLog(oldLog));
        assertFalse(oldLog.sameLog(renamed), "a different basename is a different log");
        assertFalse(renamed.sameLog(oldLog));
        assertTrue(renamed.compareTo(oldLog) < 0,
                "sanity: by string order the new log ranks BELOW the old one, which is why compareTo "
                        + "alone would call the whole new log a redelivery");

        assertTrue(SourcePosition.ofLsn(10L).sameLog(SourcePosition.ofLsn(20L)),
                "PostgreSQL positions have no file: always the same log");
    }
}

package com.altinity.clickhouse.sink.connector.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}

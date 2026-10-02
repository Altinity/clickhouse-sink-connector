package com.altinity.clickhouse.debezium.embedded.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

/**
 * The MySQL source timestamp is the second in which a statement STARTED, so it is not
 * monotonic in binlog (commit) order. The version must still be.
 */
public class MonotonicVersionTsTest {

    private static final long S = 1_790_938_970_000L; // a source second, in ms
    private static final String FILE = "mysql-bin-changelog.124802";

    @BeforeEach
    @AfterEach
    public void resetSequenceState() {
        DebeziumChangeEventCapture.sequenceNumber = DebeziumChangeEventCapture.SEQUENCE_START;
        DebeziumChangeEventCapture.sequenceAnchorTs = 0L;
        DebeziumChangeEventCapture.lastVersionTs = 0L;
        DebeziumChangeEventCapture.maxBinlogFileIndex = -1L;
        DebeziumChangeEventCapture.maxBinlogPos = -1L;
    }

    private ClickHouseStruct binlogRecord(long sourceTsMs, String file, long pos) {
        ClickHouseStruct record = new ClickHouseStruct();
        record.setTs_ms(sourceTsMs);
        record.setDebezium_ts_ms(sourceTsMs + 50);
        record.setFile(file);
        record.setPos(pos);
        return record;
    }

    private void assertStrictlyIncreasing(List<ClickHouseStruct> records) {
        for (int i = 1; i < records.size(); i++) {
            assertTrue(records.get(i - 1).getSequenceNumber() < records.get(i).getSequenceNumber(),
                    "version of record " + i + " must be higher than that of record " + (i - 1));
        }
    }

    @Test
    @DisplayName("a DELETE carrying the previous second after a counter reset still out-ranks its INSERT")
    public void lateDeleteAfterCounterResetOutranksInsert() {
        List<ClickHouseStruct> stream = Arrays.asList(
                binlogRecord(S - 5000, FILE, 50),   // leaves the post-start (500m) counter domain
                binlogRecord(S - 1000, FILE, 100),  // opens a counter window at S-1
                binlogRecord(S, FILE, 150),         // some other row, second S
                binlogRecord(S, FILE, 200),         // INSERT of the queue row, second S
                binlogRecord(S + 1000, FILE, 300),  // another transaction, second S+1: counter reset
                binlogRecord(S, FILE, 400));        // DELETE of the queue row: started in S, committed later
        DebeziumChangeEventCapture.addVersion(stream);

        assertStrictlyIncreasing(stream);
        assertTrue(stream.get(5).getSequenceNumber() > stream.get(3).getSequenceNumber(),
                "the DELETE must out-rank the INSERT of the same row, or ReplacingMergeTree keeps a ghost row");
    }

    @Test
    @DisplayName("binlog events replayed after the snapshot out-rank the snapshot rows")
    public void replayAfterSnapshotOutranksSnapshotRows() {
        long snapshotStart = S;
        long tableReadAt = S + 3_600_000L; // the table was read an hour after snapshot start
        ClickHouseStruct snapshotRow = new ClickHouseStruct();
        snapshotRow.setDebezium_ts_ms(tableReadAt); // snapshot records carry no source ts
        snapshotRow.setFile(FILE);
        snapshotRow.setPos(100L);                   // the snapshot's binlog start position
        DebeziumChangeEventCapture.addVersion(Arrays.asList(snapshotRow));

        // The stream then replays from snapshot start: a DELETE made 10 minutes after it.
        List<ClickHouseStruct> replay = Arrays.asList(
                binlogRecord(snapshotStart + 600_000L, FILE, 150));
        DebeziumChangeEventCapture.addVersion(replay);

        assertTrue(replay.get(0).getSequenceNumber() > snapshotRow.getSequenceNumber(),
                "a change made after snapshot start must out-rank the snapshot row it changes");
    }

    @Test
    @DisplayName("the clamp carries across binlog files and batches")
    public void monotonicAcrossFilesAndBatches() {
        List<ClickHouseStruct> first = Arrays.asList(
                binlogRecord(S - 1000, FILE, 100),
                binlogRecord(S + 1000, FILE, 900));
        DebeziumChangeEventCapture.addVersion(first);
        List<ClickHouseStruct> second = Arrays.asList(
                binlogRecord(S, "mysql-bin-changelog.124803", 4));
        DebeziumChangeEventCapture.addVersion(second);

        assertTrue(second.get(0).getSequenceNumber() > first.get(1).getSequenceNumber());
    }

    @Test
    @DisplayName("a re-delivery after an in-process rewind keeps its source timestamp (#1346)")
    public void rewindKeepsSourceTimestamp() {
        List<ClickHouseStruct> original = Arrays.asList(
                binlogRecord(S, FILE, 100),           // DELETE
                binlogRecord(S + 3000, FILE, 200));   // re-INSERT
        DebeziumChangeEventCapture.addVersion(original);
        long reinsert = original.get(1).getSequenceNumber();

        // Only the DELETE is re-delivered: its coordinates are before the highest seen.
        List<ClickHouseStruct> redelivered = Arrays.asList(binlogRecord(S, FILE, 100));
        DebeziumChangeEventCapture.addVersion(redelivered);

        assertTrue(redelivered.get(0).getSequenceNumber() < reinsert,
                "a re-delivered DELETE must not out-rank the later re-INSERT");
    }

    @Test
    @DisplayName("records without binlog coordinates keep the historical behaviour")
    public void noCoordinatesNoClamp() {
        ClickHouseStruct later = new ClickHouseStruct();
        later.setTs_ms(S + 5000);
        ClickHouseStruct earlier = new ClickHouseStruct();
        earlier.setTs_ms(S);
        DebeziumChangeEventCapture.addVersion(Arrays.asList(later, earlier));

        assertEquals(S * 1_000_000L + DebeziumChangeEventCapture.SEQUENCE_START_INITIAL + 2,
                earlier.getSequenceNumber());
    }

    @Test
    @DisplayName("binlog file index parsing")
    public void binlogFileIndex() {
        assertEquals(124802L, DebeziumChangeEventCapture.binlogFileIndex(FILE));
        assertEquals(-1L, DebeziumChangeEventCapture.binlogFileIndex(""));
        assertEquals(-1L, DebeziumChangeEventCapture.binlogFileIndex(null));
        assertEquals(-1L, DebeziumChangeEventCapture.binlogFileIndex("mysql-bin."));
        assertEquals(-1L, DebeziumChangeEventCapture.binlogFileIndex("wal.abc"));
    }
}

package com.altinity.clickhouse.debezium.embedded.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.SourcePosition;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pins the commit-order guarantee of the {@code _version} sequence.
 *
 * <p>On MySQL {@code source.ts_ms} is the timestamp of the STATEMENT that produced a
 * row event, not of its commit. A transaction that stays open while others commit
 * reaches the binlog after them, carrying an older timestamp. Before the fix the
 * version of such an event was {@code olderTs * 1e6 + counter}; when the newer
 * events in between had reset the counter, that ranked BELOW the earlier write of the
 * same key (same source second, higher counter) and ReplacingMergeTree kept the stale
 * row - the checksum job then reported the row as divergent from MySQL while the row
 * counts matched.</p>
 *
 * <p>The binlog position separates a first delivery (position above the run's
 * high-water mark - commit order applies) from a redelivery (position at or below it
 * - the redelivery-stable assignment of issue #1346 must be kept). Records that carry
 * no position keep the historical behaviour, which the pre-existing
 * {@link SourceTsVersionAnchorTest} pins.</p>
 */
public class CommitOrderVersionClampTest {

    private static final long TS = 1_757_900_000_000L; // fixed source second (ms)
    private static final long MULTIPLIER = 1_000_000L;

    @BeforeEach
    public void resetSequenceState() {
        DebeziumChangeEventCapture.sequenceNumber = DebeziumChangeEventCapture.SEQUENCE_START;
        DebeziumChangeEventCapture.sequenceAnchorTs = 0L;
        DebeziumChangeEventCapture.sequenceHighWaterPosition = null;
        DebeziumChangeEventCapture.sequenceHighWaterEffectiveTs = 0L;
        DebeziumChangeEventCapture.sequenceMaxSourceTs = 0L;
    }

    /** A streaming record at binlog position {@code (file, pos, row)} with source ts {@code sourceTsMs}. */
    private static ClickHouseStruct at(long sourceTsMs, String file, long pos, int row) {
        ClickHouseStruct record = new ClickHouseStruct();
        record.setTs_ms(sourceTsMs);
        record.setDebezium_ts_ms(sourceTsMs + 250); // processing time, never used when ts_ms > 0
        record.setFile(file);
        record.setPos(pos);
        record.setRow(row);
        return record;
    }

    private static ClickHouseStruct at(long sourceTsMs, long pos) {
        return at(sourceTsMs, "mysql-bin.000007", pos, 0);
    }

    private static long versionOf(ClickHouseStruct record) {
        DebeziumChangeEventCapture.addVersion(Arrays.asList(record));
        return record.getSequenceNumber();
    }

    @Test
    @DisplayName("a commit that reaches the binlog after newer-timestamped commits ranks above "
            + "the earlier write of the same key (the production sequence)")
    public void lateCommitWithOlderStatementTimestampRanksAboveEarlierWrite() {
        // Warm-up second: leaves the 500m post-start domain on the next >1s advance.
        versionOf(at(TS - 10_000, 100));

        // Second T: a batch of writes (filler rows) ending with the key's own write.
        // The first event of the second resets the counter to SEQUENCE_START, the
        // following 50 increment it: the key's write lands at SEQUENCE_START + 50.
        long earlyWrite = 0;
        for (int i = 0; i < 51; i++) {
            earlyWrite = versionOf(at(TS, 200 + i));
        }
        assertEquals(TS * MULTIPLIER + DebeziumChangeEventCapture.SEQUENCE_START + 50, earlyWrite,
                "sanity: the key's earlier write sits high in second T's counter range");

        // Second T+5: an unrelated commit advances the source clock and resets the counter.
        long unrelated = versionOf(at(TS + 5_000, 300));
        assertEquals((TS + 5_000) * MULTIPLIER + DebeziumChangeEventCapture.SEQUENCE_START, unrelated);

        // The long transaction commits now. Its UPDATE was EXECUTED in second T, so its
        // row event carries ts = T, but it is at a HIGHER binlog position than everything
        // above: it committed last, and it is the newest state of the key.
        long lateCommit = versionOf(at(TS, 400));

        assertTrue(lateCommit > earlyWrite,
                "the later commit must out-rank the earlier write of the same key; before the "
                        + "fix it was " + (TS * MULTIPLIER + DebeziumChangeEventCapture.SEQUENCE_START + 1)
                        + " < " + earlyWrite + " and ReplacingMergeTree kept the stale row");
        assertTrue(lateCommit > unrelated,
                "versions must stay strictly increasing in commit order");
        assertEquals((TS + 5_000) * MULTIPLIER + DebeziumChangeEventCapture.SEQUENCE_START + 1, lateCommit,
                "the timestamp component is floored at the newest first-delivery timestamp "
                        + "and the counter continues - the 2.8.0 formula is untouched");
    }

    /**
     * Spec 02.02 section 3.1.2. Debezium stamps every row event of a MySQL
     * transaction with the transaction's binlog position (the row index restarts
     * at 0 per statement), so the rows after a transaction's first row compare
     * EQUAL to the high-water mark. They must be floored like the first row:
     * before the fix only the first row of a late-committing transaction was
     * clamped and the rest kept their older statement time, so an INSERT ranked
     * above the UPDATE and DELETE of the same transaction and the deleted row
     * stayed live on the replica (found by the end-to-end history suite: INSERT +
     * UPDATE + DELETE of one key in one transaction).
     */
    @Test
    @DisplayName("every row of one transaction (one binlog position) is floored like its first row")
    public void rowsOfOneTransactionShareTheFirstRowsFloor() {
        versionOf(at(TS - 10_000, 100));
        long unrelated = versionOf(at(TS + 5_000, 300)); // a newer commit raises the floor
        // The long transaction commits now: its three row events were executed in
        // second T and all carry the transaction's position 400.
        long insert = versionOf(at(TS, 400));
        long update = versionOf(at(TS, 400));
        long delete = versionOf(at(TS, 400));

        assertTrue(insert > unrelated, "the transaction's first row is floored above the newer commit");
        assertTrue(update > insert, "the UPDATE of the same transaction must out-rank its INSERT; "
                + "before the fix it was versioned in second T, below the floored INSERT");
        assertTrue(delete > update, "the DELETE must out-rank the UPDATE");
        assertEquals(insert + 1, update, "same floored second, counter +1");
        assertEquals(insert + 2, delete, "same floored second, counter +2");
    }

    @Test
    @DisplayName("a transaction whose rows carry a NEWER statement time than the floor keeps it")
    public void rowsOfOneTransactionKeepANewerTimestamp() {
        versionOf(at(TS - 10_000, 100));
        versionOf(at(TS, 300));
        long first = versionOf(at(TS + 2_000, 400));   // newer than the floor: not clamped
        long second = versionOf(at(TS + 2_000, 400));  // same transaction, same position
        assertEquals((TS + 2_000) * MULTIPLIER + DebeziumChangeEventCapture.SEQUENCE_START, first);
        assertEquals(first + 1, second);
    }

    // DESTRUCTIVE: nothing is deleted here. This unit test only versions in-memory
    // records that model a DELETE event; no database, file or table is touched.
    @Test
    @DisplayName("a late DELETE from a long transaction still retires the row")
    public void lateDeleteRanksAboveEarlierWrite() {
        versionOf(at(TS - 10_000, 100));
        versionOf(at(TS, 200));               // other writes in second T ...
        versionOf(at(TS, 201));
        long insert = versionOf(at(TS, 202)); // ... then the key's INSERT (counter +2)
        versionOf(at(TS + 3_000, 300)); // counter reset by a newer commit
        long delete = versionOf(at(TS, 400)); // DELETE executed in second T, committed last

        assertTrue(delete > insert,
                "a tombstone that ranks below the INSERT resurrects the row on the replica");
    }

    @Test
    @DisplayName("a row without a position that resets the counter (a source without log coordinates, "
            + "versioned on its envelope timestamp) also raises the floor for the next late first delivery")
    public void unpositionedCounterResetRaisesTheFloor() {
        versionOf(at(TS - 10_000, 100));
        long earlyWrite = 0;
        for (int i = 0; i < 51; i++) {
            earlyWrite = versionOf(at(TS, 200 + i));
        }

        // A ROW without a log position (no source coordinates; envelope timestamp
        // only), newer than the source clock. It enters the sequence like any row,
        // moves the anchor and resets the counter exactly like a newer commit would.
        // Heartbeats and transaction metadata do NOT take this path any more: the
        // dispatch loop keeps control records out of the sequence entirely (spec
        // 02.02 section 3.2; DebeziumChangeEventCaptureTest pins that).
        ClickHouseStruct positionlessRow = new ClickHouseStruct();
        positionlessRow.setDebezium_ts_ms(TS + 5_000);
        DebeziumChangeEventCapture.addVersion(Arrays.asList(positionlessRow));
        assertEquals((TS + 5_000) * MULTIPLIER + DebeziumChangeEventCapture.SEQUENCE_START,
                positionlessRow.getSequenceNumber(), "sanity: the positionless row reset the counter");

        long lateCommit = versionOf(at(TS, 400));

        assertTrue(lateCommit > earlyWrite,
                "the counter reset caused by the positionless row must be accompanied by the floor: "
                        + "early=" + earlyWrite + " late=" + lateCommit);
        assertEquals((TS + 5_000) * MULTIPLIER + DebeziumChangeEventCapture.SEQUENCE_START + 1, lateCommit);
    }

    @Test
    @DisplayName("the floor applies within a single batch as well as across batches")
    public void floorAppliesWithinOneBatch() {
        List<ClickHouseStruct> batch = new ArrayList<>();
        batch.add(at(TS - 10_000, 100));
        for (int i = 0; i < 5; i++) {
            batch.add(at(TS, 200 + i));
        }
        batch.add(at(TS + 4_000, 300));
        batch.add(at(TS, 400)); // long transaction, committed last
        DebeziumChangeEventCapture.addVersion(batch);

        for (int i = 1; i < batch.size(); i++) {
            assertTrue(batch.get(i - 1).getSequenceNumber() < batch.get(i).getSequenceNumber(),
                    "record " + i + " must rank above record " + (i - 1) + " (commit order)");
        }
    }

    @Test
    @DisplayName("#1346 is preserved: a re-delivered DELETE (position at or below the high-water "
            + "mark) keeps its original version and cannot out-rank the later re-INSERT")
    public void redeliveryKeepsRedeliveryStableVersion() {
        long delete = versionOf(at(TS, 10));            // DELETE
        long reinsert = versionOf(at(TS + 3_000, 20));  // re-INSERT, later commit
        assertTrue(delete < reinsert, "sanity: in-order delivery ranks the re-INSERT higher");

        // Offset rewind inside the same process (engine retry): the DELETE is delivered
        // again at its ORIGINAL position, which is not above the high-water mark.
        long redeliveredDelete = versionOf(at(TS, 10));

        assertTrue(redeliveredDelete < reinsert,
                "a redelivery is not a new commit: it must keep ranking below the later "
                        + "re-INSERT, exactly as before this change");
        assertEquals(TS * MULTIPLIER + DebeziumChangeEventCapture.SEQUENCE_START + 1, redeliveredDelete,
                "the redelivered event keeps the source-timestamp anchored assignment: "
                        + "no floor, counter continues");
    }

    @Test
    @DisplayName("a redelivered older event does not raise the floor for later first deliveries")
    public void redeliveryDoesNotDisturbTheFloor() {
        versionOf(at(TS, 10));
        long newest = versionOf(at(TS + 3_000, 20));
        versionOf(at(TS, 10)); // redelivery
        long next = versionOf(at(TS + 3_100, 30)); // genuinely new commit
        assertTrue(next > newest);
        assertEquals((TS + 3_100) * MULTIPLIER + DebeziumChangeEventCapture.SEQUENCE_START + 2, next,
                "the floor moved to TS+3000 with the newest first delivery and the redelivery "
                        + "left it there");
    }

    @Test
    @DisplayName("a binary log rotation resets pos but is still a first delivery")
    public void rotationIsAFirstDelivery() {
        versionOf(at(TS - 10_000, "mysql-bin.000001", 900_000, 0));
        versionOf(at(TS, "mysql-bin.000001", 900_050, 0));
        versionOf(at(TS, "mysql-bin.000001", 900_060, 0));
        long early = versionOf(at(TS, "mysql-bin.000001", 900_100, 0)); // counter +2 in second T
        versionOf(at(TS + 5_000, "mysql-bin.000001", 900_200, 0));

        // First event of the new file: a LOWER pos in a HIGHER file number.
        long afterRotation = versionOf(at(TS, "mysql-bin.000002", 4, 0));

        assertTrue(afterRotation > early,
                "mysql-bin.000002:4 is beyond mysql-bin.000001:900200 - it is the newest "
                        + "commit and must rank above every earlier write");
    }

    @Test
    @DisplayName("rows of one multi-row event are ordered by their row index")
    public void rowsWithinOneEventAreFirstDeliveries() {
        versionOf(at(TS - 10_000, 100));
        for (int i = 0; i < 5; i++) {
            versionOf(at(TS, 200 + i));
        }
        long early = versionOf(at(TS, 205)); // counter +5 in second T
        versionOf(at(TS + 5_000, 300));

        long row0 = versionOf(at(TS, "mysql-bin.000007", 400, 0));
        long row1 = versionOf(at(TS, "mysql-bin.000007", 400, 1));
        long row2 = versionOf(at(TS, "mysql-bin.000007", 400, 2));

        assertTrue(early < row0 && row0 < row1 && row1 < row2,
                "every row of the late multi-row event out-ranks the earlier write, in row order");
    }

    @Test
    @DisplayName("the counter still resets to SEQUENCE_START when the floored clock advances by more than one second")
    public void counterResetFollowsTheEffectiveClock() {
        versionOf(at(TS - 10_000, 100));
        versionOf(at(TS, 200));
        versionOf(at(TS, 400)); // same second, still first delivery
        long later = versionOf(at(TS + 2_500, 500));
        assertEquals((TS + 2_500) * MULTIPLIER + DebeziumChangeEventCapture.SEQUENCE_START, later,
                "a >1s advance of the effective clock resets the counter exactly as 2.8.0 did");
    }

    @Test
    @DisplayName("records without a log position keep the historical assignment (no floor)")
    public void recordsWithoutPositionAreUnchanged() {
        ClickHouseStruct newer = new ClickHouseStruct();
        newer.setTs_ms(TS + 10_000);
        newer.setDebezium_ts_ms(TS + 10_020);
        ClickHouseStruct older = new ClickHouseStruct();
        older.setTs_ms(TS);
        older.setDebezium_ts_ms(TS + 10_030);
        DebeziumChangeEventCapture.addVersion(Arrays.asList(newer));
        DebeziumChangeEventCapture.addVersion(Arrays.asList(older));

        assertEquals(TS * MULTIPLIER + DebeziumChangeEventCapture.SEQUENCE_START_INITIAL + 2,
                older.getSequenceNumber(),
                "without a position nothing proves this is a first delivery, so the "
                        + "source-timestamp anchored assignment stays exactly as before");
    }

    @Test
    @DisplayName("a PostgreSQL LSN orders first deliveries the same way")
    public void postgresLsnIsAPosition() {
        ClickHouseStruct a = new ClickHouseStruct();
        a.setTs_ms(TS - 10_000);
        a.setLsn(1_000L);
        ClickHouseStruct early = new ClickHouseStruct();
        early.setTs_ms(TS);
        early.setLsn(2_000L);
        ClickHouseStruct newer = new ClickHouseStruct();
        newer.setTs_ms(TS + 5_000);
        newer.setLsn(3_000L);
        ClickHouseStruct late = new ClickHouseStruct();
        late.setTs_ms(TS);
        late.setLsn(4_000L);
        DebeziumChangeEventCapture.addVersion(Arrays.asList(a, early, newer, late));

        assertNotNull(late.getSourcePosition());
        assertTrue(late.getSequenceNumber() > early.getSequenceNumber());
        assertTrue(late.getSequenceNumber() > newer.getSequenceNumber());
    }

    @Test
    @DisplayName("the position read from a struct matches the position read from the source fields")
    public void structPositionMatchesFields() {
        ClickHouseStruct record = at(TS, "mysql-bin.000123", 4_567, 3);
        SourcePosition fromFields = SourcePosition.ofBinlog("mysql-bin.000123", 4_567L, 3);
        assertEquals(0, fromFields.compareTo(record.getSourcePosition()));
        assertEquals(fromFields, record.getSourcePosition());
    }

    /**
     * A binary log basename change (spec 01.02 section 3.1.1): the new log's
     * positions compare by string order of the prefix, and "binlog" sorts below
     * "mysql-bin", so before the fix every record of the new log ranked below the
     * high-water mark, was treated as a redelivery and was never clamped -- a late
     * commit in the new log was versioned in its own older second and lost to the
     * earlier write of the same key. The sequence must instead reset the mark to
     * the new log and treat its first record as a first delivery.
     */
    @Test
    @DisplayName("a positioned record of a differently named binary log is a first delivery and resets the high-water mark")
    public void binlogBasenameChangeResetsTheHighWaterMark() {
        versionOf(at(TS - 10_000, "mysql-bin.000009", 500, 0));
        long early = versionOf(at(TS, "mysql-bin.000009", 600, 0));
        long newest = versionOf(at(TS + 5_000, "mysql-bin.000009", 700, 0)); // floor = TS + 5000

        // The log was renamed; the first row of the new log is a late commit
        // (statement time TS) at the very start of the new file.
        long afterRename = versionOf(at(TS, "binlog.000001", 4, 0));
        assertTrue(afterRename > newest,
                "the first record of the renamed log is a first delivery: clamped to the floor and above "
                        + "every earlier write; before the fix it compared below the mark, was not clamped and "
                        + "ranked " + (TS * MULTIPLIER) + "-ish below early=" + early);
        assertEquals(SourcePosition.ofBinlog("binlog.000001", 4L, 0),
                DebeziumChangeEventCapture.sequenceHighWaterPosition,
                "the mark moved to the new log");

        // From here on the new log is the log: in-order records are first deliveries
        // and a rewound one is a redelivery again.
        long next = versionOf(at(TS, "binlog.000001", 5, 0));
        assertTrue(next > afterRename, "log order within the new log is honoured");
        long rewound = versionOf(at(TS, "binlog.000001", 4, 0));
        assertEquals(TS * MULTIPLIER + DebeziumChangeEventCapture.sequenceNumber, rewound,
                "a rewind inside the new log is a redelivery: not clamped, source-timestamp anchored");
    }
}

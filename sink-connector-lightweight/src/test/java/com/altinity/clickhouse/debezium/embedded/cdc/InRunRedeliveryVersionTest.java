package com.altinity.clickhouse.debezium.embedded.cdc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.altinity.clickhouse.sink.connector.model.SourcePosition;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The high-water position of the version sequence is a process-wide static that
 * no engine start resets (spec 02.02 section 2). Two failure modes follow from it
 * (spec 02.02 section 7 FM-02.02-6, spec 02.04 section 7 FM-02.04-2):
 *
 * <ul>
 *   <li>An engine recreated INSIDE the process -- the completion-callback retry
 *   after a transient failure (spec 10.04 section 3.5), REST {@code /restart},
 *   {@code start_replica} after {@code stop_replica}, the restart monitor --
 *   re-reads from the last committed offset. The redelivered records are at or
 *   below the old mark, so they are classified as redeliveries and keep their raw
 *   statement time. The rows among them that were handed off but never written
 *   (retired, spec 09.01 section 3.8) have no stored copy to lose to: a late
 *   commit among them is versioned below the earlier commit it follows.</li>
 *   <li>A source failover (or {@code RESET MASTER}) inside one engine run that
 *   keeps the log basename but restarts the file sequence lower: every event of
 *   the new primary compares below the mark and loses the commit-order floor.</li>
 * </ul>
 */
public class InRunRedeliveryVersionTest {

    private static final long TS = 1_757_900_000_000L;

    @BeforeEach
    public void resetSequenceState() {
        DebeziumChangeEventCapture.sequenceNumber = DebeziumChangeEventCapture.SEQUENCE_START;
        DebeziumChangeEventCapture.sequenceAnchorTs = 0L;
        DebeziumChangeEventCapture.sequenceHighWaterPosition = null;
        DebeziumChangeEventCapture.sequenceHighWaterEffectiveTs = 0L;
        DebeziumChangeEventCapture.sequenceMaxSourceTs = 0L;
    }

    private static long version(long ts, String file, long pos) {
        return DebeziumChangeEventCapture.nextSequenceNumber(ts, SourcePosition.ofBinlog(file, pos, 0));
    }

    @Test
    @Disabled("DEFECT FM-02.04-2: an engine recreated in the same JVM keeps the high-water position, "
            + "so a redelivered late commit that was never written is versioned below the commit it follows")
    @DisplayName("after an in-process engine restart, a redelivered late-commit pair keeps commit order")
    public void redeliveredLateCommitPairKeepsCommitOrderAfterAnInProcessEngineRestart() {
        version(TS - 10_000, "mysql-bin.000007", 50);
        // First delivery: T2 (statement time 110) then the late commit T1
        // (statement time 100, committed after T2). T1 is clamped above T2.
        long t2 = version(TS + 110, "mysql-bin.000007", 100);
        long t1 = version(TS + 100, "mysql-bin.000007", 200);
        assertTrue(t1 > t2, "first delivery keeps commit order (spec 02.02 section 3.1)");
        // The engine read one more event before the failure: the mark is past both.
        version(TS + 120, "mysql-bin.000007", 300);

        // The source connection drops before either unit is written; the stopped
        // engine's units are retired (spec 09.01 section 3.8) and the completion
        // callback recreates the engine in this JVM. The statics are NOT reset --
        // that is the production path. Debezium redelivers both from the last
        // committed offset.
        long t2Again = version(TS + 110, "mysql-bin.000007", 100);
        long t1Again = version(TS + 100, "mysql-bin.000007", 200);

        assertTrue(t1Again > t2Again,
                "neither copy was ever written, so these are the only rows ClickHouse will hold; the "
                        + "late commit T1 is the newer state but was versioned " + t1Again + " < " + t2Again
                        + " because both were classified as redeliveries and kept their raw statement time");
    }

    @Test
    @Disabled("DEFECT FM-02.02-6: a failover to a primary whose log has the same basename and a lower "
            + "file sequence is classified as redelivery, so the commit-order floor stops applying")
    @DisplayName("an in-run failover to a lower-numbered log of the same name is a first delivery")
    public void failoverToALowerNumberedLogOfTheSameNameIsAFirstDelivery() {
        version(TS - 10_000, "mysql-bin.000500", 100);
        // The old primary's last write of key k.
        long lastOnOldPrimary = version(TS, "mysql-bin.000500", 900);

        // GTID-mode failover: Debezium's binlog client reconnects to the new primary
        // inside the same engine run. Its log is also called mysql-bin but is at
        // sequence 20; its first transaction rewrites k with a statement time 5 ms
        // behind (clock skew between the primaries, or a late commit).
        long firstOnNewPrimary = version(TS - 5, "mysql-bin.000020", 4);

        assertTrue(firstOnNewPrimary > lastOnOldPrimary,
                "the new primary's write is the newer state of k but was versioned " + firstOnNewPrimary
                        + " < " + lastOnOldPrimary + ": mysql-bin.000020 compares below the mark "
                        + "mysql-bin.000500, so the record was taken for a redelivery and not clamped");
    }
}

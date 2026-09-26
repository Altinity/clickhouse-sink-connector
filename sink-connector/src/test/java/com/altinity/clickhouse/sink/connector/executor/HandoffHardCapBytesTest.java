package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.executor.OffsetTestSupport.RecordingCommitter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hard cap on the reader's lead over the writers, in ESTIMATED BYTES
 * (spec 01.05 §3.4 item 7).
 *
 * <p><b>The defect.</b> The cap that stops the reader from filling the heap
 * was counted in rows. A row count means something different for every table
 * width: 500,000 rows of a narrow table is a few gigabytes, 500,000 rows of a
 * table with megabyte BLOBs is far more than any heap -- the JVM is lost to
 * garbage collection long before the row cap is met. The heap is measured in
 * bytes, so the cap must be too.</p>
 *
 * <p><b>The rule.</b> Every handed-off unit carries an estimate of its
 * retained bytes, the outstanding total follows handoff and acknowledgement
 * exactly as the row count does, and the producer pauses when EITHER the row
 * cap or the byte cap is met; either may be disabled with {@code 0}.</p>
 *
 * <p>The synthetic rows here carry no envelope, so each costs exactly
 * {@link RecordSizeEstimator#PER_RECORD_OVERHEAD_BYTES}; that makes the byte
 * arithmetic exact.</p>
 */
public class HandoffHardCapBytesTest {

    private static final long ROW = RecordSizeEstimator.PER_RECORD_OVERHEAD_BYTES;

    @BeforeEach
    public void quiescent() {
        DebeziumOffsetManagement.reset();
    }

    @AfterEach
    public void cleanup() {
        DebeziumOffsetManagement.reset();
    }

    private static List<ClickHouseStruct> handOff(RecordingCommitter committer, int id, int rows) {
        String[] names = new String[rows];
        for (int i = 0; i < rows; i++) {
            names[i] = "u" + id + "r" + i;
        }
        List<ClickHouseStruct> unit = OffsetTestSupport.unit(committer, 1_000L + id, names);
        DebeziumOffsetManagement.registerHandoff(unit, Collections.singletonList(unit));
        return unit;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static Thread after(long delayMs, ThrowingRunnable body, AtomicReference<Throwable> failure) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delayMs);
                body.run();
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "hard-cap-bytes-test-writer");
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Test
    @DisplayName("the outstanding byte count is added at handoff, stamped on every row, and released only at acknowledgement")
    public void byteCountFollowsHandoffAndAcknowledgement() throws InterruptedException {
        RecordingCommitter committer = new RecordingCommitter();
        assertEquals(0L, DebeziumOffsetManagement.outstandingByteCount());

        List<ClickHouseStruct> first = handOff(committer, 0, 3);
        List<ClickHouseStruct> second = handOff(committer, 1, 2);
        assertEquals(5 * ROW, DebeziumOffsetManagement.outstandingByteCount(), "two units, five rows");
        for (ClickHouseStruct r : first) {
            assertEquals(ROW, r.getEstimatedBytes(), "every row carries its share");
        }

        // The second unit is written first: parked, still counted.
        DebeziumOffsetManagement.checkIfBatchCanBeCommitted(second);
        assertEquals(5 * ROW, DebeziumOffsetManagement.outstandingByteCount(), "a parked unit is still on the heap");

        DebeziumOffsetManagement.checkIfBatchCanBeCommitted(first);
        assertEquals(0L, DebeziumOffsetManagement.outstandingByteCount(), "head acknowledged, parked unit drained behind it");
    }

    @Test
    @DisplayName("at the byte cap the producer waits until the head is acknowledged, with the row cap disabled")
    public void atTheByteCapWaitsUntilTheHeadIsAcknowledged() throws InterruptedException {
        RecordingCommitter committer = new RecordingCommitter();
        List<ClickHouseStruct> head = handOff(committer, 0, 2);
        handOff(committer, 1, 2);
        assertEquals(4 * ROW, DebeziumOffsetManagement.outstandingByteCount());

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = after(300, () -> DebeziumOffsetManagement.checkIfBatchCanBeCommitted(head), failure);

        long start = System.nanoTime();
        DebeziumOffsetManagement.awaitHandoffCapacity(0L, 4 * ROW, 10_000, null);
        long waitedMs = (System.nanoTime() - start) / 1_000_000L;
        writer.join(10_000);
        assertFalse(writer.isAlive());
        assertTrue(failure.get() == null, "the acknowledging thread failed: " + failure.get());

        assertTrue(waitedMs >= 200, "held until the acknowledgement (" + waitedMs + " ms)");
        assertTrue(waitedMs < 5_000, "released promptly after it (" + waitedMs + " ms)");
        assertEquals(2 * ROW, DebeziumOffsetManagement.outstandingByteCount());
    }

    @Test
    @DisplayName("under the byte cap the producer is not delayed, whatever the row count; 0 disables the byte cap")
    public void underTheByteCapReturnsAtOnce() throws InterruptedException {
        RecordingCommitter committer = new RecordingCommitter();
        handOff(committer, 0, 3);

        long start = System.nanoTime();
        DebeziumOffsetManagement.awaitHandoffCapacity(0L, 4 * ROW, 10_000, null);
        DebeziumOffsetManagement.awaitHandoffCapacity(0L, 0L, 10_000, null);
        DebeziumOffsetManagement.awaitHandoffCapacity(1_000L, 0L, 10_000, null);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMs < 500, "no wait under either cap or with both disabled (" + elapsedMs + " ms)");
    }

    @Test
    @DisplayName("either cap alone pauses the producer: rows met with bytes disabled, bytes met with rows disabled")
    public void eitherCapPauses() {
        RecordingCommitter committer = new RecordingCommitter();
        handOff(committer, 0, 3);
        // Row cap met (3 >= 3), byte cap far away: pauses, times out loudly.
        IllegalStateException byRows = assertThrows(IllegalStateException.class,
                () -> DebeziumOffsetManagement.awaitHandoffCapacity(3L, 1L << 40, 200, null));
        assertTrue(byRows.getMessage().contains("3 row(s) in 1 unit(s)"), byRows.getMessage());
        // Byte cap met (3 x ROW >= 2 x ROW), row cap far away: pauses too.
        IllegalStateException byBytes = assertThrows(IllegalStateException.class,
                () -> DebeziumOffsetManagement.awaitHandoffCapacity(1_000_000L, 2 * ROW, 200, null));
        assertTrue(byBytes.getMessage().contains("sink.connector.handoff.max.outstanding.bytes"), byBytes.getMessage());
    }

    @Test
    @DisplayName("reset() zeroes the byte count with the rest of the FIFO")
    public void resetZeroesTheBytes() {
        RecordingCommitter committer = new RecordingCommitter();
        handOff(committer, 0, 3);
        assertEquals(3 * ROW, DebeziumOffsetManagement.outstandingByteCount());
        DebeziumOffsetManagement.reset();
        assertEquals(0L, DebeziumOffsetManagement.outstandingByteCount());
    }
}

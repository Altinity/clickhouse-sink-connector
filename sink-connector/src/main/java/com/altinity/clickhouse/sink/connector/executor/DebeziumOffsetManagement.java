package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decides WHEN a batch that has been written to ClickHouse may have its
 * Debezium offset acknowledged (spec 09.01).
 *
 * <p>Worker threads finish batches in arbitrary wall-clock order, and the
 * offset store keeps the LAST offset staged per partition, so the
 * acknowledgement order alone decides whether the durable binlog position can
 * run ahead of rows that are still queued or in flight. This class fixes that
 * order to the <b>handoff sequence</b>: a monotone counter assigned on the
 * Debezium thread when a batch is handed to the writers, i.e. binlog order. A
 * batch is acknowledged only when every lower sequence has been acknowledged;
 * a batch written out of turn is parked, never re-executed.</p>
 *
 * <p>The previous rule compared envelope-timestamp ranges among the batches a
 * worker had already picked up. Under per-table hash routing one Debezium batch
 * becomes one group per table on different workers' queues; a group still
 * queued on a busy worker was invisible to that rule, so an idle worker's group
 * -- carrying the Debezium batch's terminal marker -- was acknowledged and the
 * offset was committed past the queued rows. Equal timestamps (strict
 * {@code >}) did not block either. Both are fixed by ordering on the handoff
 * sequence; see {@code Replication.OffsetFifo} for the machine-checked model.</p>
 */
public class DebeziumOffsetManagement {

    /**
     * Logger for the DebeziumOffsetManagement class.
     */
    private static final Logger log = LogManager.getLogger(
            DebeziumOffsetManagement.class);

    /**
     * Identity key for a tracked batch.
     * <p>
     * The group map MUST be keyed by the batch's object identity, not by its
     * {@code (minTs, maxTs)} timestamp range. Two distinct batches routinely
     * share a timestamp range — a single multi-row statement split across
     * batches, or two batches whose rows all fall in the same millisecond — and
     * keying by the range made them collide: {@code put} silently overwrote the
     * earlier batch's entry and {@code remove} deleted the wrong one, so a batch
     * that was still unwritten stopped blocking the offset commit. Keying by
     * identity makes every batch a distinct entry regardless of its timestamps.
     * {@code equals}/{@code hashCode} are by reference so this stays correct on
     * a {@link ConcurrentHashMap} (whose default keying would otherwise fall
     * back to {@code List} content equality).
     */
    static final class BatchKey {
        final List<ClickHouseStruct> batch;

        BatchKey(List<ClickHouseStruct> batch) {
            this.batch = batch;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(batch);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof BatchKey && ((BatchKey) o).batch == this.batch;
        }
    }

    /**
     * One list handed to the asynchronous writers by the Debezium thread (one
     * {@code appendToRecords} call): the unit of acknowledgement.
     * <p>
     * {@code records} is the handed-off list in binlog order, with the terminal
     * marker on its last row. In routing mode the unit is split into per-table
     * groups that go to different workers; {@code remainingGroups} counts the
     * groups not yet written. The unit is acknowledged as a whole, in
     * {@code records} order, so the offsets it stages are monotone even though
     * its groups interleave in the binlog (spec 09.01 §3.3).
     * </p>
     */
    static final class HandoffUnit {
        final long sequence;
        final List<ClickHouseStruct> records;
        /** Estimated retained bytes of the unit's rows (spec 01.05 §3.4 item 7), fixed at handoff. */
        final long bytes;
        int remainingGroups;

        HandoffUnit(long sequence, List<ClickHouseStruct> records, int groups, long bytes) {
            this.sequence = sequence;
            this.records = records;
            this.remainingGroups = groups;
            this.bytes = bytes;
        }
    }

    /**
     * The next handoff sequence to assign. Monotone for the life of the JVM;
     * assigned only on the producer (Debezium) thread, in binlog order.
     */
    private static final AtomicLong handoffCounter = new AtomicLong();

    /**
     * Rows handed to the writers and not yet acknowledged: the sum of
     * {@code unit.size()} over {@link #outstandingSequences}. Every one of
     * them is held on the heap (queued, in flight, or written-but-parked)
     * until its unit is acknowledged, so this -- not the unit count, and not
     * the per-queue capacity, which is counted in batches of any size -- is
     * what bounds the reader's memory footprint (spec 01.05 §3.4). Added under
     * the class monitor at handoff, subtracted at acknowledgement, zeroed by
     * {@link #reset()}.
     */
    private static final AtomicLong outstandingRecords = new AtomicLong();

    /**
     * Estimated retained bytes of the rows handed off and not yet acknowledged:
     * the sum of {@link HandoffUnit#bytes} over {@link #outstandingSequences}
     * (spec 01.05 §3.4 item 7). The row count above is blind to row width; on
     * a fixed heap this is the number that matters. Added at handoff,
     * subtracted at acknowledgement, zeroed by {@link #reset()}.
     */
    private static final AtomicLong outstandingBytes = new AtomicLong();

    /**
     * How long {@link #awaitHandoffCapacity} sleeps between re-reads of the
     * outstanding count. An acknowledgement or a reset wakes it early.
     */
    static final long CAPACITY_WAIT_SLICE_MS = 50;

    /**
     * A pause that begins within this many milliseconds of the previous
     * release continues the same PACING PERIOD and is counted, not logged
     * (spec 01.05 §3.4 item 6). At the cap the reader oscillates by
     * construction -- one unit acknowledged releases it, the next handoff
     * meets the cap again -- so "one WARN per wait" was one WARN per batch:
     * measured on the first deployment, ~300 {@code Handoff hard cap} lines a
     * minute and 990 WARNs into the error log in seven minutes, while the cap
     * was doing exactly its job.
     */
    static final long CAPACITY_PACING_REARM_MS = 60_000;

    /**
     * While a pacing period lasts, one summary INFO per this many
     * milliseconds -- the pauses and the time paused since the previous line
     * and since the period began, and what is outstanding now.
     */
    static final long CAPACITY_PACING_SUMMARY_MS = 60_000;

    /**
     * Monotone nanosecond clock behind the pacing bookkeeping. Tests
     * substitute a controllable one; the waits themselves and the wait limit
     * stay on {@link System#nanoTime()}.
     */
    static volatile java.util.function.LongSupplier capacityClock = System::nanoTime;

    // Pacing-period bookkeeping (spec 01.05 §3.4 item 6). Written under the
    // class monitor by the producer thread; closed and cleared by reset().
    private static long pacingStartNanos;   // 0 while the reader is not paced
    private static long pacingCap;
    private static long pacingCapBytes;
    private static long lastReleaseNanos;
    private static long pacingPauses;
    private static long pacingPausedNanos;
    private static long lastSummaryNanos;
    private static long summaryPauses;
    private static long summaryPausedNanos;
    // The outstanding rows, units and bytes at the moment of the LAST release:
    // what the "pacing ended" line reports. The period is closed lazily -- by
    // the first acknowledgement, or the first wait, more than
    // CAPACITY_PACING_REARM_MS after that release -- and by then the live
    // counters describe what closed it (at a wait: the NEXT crossing, at or
    // above the cap), not the quiet gap that ended the period.
    private static long lastReleaseRecords;
    private static long lastReleaseUnits;
    private static long lastReleaseBytes;
    // True from beginPause() to endPause(). An acknowledgement that lands
    // during a wait longer than the re-arm window is the period continuing
    // (the head unit was slow), not ending, and must not close it.
    private static boolean pauseInProgress;

    /**
     * Offsets acknowledged to Debezium ({@code markBatchFinished()} returned)
     * since the JVM started, on every path -- written units and control
     * records alike. Monotone; never reset, not even by {@link #reset()}: it
     * is the engine's proof of PROGRESS, and the engine's completion callback
     * compares two readings of it to tell a recovery from a restart loop
     * (spec 10.04 section 3.5).
     */
    private static final AtomicLong acknowledgements = new AtomicLong();

    /**
     * Number of offsets acknowledged to Debezium since the JVM started. A
     * reading that differs from an earlier one means at least one offset was
     * committed in between: the pipeline made progress.
     */
    public static long acknowledgements() {
        return acknowledgements.get();
    }

    /**
     * Sequences handed to the writers and not yet acknowledged, ordered.
     * {@code first()} is the head: the oldest batch whose offset is still
     * unstaged. A sequence is added at handoff -- before the batch is visible
     * to any consumer -- and removed only when its unit is acknowledged, so
     * {@link #hasUnwrittenBatches()} is conservative in exactly one direction:
     * it can withhold a control-record commit, never permit an unsafe one.
     */
    static final ConcurrentSkipListSet<Long> outstandingSequences =
            new ConcurrentSkipListSet<>();

    /**
     * Every group (routed per-table list, or the legacy batch itself) that is
     * not yet written, keyed by identity, mapped to its unit.
     */
    static final ConcurrentHashMap<BatchKey, HandoffUnit> groupToUnit =
            new ConcurrentHashMap<>();

    /**
     * Units whose groups are ALL written, parked until every lower sequence is
     * acknowledged. Keyed by sequence so the drain can match the head of
     * {@link #outstandingSequences} directly.
     */
    static final ConcurrentSkipListMap<Long, HandoffUnit> completedUnits =
            new ConcurrentSkipListMap<>();

    /**
     * Shared lock that serializes every offset commit driven by the connector
     * worker threads, guaranteeing a single connector-side flush at a time so
     * that concurrent worker threads never issue overlapping
     * {@code markBatchFinished()} calls against the same OffsetStorageWriter.
     */
    private static final Object OFFSET_COMMIT_LOCK = new Object();

    /**
     * Every handoff sequence below this value is RETIRED (spec 09.01 §3.8
     * item 5): it was assigned by an engine that has since stopped, whose
     * offset store closed with it, so nothing in that unit can ever be
     * acknowledged -- its committer would throw from the closed store. Set
     * by {@link #reset()} to the counter's current value, so a sequence
     * assigned after the reset is never retired by it. Monotone.
     */
    private static volatile long retiredBelowSequence = 0L;

    /**
     * Registers one handed-off unit and assigns its handoff sequence.
     * <p>
     * Call on the producer thread BEFORE any of the unit's groups becomes
     * visible to a consumer: a consumer can then never finish a group before
     * its unit exists, and the unit reads as unwritten from this instant --
     * including the window between a worker's {@code poll()} and its write.
     * </p>
     *
     * @param unit   the handed-off list, in binlog order, terminal marker on
     *               its last row.
     * @param groups the independently-written parts of {@code unit}: the
     *               per-table routed lists, or {@code [unit]} in legacy mode.
     * @return the sequence assigned to the unit.
     */
    public static synchronized long registerHandoff(List<ClickHouseStruct> unit,
                                                    List<List<ClickHouseStruct>> groups) {
        if (unit == null || unit.isEmpty()) {
            throw new IllegalArgumentException("an empty batch cannot be handed off");
        }
        if (groups == null || groups.isEmpty()) {
            throw new IllegalArgumentException("a handed-off batch must have at least one group");
        }
        long sequence = handoffCounter.getAndIncrement();
        // Bytes are estimated per group -- the rows of one table in one batch
        // are alike in width -- and every row is stamped with its share, which
        // the INSERT chunker reads later (spec 01.05 §3.4 item 7).
        long bytes = 0L;
        for (List<ClickHouseStruct> group : groups) {
            bytes += RecordSizeEstimator.estimateGroup(group);
        }
        HandoffUnit handoffUnit = new HandoffUnit(sequence, unit, groups.size(), bytes);
        // Stamp the sequence on the rows themselves: after an in-process engine
        // restart the unit is gone from the maps below, and the stamp is what
        // lets the worker that finally writes the group recognise a RETIRED
        // unit instead of a producer bug (spec 09.01 §3.8 item 5).
        for (ClickHouseStruct record : unit) {
            if (record != null) {
                record.setHandoffSequence(sequence);
            }
        }
        for (List<ClickHouseStruct> group : groups) {
            if (groupToUnit.putIfAbsent(new BatchKey(group), handoffUnit) != null) {
                throw new IllegalStateException(
                        "batch handed off twice; a batch must be registered exactly once");
            }
        }
        outstandingSequences.add(sequence);
        outstandingRecords.addAndGet(unit.size());
        outstandingBytes.addAndGet(bytes);
        noteBacklog();
        return sequence;
    }

    /**
     * Number of rows handed to the writers and not yet acknowledged (queued,
     * in flight, or parked): the heap the reader's lead over the writers is
     * costing right now.
     *
     * @return the sum of the outstanding units' sizes.
     */
    public static long outstandingRecordCount() {
        return outstandingRecords.get();
    }

    /**
     * Estimated retained bytes of the rows handed to the writers and not yet
     * acknowledged (spec 01.05 §3.4 item 7).
     *
     * @return the sum of the outstanding units' byte estimates.
     */
    public static long outstandingByteCount() {
        return outstandingBytes.get();
    }

    /**
     * Pauses the producer (Debezium) thread while the rows handed off and not
     * yet acknowledged are at or above {@code maxOutstandingRecords} -- the
     * hard cap on the reader's lead over the writers (spec 01.05 §3.4).
     * <p>
     * Call on the producer thread BEFORE {@link #registerHandoff} of the next
     * unit. The unit being handed off is never split, so the count may exceed
     * the cap by at most one unit. The per-queue capacity
     * ({@code sink.connector.max.queue.size}) is counted in batches of any
     * size, so it bounds nothing in bytes; without this cap a reader that
     * outran stalled writers handed off rows until the heap was full, and the
     * JVM then spent the rest of its life in back-to-back full garbage
     * collections -- a stall with no error line, which the source ended by
     * aborting the binlog dump the reader had stopped draining. Blocking here
     * instead lets Debezium's own bounded queue apply the backpressure to the
     * binlog client, and the heap stays bounded.
     * </p>
     * <p>
     * The wait is loud, paced and bounded. ONE WARN is logged when a PACING
     * PERIOD begins -- a wait starting more than
     * {@link #CAPACITY_PACING_REARM_MS} after the previous release, or with no
     * previous release -- naming the counts, and ONE INFO when that first wait
     * ends, naming how long it lasted. Every later wait that begins within the
     * re-arm window continues the period and is counted, not logged; ONE INFO
     * summary per {@link #CAPACITY_PACING_SUMMARY_MS} while the period lasts;
     * ONE INFO "pacing ended" once the reader has stayed under the cap for
     * longer than the re-arm window -- written by the first acknowledgement
     * after that gap or by the next wait, whichever comes first, and naming
     * the rows and units outstanding at the LAST release -- or on
     * {@link #reset()}; nothing per slice. (At the cap the reader
     * oscillates one unit at a time, so a line per wait was a line per batch.)
     * Every {@link #CAPACITY_WAIT_SLICE_MS} the {@code livenessCheck}
     * runs (the caller passes its dead-worker check, spec 03.01 §3.3: a
     * dead worker can never acknowledge, so waiting on it would be the very
     * stall this method exists to prevent), and after {@code timeoutMs} the
     * wait ends in an {@link IllegalStateException}: writers that have not
     * acknowledged the head of the FIFO in that long are stalled, not slow,
     * and the engine must stop loudly (spec 10.04) rather than hold the
     * source connection open on a reader that will never read again.
     * </p>
     *
     * @param maxOutstandingRecords the cap, in rows; {@code <= 0} disables the
     *                              wait entirely.
     * @param timeoutMs             the longest a single wait may last.
     * @param livenessCheck         run between slices; a throw ends the wait
     *                              with that exception. May be null.
     * @throws InterruptedException  if the producer is interrupted while
     *                               waiting (the engine is stopping).
     * @throws IllegalStateException if the cap is still met after
     *                               {@code timeoutMs}.
     */
    public static void awaitHandoffCapacity(long maxOutstandingRecords, long timeoutMs,
                                            Runnable livenessCheck) throws InterruptedException {
        awaitHandoffCapacity(maxOutstandingRecords, 0L, timeoutMs, livenessCheck);
    }

    /**
     * As {@link #awaitHandoffCapacity(long, long, Runnable)}, with the cap
     * also expressed in ESTIMATED BYTES (spec 01.05 §3.4 item 7): the producer
     * pauses while the outstanding rows are at or above {@code
     * maxOutstandingRecords} OR the outstanding estimated bytes are at or
     * above {@code maxOutstandingBytes}. Either bound may be {@code <= 0} to
     * disable that dimension; both disabled means no wait at all.
     *
     * @param maxOutstandingRecords the cap in rows; {@code <= 0} disables it.
     * @param maxOutstandingBytes   the cap in estimated bytes; {@code <= 0}
     *                              disables it.
     * @param timeoutMs             the longest a single wait may last.
     * @param livenessCheck         run between slices; a throw ends the wait
     *                              with that exception. May be null.
     * @throws InterruptedException  if the producer is interrupted while
     *                               waiting (the engine is stopping).
     * @throws IllegalStateException if a cap is still met after
     *                               {@code timeoutMs}.
     */
    public static void awaitHandoffCapacity(long maxOutstandingRecords, long maxOutstandingBytes,
                                            long timeoutMs, Runnable livenessCheck) throws InterruptedException {
        if ((maxOutstandingRecords <= 0 && maxOutstandingBytes <= 0)
                || !isAtCapacity(maxOutstandingRecords, maxOutstandingBytes)) {
            return;
        }
        long startNanos = System.nanoTime();
        boolean firstPauseOfPeriod = beginPause(maxOutstandingRecords, maxOutstandingBytes, timeoutMs);
        while (true) {
            synchronized (DebeziumOffsetManagement.class) {
                if (!isAtCapacity(maxOutstandingRecords, maxOutstandingBytes)) {
                    break;
                }
                DebeziumOffsetManagement.class.wait(CAPACITY_WAIT_SLICE_MS);
                if (!isAtCapacity(maxOutstandingRecords, maxOutstandingBytes)) {
                    break;
                }
            }
            if (livenessCheck != null) {
                livenessCheck.run();
            }
            long waitedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            if (waitedMs >= timeoutMs) {
                throw new IllegalStateException(String.format(
                        "Handoff hard cap: %d row(s) in %d unit(s), ~%d MiB estimated, are still "
                                + "unacknowledged after %d ms at or above the cap of %d row(s) / %d MiB "
                                + "(sink.connector.handoff.max.outstanding.records / "
                                + "sink.connector.handoff.max.outstanding.bytes; the wait limit is "
                                + "sink.connector.handoff.wait.timeout.ms). The writers have not "
                                + "acknowledged the head of the FIFO in that long: they are stalled, "
                                + "not slow. Stopping the engine rather than holding the source "
                                + "connection on a reader that cannot make progress.",
                        outstandingRecords.get(), outstandingSequences.size(), mib(outstandingBytes.get()),
                        waitedMs, maxOutstandingRecords, mib(maxOutstandingBytes)));
            }
        }
        endPause(maxOutstandingRecords, maxOutstandingBytes, firstPauseOfPeriod, System.nanoTime() - startNanos);
    }

    /** Bytes as whole MiB, for log lines. */
    private static long mib(long bytes) {
        return bytes >> 20;
    }

    /**
     * Opens or continues a pacing period for a wait that is about to begin,
     * and logs accordingly (spec 01.05 §3.4 item 6): the first wait of a
     * period is the WARN; a wait beginning within
     * {@link #CAPACITY_PACING_REARM_MS} of the previous release is counted
     * silently; a wait after a longer quiet gap first closes the old period
     * with its "pacing ended" line, then opens a new one.
     *
     * @return true when this wait opened a period (its release is logged too).
     */
    private static synchronized boolean beginPause(long cap, long capBytes, long timeoutMs) {
        long now = capacityClock.getAsLong();
        pauseInProgress = true;
        if (pacingStartNanos != 0 && now - lastReleaseNanos <= CAPACITY_PACING_REARM_MS * 1_000_000L) {
            return false;
        }
        if (pacingStartNanos != 0) {
            closePacingPeriod("the reader stayed under the cap for more than "
                    + (CAPACITY_PACING_REARM_MS / 1000) + " s");
        }
        pacingStartNanos = now;
        pacingCap = cap;
        pacingCapBytes = capBytes;
        lastReleaseNanos = now;
        lastSummaryNanos = now;
        pacingPauses = 0;
        pacingPausedNanos = 0;
        summaryPauses = 0;
        summaryPausedNanos = 0;
        log.warn("Handoff hard cap: {} row(s) in {} unit(s) handed off and not yet acknowledged "
                + "(~{} MiB estimated), at or above the cap of {} row(s) / {} MiB "
                + "(sink.connector.handoff.max.outstanding.records / .bytes). "
                + "Pausing the reader until the writers acknowledge the head of the FIFO; nothing "
                + "has failed. The next line about this wait is the one reporting it released, or "
                + "the failure if it exceeds {} ms. Further pauses that begin within {} s of a "
                + "release are counted, not logged: expect one 'Handoff hard cap pacing:' summary "
                + "per {} s while the reader stays paced, and one 'pacing ended' line when it stops.",
                outstandingRecords.get(), outstandingSequences.size(), mib(outstandingBytes.get()),
                cap, mib(capBytes), timeoutMs,
                CAPACITY_PACING_REARM_MS / 1000, CAPACITY_PACING_SUMMARY_MS / 1000);
        return true;
    }

    /**
     * Records the end of a wait: the first wait of a period gets its release
     * INFO; a later one is counted, and the summary INFO is emitted once per
     * {@link #CAPACITY_PACING_SUMMARY_MS}. A period already closed by
     * {@link #reset()} while this wait was in progress is not reopened.
     */
    private static synchronized void endPause(long cap, long capBytes, boolean firstPauseOfPeriod,
                                              long pausedNanos) {
        long now = capacityClock.getAsLong();
        pauseInProgress = false;
        if (firstPauseOfPeriod) {
            log.info("Handoff hard cap released after {} ms: {} row(s) in {} unit(s) outstanding "
                    + "(~{} MiB estimated), under the cap of {} row(s) / {} MiB.", pausedNanos / 1_000_000L,
                    outstandingRecords.get(), outstandingSequences.size(), mib(outstandingBytes.get()),
                    cap, mib(capBytes));
        }
        if (pacingStartNanos == 0) {
            return;
        }
        lastReleaseNanos = now;
        lastReleaseRecords = outstandingRecords.get();
        lastReleaseUnits = outstandingSequences.size();
        lastReleaseBytes = outstandingBytes.get();
        pacingPauses++;
        pacingPausedNanos += pausedNanos;
        if (firstPauseOfPeriod) {
            return;
        }
        summaryPauses++;
        summaryPausedNanos += pausedNanos;
        if (now - lastSummaryNanos >= CAPACITY_PACING_SUMMARY_MS * 1_000_000L) {
            log.info("Handoff hard cap pacing: {} pause(s) totalling {} ms since the previous line; "
                    + "{} pause(s), {} ms paused since pacing began {} s ago; {} row(s) in {} unit(s) "
                    + "outstanding (~{} MiB estimated), cap {} row(s) / {} MiB. The reader is being "
                    + "paced by the writers; nothing has failed.",
                    summaryPauses, summaryPausedNanos / 1_000_000L, pacingPauses,
                    pacingPausedNanos / 1_000_000L, (now - pacingStartNanos) / 1_000_000_000L,
                    outstandingRecords.get(), outstandingSequences.size(), mib(outstandingBytes.get()),
                    cap, mib(capBytes));
            lastSummaryNanos = now;
            summaryPauses = 0;
            summaryPausedNanos = 0;
        }
    }

    /**
     * Closes the pacing period from the acknowledgement path once the reader
     * has stayed under the cap for longer than
     * {@link #CAPACITY_PACING_REARM_MS} (spec 01.05 §3.4 item 6). Before this
     * the period was closed only by the NEXT wait: a burst that paced the
     * reader for a minute and never met the cap again left its period open
     * for hours with no line, and when the line finally came it was written
     * at the next crossing and named that crossing's over-cap state. A no-op
     * while the reader is not paced, and while a wait is in progress: one
     * wait longer than the re-arm window (a slow head unit) is the period
     * continuing, not ending. Called under the class monitor.
     */
    private static synchronized void closePacingPeriodIfQuiet() {
        if (pacingStartNanos == 0 || pauseInProgress) {
            return;
        }
        if (capacityClock.getAsLong() - lastReleaseNanos > CAPACITY_PACING_REARM_MS * 1_000_000L) {
            closePacingPeriod("the reader stayed under the cap for more than "
                    + (CAPACITY_PACING_REARM_MS / 1000) + " s");
        }
    }

    /**
     * Closes the pacing period with its one INFO line and clears the
     * bookkeeping. A no-op while the reader is not paced. The line names the
     * outstanding rows, units and bytes AT THE LAST RELEASE -- the state the
     * period ended in -- never the live counters, which at a lazy close from
     * {@link #beginPause} already describe the next crossing.
     */
    private static synchronized void closePacingPeriod(String why) {
        if (pacingStartNanos == 0) {
            return;
        }
        log.info("Handoff hard cap pacing ended ({}): {} pause(s) totalling {} ms paused over {} s; "
                + "{} row(s) in {} unit(s) outstanding at the last release (~{} MiB estimated), "
                + "cap {} row(s) / {} MiB.",
                why, pacingPauses, pacingPausedNanos / 1_000_000L,
                (lastReleaseNanos - pacingStartNanos) / 1_000_000_000L,
                lastReleaseRecords, lastReleaseUnits, mib(lastReleaseBytes),
                pacingCap, mib(pacingCapBytes));
        pacingStartNanos = 0;
        pacingCap = 0;
        pacingCapBytes = 0;
        lastReleaseNanos = 0;
        lastSummaryNanos = 0;
        pacingPauses = 0;
        pacingPausedNanos = 0;
        summaryPauses = 0;
        summaryPausedNanos = 0;
        lastReleaseRecords = 0;
        lastReleaseUnits = 0;
        lastReleaseBytes = 0;
    }

    /**
     * Whether the outstanding rows meet the cap. An empty FIFO is never at
     * capacity: with nothing outstanding there is nothing to wait for, whatever
     * the counter says, so a bookkeeping fault can never wedge the producer.
     */
    private static boolean isAtCapacity(long maxOutstandingRecords, long maxOutstandingBytes) {
        if (outstandingSequences.isEmpty()) {
            return false;
        }
        boolean rowsMet = maxOutstandingRecords > 0 && outstandingRecords.get() >= maxOutstandingRecords;
        boolean bytesMet = maxOutstandingBytes > 0 && outstandingBytes.get() >= maxOutstandingBytes;
        return rowsMet || bytesMet;
    }

    /**
     * Outstanding units above which the handoff backlog advisory is raised
     * (spec 09.01 §3.1 step 4). The reader has run this far ahead of the
     * writers; nothing has failed and nothing is lost -- the FIFO below
     * still acknowledges every unit in binlog order.
     */
    static final int BACKLOG_ADVISORY_THRESHOLD = 1000;

    /**
     * Outstanding units at or under which a raised advisory is cleared (spec
     * 09.01 §3.1 step 4): the threshold less a tenth of itself. The raise and
     * clear levels differ on purpose. A backlog that hovers at the threshold
     * -- the writers acknowledging one unit as the reader hands off the next,
     * the normal shape when the writers are exactly saturated -- crosses a
     * single level on every flip, and with one level each flip was a WARN/INFO
     * pair: three pairs in 555 ms on one deployment, all naming 1001 and 1000,
     * followed by seven minutes above the threshold. With the clear level a
     * tenth below the raise level that episode is one WARN and one INFO.
     */
    static final int BACKLOG_ADVISORY_CLEAR_LEVEL =
            BACKLOG_ADVISORY_THRESHOLD - BACKLOG_ADVISORY_THRESHOLD / 10;

    /**
     * Whether the backlog advisory is currently raised. Guarded by the class
     * monitor like every other mutation here. The advisory is edge-triggered
     * with hysteresis: one WARN when the outstanding count first exceeds
     * {@link #BACKLOG_ADVISORY_THRESHOLD}, one INFO when an acknowledgement
     * brings it down to or under {@link #BACKLOG_ADVISORY_CLEAR_LEVEL}, then
     * it is re-armed; between the two levels nothing is logged in either
     * direction. Never one line per handoff, and never at ERROR: every handoff
     * above the threshold used to log an ERROR, which produced 4,003 lines in
     * seven minutes on one deployment and buried the six genuine warnings of
     * that hour in the error log.
     */
    private static boolean backlogAdvisoryRaised = false;

    /**
     * Raises or clears the backlog advisory on the edge only, with hysteresis
     * (spec 09.01 §3.1 step 4). Call under the class monitor after every
     * change to {@link #outstandingSequences}.
     */
    private static void noteBacklog() {
        int outstanding = outstandingSequences.size();
        if (!backlogAdvisoryRaised && outstanding > BACKLOG_ADVISORY_THRESHOLD) {
            backlogAdvisoryRaised = true;
            log.warn("Handoff backlog: {} batch(es) awaiting acknowledgement, above the advisory "
                    + "threshold of {}. The reader is ahead of the writers; nothing has failed. "
                    + "Logged once per crossing; the next backlog line is the one reporting it "
                    + "back at or under {}.", outstanding, BACKLOG_ADVISORY_THRESHOLD,
                    BACKLOG_ADVISORY_CLEAR_LEVEL);
        } else if (backlogAdvisoryRaised && outstanding <= BACKLOG_ADVISORY_CLEAR_LEVEL) {
            backlogAdvisoryRaised = false;
            log.info("Handoff backlog back under the advisory clear level: {} batch(es) awaiting "
                    + "acknowledgement (raised above {}, cleared at or under {}).",
                    outstanding, BACKLOG_ADVISORY_THRESHOLD, BACKLOG_ADVISORY_CLEAR_LEVEL);
        }
    }

    /**
     * Whether the backlog advisory is raised right now. For tests.
     *
     * @return true between the WARN that raised it and the INFO that cleared it.
     */
    static synchronized boolean isBacklogAdvisoryRaised() {
        return backlogAdvisoryRaised;
    }

    /**
     * Reports whether any batch handed to the writers is still unacknowledged:
     * queued, in flight, or written-but-parked behind an older batch.
     * <p>
     * The caller is the control-record offset commit in
     * {@code DebeziumChangeEventCapture}: a heartbeat carries the connector's
     * CURRENT position, so committing it while a sequence is outstanding would
     * move the committed offset past rows that are not in ClickHouse yet and
     * lose them on a crash. This predicate is what makes that commit safe.
     * </p>
     *
     * @return true if at least one handoff sequence is outstanding.
     */
    public static boolean hasUnwrittenBatches() {
        return !outstandingSequences.isEmpty();
    }

    /**
     * Number of handed-off units not yet acknowledged (queued, in flight, or
     * parked). For the stop-sequence log line and the start-time refusal.
     *
     * @return the size of the outstanding set.
     */
    public static int outstandingCount() {
        return outstandingSequences.size();
    }

    /**
     * Abandons every outstanding unit: an in-process engine restart
     * (spec 09.01 §3.8, spec 01.01 §3.3).
     * <p>
     * This bookkeeping is process-wide, but the embedded engine is restarted
     * INSIDE the process (REST {@code /restart}, {@code /start} after
     * {@code /stop}, the restart monitor). The old engine's worker pool is
     * terminated by {@code stop()}, so a unit still outstanding at that point
     * can never be written by anyone -- yet without this call it stayed the
     * FIFO head for the life of the JVM: every unit of the NEW engine parked
     * behind it forever (no offset acknowledged, {@link #hasUnwrittenBatches}
     * true, no control-record commit, every DDL drain timing out), and the
     * parked units' record lists leaked.
     * </p>
     * <p>
     * Call ONLY after the producer is closed (no more handoffs) and the pool
     * has terminated (no worker can still report a write). Nothing abandoned
     * here was acknowledged, so the next engine redelivers it from the last
     * committed offset: the cost of a restart is redelivery (at-least-once),
     * never a lost or rolled-back offset. {@code handoffCounter} is NOT reset:
     * sequences stay unique for the life of the JVM.
     * </p>
     *
     * @return the number of units abandoned.
     */
    public static synchronized int reset() {
        // Every sequence assigned so far belongs to an engine that is gone:
        // retire them all, so a worker reporting one of them later is answered
        // "not acknowledged, redelivered" instead of an error (§3.8 item 5).
        retiredBelowSequence = handoffCounter.get();
        int abandoned = outstandingSequences.size();
        if (abandoned > 0) {
            log.warn("Offset FIFO reset: abandoning {} handed-off unit(s) that were never "
                    + "acknowledged ({} group(s) unwritten, {} unit(s) written but parked). Their "
                    + "rows were not acknowledged, so the next engine redelivers them from the "
                    + "last committed offset.", abandoned, groupToUnit.size(), completedUnits.size());
        }
        outstandingSequences.clear();
        groupToUnit.clear();
        completedUnits.clear();
        outstandingRecords.set(0);
        outstandingBytes.set(0);
        // The set it advised on is gone; clear the advisory with it, silently
        // (the abandonment WARN above is the line for this event).
        backlogAdvisoryRaised = false;
        // The units the reader was paced on are gone with the engine: close
        // the pacing period with its one line (spec 01.05 §3.4 item 6).
        closePacingPeriod("reset");
        pauseInProgress = false;
        // A producer paused at the hard cap is waiting on units that no longer
        // exist; wake it so it re-reads the (now empty) FIFO.
        DebeziumOffsetManagement.class.notifyAll();
        return abandoned;
    }

    /**
     * Reports that a group's rows are durably in ClickHouse and lets the FIFO
     * decide whether its unit's offset can be acknowledged now.
     * <p>
     * Call EXACTLY ONCE per written group, and then drop the group whatever
     * this returns: if the unit is not yet commit-eligible it is parked here,
     * and its acknowledgement is driven by whichever call later acknowledges
     * the head of the FIFO. Re-running a written batch would re-insert its
     * rows (WRITTEN-ONCE, spec 09.01 §3.2).
     * </p>
     *
     * @param batch the written group.
     * @return true iff the group's unit was acknowledged during this call.
     * @throws InterruptedException  if acknowledging is interrupted.
     * @throws IllegalStateException if the batch carries a Debezium committer
     *                               but was never registered at handoff -- it
     *                               cannot be ordered and must not be
     *                               acknowledged silently.
     */
    static synchronized public boolean checkIfBatchCanBeCommitted(
            List<ClickHouseStruct> batch) throws InterruptedException {
        HandoffUnit unit = groupToUnit.remove(new BatchKey(batch));
        if (unit == null) {
            if (isRetired(batch)) {
                // The engine that handed this unit off has stopped and its
                // offset store closed with it (§3.8 item 5): its committer
                // cannot acknowledge anything -- it throws from the closed
                // store, and the failed flush leaves the OffsetStorageWriter
                // "already flushing" for good, which is how a worker used to
                // die here. The rows are in ClickHouse; the offset was never
                // committed; the restarted engine redelivers the unit from the
                // last committed offset (at-least-once, spec 02.04). Not an
                // error: the worker moves on.
                log.info("Handoff sequence {} was retired by an engine restart before its rows were "
                        + "reported written: not acknowledged (the engine that handed it off has "
                        + "stopped and its offset store is closed); the restarted engine redelivers "
                        + "it from the last committed offset.", handoffSequenceOf(batch));
                return false;
            }
            if (carriesCommitter(batch)) {
                throw new IllegalStateException("a batch carrying a Debezium committer reached "
                        + "the writer without a handoff sequence; it cannot be ordered against "
                        + "the other outstanding batches, so its offset is not acknowledged");
            }
            // The Kafka Connect sink path: no Debezium committer, offsets are
            // committed through the task's own durable watermark. Nothing to
            // order here.
            return true;
        }
        unit.remainingGroups--;
        if (unit.remainingGroups > 0) {
            log.debug("Handoff sequence {}: {} group(s) still unwritten", unit.sequence,
                    unit.remainingGroups);
            return false;
        }
        completedUnits.put(unit.sequence, unit);
        drainCompletedUnits();
        return !outstandingSequences.contains(unit.sequence);
    }

    /**
     * Acknowledges parked units from the head of the FIFO while the head is
     * written, and stops at the first outstanding sequence that is not.
     */
    private static synchronized void drainCompletedUnits() throws InterruptedException {
        while (!completedUnits.isEmpty()) {
            if (outstandingSequences.isEmpty()) {
                throw new IllegalStateException("a completed unit is not outstanding; "
                        + "the handoff FIFO bookkeeping is corrupt");
            }
            Long head = outstandingSequences.first();
            HandoffUnit unit = completedUnits.get(head);
            if (unit == null) {
                log.debug("Handoff sequence {} is written but parked behind unacknowledged "
                        + "sequence {}", completedUnits.firstKey(), head);
                return;
            }
            acknowledgeRecords(unit.records);
            completedUnits.remove(head);
            outstandingSequences.remove(head);
            outstandingRecords.addAndGet(-unit.records.size());
            outstandingBytes.addAndGet(-unit.bytes);
            noteBacklog();
            // The writers are draining: if the reader has not been paced for
            // longer than the re-arm window, its pacing period is over.
            closePacingPeriodIfQuiet();
            // The head moved: a producer paused at the hard cap may proceed.
            DebeziumOffsetManagement.class.notifyAll();
        }
    }

    /**
     * Whether the batch belongs to a unit retired by {@link #reset()}: it was
     * handed off (a record carries a handoff sequence) and that sequence is
     * below the retirement watermark. A batch that was never handed off (the
     * Kafka Connect path) is never retired.
     */
    static boolean isRetired(List<ClickHouseStruct> batch) {
        long sequence = handoffSequenceOf(batch);
        return sequence >= 0 && sequence < retiredBelowSequence;
    }

    /** The handoff sequence stamped on the batch, or -1 when it was never handed off. */
    static long handoffSequenceOf(List<ClickHouseStruct> batch) {
        if (batch == null) {
            return -1L;
        }
        for (ClickHouseStruct record : batch) {
            if (record != null && record.getHandoffSequence() >= 0) {
                return record.getHandoffSequence();
            }
        }
        return -1L;
    }

    private static boolean carriesCommitter(List<ClickHouseStruct> batch) {
        if (batch == null) {
            return false;
        }
        for (ClickHouseStruct record : batch) {
            if (record != null && record.getCommitter() != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Acknowledges the given batch of records.
     * <p>
     * Iterates through each record and marks it as processed using the
     * record committer. If a record is the last in its batch, the batch is
     * marked as finished.
     * </p>
     *
     * <p><b>Delivery semantics.</b> {@code markBatchFinished()} REQUESTS an
     * offset flush; it does not guarantee one. Debezium's embedded engine
     * honours the request no more often than {@code offset.flush.interval.ms}
     * (5000 in the shipped configs), so the committed position in
     * {@code altinity_sink_connector.replica_source_info} can lag the data
     * already written to ClickHouse by up to that interval. A crash in that
     * window re-delivers every event after the last flushed position on
     * restart. Delivery is therefore AT-LEAST-ONCE, not exactly-once.</p>
     *
     * <p>Measured on 2026-08-25 against ClickHouse 24.8.14 by hard-killing the
     * connector mid-flight ({@code podman kill}, no graceful flush). The same
     * batch and the same DDL were both re-executed after restart:</p>
     * <pre>
     *   05:29:57.959  EXECUTED BATCH Successfully Records: 3
     *   05:29:58.090  Executed Source DB DDL: ... ADD COLUMN burstcol
     *   --- hard kill + restart ---
     *   05:30:03.553  EXECUTED BATCH Successfully Records: 3      &lt;- re-sent
     *   05:30:03.672  Executed Source DB DDL: ... ADD COLUMN burstcol
     * </pre>
     *
     * <p>No data was corrupted, and the reason is worth stating precisely,
     * because it is NOT that the replayed event reproduces its original
     * {@code _version} -- it deliberately does not. On resume the counter is
     * seeded at {@code SEQUENCE_START_INITIAL} (500m) rather than
     * {@code SEQUENCE_START} (1000m), so a re-published event in the same
     * source second is issued a strictly LOWER version than the pre-restart
     * write of that same event. Combined with a stable row key (MySQL's
     * generated invisible primary key) as the ReplacingMergeTree sorting key,
     * the replayed copy therefore LOSES to the row already stored and is
     * discarded, rather than winning and overwriting it. Measured:
     * {@code mysql=8 ch_raw=8 ch_live=8}, per-row raw copies 1.</p>
     *
     * <p>That ordering is the safety property, and it is load-bearing in a way
     * that is easy to break by accident. It depends on the version being
     * anchored to the SOURCE commit timestamp ({@code source.ts_ms}), which is
     * identical on every re-delivery, so an IN-RUN redelivery (an engine retry
     * without a restart) is recognised by its log position, keeps its own
     * source-timestamp anchored version and loses to the stored copy.</p>
     *
     * <p>Across a RESTART the outcome described above is no longer decided by
     * the counter seed (spec 02.04 section 3.2): the version floor is seeded at
     * engine start from the durable high-water mark (spec 02.02 section 3.5),
     * so every replayed copy is a first delivery to the new run and is versioned
     * ABOVE the copy already stored. That is safe because a replay is the
     * contiguous suffix of the binlog from the committed offset, delivered in
     * log order: for every key the last replayed event is the same event that
     * was last in the original run, so {@code FINAL} resolves to the same row
     * image whichever copy wins, and every genuinely new event after the suffix
     * ranks above both. What is load-bearing, and easy to break by accident, is
     * therefore that the new run's versions are totally ordered above the old
     * run's and in log order among themselves. See the anchoring comment in
     * {@code DebeziumChangeEventCapture#handleChangeEventBatch} and
     * {@code seedVersionFloor} before changing the sequence seeding, the floor
     * seeding or the timestamp source.</p>
     *
     * <p><b>Inherited arithmetic.</b> The encoding
     * {@code sourceTsMs * 1_000_000 + sequence} leaves six decimal digits for
     * the sequence while the seeds are ten digits, so the addition carries into
     * the timestamp field and acts as a ~1000&nbsp;ms shift. With the floor
     * starting at 0 after a restart, a genuinely NEWER event arriving just after
     * a resume ranked BELOW an older pre-restart event and was discarded:</p>
     *
     * <pre>
     *   older, pre-restart  (T)     -&gt; T*1e6 + 1_000_000_000 = 1787635798000000000
     *   newer, post-restart (T+1ms) -&gt; (T+1)*1e6 + 500_000_000 = 1787635797501000000
     * </pre>
     *
     * <p>The arithmetic is kept as it is (it is the 2.8.0 contract; changing it
     * would break upgrade and downgrade against every stored version). The
     * inversion is closed by what feeds it: the floor is seeded at engine start
     * from the durable high-water mark, so the post-restart event is clamped to
     * {@code T + 1001} and out-ranks the older one (spec 02.02 section 3.5).
     * {@code SequenceSeedOverflowTest} pins the raw arithmetic; the lightweight
     * {@code DebeziumChangeEventCaptureTest} pins the seeded restart.</p>
     *
     * <p>The engine matters too. ReplacingMergeTree resolves a duplicate by
     * version, so a losing replay is simply dropped. CollapsingMergeTree sign
     * rows are ADDITIVE: a replayed {@code +1} sums to {@code +2} and never
     * cancels against a single {@code -1}, so the same replay would corrupt.
     * The connector never auto-creates that engine -- auto-create emits only
     * ReplacingMergeTree / ReplicatedReplacingMergeTree -- so it is reachable
     * only for a pre-existing table a user points the connector at.</p>
     *
     * @param batch The batch of ClickHouseStruct records to acknowledge, in
     *              binlog order.
     * @throws InterruptedException If the commit operation is interrupted.
     */
    static synchronized void acknowledgeRecords(List<ClickHouseStruct> batch)
                                            throws InterruptedException {
        // acknowledge records
        // Iterate through the records
        // and use the record committer to commit the offsets.
        // markProcessed() and markBatchFinished() MUST run inside the SAME
        // critical section. Serializing only markBatchFinished() is not
        // sufficient: markProcessed() mutates the OffsetStorageWriter's
        // pending-offset map, which is exactly the state beginFlush()
        // snapshots, and neither is thread-safe. Debezium also builds a NEW
        // RecordCommitter per batch (EmbeddedEngine.buildRecordCommitter), so
        // its own `synchronized` methods lock different monitors for different
        // batches and give no mutual exclusion across worker threads. The
        // single shared OFFSET_COMMIT_LOCK is the only thing serialising
        // access to the OffsetStorageWriter underneath.
        synchronized (OFFSET_COMMIT_LOCK) {
            for (ClickHouseStruct record : batch) {
                if (record.getCommitter() != null && record.getSourceRecord() != null) {

                    record.getCommitter().markProcessed(record.getSourceRecord());

                    if (record.isLastRecordInBatch()) {
                        record.getCommitter().markBatchFinished();
                        acknowledgements.incrementAndGet();
                        // Per-batch progress line: INFO by design (spec 03.06 section 3.3) --
                        // operators read the connector's progress from the log.
                        log.info("***** BATCH marked as processed to debezium ****" + "Binlog file:" +
                                record.getFile() + " Binlog position: " + record.getPos() + " GTID: " + record.getGtid()
                                + " Sequence Number: " + record.getSequenceNumber() + " Debezium Timestamp: " + record.getDebezium_ts_ms());
                    }
                }
            }
        }
    }

    /**
     * Acknowledges a single change event record using the provided record
     * committer.
     *
     * @param recordCommitter  The record committer to be used.
     * @param sourceRecord     The change event record.
     * @param lastRecordInBatch True if this is the last record in the batch.
     * @throws InterruptedException If the commit operation is interrupted.
     */
    public static synchronized void acknowledgeRecords(
            DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>>
                    recordCommitter,
            ChangeEvent<SourceRecord, SourceRecord> sourceRecord,
            boolean lastRecordInBatch)
            throws InterruptedException {
        if (sourceRecord != null) {
            // Same critical section as the batch variant above -- see the
            // comment there for why markProcessed() must be inside it too.
            synchronized (OFFSET_COMMIT_LOCK) {
                recordCommitter.markProcessed(sourceRecord);
                if (lastRecordInBatch == true) {
                    recordCommitter.markBatchFinished();
                    acknowledgements.incrementAndGet();
                }
            }
        }
    }

    /**
     * Acknowledges a single record on the shared offset-commit lock.
     * <p>
     * Exposed so that every offset-committing path in the connector funnels
     * through the SAME lock. Any path that calls {@code markProcessed()} /
     * {@code markBatchFinished()} directly bypasses the serialization and can
     * drive concurrent {@code beginFlush()} calls into the non-thread-safe
     * OffsetStorageWriter, which throws
     * {@code ConnectException: OffsetStorageWriter is already flushing}.
     * </p>
     *
     * @param recordCommitter    The record committer to be used.
     * @param sourceRecord       The source record to mark as processed.
     * @param lastRecordInBatch  True if this is the last record in the batch.
     * @throws InterruptedException If the commit operation is interrupted.
     */
    public static void acknowledgeRecord(
            DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>>
                    recordCommitter,
            ChangeEvent<SourceRecord, SourceRecord> sourceRecord,
            boolean lastRecordInBatch)
            throws InterruptedException {
        if (recordCommitter == null || sourceRecord == null) {
            return;
        }
        synchronized (OFFSET_COMMIT_LOCK) {
            recordCommitter.markProcessed(sourceRecord);
            if (lastRecordInBatch) {
                recordCommitter.markBatchFinished();
            }
        }
    }
}

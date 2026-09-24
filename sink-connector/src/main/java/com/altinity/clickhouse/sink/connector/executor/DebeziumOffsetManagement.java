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
        int remainingGroups;

        HandoffUnit(long sequence, List<ClickHouseStruct> records, int groups) {
            this.sequence = sequence;
            this.records = records;
            this.remainingGroups = groups;
        }
    }

    /**
     * The next handoff sequence to assign. Monotone for the life of the JVM;
     * assigned only on the producer (Debezium) thread, in binlog order.
     */
    private static final AtomicLong handoffCounter = new AtomicLong();

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
        HandoffUnit handoffUnit = new HandoffUnit(sequence, unit, groups.size());
        for (List<ClickHouseStruct> group : groups) {
            if (groupToUnit.putIfAbsent(new BatchKey(group), handoffUnit) != null) {
                throw new IllegalStateException(
                        "batch handed off twice; a batch must be registered exactly once");
            }
        }
        outstandingSequences.add(sequence);
        if (outstandingSequences.size() > 1000) {
            log.error("*********** Batches awaiting acknowledgement is greater than 1000 "
                    + "***********");
        }
        return sequence;
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
    private static void drainCompletedUnits() throws InterruptedException {
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
        }
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

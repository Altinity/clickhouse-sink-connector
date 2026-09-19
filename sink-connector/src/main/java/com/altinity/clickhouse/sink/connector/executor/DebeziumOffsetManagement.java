package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class is used to manage the state of the offsets from all the
 * different consumer threads.
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
     * The maps below MUST be keyed by the batch's object identity, not by its
     * {@code (minTs, maxTs)} timestamp range. Two distinct batches routinely
     * share a timestamp range — a single multi-row statement split across
     * batches, or two batches whose rows all fall in the same millisecond — and
     * keying by the range made them collide: {@code put} silently overwrote the
     * earlier batch's entry and {@code remove} deleted the wrong one, so a batch
     * that was still unwritten stopped blocking the offset commit. The committed
     * binlog position could then advance past rows not yet in ClickHouse, losing
     * them on a crash. Keying by identity makes every batch a distinct entry
     * regardless of its timestamps. {@code equals}/{@code hashCode} are by
     * reference so this stays correct on a {@link ConcurrentHashMap} (whose
     * default keying would otherwise fall back to {@code List} content equality).
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
     * A concurrent map holding the in-flight batches, keyed by batch identity
     * (see {@link BatchKey}). The value is the list of ClickHouseStruct records.
     */
    static ConcurrentHashMap<BatchKey, List<ClickHouseStruct>>
            inFlightBatches = new ConcurrentHashMap<>();

    /**
     * A concurrent map holding the completed batches, keyed by batch identity.
     * Once a batch is fully processed, it is moved from inFlightBatches to
     * completedBatches.
     */
    static ConcurrentHashMap<BatchKey, List<ClickHouseStruct>>
            completedBatches = new ConcurrentHashMap<>();

    /**
     * Shared lock that serializes every offset commit driven by the connector
     * worker threads, guaranteeing a single connector-side flush at a time so
     * that concurrent worker threads never issue overlapping
     * {@code markBatchFinished()} calls against the same OffsetStorageWriter.
     */
    private static final Object OFFSET_COMMIT_LOCK = new Object();

    /**
     * Constructor to initialize DebeziumOffsetManagement with a provided
     * in-flight batch map.
     *
     * @param inFlightBatches A map containing the in-flight batches.
     */
    public DebeziumOffsetManagement(
            ConcurrentHashMap<BatchKey, List<ClickHouseStruct>>
                    inFlightBatches) {
        this.inFlightBatches = inFlightBatches;
    }

    /**
     * Registers the given batch as in-flight, keyed by its identity.
     *
     * @param batch A list of ClickHouseStruct records.
     */
    public static void addToBatchTimestamps(List<ClickHouseStruct> batch) {
        if (inFlightBatches.size() > 1000) {
            log.error("*********** Requests in Flight is greater than 1000 "
                    + "***********");
        }
        inFlightBatches.put(new BatchKey(batch), batch);
    }

    /**
     * Removes the given batch from the in-flight map (by identity).
     *
     * @param batch The batch to remove.
     */
    public void removeFromBatchTimestamps(List<ClickHouseStruct> batch) {
        inFlightBatches.remove(new BatchKey(batch));
    }

    /**
     * Returns the map of in-flight batches, keyed by batch identity.
     *
     * @return A map of batch keys to their associated record lists.
     */
    public Map<BatchKey, List<ClickHouseStruct>> getBatchTimestamps() {
        return inFlightBatches;
    }

    /**
     * Reports whether any batch read from the source is still unwritten.
     * <p>
     * A batch sits in {@link #inFlightBatches} from the moment a consumer
     * picks it up until its rows are in ClickHouse and its offsets are
     * acknowledged, and in {@link #completedBatches} while it waits for an
     * older overlapping batch to finish. Either map being non-empty means
     * there are records the connector has read but not yet persisted.
     * </p>
     * <p>
     * The caller is the control-record offset commit in
     * {@code DebeziumChangeEventCapture}: a heartbeat carries the connector's
     * CURRENT position, so committing it while these maps are non-empty would
     * move the committed offset past rows that are not in ClickHouse yet and
     * lose them on a crash. This predicate is what makes that commit safe.
     * </p>
     *
     * @return true if at least one batch is still awaiting persistence.
     */
    public static boolean hasUnwrittenBatches() {
        return outstandingBatches.get() > 0
                || !inFlightBatches.isEmpty()
                || !completedBatches.isEmpty();
    }

    /**
     * Batches handed to the asynchronous consumers that have not yet been
     * acknowledged.
     * <p>
     * The two maps above cannot answer this on their own. A consumer
     * {@code poll()}s a batch off the handoff queue and only registers it in
     * {@link #inFlightBatches} once it reaches
     * {@code ClickHouseBatchRunnable#processBatch}; in between -- which
     * includes the replication-history write -- the batch is in neither
     * collection and the pipeline would falsely read as quiescent. A
     * control-record offset committed inside that window would advance past
     * rows that are not in ClickHouse yet and lose them on a crash.
     * </p>
     * <p>
     * This counter closes that window because it is incremented by the
     * PRODUCER at handoff, before the batch is visible to any consumer, and
     * decremented only after the batch has been acknowledged. The producer is
     * also the thread that reads it, so its own increments happen-before its
     * own read and no batch it has handed off can be missed. A batch that
     * fails and is retried is never decremented until it finally succeeds,
     * so the predicate stays conservative -- it can only ever withhold a
     * commit, never permit an unsafe one.
     * </p>
     */
    private static final java.util.concurrent.atomic.AtomicLong outstandingBatches =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Records that a batch has been handed to the asynchronous consumers.
     * Call on the producer thread immediately before the batch becomes
     * visible to a consumer.
     */
    public static void batchHandedOff() {
        outstandingBatches.incrementAndGet();
    }

    /**
     * Releases a registration made by {@link #batchHandedOff()} for a batch
     * that never reached a consumer, so no acknowledgement will ever arrive
     * for it. Without this the counter would stay above zero forever and no
     * control-record offset could be committed again for the life of the
     * process.
     */
    public static void batchHandoffFailed() {
        outstandingBatches.updateAndGet(v -> v > 0 ? v - 1 : 0);
    }

    /**
     * Calculates the minimum and maximum Debezium timestamps from the given batch.
     *
     * @param batch A list of ClickHouseStruct records.
     * @return A Pair where the left value is the minimum timestamp and the
     *         right value is the maximum timestamp.
     */
    public static Pair<Long, Long> calculateMinMaxTimestampFromBatch(
            List<ClickHouseStruct> batch) {
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (ClickHouseStruct clickHouseStruct : batch) {
            if (clickHouseStruct.getDebezium_ts_ms() < min) {
                min = clickHouseStruct.getDebezium_ts_ms();
            }
            if (clickHouseStruct.getDebezium_ts_ms() > max) {
                max = clickHouseStruct.getDebezium_ts_ms();
            }
        }
        return Pair.of(min, max);
    }

    /**
     * Checks if there are any in-flight requests that overlap with the current
     * batch's timestamp range.
     *
     * @param currentBatch A list of ClickHouseStruct records.
     * @return true if there is an overlap; false otherwise.
     */
    static boolean checkIfThereAreInflightRequests(
            List<ClickHouseStruct> currentBatch) {
        boolean result = false;
        Pair<Long, Long> currentBatchPair =
                calculateMinMaxTimestampFromBatch(currentBatch);
        // Iterate through inFlightBatches and check if there is any OTHER batch
        // that overlaps the current one.
        for (Map.Entry<BatchKey, List<ClickHouseStruct>> entry
                : inFlightBatches.entrySet()) {
            // Ignore the same batch -- by IDENTITY, not by timestamp range. Two
            // different batches can share a range; comparing ranges here made a
            // batch treat a distinct overlapping sibling as "itself" and skip
            // it, so an unwritten older batch stopped blocking the commit.
            if (entry.getKey().batch == currentBatch) {
                continue;
            }
            Pair<Long, Long> otherPair =
                    calculateMinMaxTimestampFromBatch(entry.getValue());
            // Check if max of current batch is greater than min of inflight batch.
            if (currentBatchPair.getRight().longValue() > otherPair.getLeft().longValue()) {
                result = true;
                break;
            }
        }
        return result;
    }

    /**
     * Checks if the batch can be committed.
     * <p>
     * If there are no in-flight requests overlapping with the current batch,
     * the batch is acknowledged and committed. Otherwise, the batch is moved to
     * completedBatches.
     * </p>
     *
     * @param batch A list of ClickHouseStruct records.
     * @return true if the batch can be committed; false otherwise.
     * @throws InterruptedException If the commit operation is interrupted.
     */
    static synchronized public boolean checkIfBatchCanBeCommitted(
    List<ClickHouseStruct> batch) throws InterruptedException {
        boolean result = false;
        if (true == checkIfThereAreInflightRequests(batch)) {
            // Remove the record from inFlightBatches and move it to
            // completedBatches -- keyed by identity so equal-timestamp batches
            // do not clobber each other.
            BatchKey key = new BatchKey(batch);
            inFlightBatches.remove(key);
            completedBatches.put(key, batch);
        } else {
            // Acknowledge current batch
            acknowledgeRecords(batch);
            result = true;
            // Check if completed batches can also be acknowledged.
            completedBatches.forEach((k, v) -> {
                if (false == checkIfThereAreInflightRequests(v)) {
                    try {
                        acknowledgeRecords(v);
                    } catch (InterruptedException e) {
                        log.error("*** Error acknowlegeRecords ***", e);
                        throw new RuntimeException(e);
                    }
                    completedBatches.remove(k);
                }
            });
        }
        return result;
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
     * identical on every re-delivery. Anchoring it to any processing-side or
     * wall-clock value instead would give the replayed copy a HIGHER version,
     * so it would supersede the correct row -- silently, with row counts still
     * matching. See the anchoring comment in
     * {@code DebeziumChangeEventCapture#handleBatch} before changing either
     * the sequence seeding or the timestamp source.</p>
     *
     * <p><b>KNOWN DEFECT, not fixed here.</b> The guarantee above holds only
     * for the SAME event re-delivered. It does not generalise, because the
     * encoding {@code sourceTsMs * 1_000_000 + sequence} leaves six decimal
     * digits for the sequence while the seeds are ten digits, so the addition
     * carries into the timestamp field and acts as a ~1000&nbsp;ms shift.
     * A genuinely NEWER event arriving just after a resume can then rank BELOW
     * an older pre-restart event and be discarded:</p>
     *
     * <pre>
     *   older, pre-restart  (T)     -&gt; T*1e6 + 1_000_000_000 = 1787635798000000000
     *   newer, post-restart (T+1ms) -&gt; (T+1)*1e6 + 500_000_000 = 1787635797501000000
     * </pre>
     *
     * <p>The {@code diff &gt; 1} second reset does not cover it: a 1&nbsp;ms
     * advance yields {@code diff == 0}, so the 500m seed still applies. Fixing
     * it means widening the multiplier (or shrinking the seeds) so the
     * sequence cannot carry -- a change to the version scheme itself, which
     * needs its own review and a migration story for existing versions.
     * {@code ReplaySafetyTest} pins the arithmetic so the gap cannot be
     * mistaken for intended behaviour.</p>
     *
     * <p>The engine matters too. ReplacingMergeTree resolves a duplicate by
     * version, so a losing replay is simply dropped. CollapsingMergeTree sign
     * rows are ADDITIVE: a replayed {@code +1} sums to {@code +2} and never
     * cancels against a single {@code -1}, so the same replay would corrupt.
     * The connector never auto-creates that engine -- auto-create emits only
     * ReplacingMergeTree / ReplicatedReplacingMergeTree -- so it is reachable
     * only for a pre-existing table a user points the connector at.</p>
     *
     * @param batch The batch of ClickHouseStruct records to acknowledge.
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
                        log.info("***** BATCH marked as processed to debezium ****" + "Binlog file:" +
                                record.getFile() + " Binlog position: " + record.getPos() + " GTID: " + record.getGtid()
                                + " Sequence Number: " + record.getSequenceNumber() + " Debezium Timestamp: " + record.getDebezium_ts_ms());
                    }
                }
            }
        }

        // Remove the batch from the inFlightBatches (by identity).
        inFlightBatches.remove(new BatchKey(batch));

        // The batch is acknowledged, so it no longer blocks a control-record
        // offset commit. Decremented only here, after markProcessed, so the
        // counter can never drop while rows are still unwritten.
        //
        // Floored at zero because a batch that was parked in completedBatches
        // and then retried can reach this method more than once; letting the
        // counter go negative would make the pipeline read as quiescent while
        // work is outstanding, which is the one direction that is unsafe. The
        // map checks in hasUnwrittenBatches remain as the second line of
        // defence for exactly that case -- a retried batch is back in
        // inFlightBatches, so it is still seen.
        outstandingBatches.updateAndGet(v -> v > 0 ? v - 1 : 0);
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

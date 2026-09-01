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
     * A concurrent map holding the in-flight batch timestamps. The key is a
     * Pair of minimum and maximum timestamps, and the value is the list of
     * corresponding ClickHouseStruct records.
     */
    static ConcurrentHashMap<Pair<Long, Long>, List<ClickHouseStruct>>
            inFlightBatches = new ConcurrentHashMap<>();

    /**
     * A concurrent map holding the completed batches. Once a batch is
     * fully processed, it is moved from inFlightBatches to completedBatches.
     */
    static ConcurrentHashMap<Pair<Long, Long>, List<ClickHouseStruct>>
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
            ConcurrentHashMap<Pair<Long, Long>, List<ClickHouseStruct>>
                    inFlightBatches) {
        this.inFlightBatches = inFlightBatches;
    }

    /**
     * Adds the given batch's timestamp range to the in-flight batches map.
     *
     * @param batch A list of ClickHouseStruct records.
     */
    public static void addToBatchTimestamps(List<ClickHouseStruct> batch) {
        Pair<Long, Long> pair = calculateMinMaxTimestampFromBatch(batch);
        if (inFlightBatches.size() > 1000) {
            log.error("*********** Requests in Flight is greater than 1000 "
                    + "***********");
        }
        inFlightBatches.put(pair, batch);
    }

    /**
     * Removes the batch corresponding to the given timestamp range.
     *
     * @param pair The Pair of minimum and maximum timestamps.
     */
    public void removeFromBatchTimestamps(Pair<Long, Long> pair) {
        inFlightBatches.remove(pair);
    }

    /**
     * Returns the map of in-flight batch timestamps.
     *
     * @return A map of timestamp pairs to their associated record lists.
     */
    public Map<Pair<Long, Long>, List<ClickHouseStruct>> getBatchTimestamps() {
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
        return !inFlightBatches.isEmpty() || !completedBatches.isEmpty();
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
        // Iterate through inFlightBatches and check if there is any batch
        // which is lower than the current batch.
        for (Map.Entry<Pair<Long, Long>, List<ClickHouseStruct>> entry
                : inFlightBatches.entrySet()) {
            Pair<Long, Long> key = entry.getKey();
            // Ignore the same batch.
            if (currentBatchPair.getLeft().longValue() == key.getLeft().longValue()
                    && currentBatchPair.getRight().longValue() == key.getRight().longValue()) {
                continue;
            }
            // Check if max of current batch is greater than min of inflight batch.
            if (currentBatchPair.getRight().longValue() > key.getLeft().longValue()) {
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
            // completedBatches.
            Pair<Long, Long> pair = calculateMinMaxTimestampFromBatch(batch);
            inFlightBatches.remove(pair);
            completedBatches.put(pair, batch);
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
        for(ClickHouseStruct record: batch) {
            if (record.getCommitter() != null && record.getSourceRecord() != null) {

                record.getCommitter().markProcessed(record.getSourceRecord());
//                log.debug("***** Record successfully marked as processed ****" + "Binlog file:" +
//                        record.getFile() + " Binlog position: " + record.getPos() + " GTID: " + record.getGtid()
//                + "Sequence Number: " + record.getSequenceNumber() + "Debezium Timestamp: " + record.getDebezium_ts_ms());

                if(record.isLastRecordInBatch()) {
                    markBatchFinishedSafely(record.getCommitter());
                    log.info("***** BATCH marked as processed to debezium ****" + "Binlog file:" +
                            record.getFile() + " Binlog position: " + record.getPos() + " GTID: " + record.getGtid()
                            + " Sequence Number: " + record.getSequenceNumber() + " Debezium Timestamp: " + record.getDebezium_ts_ms());
                }
            }
        }

        // Remove the batch from the inFlightBatches
        Pair<Long, Long> pair = calculateMinMaxTimestampFromBatch(batch);
        inFlightBatches.remove(pair);
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
            recordCommitter.markProcessed(sourceRecord);
            if (lastRecordInBatch == true) {
                markBatchFinishedSafely(recordCommitter);
            }
        }
    }

    /**
     * Finishes a Debezium batch (which triggers an offset flush).
     * <p>
     * All connector-driven commits are serialized on {@link #OFFSET_COMMIT_LOCK}
     * so that worker threads never issue overlapping
     * {@code markBatchFinished()} / {@code beginFlush()} calls against the same,
     * non-thread-safe {@code OffsetStorageWriter}. This serialization, combined
     * with Debezium's own single {@code RecordCommitter} whose methods are
     * {@code synchronized}, prevents concurrent flushes. Any exception is
     * allowed to propagate so it is handled by the caller's existing
     * retriable-error path rather than being silently swallowed.
     *
     * @param committer The Debezium record committer.
     * @throws InterruptedException If the commit is interrupted.
     */
    private static void markBatchFinishedSafely(
            DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>>
                    committer)
            throws InterruptedException {
        if (committer == null) {
            return;
        }
        synchronized (OFFSET_COMMIT_LOCK) {
            committer.markBatchFinished();
        }
    }
}

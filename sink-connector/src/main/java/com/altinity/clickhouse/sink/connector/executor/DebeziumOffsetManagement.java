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
     * <p>No data was corrupted, and the reason matters: the APPLY is
     * idempotent, not the delivery. Each row carries a stable key
     * (MySQL's generated invisible primary key) as the ReplacingMergeTree
     * sorting key, and the re-sent event regenerates the SAME {@code _version},
     * so the replayed copy collapses onto the original instead of adding a row
     * ({@code mysql=8 ch_raw=8 ch_live=8}; per-row raw copies = 1, distinct
     * versions = 1).</p>
     *
     * <p>The consequence for anyone changing this path: correctness rests on
     * every write being replay-safe. A non-idempotent apply would corrupt.
     * The known shape to watch is a table whose version is derived from
     * wall-clock time rather than the binlog coordinates -- two deliveries of
     * one event would then produce two distinct versions and stop collapsing.
     * CollapsingMergeTree sign rows are additive rather than replacing, but
     * the connector never auto-creates that engine (auto-create emits only
     * ReplacingMergeTree / ReplicatedReplacingMergeTree), so it is reachable
     * only for a pre-existing table the user points the connector at.</p>
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

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
     * Shared lock serialising every connector-driven offset commit.
     * <p>
     * Kafka's {@code OffsetStorageWriter} is not thread-safe and rejects overlapping
     * flushes with {@code ConnectException: OffsetStorageWriter is already flushing}.
     * Debezium's own {@code RecordCommitter} methods are {@code synchronized}, but a
     * NEW committer instance is built per batch
     * ({@code EmbeddedEngine.buildRecordCommitter}), so concurrent worker threads
     * synchronise on different monitors and get no mutual exclusion. This single
     * static lock is the actual barrier.
     * </p>
     */
    static final Object OFFSET_COMMIT_LOCK = new Object();

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
        if (batch == null || batch.isEmpty()) {
            return Pair.of(0L, 0L);
        }
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
            // Collect keys first to avoid ConcurrentModificationException
            // when removing entries during iteration
            java.util.List<Pair<Long, Long>> toRemove = new java.util.ArrayList<>();
            for (java.util.Map.Entry<Pair<Long, Long>, java.util.List<ClickHouseStruct>> entry
                    : completedBatches.entrySet()) {
                if (false == checkIfThereAreInflightRequests(entry.getValue())) {
                    try {
                        acknowledgeRecords(entry.getValue());
                    } catch (InterruptedException e) {
                        log.error("*** Error acknowledgeRecords ***", e);
                        throw new RuntimeException(e);
                    }
                    toRemove.add(entry.getKey());
                }
            }
            for (Pair<Long, Long> key : toRemove) {
                completedBatches.remove(key);
            }
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
     * @param batch The batch of ClickHouseStruct records to acknowledge.
     * @throws InterruptedException If the commit operation is interrupted.
     */
    static synchronized void acknowledgeRecords(List<ClickHouseStruct> batch)
                                            throws InterruptedException {
        // acknowledge records
        // Iterate through the records
        // and use the record committer to commit the offsets.
        //
        // Both sides of the merge are kept, because they fix DIFFERENT halves of
        // the same race:
        //  - develop widens the critical section: markProcessed() and
        //    markBatchFinished() MUST be inside the SAME one. Debezium builds a
        //    NEW RecordCommitter per batch (EmbeddedEngine.buildRecordCommitter),
        //    so its own `synchronized` methods lock different monitors for
        //    different batches and provide no mutual exclusion across worker
        //    threads. 2.10.0 locked only the markBatchFinished() call, leaving
        //    markProcessed() outside the barrier.
        //  - 2.10.0 routes the finish through markBatchFinishedSafely(), which
        //    null-guards the committer and keeps every finish path funnelled
        //    through one helper. The helper re-acquires OFFSET_COMMIT_LOCK; that
        //    is safe because Java monitors are reentrant.
        synchronized (OFFSET_COMMIT_LOCK) {
            for (ClickHouseStruct record : batch) {
                if (record.getCommitter() != null && record.getSourceRecord() != null) {

                    record.getCommitter().markProcessed(record.getSourceRecord());

                    if (record.isLastRecordInBatch()) {
                        markBatchFinishedSafely(record.getCommitter());
                        log.info("***** BATCH marked as processed to debezium ****" + "Binlog file:" +
                                record.getFile() + " Binlog position: " + record.getPos() + " GTID: " + record.getGtid()
                                + " Sequence Number: " + record.getSequenceNumber() + " Debezium Timestamp: " + record.getDebezium_ts_ms());
                    }
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
            // Same critical section as the batch variant above — see the comment there.
            synchronized (OFFSET_COMMIT_LOCK) {
                recordCommitter.markProcessed(sourceRecord);
                if (lastRecordInBatch) {
                    // Every finish path goes through the null-guarded helper, so
                    // no caller can reach markBatchFinished() outside the lock.
                    markBatchFinishedSafely(recordCommitter);
                }
            }
        }
    }

    /**
     * Acknowledges a single record on the shared offset-commit lock.
     * <p>
     * Exposed so that every offset-committing path in the connector funnels through
     * the SAME lock. Any path that calls {@code markProcessed()} /
     * {@code markBatchFinished()} directly bypasses the serialization and can drive
     * concurrent {@code beginFlush()} calls into the non-thread-safe
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
                // Funnel every finish through the null-guarded helper (2.10.0)
                // while staying inside develop's widened critical section.
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

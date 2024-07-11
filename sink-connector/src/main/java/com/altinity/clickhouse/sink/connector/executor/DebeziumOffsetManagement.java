package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class is used to manage the state of the offsets from
 * all the different consumer threads.
 */
public class DebeziumOffsetManagement {

    // Instantiate logger
    private static final Logger log = LogManager.getLogger(DebeziumOffsetManagement.class);

    // A list of minimum , maximum timestamps of batches in flight
    static ConcurrentHashMap<Pair<Long, Long>, List<ClickHouseStruct>> inFlightBatches = new ConcurrentHashMap<>();

    static ConcurrentHashMap<Pair<Long, Long>, List<ClickHouseStruct>> completedBatches = new ConcurrentHashMap<>();

    public DebeziumOffsetManagement(ConcurrentHashMap<Pair<Long, Long>, List<ClickHouseStruct>> inFlightBatches) {
        this.inFlightBatches = inFlightBatches;
    }


    public static void addToBatchTimestamps(List<ClickHouseStruct> batch) {
        Pair<Long, Long> pair = calculateMinMaxTimestampFromBatch(batch);
        if(inFlightBatches.size() > 1000) {
            log.error("*********** Requests in Flight is greater than 1000 ***********");
        }
        inFlightBatches.put(pair, batch);
    }

    public void removeFromBatchTimestamps(Pair<Long, Long> pair) {
        inFlightBatches.remove(pair);
    }

    public Map<Pair<Long, Long>, List<ClickHouseStruct>> getBatchTimestamps() {
        return inFlightBatches;
    }

    /**
     * Function to calculate the minimum and maximum timestamps from the batch
     * @param batch
     * @return
     */
    public static Pair<Long, Long> calculateMinMaxTimestampFromBatch(List<ClickHouseStruct> batch) {

        // iterate through the batch.
        // Get the debezium min timestamp and max timestamp.
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
     * Function to check if there are inflight requests that are within the range of the
     * current batch.
     * @param batch
     */
    static boolean checkIfThereAreInflightRequests(List<ClickHouseStruct> currentBatch) {
        boolean result = false;
        Pair<Long, Long> currentBatchPair = calculateMinMaxTimestampFromBatch(currentBatch);

        //Iterate through inFlightBatches and check if there is any batch
        // which is lower than the current batch.
        for (Map.Entry<Pair<Long, Long>, List<ClickHouseStruct>> entry : inFlightBatches.entrySet()) {
            Pair<Long, Long> key = entry.getKey();

            // Ignore the same batch
            if (currentBatchPair.getLeft().longValue() == key.getLeft().longValue() &&
                    currentBatchPair.getRight().longValue() == key.getRight().longValue()) {
                continue;
            }

            // Check if max of current batch is greater than min of inflight batch
            if(currentBatchPair.getRight().longValue() > key.getLeft().longValue()) {
                result = true;
                break;
            }
        }
        return result;
    }

    static synchronized public boolean checkIfBatchCanBeCommitted(List<ClickHouseStruct> batch) throws InterruptedException {
        boolean result = false;

        if(true == checkIfThereAreInflightRequests(batch)) {
            // Remove the record from inflightBatches
            // and move it to completedBatches.
            Pair<Long, Long> pair = calculateMinMaxTimestampFromBatch(batch);
            inFlightBatches.remove(pair);
            completedBatches.put(pair, batch);
        } else {
            // Acknowledge current batch
            acknowledgeRecords(batch);
            result = true;
            // Check if completed batch can also be acknowledged.
            completedBatches.forEach((k, v) -> {
                if(false == checkIfThereAreInflightRequests(v)) {
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

    static synchronized void acknowledgeRecords(List<ClickHouseStruct> batch) throws InterruptedException {
        // Acknowledge the records.

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
                    record.getCommitter().markBatchFinished();
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
}

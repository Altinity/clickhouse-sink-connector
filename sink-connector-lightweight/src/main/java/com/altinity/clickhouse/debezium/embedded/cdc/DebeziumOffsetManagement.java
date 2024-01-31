package com.altinity.clickhouse.debezium.embedded.cdc;

import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

/**
 * This class is used to manage the state of the offsets from
 * all the different consumer threads.
 */
public class DebeziumOffsetManagement {

    // A list of minimum , maximum timestamps of batches in flight
    private List<Pair<Long, Long>> batchTimestamps;

    public DebeziumOffsetManagement(List<Pair<Long, Long>> batchTimestamps) {
        this.batchTimestamps = batchTimestamps;
    }

    public List<Pair<Long, Long>> getBatchTimestamps() {
        return batchTimestamps;
    }

    /**
     * Function to check if provided pair of timestamps is within the range of
     * the batchTimestamps
     */
    public boolean isWithinRange(Pair<Long, Long> pair) {
        for (Pair<Long, Long> batchTimestamp : batchTimestamps) {
            if (pair.getLeft() >= batchTimestamp.getLeft() && pair.getRight() <= batchTimestamp.getRight()) {
                return true;
            }
        }
        return false;
    }
}

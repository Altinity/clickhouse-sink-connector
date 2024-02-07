package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;
import java.util.Map;

/**
 * This class is used to manage the state of the offsets from
 * all the different consumer threads.
 */
public class DebeziumOffsetManagement {

    // A list of minimum , maximum timestamps of batches in flight
    private Map<Pair<Long, Long>, List<ClickHouseStruct>> batchTimestamps;

    public DebeziumOffsetManagement(Map<Pair<Long, Long>, List<ClickHouseStruct>> batchTimestamps) {
        this.batchTimestamps = batchTimestamps;
    }

    public void addToBatchTimestamps(Pair<Long, Long> pair, List<ClickHouseStruct> clickHouseStructs) {
        batchTimestamps.put(pair, clickHouseStructs);
    }

    public void removeFromBatchTimestamps(Pair<Long, Long> pair) {
        batchTimestamps.remove(pair);
    }

    public Map<Pair<Long, Long>, List<ClickHouseStruct>> getBatchTimestamps() {
        return batchTimestamps;
    }

    /**
     * Function to check if provided pair of timestamps is within the range of
     * the batchTimestamps
     */
    public boolean isWithinRange(Pair<Long, Long> pair) {
        // Iterate through all the batchTimestamps and check if the pair is within the range
        // Iterate through batchTimestamps
        for (Map.Entry<Pair<Long, Long>, List<ClickHouseStruct>> entry : batchTimestamps.entrySet()) {
        Pair<Long, Long> batchTimestamp = entry.getKey();
            if (pair.getLeft() >= batchTimestamp.getLeft() && pair.getRight() <= batchTimestamp.getRight()) {
                return true;
            }
        }
        return false;
    }
}

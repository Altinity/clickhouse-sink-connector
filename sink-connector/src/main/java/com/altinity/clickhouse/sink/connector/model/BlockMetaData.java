package com.altinity.clickhouse.sink.connector.model;

import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.tuple.MutablePair;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * BlockMetaData stores all information about a block for metrics
 * and logging purposes.
 *
 * <p>This class contains details such as partition offsets, lag times,
 * binlog information, and other metadata collected during the processing
 * of records.
 */
public class BlockMetaData {

    /**
     * A map that tracks the offset for each partition.
     * The key is typically a topic name or partition identifier,
     * and the value is a pair where the left element is the partition
     * number and the right element is the offset.
     */
    @Getter
    @Setter
    HashMap<String, MutablePair<Integer, Long>> partitionToOffsetMap =
            new HashMap<>();

    /**
     * A set that maps server IDs to thread information.
     */
    @Getter
    @Setter
    Set<String> serverIdToThreadMap;

    /**
     * A set of strings containing binlog file, binlog position, binlog row,
     * and GTID.
     */
    @Getter
    @Setter
    Set<String> bingLogList;

    /**
     * The minimum lag (in milliseconds) between the source timestamp and the
     * time the records were inserted into ClickHouse for this block.
     */
    @Getter
    @Setter
    long minSourceLag;

    /**
     * The maximum lag (in milliseconds) between the source timestamp and the
     * time the records were inserted into ClickHouse for this block.
     */
    @Getter
    @Setter
    long maxSourceLag;

    /**
     * The minimum lag (in milliseconds) between the Kafka consumer timestamp
     * and the time the records were inserted into ClickHouse.
     */
    @Getter
    @Setter
    long minConsumerLag;

    /**
     * The maximum lag (in milliseconds) between the Kafka consumer timestamp
     * and the time the records were inserted into ClickHouse.
     */
    @Getter
    @Setter
    long maxConsumerLag;

    /**
     * The current binlog position.
     */
    @Getter
    @Setter
    long binLogPosition = 0;

    /**
     * The name of the binlog file.
     */
    @Getter
    @Setter
    String binLogFile = "";

    /**
     * The transaction ID (GTID) associated with the block.
     * A value of -1 indicates that no transaction ID has been recorded.
     */
    @Getter
    @Setter
    long transactionId = -1;

    /**
     * The Kafka partition number associated with the block.
     * A value of -1 indicates that the partition has not been set.
     */
    @Getter
    @Setter
    int partition = -1;

    /**
     * The name of the Kafka topic associated with the block.
     */
    @Getter
    @Setter
    String topicName = null;

    /**
     * A map that stores the lag between the source timestamp and the time
     * the records were inserted into ClickHouse for each topic.
     */
    @Getter
    @Setter
    Map<String, Long> sourceToCHLag = new HashMap<>();

    /**
     * A map that stores the lag between the Debezium event timestamp and the
     * time the records were inserted into ClickHouse for each topic.
     */
    @Getter
    @Setter
    Map<String, Long> debeziumToCHLag = new HashMap<>();

    /**
     * The timestamp recorded when the block was inserted into ClickHouse.
     */
    long blockInsertionTimestamp = System.currentTimeMillis();

    /**
     * Updates the block metadata using the data from the given
     * {@code ClickHouseStruct} record.
     *
     * <p>This method updates the transaction ID, binlog position,
     * binlog file, Kafka partition, topic name, and computes lag values
     * based on the block insertion timestamp.
     *
     * @param record the {@code ClickHouseStruct} record containing
     *               new metadata values.
     */
    public void update(ClickHouseStruct record) {

        long gtId = record.getGtid();
        if (gtId != -1) {
            if (gtId > this.transactionId) {
                this.transactionId = gtId;
            }
        }

        if (record.getPos() != null && record.getPos() > binLogPosition) {
            this.binLogPosition = record.getPos();
        }

        if (record.getFile() != null) {
            this.binLogFile = record.getFile();
        }

        if (record.getKafkaPartition() != null) {
            this.partition = record.getKafkaPartition();
        }

        if (record.getTopic() != null) {
            this.topicName = record.getTopic();
        }

        long sourceDbLag = blockInsertionTimestamp - record.getTs_ms();
        if (minSourceLag == 0 || sourceDbLag < minSourceLag) {
            minSourceLag = sourceDbLag;
        }
        if (sourceDbLag > maxSourceLag) {
            maxSourceLag = sourceDbLag;
        }
        if (sourceToCHLag.containsKey(this.topicName)) {
            long storedSourceLag = sourceToCHLag.get(this.topicName);
            if (sourceDbLag > storedSourceLag) {
                sourceToCHLag.put(this.topicName, sourceDbLag);
            }
        } else {
            sourceToCHLag.put(this.topicName, sourceDbLag);
        }

        long debeziumLag = blockInsertionTimestamp - record.getDebezium_ts_ms();
        if (minConsumerLag == 0 || debeziumLag < minConsumerLag) {
            minConsumerLag = debeziumLag;
        }
        if (debeziumLag > maxConsumerLag) {
            maxConsumerLag = debeziumLag;
        }
        if (debeziumToCHLag.containsKey(this.topicName)) {
            long storedDebeziumLag = debeziumToCHLag.get(this.topicName);
            if (debeziumLag > storedDebeziumLag) {
                debeziumToCHLag.put(this.topicName, debeziumLag);
            }
        } else {
            debeziumToCHLag.put(this.topicName, debeziumLag);
        }

        long offset = record.getKafkaOffset();

        if (partitionToOffsetMap.containsKey(this.topicName)) {
            MutablePair<Integer, Long> mp = partitionToOffsetMap.get(this.topicName);
            if (offset >= mp.right) {
                // Update the partition offset pair.
                mp.right = offset;
                mp.left = partition;
                partitionToOffsetMap.put(topicName, mp);
            }
        } else {
            MutablePair<Integer, Long> mp = new MutablePair<>();
            mp.right = offset;
            mp.left = partition;
            partitionToOffsetMap.put(topicName, mp);
        }
    }
}

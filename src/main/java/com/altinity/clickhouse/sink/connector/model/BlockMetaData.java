package com.altinity.clickhouse.sink.connector.model;

import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.tuple.MutablePair;

import java.util.HashMap;
import java.util.Set;

/**
 * Class to store all the information about the block
 * for metrics and logging purposes.
 */
public class BlockMetaData {

    // Map of partitions to offsets.
    @Getter
    @Setter
    HashMap<String, MutablePair<Integer, Long>> partitionToOffsetMap = new HashMap<>();

    @Getter
    @Setter
    Set<String> serverIdToThreadMap;

    // List of binlog file, binlog position, binlog row, gtid
    @Getter
    @Setter
    Set<String> bingLogList;

    // Lag between the source timestamp and the time the records
    // were inserted to ClickHouse.(Min value of Block).
    @Getter
    @Setter
    long minSourceLag;

    // Lag between the source timestamp and the time the records
    // were inserted to ClickHouse.(Max value of Block).
    @Getter
    @Setter
    long maxSourceLag;

    // Lag between the Kafka consumer and the time the records
    // were inserted to ClickHouse.(Min value of Block).
    @Getter
    @Setter
    long minConsumerLag;

    // Lag between the Kafka consumer and the time the records
    // were inserted to ClickHouse.(Max value of Block).
    @Getter
    @Setter
    long maxConsumerLag;

    @Getter
    @Setter
    long binLogPosition = -1;


    @Getter
    @Setter
    int transactionId = -1;

    @Getter
    @Setter
    int partition = -1;


    @Getter
    @Setter
    String topicName = null;

    public void update(ClickHouseStruct record) {

        int gtId = record.getGtid();
        if (gtId != -1) {
            if (gtId > this.transactionId) {
                this.transactionId = gtId;
            }
        }
        if (record.getPos() != null && record.getPos() > binLogPosition) {
            this.binLogPosition = record.getPos();
        }

        if(record.getKafkaPartition() != null) {
            this.partition = record.getKafkaPartition();
        }

        long offset = record.getKafkaOffset();

        if(record.getTopic() != null) {
            this.topicName = record.getTopic();
        }

        if (partitionToOffsetMap.containsKey(this.topicName)) {
            MutablePair<Integer, Long> mp = partitionToOffsetMap.get(this.topicName);
            if (offset >= mp.right) {
                // Update ap.
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

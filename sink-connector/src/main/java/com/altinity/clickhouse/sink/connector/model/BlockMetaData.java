package com.altinity.clickhouse.sink.connector.model;

import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.tuple.MutablePair;

import java.util.HashMap;
import java.util.Map;
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
    long binLogPosition = 0;

    @Getter
    @Setter
    String binLogFile = "";


    @Getter
    @Setter
    long transactionId = -1;

    @Getter
    @Setter
    int partition = -1;


    @Getter
    @Setter
    String topicName = null;

    // The time when the block is persisted to clickhouse
    // and binlog timestamp
    @Getter
    @Setter
    Map<String, Long> sourceToCHLag = new HashMap();

    @Getter
    @Setter
    Map<String, Long> debeziumToCHLag = new HashMap();


    // Timestamp recorded when the block was written;
    long blockInsertionTimestamp = System.currentTimeMillis();

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

        if(record.getKafkaPartition() != null) {
            this.partition = record.getKafkaPartition();
        }

        if(record.getTopic() != null) {
            this.topicName = record.getTopic();
        }

        long sourceDbLag = blockInsertionTimestamp - record.getTs_ms();
        if(sourceToCHLag.containsKey(this.topicName)) {
            long storedSourceLag = sourceToCHLag.get(this.topicName);
            if(sourceDbLag > storedSourceLag) {
                sourceToCHLag.put(this.topicName, sourceDbLag);
            }
        } else {
            sourceToCHLag.put(this.topicName, sourceDbLag);
        }

        long debeziumLag = blockInsertionTimestamp - record.getDebezium_ts_ms();
        if(debeziumToCHLag.containsKey(this.topicName)) {
            long storedDebeziumLag = debeziumToCHLag.get(this.topicName);
            if(debeziumLag > storedDebeziumLag) {
                debeziumToCHLag.put(this.topicName, debeziumLag);
            }
        } else {
            debeziumToCHLag.put(this.topicName, debeziumLag);
        }

        long offset = record.getKafkaOffset();

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

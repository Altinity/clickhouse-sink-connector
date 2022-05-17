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
    HashMap<Integer, MutablePair<Long, Long>> partitionToOffsetMap;

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


}

package com.altinity.clickhouse.sink.connector.model;

import lombok.Getter;
import lombok.Setter;
import org.apache.kafka.connect.data.Struct;

/**
 * Class that wraps the Kafka Connect Struct
 * with extra kafka related metadata.
 * (Mostly from SinkRecord.
 */
public class ClickHouseStruct {

    @Getter
    @Setter
    private long kafkaOffset;
    @Getter
    @Setter
    private String topic;
    @Getter
    @Setter
    private Integer kafkaPartition;
    @Getter
    @Setter
    private Long timestamp;

    @Getter
    @Setter
    private String key;

    // Inheritance doesn't work because of different package
    // error, composition.
    @Getter
    @Setter
    Struct struct;


    public ClickHouseStruct(long kafkaOffset, String topic, Struct key, Integer kafkaPartition, Long timestamp) {

        this.kafkaOffset = kafkaOffset;
        this.topic = topic;
        this.kafkaPartition = kafkaPartition;
        this.timestamp = timestamp;
        this.key = key.toString();
    }
}

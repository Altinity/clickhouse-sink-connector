package com.altinity.clickhouse.sink.connector.model;

import lombok.Getter;
import lombok.Setter;

/**
 * Class to store kafka offset information
 */
public class KafkaOffset {

    @Getter
    @Setter
    private String topic;

    @Getter
    @Setter
    private long offset;

    @Getter
    @Setter
    private int partition;

    public KafkaOffset(long offset, int partition, String topic) {
        this.offset = offset;
        this.partition = partition;
        this.topic = topic;
    }
}

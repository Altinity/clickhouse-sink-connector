package com.altinity.clickhouse.sink.connector.model;

import lombok.Getter;
import lombok.Setter;

/**
 * Class to store Kafka offset information.
 * <p>
 * This class holds the offset, partition, and topic details for
 * Kafka messages.
 * </p>
 */
public class KafkaOffset {

    /**
     * The Kafka topic.
     */
    @Getter
    @Setter
    private String topic;

    /**
     * The offset of the message in Kafka.
     */
    @Getter
    @Setter
    private long offset;

    /**
     * The partition number of the Kafka topic.
     */
    @Getter
    @Setter
    private int partition;

    /**
     * Constructs a new KafkaOffset with the given offset,
     * partition, and topic.
     *
     * @param offset    the offset of the message.
     * @param partition the partition number.
     * @param topic     the Kafka topic name.
     */
    public KafkaOffset(long offset, int partition, String topic) {
        this.offset = offset;
        this.partition = partition;
        this.topic = topic;
    }
}

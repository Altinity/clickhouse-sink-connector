package com.altinity.clickhouse.sink.connector.model;

import lombok.Getter;

/**
 * Additional Kafka metadata columns that are stored in
 * Clickhouse tables
 */
public enum KafkaMetaData {
    OFFSET("kafkaOffset"),
    TOPIC("topic"),
    PARTITION("kafkaPartition"),
    TIMESTAMP("timestamp");

    @Getter
    private String column;

    KafkaMetaData(String column) {
        this.column = column;
    }

}

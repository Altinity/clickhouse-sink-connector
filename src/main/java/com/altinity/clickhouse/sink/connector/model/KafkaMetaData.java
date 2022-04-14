package com.altinity.clickhouse.sink.connector.model;

import lombok.Getter;

/**
 * Additional Kafka metadata columns that are stored in
 * Clickhouse tables
 */
public enum KafkaMetaData {

    // Kafka Topic
    TOPIC("_topic"),

    // Key of the message
    KEY("_key"),

    //Offset of the message
    OFFSET("_offset"),

    //Timestamp of the message
    TIMESTAMP("_timestamp"),

    //Timestamp in milliseconds of the message.
    TIMESTAMP_MS("_timestamp_ms"),

    PARTITION("_partition");

    @Getter
    private String column;

    KafkaMetaData(String column) {
        this.column = column;
    }

}

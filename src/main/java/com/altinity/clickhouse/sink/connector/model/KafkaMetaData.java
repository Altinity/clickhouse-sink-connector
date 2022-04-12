package com.altinity.clickhouse.sink.connector.model;

public enum KafkaMetaData {
    OFFSET("kafkaOffset"),
    TOPIC("topic"),
    PARTITION("kafkaPartition"),
    TIMESTAMP("timestamp");

    private String column;

    KafkaMetaData(String column) {
        this.column = column;
    }

}

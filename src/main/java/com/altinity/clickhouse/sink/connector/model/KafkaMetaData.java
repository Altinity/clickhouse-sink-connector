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

    // Offset of the message
    OFFSET("_offset"),

    // Timestamp of the message
    TIMESTAMP("_timestamp"),

    // Timestamp in milliseconds of the message.
    TIMESTAMP_MS("_timestamp_ms"),

    PARTITION("_partition"),

    // In the source object, ts_ms indicates the time that the change was made in the database.
    // By comparing the value for payload.source.ts_ms with the value for payload.ts_ms,
    // you can determine the lag between the source database update and Debezium.
    TS_MS("_ts_ms"),

    // Source Server ID.
    SERVER_ID("_server_id"),

    // GT ID(This needs to be enabled in the source(MySQL or PostgreSQL)
    GTID("_gtid"),

    // Source bin log file name, Example(mysql-bin-0000003)
    BINLOG_FILE("_binlog_file"),

    // Source bin log position
    BINLOG_POSITION("_binlog_pos"),

    // Source bin log row
    BINLOG_ROW("_binlog_row"),

    // Source Server Thread(Example MySQL Server thread
    SERVER_THREAD("_server_thread");

    @Getter
    private String column;

    KafkaMetaData(String column) {
        this.column = column;
    }
}

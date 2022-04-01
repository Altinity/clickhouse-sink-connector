package com.altinity.clickhouse.sink.connector.metadata;


/**
 * Enum class for Kafka schema or record type, either value or key.
 */
public enum KafkaSchemaRecordType {

    VALUE("value"),
    KEY("key");

    private final String str;

    /**
     * Constructor
     *
     * @param str string value
     */
    KafkaSchemaRecordType(String str) {
        this.str = str;
    }

    /**
     * @return
     */
    public String toString() {
        return this.str;
    }
}

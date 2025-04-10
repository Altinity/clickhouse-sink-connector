package com.altinity.clickhouse.sink.connector.metadata;

/**
 * Enum class for Kafka schema or record type,
 * either value or key.
 */
public enum KafkaSchemaRecordType {

    /**
     * Represents the value part of the Kafka record.
     */
    VALUE("value"),

    /**
     * Represents the key part of the Kafka record.
     */
    KEY("key");

    /**
     * String representation of the enum.
     */
    private final String str;

    /**
     * Constructor.
     *
     * @param str string value for the record type.
     */
    KafkaSchemaRecordType(String str) {
        this.str = str;
    }

    /**
     * Returns the string representation of the enum.
     *
     * @return the string value.
     */
    @Override
    public String toString() {
        return this.str;
    }
}

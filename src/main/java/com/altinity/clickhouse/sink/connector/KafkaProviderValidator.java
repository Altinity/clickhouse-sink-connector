package com.altinity.clickhouse.sink.connector;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;

/**
 * KafkaProviderValidator validates KafkaProvider enum values
 */
public class KafkaProviderValidator implements ConfigDef.Validator {

    public static final String PROVIDER_CONFIG = "provider";

    public KafkaProviderValidator() {
    }

    /**
     * ensureValid is called by framework to ensure the validity
     * 1. when connector is started or
     * 2. when validate REST API is called
     *
     * @param name
     * @param value
     */
    @Override
    public void ensureValid(String name, Object value) {
        assert value instanceof String;
        final String strValue = (String) value;
        // The value can be null or empty.
        try {
            KafkaProvider kafkaProvider = KafkaProvider.of(strValue);
        } catch (final IllegalArgumentException e) {
            throw new ConfigException(PROVIDER_CONFIG, value, e.getMessage());
        }
    }

    public String toString() {
        return "Whether kafka is running on Confluent code, self hosted or other managed service."
                + " Allowed values are:"
                + String.join(",", KafkaProvider.PROVIDER_NAMES);
    }
}
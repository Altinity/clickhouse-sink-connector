package com.altinity.clickhouse.sink.connector.validators;

import com.altinity.clickhouse.sink.connector.KafkaProvider;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;

/**
 * KafkaProviderValidator validates KafkaProvider enum values.
 * <p>
 * This validator checks that the provided value is a valid Kafka
 * provider, ensuring that it corresponds to one of the allowed enum
 * values defined in KafkaProvider.
 * </p>
 */
public class KafkaProviderValidator implements ConfigDef.Validator {

    /**
     * The configuration key for the provider.
     */
    public static final String PROVIDER_CONFIG = "provider";

    /**
     * Constructs a new KafkaProviderValidator.
     */
    public KafkaProviderValidator() {
    }

    /**
     * Validates the given value for the configuration parameter.
     * <p>
     * This method is called by the framework during connector startup
     * or when the validate REST API is invoked. It ensures that the
     * value is a valid KafkaProvider.
     * </p>
     *
     * @param name  the configuration name.
     * @param value the configuration value.
     * @throws ConfigException if the value is invalid.
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

    /**
     * Returns a description of this validator.
     * <p>
     * The description includes information on whether Kafka is running
     * on Confluent code, self-hosted, or via another managed service,
     * as well as the allowed values.
     * </p>
     *
     * @return a string representation of the validator.
     */
    @Override
    public String toString() {
        return "Whether kafka is running on Confluent code, self hosted or " +
                "other managed service. Allowed values are:" +
                String.join(",", KafkaProvider.PROVIDER_NAMES);
    }
}

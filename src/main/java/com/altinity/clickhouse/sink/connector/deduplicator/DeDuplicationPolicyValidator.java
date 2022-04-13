package com.altinity.clickhouse.sink.connector.deduplicator;

import com.altinity.clickhouse.sink.connector.KafkaProvider;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;

/**
 * DeDuplicationPolicyValidator validates DeDuplicationPolicy enum values
 */
public class DeDuplicationPolicyValidator implements ConfigDef.Validator {

    /**
     * Default constructor
     */
    public DeDuplicationPolicyValidator() {
    }

    /**
     * ensureValid is called by framework to ensure the validity
     * 1. when connector is started or
     * 2. when validate REST API is called
     *
     * @param name  name of the property
     * @param value value of the property
     * @throws ConfigException in case property is not valid
     */
    @Override
    public void ensureValid(String name, Object value) {
        // Sanity check for the value type
        assert value instanceof String;
        final String strValue = (String) value;

        // The value can be null or empty and it is not an error
        try {
            DeDuplicationPolicy policy = DeDuplicationPolicy.of(strValue);
        } catch (final IllegalArgumentException e) {
            throw new ConfigException(DeDuplicationPolicyValidator.class.getName(), value, e.getMessage());
        }
    }

    /**
     * String representation of the validator
     *
     * @return string representation
     */
    public String toString() {
        return "What DeDuplication policy is used."
                + " Allowed values are:"
                + String.join(",", DeDuplicationPolicy.POLICY_NAMES);
    }
}

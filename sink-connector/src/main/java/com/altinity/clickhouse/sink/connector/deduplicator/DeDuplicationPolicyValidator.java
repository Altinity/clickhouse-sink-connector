package com.altinity.clickhouse.sink.connector.deduplicator;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;

/**
 * DeDuplicationPolicyValidator validates DeDuplicationPolicy enum values.
 * <p>
 * This validator ensures that the configured value for the de-duplication
 * policy is one of the valid {@link DeDuplicationPolicy} values.
 * If the provided string does not match a known policy, it throws
 * a {@link ConfigException}.
 * </p>
 */
public class DeDuplicationPolicyValidator implements ConfigDef.Validator {

    /**
     * Default constructor for DeDuplicationPolicyValidator.
     */
    public DeDuplicationPolicyValidator() {
    }

    /**
     * Ensures that the given value is valid for this validator.
     * <p>
     * 1. This method is invoked when the connector is started.
     * 2. It is also invoked when the validate REST API is called.
     * </p>
     *
     * @param name  The name of the property.
     * @param value The value of the property. It may be null or empty,
     *              in which case it will fall back to the default value.
     * @throws ConfigException If the property is invalid.
     */
    @Override
    public void ensureValid(String name, Object value) {
        // Sanity check for the value type
        assert value instanceof String;
        final String strValue = (String) value;

        // The value can be null or empty - it is not an error,
        // but fallback to default value
        try {
            DeDuplicationPolicy policy = DeDuplicationPolicy.of(strValue);
        } catch (final IllegalArgumentException e) {
            throw new ConfigException(
                    DeDuplicationPolicyValidator.class.getName(),
                    value,
                    e.getMessage()
            );
        }
    }

    /**
     * Returns a string representation of the validator.
     *
     * @return A description of the valid DeDuplicationPolicy values.
     */
    public String toString() {
        return "What DeDuplication policy is used. Allowed values are: "
                + String.join(",", DeDuplicationPolicy.POLICY_NAMES);
    }
}

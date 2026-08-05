package com.altinity.clickhouse.sink.connector.validators;

import com.altinity.clickhouse.sink.connector.common.Utils;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;

/**
 * The TopicToTableValidator class is responsible for validating the topic-to-table mapping configuration
 * for the Kafka sink connector. It ensures that the format of the configuration follows the expected
 * format of comma-separated tuples in the form of <topic-1>:<table-1>,<topic-2>:<table-2>,...
 * <p>
 * This validator is used when the connector is started or when the validate REST API is called to check
 * if the configuration is valid.
 * </p>
 */
public class TopicToTableValidator implements ConfigDef.Validator {

    /**
     * Default constructor for the TopicToTableValidator class.
     */
    public TopicToTableValidator() {
    }

    /**
     * Ensures the validity of the topic-to-table mapping configuration.
     * <p>
     * This method is called by the framework when:
     * 1. The connector is started.
     * 2. The validate REST API is called.
     * </p>
     * <p>
     * It checks if the provided value (topic-to-table map) is in the expected format.
     * If the format is invalid, a ConfigException is thrown.
     * </p>
     *
     * @param name the name of the configuration being validated.
     * @param value the value to be validated.
     * @throws ConfigException if the value does not match the expected format.
     */
    public void ensureValid(String name, Object value) {
        String s = (String) value;

        // If the value is null or empty, it's considered valid
        if (s == null || s.isEmpty()) {
            return;
        }

        try {
            // Try to parse the topic-to-table map using the utility method
            if (Utils.parseTopicToTableMap(s) == null) {
                throw new ConfigException(name, value,
                        "Format: <topic-1>:<table-1>,<topic-2>:<table-2>,...");
            }
        } catch (Exception e) {
            throw new ConfigException(name, value,
                    "Format: <topic-1>:<table-1>,<topic-2>:<table-2>,...  Error: " + e.getMessage());
        }
    }

    /**
     * Returns a description of the expected format for the topic-to-table map.
     *
     * @return a string description of the expected format for the topic-to-table map.
     */
    public String toString() {
        return "Topic to table map format : comma-separated tuples, e.g."
                + " <topic-1>:<table-1>,<topic-2>:<table-2>,... ";
    }
}

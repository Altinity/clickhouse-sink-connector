package com.altinity.clickhouse.sink.connector.validators;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;

/**
 * Refuses {@code clickhouse.jdbc.v1=true} when the connector configuration is
 * parsed, so the connector fails at start instead of stalling on the first batch.
 * <p>
 * The legacy ClickHouse JDBC V1 driver cannot bind the epoch-text datetime
 * values the connector writes: every batch with a datetime column fails with
 * {@code DateTimeParseException} in {@code PreparedStatementExecutor.executePreparedStatement}
 * and the task retries it forever. No production configuration sets the flag.
 * The V2 driver is the only supported driver. Spec 03.08.
 * </p>
 */
public class JdbcV1DriverValidator implements ConfigDef.Validator {

    /** The message appended to the Kafka ConfigException. */
    public static final String REFUSAL_MESSAGE =
            "the legacy ClickHouse JDBC V1 driver is not supported: it cannot bind the "
                    + "datetime values the connector writes (DateTimeParseException on epoch "
                    + "text), so every batch with a datetime column stalls. Remove "
                    + "clickhouse.jdbc.v1 (the V2 driver is the default) and restart.";

    /**
     * Constructs a new JdbcV1DriverValidator.
     */
    public JdbcV1DriverValidator() {
    }

    /**
     * Accepts an unset or false value and refuses true.
     *
     * @param name  the configuration name.
     * @param value the parsed boolean value.
     * @throws ConfigException when the value is true.
     */
    @Override
    public void ensureValid(String name, Object value) {
        if (Boolean.TRUE.equals(value)) {
            throw new ConfigException(name, value, REFUSAL_MESSAGE);
        }
    }

    /**
     * Returns a description of this validator.
     *
     * @return a string describing the refusal.
     */
    @Override
    public String toString() {
        return "clickhouse.jdbc.v1 must be false or unset (the V1 driver is refused)";
    }
}

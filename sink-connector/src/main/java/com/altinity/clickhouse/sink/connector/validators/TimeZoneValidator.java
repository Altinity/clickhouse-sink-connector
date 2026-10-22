package com.altinity.clickhouse.sink.connector.validators;

import com.altinity.clickhouse.sink.connector.common.TimeZoneResolver;
import com.altinity.clickhouse.sink.connector.common.TimeZoneResolver.InvalidTimeZoneException;
import com.altinity.clickhouse.sink.connector.common.TimeZoneResolver.Kind;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;

import java.util.Map;

/**
 * Validates a timezone property with {@link TimeZoneResolver} (spec 07.03 section 3.1.2).
 * Both zone properties are mandatory, so an unset value (absent, empty, blank or null) is refused with a message
 * that names the key and a suggested value.
 */
public class TimeZoneValidator implements ConfigDef.Validator {

    private final Kind kind;

    public TimeZoneValidator(Kind kind) {
        this.kind = kind;
    }

    @Override
    public void ensureValid(String name, Object value) {
        String raw = value == null ? null : String.valueOf(value);
        try {
            TimeZoneResolver.resolve(name, raw, kind);
        } catch (InvalidTimeZoneException e) {
            throw toConfigException(e, value);
        }
    }

    /**
     * Runs the raw-map checks (misspelled keys, untrimmed values, unset mandatory keys) and reports them as a
     * ConfigException.
     */
    public static void validateRawProperties(Map<?, ?> properties) {
        try {
            TimeZoneResolver.rejectMisspelledKeys(properties);
            TimeZoneResolver.rejectUntrimmed(properties);
            TimeZoneResolver.requireConfigured(properties);
        } catch (InvalidTimeZoneException e) {
            throw toConfigException(e, e.getRawValue());
        }
    }

    private static ConfigException toConfigException(InvalidTimeZoneException e, Object value) {
        if (TimeZoneResolver.isUnset(e.getRawValue()) || value == null || TimeZoneResolver.isUnset(String.valueOf(value))) {
            return new ConfigException(e.getMessage());
        }
        return new ConfigException(e.getProperty(), value, e.getReason());
    }
}

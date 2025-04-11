package com.altinity.clickhouse.sink.connector.validators;

import com.altinity.clickhouse.sink.connector.common.Utils;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;

/**
 * A validator that ensures the format of a database override map is correct.
 * <p>
 * The expected format is:
 * {@code <src_database-1>:<dest_database-1>,
 *  <src_database-2>:<dest_database-2>, ... }
 * </p>
 * An empty or null value is considered valid because the override is optional.
 */
public class DatabaseOverrideValidator implements ConfigDef.Validator {

    @Override
    public void ensureValid(String name, Object value) {
        String s = (String) value;
        if (s == null || s.isEmpty()) {
            // Value is optional and therefore empty is valid
            return;
        }
        try {
            if (Utils.parseSourceToDestinationDatabaseMap(s) == null) {
                throw new ConfigException(
                        name,
                        value,
                        "Format: <src_database-1>:<destination_database-1>,"
                                + "<src_database-2>:<destination_database-2>,..."
                );
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

package com.altinity.clickhouse.sink.connector.validators;

import com.altinity.clickhouse.sink.connector.common.Utils;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;

public class DatabaseOverrideValidator implements ConfigDef.Validator {
    @Override

        public void ensureValid(String name, Object value) {
            String s = (String) value;
            if (s == null || s.isEmpty()) {
                // Value is optional and therefore empy is pretty valid
                return;
            }
            try {
                if (Utils.parseSourceToDestinationDatabaseMap(s) == null) {
                    throw new ConfigException(name, value, "Format: <src_database-1>:<destination_database-1>,<src_database-2>:<destination_database-2>,...");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

}

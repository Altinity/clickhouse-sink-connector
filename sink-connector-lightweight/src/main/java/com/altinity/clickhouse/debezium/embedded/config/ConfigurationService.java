package com.altinity.clickhouse.debezium.embedded.config;

import java.util.Properties;

/**
 * A service interface that defines a method to parse and return a
 * configuration as {@link Properties}.
 */
public interface ConfigurationService {

    /**
     * Parses the configuration source and returns a {@link Properties}
     * object containing the configuration key-value pairs.
     *
     * @return A {@link Properties} object holding configuration values.
     * @throws Exception If any error occurs during parsing.
     */
    Properties parse() throws Exception;
}

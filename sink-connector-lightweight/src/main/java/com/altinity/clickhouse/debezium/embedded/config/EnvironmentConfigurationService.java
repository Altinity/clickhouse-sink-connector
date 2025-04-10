package com.altinity.clickhouse.debezium.embedded.config;

import com.altinity.clickhouse.debezium.embedded.common.PropertiesHelper;
import com.google.inject.Singleton;

import java.util.Map;
import java.util.Properties;

/**
 * Configuration service that loads environment and default properties.
 * <p>
 * This service loads configuration from a default properties file and
 * environment variables. Environment variables override the default
 * configuration.
 * </p>
 */
@Singleton
public class EnvironmentConfigurationService implements ConfigurationService {

    /**
     * Parses and returns the merged configuration properties.
     * <p>
     * This method first loads the default properties from the
     * "config.properties" file, then loads the environment variables.
     * Environment variables override the default configuration.
     * </p>
     *
     * @return The merged configuration properties.
     * @throws Exception If an error occurs during properties loading.
     */
    @Override
    public Properties parse() throws Exception {

        Properties resultProperties = new Properties();
        // Environment variables should override the default config.
        Map<String, String> environmentVariables = System.getenv();
        Properties environmentProperties = new Properties();
        environmentProperties.putAll(environmentVariables);

        Properties defaultProperties =
                PropertiesHelper.getProperties("config.properties");

        resultProperties.putAll(defaultProperties);
        resultProperties.putAll(environmentProperties);

        return resultProperties;
    }
}

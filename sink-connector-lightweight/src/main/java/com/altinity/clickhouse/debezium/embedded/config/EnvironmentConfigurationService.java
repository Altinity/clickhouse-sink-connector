package com.altinity.clickhouse.debezium.embedded.config;

import com.altinity.clickhouse.debezium.embedded.common.PropertiesHelper;
import com.google.inject.Singleton;

import java.util.Map;
import java.util.Properties;

@Singleton
public class EnvironmentConfigurationService implements ConfigurationService {

    @Override
    public Properties parse() throws Exception {


        Properties resultProperties = new Properties();
        // Environment variables should override the default config.
        Map<String, String> environmentVariables = System.getenv();
        Properties environmentProperties = new Properties();
        environmentProperties.putAll(environmentVariables);

        Properties defaultProperties = PropertiesHelper.getProperties("config.properties");

        resultProperties.putAll(defaultProperties);
        resultProperties.putAll(environmentProperties);

        return resultProperties;

    }
}

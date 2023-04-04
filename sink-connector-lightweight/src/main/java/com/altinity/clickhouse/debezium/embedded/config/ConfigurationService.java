package com.altinity.clickhouse.debezium.embedded.config;

import java.util.Properties;

public interface ConfigurationService {
    Properties parse() throws Exception;
}

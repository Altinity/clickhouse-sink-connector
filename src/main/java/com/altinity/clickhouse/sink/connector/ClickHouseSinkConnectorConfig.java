package com.altinity.clickhouse.sink.connector;


import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.common.config.ConfigDef.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class for defining the configuration 
 * for the connector.
 * 
 * https://www.confluent.io/blog/write-a-kafka-connect-connector-with-configuration-handling/?_ga=2.60332132.837662403.1644687538-770780523.1642652755
 */
public class ClickHouseSinkConnectorConfig {
    
    // ClickHouse connection
    private static final String CLICKHOUSE_LOGIN_INFO = "ClickHouse Login Info";
    private static final String CONNECTOR_CONFIG = "Connector Config";

    private static final String PROVIDER_CONFIG = "provider";

    private static final Logger LOGGER = LoggerFactory.getLogger(ClickHouseSinkConnectorConfig.class);

    static ConfigDef newConfigDef() {
        return new ConfigDef()
            // ClickHouse login info
            .define(
                ClickHouseConfigurationVariables.CLICKHOUSE_URL,
                Type.STRING,
                null,
                new ConfigDef.NonEmptyString(),
                Importance.HIGH,
                "ClickHouse account url",
                CLICKHOUSE_LOGIN_INFO,
                0,
                ConfigDef.Width.NONE,
                ClickHouseConfigurationVariables.CLICKHOUSE_URL)
            .define(
                ClickHouseConfigurationVariables.CLICKHOUSE_USER,
                Type.STRING,
                null,
                new ConfigDef.NonEmptyString(),
                Importance.HIGH,
                "ClickHouse user name",
                CLICKHOUSE_LOGIN_INFO,
                1,
                ConfigDef.Width.NONE,
                ClickHouseConfigurationVariables.CLICKHOUSE_USER)
            .define(
                ClickHouseConfigurationVariables.CLICKHOUSE_PASS,
                Type.STRING,
                null,
                new ConfigDef.NonEmptyString(),
                Importance.HIGH,
                "ClickHouse password",
                CLICKHOUSE_LOGIN_INFO,
                1,
                ConfigDef.Width.NONE,
                ClickHouseConfigurationVariables.CLICKHOUSE_PASS)
            .define(
                ClickHouseConfigurationVariables.CLICKHOUSE_DATABASE,
                Type.STRING,
                null,
                new ConfigDef.NonEmptyString(),
                Importance.HIGH,
                "ClickHouse database name",
                CLICKHOUSE_LOGIN_INFO,
                4,
                ConfigDef.Width.NONE,
                ClickHouseConfigurationVariables.CLICKHOUSE_DATABASE)
            // ToDo: Add JVM Proxy 
            // Connector Config
            .define(
                ClickHouseConfigurationVariables.CLICKHOUSE_TOPICS_TABLES_MAP,
                Type.STRING,
                "",
                new TopicToTableValidator(),
                Importance.LOW,
                "Map of topics to tables (optional). Format : comma-separated tuples, e.g."
                    + " <topic-1>:<table-1>,<topic-2>:<table-2>,... ",
                CONNECTOR_CONFIG,
                0,
                ConfigDef.Width.NONE,
                ClickHouseConfigurationVariables.CLICKHOUSE_TOPICS_TABLES_MAP)
            .define(
                PROVIDER_CONFIG,
                Type.STRING,
                KafkaProvider.UNKNOWN.name(),
                new KafkaProviderValidator(),
                Importance.LOW,
                "Whether kafka is running on Confluent code, self hosted or other managed service");
      }
}

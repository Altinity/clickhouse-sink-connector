package com.altinity.clickhouse.sink.connector;


import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.common.config.ConfigDef.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Class for defining the configuration 
 * for the connector.
 * 
 * https://www.confluent.io/blog/write-a-kafka-connect-connector-with-configuration-handling/?_ga=2.60332132.837662403.1644687538-770780523.1642652755
 */
public class ClickHouseSinkConnectorConfig {
    static final String NAME = Const.NAME;
    public static final String TOPICS = "topics";

    private static final Logger log = LoggerFactory.getLogger(ClickHouseSinkConnectorConfig.class.getName());

    // ClickHouse connection
    private static final String CONFIG_GROUP_CLICKHOUSE_LOGIN_INFO = "ClickHouse Login Info";
    private static final String CONFIG_GROUP_CONNECTOR_CONFIG = "Connector Config";


    private static final Logger LOGGER = LoggerFactory.getLogger(ClickHouseSinkConnectorConfig.class);

    /**
     *
     * @param config
     */
    public static void setDefaultValues(Map<String, String> config) {
        setFieldToDefaultValues(config, ClickHouseSinkConnectorConfigVariables.BUFFER_COUNT, ClickHouseSinkConnectorConfigVariables.BUFFER_COUNT_DEFAULT);
    }

    /**
     *
     * @param config
     * @param field
     * @param value
     */
    static void setFieldToDefaultValues(Map<String, String> config, String field, Long value) {
        if (!config.containsKey(field)) {
            config.put(field, "" + value);
            log.info("setFieldToDefaultValues(){}={}", field, value);
        }
    }

    /**
     *
     * @param config
     * @param key
     * @return
     */
    static String getProperty(final Map<String, String> config, final String key) {
        if (config.containsKey(key) && !config.get(key).isEmpty()) {
            return config.get(key);
        } else {
            return null;
        }
    }

    /**
     *
     * @return
     */
    static ConfigDef newConfigDef() {
        return new ConfigDef()
                    // Config Group "Connector config"
                    .define(
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_TOPICS_TABLES_MAP,
                        Type.STRING,
                        "",
                        new TopicToTableValidator(),
                        Importance.LOW,
                        "Map of topics to tables (optional). Format : comma-separated tuples, e.g."
                                + " <topic-1>:<table-1>,<topic-2>:<table-2>,... ",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        0,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_TOPICS_TABLES_MAP)
                    .define(
                        ClickHouseSinkConnectorConfigVariables.BUFFER_COUNT,
                        Type.LONG,
                        ClickHouseSinkConnectorConfigVariables.BUFFER_COUNT_DEFAULT,
                        ConfigDef.Range.atLeast(1),
                        Importance.LOW,
                        "BufCount",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        1,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.BUFFER_COUNT)
                    .define(
                        ClickHouseSinkConnectorConfigVariables.PROVIDER_CONFIG,
                        Type.STRING,
                        KafkaProvider.UNKNOWN.name(),
                        new KafkaProviderValidator(),
                        Importance.LOW,
                        "Whether kafka is running on Confluent code, self hosted or other managed service",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        2,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.PROVIDER_CONFIG)

                    // Config Group "ClickHouse login info"
                    .define(
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_URL,
                        Type.STRING,
                        null,
                        new ConfigDef.NonEmptyString(),
                        Importance.HIGH,
                        "ClickHouse account url",
                        CONFIG_GROUP_CLICKHOUSE_LOGIN_INFO,
                        0,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_URL)
                    .define(
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_USER,
                        Type.STRING,
                        null,
                        new ConfigDef.NonEmptyString(),
                        Importance.HIGH,
                        "ClickHouse user name",
                        CONFIG_GROUP_CLICKHOUSE_LOGIN_INFO,
                        1,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_USER)
                    .define(
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_PASS,
                        Type.STRING,
                        null,
                        new ConfigDef.NonEmptyString(),
                        Importance.HIGH,
                        "ClickHouse password",
                        CONFIG_GROUP_CLICKHOUSE_LOGIN_INFO,
                        2,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_PASS)
                    .define(
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATABASE,
                        Type.STRING,
                        null,
                        new ConfigDef.NonEmptyString(),
                        Importance.HIGH,
                        "ClickHouse database name",
                        CONFIG_GROUP_CLICKHOUSE_LOGIN_INFO,
                        3,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATABASE)
                        // ToDo: Add JVM Proxy
                ;
      }
}

package com.altinity.clickhouse.sink.connector;


import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.common.config.ConfigDef.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Connector configuration definition.
 * <p>
 * https://www.confluent.io/blog/write-a-kafka-connect-connector-with-configuration-handling/?_ga=2.60332132.837662403.1644687538-770780523.1642652755
 */
public class ClickHouseSinkConnectorConfig extends AbstractConfig {
    private static final Logger log = LoggerFactory.getLogger(ClickHouseSinkConnectorConfig.class.getName());

    // Configuration groups

    // Configuration group "clickhouse login info"
    private static final String CONFIG_GROUP_CLICKHOUSE_LOGIN_INFO = "ClickHouse Login Info";
    // Configuration group "connector config"
    private static final String CONFIG_GROUP_CONNECTOR_CONFIG = "Connector Config";
    // Configuration group "de-duplicator config"
    private static final String CONFIG_GROUP_DE_DUPLICATOR_CONFIG = "DeDuplicator Config";
    // Configuration group "task config"
    private static final String CONFIG_GROUP_TASK_CONFIG = "Task Config";

    public ClickHouseSinkConnectorConfig(Map<String, String> properties) {
        this(newConfigDef(), properties);
    }

    protected ClickHouseSinkConnectorConfig(ConfigDef config, Map<String, String> properties) {
        super(config, properties);
    }

    /**
     * Set default values for config
     *
     * @param config
     */
    public static void setDefaultValues(Map<String, String> config) {
        setFieldToDefaultValue(config, ClickHouseSinkConnectorConfigVariables.BUFFER_COUNT, ClickHouseSinkConnectorConfigVariables.BUFFER_COUNT_DEFAULT);
    }

    /**
     * Set one default value
     *
     * @param config
     * @param key
     * @param value
     */
    static void setFieldToDefaultValue(Map<String, String> config, String key, Long value) {
        if (config.containsKey(key)) {
            // Value already specified
            return;
        }

        // No value specified, set default one
        config.put(key, "" + value);
        log.info("setFieldToDefaultValues(){}={}", key, value);
    }

    /**
     * @param config
     * @param key
     * @return
     */
    public static String getProperty(final Map<String, String> config, final String key) {
        if (config.containsKey(key) && !config.get(key).isEmpty()) {
            return config.get(key);
        } else {
            return null;
        }
    }

    /**
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
                        CONFIG_GROUP_DE_DUPLICATOR_CONFIG,
                        1,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.BUFFER_COUNT)
                .define(
                        ClickHouseSinkConnectorConfigVariables.TASK_ID,
                        Type.LONG,
                        0,
                        ConfigDef.Range.atLeast(0),
                        Importance.LOW,
                        "TaskId",
                        CONFIG_GROUP_TASK_CONFIG,
                        1,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.TASK_ID)
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
                        "locahost",
                        new ConfigDef.NonEmptyString(),
                        Importance.HIGH,
                        "ClickHouse Host Name",
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
                .define(
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_PORT,
                        Type.INT,
                        8123,
                        ConfigDef.Range.atLeast(1),
                        Importance.HIGH,
                        "ClickHouse database port number",
                        CONFIG_GROUP_CLICKHOUSE_LOGIN_INFO,
                        3,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_PORT)
                .define(
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_TABLE,
                        Type.STRING,
                        null,
                        new ConfigDef.NonEmptyString(),
                        Importance.HIGH,
                        "ClickHouse table name",
                        CONFIG_GROUP_CLICKHOUSE_LOGIN_INFO,
                        3,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_TABLE)
                // ToDo: Add JVM Proxy
                ;
    }
}

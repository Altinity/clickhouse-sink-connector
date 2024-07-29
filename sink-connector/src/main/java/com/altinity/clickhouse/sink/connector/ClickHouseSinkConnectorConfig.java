package com.altinity.clickhouse.sink.connector;


import com.altinity.clickhouse.sink.connector.deduplicator.DeDuplicationPolicy;
import com.altinity.clickhouse.sink.connector.deduplicator.DeDuplicationPolicyValidator;
import com.altinity.clickhouse.sink.connector.validators.DatabaseOverrideValidator;
import com.altinity.clickhouse.sink.connector.validators.KafkaProviderValidator;
import com.altinity.clickhouse.sink.connector.validators.TopicToTableValidator;
import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.common.config.ConfigDef.Type;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


import java.util.Map;

/**
 * Connector configuration definition.
 * <p>
 * https://www.confluent.io/blog/write-a-kafka-connect-connector-with-configuration-handling/?_ga=2.60332132.837662403.1644687538-770780523.1642652755
 */
public class ClickHouseSinkConnectorConfig extends AbstractConfig {

    public static long BUFFER_COUNT_DEFAULT = 100;

    private static final Logger log = LogManager.getLogger(ClickHouseSinkConnectorConfig.class);

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

    public ClickHouseSinkConnectorConfig(ConfigDef config, Map<String, String> properties) {
        super(config, properties, false);
    }

    /**
     * Set default values for config
     *
     * @param config
     */
    public static void setDefaultValues(Map<String, String> config) {
        setFieldToDefaultValue(config, ClickHouseSinkConnectorConfigVariables.BUFFER_COUNT.toString(), BUFFER_COUNT_DEFAULT);
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
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_TOPICS_TABLES_MAP.toString(),
                        Type.STRING,
                        "",
                        new TopicToTableValidator(),
                        Importance.LOW,
                        "Map of topics to tables (optional). Format : comma-separated tuples, e.g."
                                + " <topic-1>:<table-1>,<topic-2>:<table-2>,... ",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        0,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_TOPICS_TABLES_MAP.toString())
                // Define overrides map for ClickHouse Database
                .define(
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATABASE_OVERRIDE_MAP.toString(),
                        Type.STRING,
                        "",
                        new DatabaseOverrideValidator(),
                        Importance.LOW,
                        "Map of source to destination database(override) (optional). Format : comma-separated tuples, e.g."
                                + " <src_database-1>:<destination_database-1>,<src_database-2>:<destination_database-2>,... ",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        0,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATABASE_OVERRIDE_MAP.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.BUFFER_COUNT.toString(),
                        Type.LONG,
                        BUFFER_COUNT_DEFAULT,
                        ConfigDef.Range.atLeast(1),
                        Importance.LOW,
                        "BufCount",
                        CONFIG_GROUP_DE_DUPLICATOR_CONFIG,
                        1,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.BUFFER_COUNT.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.DEDUPLICATION_POLICY.toString(),
                        Type.STRING,
                        DeDuplicationPolicy.OFF.name(),
                        new DeDuplicationPolicyValidator(),
                        Importance.LOW,
                        "What de-duplication policy to use",
                        CONFIG_GROUP_DE_DUPLICATOR_CONFIG,
                        2,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.DEDUPLICATION_POLICY.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.TASK_ID.toString(),
                        Type.LONG,
                        0,
                        ConfigDef.Range.atLeast(0),
                        Importance.LOW,
                        "TaskId",
                        CONFIG_GROUP_TASK_CONFIG,
                        1,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.TASK_ID.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.PROVIDER_CONFIG.toString(),
                        Type.STRING,
                        KafkaProvider.UNKNOWN.name(),
                        new KafkaProviderValidator(),
                        Importance.LOW,
                        "Whether kafka is running on Confluent code, self hosted or other managed service",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        2,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.PROVIDER_CONFIG.toString())

                // Config Group "ClickHouse login info"
                .define(
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_URL.toString(),
                        Type.STRING,
                        "localhost",
                        new ConfigDef.NonEmptyString(),
                        Importance.HIGH,
                        "ClickHouse Host Name",
                        CONFIG_GROUP_CLICKHOUSE_LOGIN_INFO,
                        0,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_URL.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_USER.toString(),
                        Type.STRING,
                        null,
                        new ConfigDef.NonEmptyString(),
                        Importance.HIGH,
                        "ClickHouse user name",
                        CONFIG_GROUP_CLICKHOUSE_LOGIN_INFO,
                        1,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_USER.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_PASS.toString(),
                        Type.STRING,
                        null,
                        Importance.HIGH,
                        "ClickHouse password",
                        CONFIG_GROUP_CLICKHOUSE_LOGIN_INFO,
                        2,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_PASS.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_PORT.toString(),
                        Type.INT,
                        8123,
                        ConfigDef.Range.atLeast(1),
                        Importance.HIGH,
                        "ClickHouse database port number",
                        CONFIG_GROUP_CLICKHOUSE_LOGIN_INFO,
                        3,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_PORT.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.STORE_KAFKA_METADATA.toString(),
                        Type.BOOLEAN,
                        "false",
                        Importance.LOW,
                        "True, if the kafka metadata needs to be stored in Clickhouse tables, false otherwise",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        1,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.STORE_KAFKA_METADATA.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.ENABLE_METRICS.toString(),
                        Type.BOOLEAN,
                        "true",
                        Importance.LOW,
                        "True, if the metrics endpoint has to be enabled, false otherwise",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        1,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.ENABLE_METRICS.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.METRICS_ENDPOINT_PORT.toString(),
                        Type.INT,
                        8084,
                        Importance.LOW,
                        "Metrics endpoint Port",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        1,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.METRICS_ENDPOINT_PORT.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.STORE_RAW_DATA.toString(),
                        Type.BOOLEAN,
                        "false",
                        Importance.LOW,
                        "True, if the raw data has to be stored in JSON form, false otherwise",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        1,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.STORE_RAW_DATA.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.STORE_RAW_DATA_COLUMN.toString(),
                        Type.STRING,
                        "",
                        Importance.LOW,
                        "Column name to store the raw data(JSON form), only applicable if STORE_RAW_DATA is set to True",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        1,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.STORE_RAW_DATA_COLUMN.toString())
                .define(
                    ClickHouseSinkConnectorConfigVariables.REPLACING_MERGE_TREE_DELETE_COLUMN.toString(),
                    Type.STRING,
                    "sign",
                    Importance.LOW,
                    "Column thats used to store the sign value when the engine is ReplacingMergeTree, when a " +
                            "delete CDC record is received, this column is set to -1, 1 otherwise",
                    CONFIG_GROUP_CONNECTOR_CONFIG,
                    1,
                    ConfigDef.Width.NONE,
                    ClickHouseSinkConnectorConfigVariables.REPLACING_MERGE_TREE_DELETE_COLUMN.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.ENABLE_KAFKA_OFFSET.toString(),
                        Type.BOOLEAN,
                        false,
                        Importance.HIGH,
                        "If enabled, topic offsets are stored in CH, if false topic offsets are managed in kafka topics",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        1,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.ENABLE_KAFKA_OFFSET.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.AUTO_CREATE_TABLES.toString(),
                        Type.BOOLEAN,
                        false,
                        Importance.HIGH,
                        "If enabled, tables are created in ClickHouse",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        1,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.AUTO_CREATE_TABLES.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.AUTO_CREATE_TABLES_REPLICATED.toString(),
                        Type.BOOLEAN,
                        false,
                        Importance.HIGH,
                        "If enabled, ReplicatedReplacingMergeTree tables are created in ClickHouse",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        1,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.AUTO_CREATE_TABLES_REPLICATED.toString())

                .define(
                        ClickHouseSinkConnectorConfigVariables.ENABLE_SCHEMA_EVOLUTION.toString(),
                        Type.BOOLEAN,
                        false,
                        Importance.HIGH,
                        "If enabled, schema changes will be applied in ClickHouse",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        1,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.ENABLE_SCHEMA_EVOLUTION.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.SNOWFLAKE_ID.toString(),
                        Type.BOOLEAN,
                        true,
                        Importance.HIGH,
                        "If enabled, snowflake id will be used for version columns",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        1,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.SNOWFLAKE_ID.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.KAFKA_OFFSET_METADATA_TABLE.toString(),
                        Type.STRING,
                        "topic_offset_metadata",
                        Importance.HIGH,
                        "Table name where the kafka offsets are stored",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        1,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.KAFKA_OFFSET_METADATA_TABLE.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.BUFFER_FLUSH_TIME.toString(),
                        Type.LONG,
                        30,
                        Importance.LOW,
                        "The time in seconds to flush cached data",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        3,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.BUFFER_FLUSH_TIME.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.BUFFER_FLUSH_TIMEOUT.toString(),
                        Type.LONG,
                        1000,
                        Importance.LOW,
                        "Timeout period for flushing records to ClickHouse",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        3,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.BUFFER_FLUSH_TIMEOUT.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.BUFFER_MAX_RECORDS.toString(),
                        Type.LONG,
                        100000,
                        Importance.LOW,
                        "Maximum records in the buffer before its flushed",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        3,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.BUFFER_MAX_RECORDS.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.THREAD_POOL_SIZE.toString(),
                        Type.INT,
                        10,
                        Importance.HIGH,
                        "Number of threads in the Sink Task Thread pool",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        3,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.THREAD_POOL_SIZE.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.IGNORE_DELETE.toString(),
                        Type.BOOLEAN,
                        false,
                        Importance.HIGH,
                        "If true, Deletes are ignored are not persisted to ClickHouse.",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        3,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.IGNORE_DELETE.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.PERSIST_RAW_BYTES.toString(),
                        Type.BOOLEAN,
                        false,
                        Importance.HIGH,
                        "If true, the bytes value is not converted to a String value, its written as raw Bytes",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        3,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.PERSIST_RAW_BYTES.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATETIME_TIMEZONE.toString(),
                        Type.STRING,
                        "",
                        Importance.HIGH,
                        "Override timezone for DateTime columns in ClickHouse server",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        3,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATETIME_TIMEZONE.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.SKIP_REPLICA_START.toString(),
                        Type.BOOLEAN,
                        false,
                        Importance.HIGH,
                        "If set to true, replication is not started, the user is expected to start replication with the sink-connector-client program",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        3,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.SKIP_REPLICA_START.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.RESTART_EVENT_LOOP.toString(),
                        Type.BOOLEAN,
                        false,
                        Importance.HIGH,
                        "If set to true, the event loop will be restarted after this timeout",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        5,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.RESTART_EVENT_LOOP.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.RESTART_EVENT_LOOP_TIMEOUT_PERIOD.toString(),
                        Type.LONG,
                        3000,
                        Importance.HIGH,
                        "Defines the time period for timeout, if the time from the last packet received from the source DB is longer than this timeout, the event loop is restarted",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        5,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.RESTART_EVENT_LOOP_TIMEOUT_PERIOD.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.JDBC_PARAMETERS.toString(),
                        Type.STRING,
                        "",
                        Importance.HIGH,
                        "JDBC connection parameters, the parameters should be in this format socket_timeout=10000,connection_timeout=100, delimited by comma",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        6,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.JDBC_PARAMETERS.toString())
                // Define the max queue size.
                .define(
                        ClickHouseSinkConnectorConfigVariables.MAX_QUEUE_SIZE.toString(),
                        Type.INT,
                        500000,
                        ConfigDef.Range.atLeast(1),
                        Importance.HIGH,
                        "The maximum size of the queue",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        6,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.MAX_QUEUE_SIZE.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.REPLICA_STATUS_VIEW.toString(),
                        Type.STRING,
                        "CREATE VIEW IF NOT EXISTS %s.show_replica_status AS SELECT now() - " +
                                "fromUnixTimestamp(JSONExtractUInt(offset_val, 'ts_sec')) AS seconds_behind_source, " +
                                "toDateTime(fromUnixTimestamp(JSONExtractUInt(offset_val, 'ts_sec')), 'UTC') AS utc_time, " +
                                "fromUnixTimestamp(JSONExtractUInt(offset_val, 'ts_sec')) AS local_time," +
                                "* FROM %s FINAL",
                        Importance.HIGH,
                        "SQL query to get replica status, lag etc.",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        6,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.REPLICA_STATUS_VIEW.toString())

                ;
    }
}

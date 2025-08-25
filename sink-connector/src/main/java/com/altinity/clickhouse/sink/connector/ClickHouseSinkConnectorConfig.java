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

import static com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables.ERROR_TABLE_NAME;

/**
 * Connector configuration definition.
 * <p>
 * https://www.confluent.io/blog/write-a-kafka-connect-connector-with-configuration-handling/?_ga=2.60332132.837662403.1644687538-770780523.1642652755
 */
public class ClickHouseSinkConnectorConfig extends AbstractConfig {

    /**
     * Default buffer count value.
     */
    public static final long BUFFER_COUNT_DEFAULT = 100L;

    /**
     * Default port for ClickHouse.
     */
    private static final int DEFAULT_CLICKHOUSE_PORT = 8123;

    /**
     * Default endpoint port for metrics.
     */
    private static final int DEFAULT_METRICS_ENDPOINT_PORT = 8084;

    /**
     * Default buffer flush time (seconds).
     */
    private static final long DEFAULT_BUFFER_FLUSH_TIME = 30L;

    /**
     * Default buffer flush timeout (milliseconds).
     */
    private static final long DEFAULT_BUFFER_FLUSH_TIMEOUT = 1000L;

    /**
     * Default maximum records in the buffer before flush.
     */
    private static final long DEFAULT_BUFFER_MAX_RECORDS = 100000L;

    /**
     * Default thread pool size.
     */
    private static final int DEFAULT_THREAD_POOL_SIZE = 10;

    /**
     * Default maximum size of the queue.
     */
    private static final int DEFAULT_MAX_QUEUE_SIZE = 500000;

    /**
     * Default timeout period for restarting the event loop (milliseconds).
     */
    private static final long DEFAULT_RESTART_EVENT_LOOP_TIMEOUT_PERIOD = 3000L;

    /**
     * Default maximum size for the connection pool.
     */
    private static final int DEFAULT_CONNECTION_POOL_MAX_SIZE = 500;

    /**
     * Default connection pool timeout (milliseconds).
     */
    private static final long DEFAULT_CONNECTION_POOL_TIMEOUT = 50000L;

    /**
     * Default minimum idle connections in the pool.
     */
    private static final int DEFAULT_CONNECTION_POOL_MIN_IDLE = 10;

    /**
     * Default maximum lifetime for connections in the pool (milliseconds).
     */
    private static final long DEFAULT_CONNECTION_POOL_MAX_LIFETIME = 300000L;

    /**
     * Default maximum number of retries for errors.
     */
    private static final int DEFAULT_ERRORS_MAX_RETRIES = 3;

    /**
     * Default error table name.
     */
    private static final String DEFAULT_ERROR_TABLE = "error_table";

    /**
     * Order index for config definitions, used for grouping/ordering.
     */
    private static final int ORDER_0 = 0;
    private static final int ORDER_1 = 1;
    private static final int ORDER_2 = 2;
    private static final int ORDER_3 = 3;
    private static final int ORDER_5 = 5;
    private static final int ORDER_6 = 6;
    private static final int ORDER_7 = 7;
    private static final int ORDER_15 = 15;

    /**
     * Logger for this class.
     */
    private static final Logger log = LogManager.getLogger(
            ClickHouseSinkConnectorConfig.class);

    // Configuration groups
    /**
     * Configuration group for ClickHouse login info.
     */
    private static final String CONFIG_GROUP_CLICKHOUSE_LOGIN_INFO =
            "ClickHouse Login Info";

    /**
     * Configuration group for connector config.
     */
    private static final String CONFIG_GROUP_CONNECTOR_CONFIG =
            "Connector Config";

    /**
     * Configuration group for de-duplicator config.
     */
    private static final String CONFIG_GROUP_DE_DUPLICATOR_CONFIG =
            "DeDuplicator Config";

    /**
     * Configuration group for task config.
     */
    private static final String CONFIG_GROUP_TASK_CONFIG = "Task Config";

    /**
     * Constructor with properties map.
     *
     * @param properties The map of config properties.
     */
    public ClickHouseSinkConnectorConfig(Map<String, String> properties) {
        this(newConfigDef(), properties);
    }

    /**
     * Constructor that takes a ConfigDef and properties map.
     *
     * @param config     The ConfigDef used by this configuration.
     * @param properties The map of config properties.
     */
    public ClickHouseSinkConnectorConfig(ConfigDef config,
                                         Map<String, String> properties) {
        super(config, properties, false);
    }

    /**
     * Sets default values for the config if they are not already specified.
     *
     * @param config The configuration map.
     */
    public static void setDefaultValues(Map<String, String> config) {
        setFieldToDefaultValue(config,
                ClickHouseSinkConnectorConfigVariables.BUFFER_COUNT.toString(),
                BUFFER_COUNT_DEFAULT);
    }

    /**
     * Sets one default value if it is not already in the config.
     *
     * @param config The configuration map.
     * @param key    The key to set.
     * @param value  The default value if key is not present.
     */
    static void setFieldToDefaultValue(Map<String, String> config, String key,
                                       Long value) {
        if (config.containsKey(key)) {
            // Value already specified
            return;
        }
        // No value specified, set default one
        config.put(key, "" + value);
        log.info("setFieldToDefaultValues(){}={}", key, value);
    }

    /**
     * Retrieves a property from the config map.
     *
     * @param config The configuration map.
     * @param key    The key to look up.
     * @return The value if present and not empty; null otherwise.
     */
    public static String getProperty(final Map<String, String> config,
                                     final String key) {
        if (config.containsKey(key) && !config.get(key).isEmpty()) {
            return config.get(key);
        } else {
            return null;
        }
    }

    /**
     * Builds a new {@link ConfigDef} object, defining various connector
     * parameters.
     *
     * @return A new ConfigDef with all connector configuration definitions.
     */
    static ConfigDef newConfigDef() {
        return new ConfigDef()

                // Config Group "Connector config"
                .define(
                        ClickHouseSinkConnectorConfigVariables.CONNECTOR_CLASS
                                .toString(),
                        Type.STRING,
                        "",
                        null,
                        Importance.HIGH,
                        "Connector class"
                )
                .define(
                        ClickHouseSinkConnectorConfigVariables
                                .CLICKHOUSE_TOPICS_TABLES_MAP.toString(),
                        Type.STRING,
                        "",
                        new TopicToTableValidator(),
                        Importance.LOW,
                        "Map of topics to tables (optional). Format : comma-separated "
                                + "tuples, e.g. <topic-1>:<table-1>,<topic-2>:<table-2>,...",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        ORDER_0,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables
                                .CLICKHOUSE_TOPICS_TABLES_MAP.toString()
                )
                // Define overrides map for ClickHouse Database
                .define(
                        ClickHouseSinkConnectorConfigVariables
                                .CLICKHOUSE_DATABASE_OVERRIDE_MAP.toString(),
                        Type.STRING,
                        "",
                        new DatabaseOverrideValidator(),
                        Importance.LOW,
                        "Map of source to destination database(override) (optional). "
                                + "Format : comma-separated tuples, e.g. "
                                + "<src_database-1>:<dest_database-1>,"
                                + "<src_database-2>:<dest_database-2>,...",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        ORDER_0,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables
                                .CLICKHOUSE_DATABASE_OVERRIDE_MAP.toString()
                )

                // Config Group "DeDuplicator Config"
                .define(
                        ClickHouseSinkConnectorConfigVariables.BUFFER_COUNT
                                .toString(),
                        Type.LONG,
                        BUFFER_COUNT_DEFAULT,
                        ConfigDef.Range.atLeast(1),
                        Importance.LOW,
                        "BufCount",
                        CONFIG_GROUP_DE_DUPLICATOR_CONFIG,
                        ORDER_1,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.BUFFER_COUNT
                                .toString()
                )
                .define(
                        ClickHouseSinkConnectorConfigVariables
                                .DEDUPLICATION_POLICY.toString(),
                        Type.STRING,
                        DeDuplicationPolicy.OFF.name(),
                        new DeDuplicationPolicyValidator(),
                        Importance.LOW,
                        "What de-duplication policy to use",
                        CONFIG_GROUP_DE_DUPLICATOR_CONFIG,
                        ORDER_2,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables
                                .DEDUPLICATION_POLICY.toString()
                )

                // Config Group "Task Config"
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
                        ClickHouseSinkConnectorConfigVariables.SOURCE_DATETIME_TIMEZONE.toString(),
                        Type.STRING,
                        "",
                        Importance.HIGH,
                        "Override timezone for DateTime columns in Source(MySQL/Postgres) server",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        3,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.SOURCE_DATETIME_TIMEZONE.toString())
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
                .define(
                        ClickHouseSinkConnectorConfigVariables.JDBC_SETTINGS.toString(),
                        Type.STRING,
                        "",
                        Importance.HIGH,
                        "JDBC clickhouse settings, the settings should be in this format input_format_null_as_default=1,input_format_orc_allow_missing_columns=1, delimited by comma",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        6,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.JDBC_SETTINGS.toString())
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
                        ClickHouseSinkConnectorConfigVariables.SINGLE_THREADED.toString(),
                        Type.BOOLEAN,
                        false,
                        Importance.HIGH,
                        "Single threaded mode",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        6,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.SINGLE_THREADED.toString())
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
                .define(
                        ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_MAX_SIZE.toString(),
                        Type.INT,
                        500,
                        Importance.HIGH,
                        "The maximum size of the connection pool",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        6,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_MAX_SIZE.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_TIMEOUT.toString(),
                        Type.LONG,
                        50000,
                        Importance.HIGH,
                        "The timeout for the connection pool",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        6,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_TIMEOUT.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_MIN_IDLE.toString(),
                        Type.INT,
                        10,
                        Importance.HIGH,
                        "The minimum number of idle connections in the connection pool",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        6,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_MIN_IDLE.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_MAX_LIFETIME.toString(),
                        Type.LONG,
                        300000,
                        Importance.HIGH,
                        "The maximum lifetime of the connection pool",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        6,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_MAX_LIFETIME.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE.toString(),
                        Type.BOOLEAN,
                        false,
                        Importance.HIGH,
                        "If set to true, the connection pool is disabled",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        6,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.OFFSET_STORAGE_TABLE_NAME.toString(),
                        Type.STRING,
                        "altinity_sink_connector",
                        Importance.HIGH,
                        "The name of the offset storage table",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        7,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.OFFSET_STORAGE_TABLE_NAME.toString())
                .define(
                        ClickHouseSinkConnectorConfigVariables.NON_DEFAULT_VALUE.toString(),
                        Type.BOOLEAN,
                        false,
                        Importance.HIGH,
                        "Non default value, if value is NULL, a default value will not be returned, NULL be used instead",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        7,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.NON_DEFAULT_VALUE.toString())
                        // Define errors.max.retries
                .define(
                        ClickHouseSinkConnectorConfigVariables.ERRORS_MAX_RETRIES.toString(),
                        Type.INT,
                        3,
                        Importance.HIGH,
                        "The maximum number of retries for errors",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        15,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.ERRORS_MAX_RETRIES.toString())
                .define(
                        ERROR_TABLE_NAME.toString(),
                        Type.STRING,
                        ERROR_TABLE_NAME.toString(),
                        Importance.LOW,
                        "Default table name for storing error records",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        ORDER_0,
                        ConfigDef.Width.NONE,
                        ERROR_TABLE_NAME.toString()
                )
                .define(
                        ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString(),
                        Type.BOOLEAN,
                        false,
                        Importance.HIGH,
                        "If enabled, replication history tables are created and maintained",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        ORDER_1,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString()
                )
                .define(
                        ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_DATABASE_NAME.toString(),
                        Type.STRING,
                        "replication_history",
                        Importance.MEDIUM,
                        "Database name for replication history tables",
                        CONFIG_GROUP_CONNECTOR_CONFIG,
                        ORDER_2,
                        ConfigDef.Width.NONE,
                        ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_DATABASE_NAME.toString()
                );
    }
}

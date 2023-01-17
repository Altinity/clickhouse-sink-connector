
package com.altinity.clickhouse.sink.connector;

public class ClickHouseSinkConnectorConfigVariables {

    public static final String THREAD_POOL_SIZE = "thread.pool.size";
    public static final String BUFFER_COUNT = "buffer.count";
    public static final long BUFFER_COUNT_DEFAULT = 100;
    public static final String DEDUPLICATION_POLICY = "deduplication.policy";

    public static final String CLICKHOUSE_TOPICS_TABLES_MAP = "clickhouse.topic2table.map";

    public static final String CLICKHOUSE_URL = "clickhouse.server.url";
    public static final String CLICKHOUSE_USER = "clickhouse.server.user";
    public static final String CLICKHOUSE_PASS = "clickhouse.server.pass";
    public static final String CLICKHOUSE_DATABASE = "clickhouse.server.database";
    public static final String CLICKHOUSE_PORT = "clickhouse.server.port";

    public static final String CLICKHOUSE_TABLE = "clickhouse.table.name";

    public static final String PROVIDER_CONFIG = "provider";
    public static final String TASK_ID = "task_id";

    // Flag thats configurable by the user to store kafka metadata information
    // in clickhouse tables.
    public static final String STORE_KAFKA_METADATA = "store.kafka.metadata";

    // Flag to store the raw data as JSON string in clickhouse.
    public static final String STORE_RAW_DATA ="store.raw.data";

    public static final String ENABLE_KAFKA_OFFSET = "enable.kafka.offset";
    public static final String KAFKA_OFFSET_METADATA_TABLE = "kafka.offset.metadata.table";

    // Column to store the raw data.
    public static final String STORE_RAW_DATA_COLUMN = "store.raw.data.column";

    // Buffer flush time in milliseconds.
    public static final String BUFFER_FLUSH_TIME = "buffer.flush.time.ms";

    // Maximum size of buffer before its flushed.
    public static final String BUFFER_MAX_RECORDS = "buffer.max.records";

    // Flush timeout(in milliseconds) if max records is not reached.
    public static final String BUFFER_FLUSH_TIMEOUT = "buffer.flush.timeout.ms";

    // Flag to enable prometheus metrics and to start a prometheus scrape server endpoint.
    public static final String ENABLE_METRICS = "metrics.enable";

    // Defines the port in which the metrics endpoint will be started
    // for prometheus to scrape metrics.
    public static final String METRICS_ENDPOINT_PORT = "metrics.port";

    public static final String REPLACING_MERGE_TREE_DELETE_COLUMN = "replacingmergetree.delete.column";

    //Config variable for auto creating tables if they dont exist.
    public static final String AUTO_CREATE_TABLES = "auto.create.tables";

    // Config variable when set to true, columns will be added.
    public static final String ENABLE_SCHEMA_EVOLUTION = "schema.evolution";

    public static final String SNOWFLAKE_ID = "snowflake.id";


}
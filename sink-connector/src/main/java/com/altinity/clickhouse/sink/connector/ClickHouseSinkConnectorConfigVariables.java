package com.altinity.clickhouse.sink.connector;

public enum ClickHouseSinkConnectorConfigVariables {

    IGNORE_DELETE("ignore_delete"),
    THREAD_POOL_SIZE("thread.pool.size"),
    BUFFER_COUNT("buffer.count"),
    DEDUPLICATION_POLICY("deduplication.policy"),

    CLICKHOUSE_TOPICS_TABLES_MAP("clickhouse.topic2table.map"),
    CLICKHOUSE_DATABASE_OVERRIDE_MAP("clickhouse.database.override.map"),

    CLICKHOUSE_URL("clickhouse.server.url"),
    CLICKHOUSE_USER("clickhouse.server.user"),
    CLICKHOUSE_PASS("clickhouse.server.password"),
    CLICKHOUSE_PORT("clickhouse.server.port"),

    PROVIDER_CONFIG( "provider"),
    TASK_ID("task_id"),

    // Flag thats configurable by the user to store kafka metadata information
    // in clickhouse tables.
    STORE_KAFKA_METADATA( "store.kafka.metadata"),

    // Flag to store the raw data as JSON string in clickhouse.
    STORE_RAW_DATA("store.raw.data"),

    ENABLE_KAFKA_OFFSET("enable.kafka.offset"),
    KAFKA_OFFSET_METADATA_TABLE("kafka.offset.metadata.table"),

    // Column to store the raw data.
    STORE_RAW_DATA_COLUMN( "store.raw.data.column"),

    // Buffer flush time in milliseconds.
    BUFFER_FLUSH_TIME( "buffer.flush.time.ms"),

    // Maximum size of buffer before its flushed.
    BUFFER_MAX_RECORDS("buffer.max.records"),

    // Flush timeout(in milliseconds) if max records is not reached.
    BUFFER_FLUSH_TIMEOUT( "buffer.flush.timeout.ms"),

    // Flag to enable prometheus metrics and to start a prometheus scrape server endpoint.
    ENABLE_METRICS("metrics.enable"),

    // Defines the port in which the metrics endpoint will be started
    // for prometheus to scrape metrics.
    METRICS_ENDPOINT_PORT("metrics.port"),

    REPLACING_MERGE_TREE_DELETE_COLUMN("replacingmergetree.delete.column"),

    //Config variable for auto creating tables if they dont exist.
    AUTO_CREATE_TABLES("auto.create.tables"),

    // Config variable for auto creating ReplicatedReplacingMergeTree
    AUTO_CREATE_TABLES_REPLICATED("auto.create.tables.replicated"),

    // Config variable when set to true, columns will be added.
    ENABLE_SCHEMA_EVOLUTION("schema.evolution"),

    SNOWFLAKE_ID("snowflake.id"),

    PERSIST_RAW_BYTES("persist.raw.bytes"),

    SOURCE_DATETIME_TIMEZONE("database.connectionTimeZone"),
    CLICKHOUSE_DATETIME_TIMEZONE("clickhouse.datetime.timezone"),

    SKIP_REPLICA_START("skip_replica_start"),

    RESTART_EVENT_LOOP("restart.event.loop"),

    RESTART_EVENT_LOOP_TIMEOUT_PERIOD("restart.event.loop.timeout.period.secs"),
    JDBC_PARAMETERS("clickhouse.jdbc.params"),
    JDBC_SETTINGS("clickhouse.jdbc.settings"),
    JDBC_V1_DRIVER("clickhouse.jdbc.v1"),
    REPLICA_STATUS_VIEW("replica.status.view"),
    MAX_QUEUE_SIZE("sink.connector.max.queue.size"),

    SINGLE_THREADED("single.threaded"),

    CONNECTION_POOL_MAX_SIZE("connection.pool.max.size"),
    CONNECTION_POOL_TIMEOUT("connection.pool.timeout"),
    CONNECTION_POOL_MIN_IDLE("connection.pool.min.idle"),
    CONNECTION_POOL_MAX_LIFETIME("connection.pool.max.lifetime"),

    CONNECTION_POOL_DISABLE("connection.pool.disable"),

    OFFSET_STORAGE_TABLE_NAME("offset.storage.jdbc.table.name"),

    ERRORS_MAX_RETRIES("errors.max.retries"),

    CONNECTOR_CLASS("connector.class"),

    NON_DEFAULT_VALUE("non.default.value"),

    ERROR_TABLE_NAME("default.error.table"),

    ERROR_LOGGING_ENABLE("error.logging.enable"),

    REPLICATION_HISTORY_ENABLE("replication.history.enable"),

    REPLICATION_HISTORY_TABLE_NAME("replication.history.table.name"),

    REPLICATION_HISTORY_DATABASE_NAME("replication.history.database.name"),

    REPLICATION_HISTORY_TTL("replication.history.ttl"),

    REPLICATION_HISTORY_REPLICATION_LOG_ONLY("replication.history.replication_log_only"),

    DDL_SCHEMA_CHANGE_TIMEOUT_MS("ddl.schema.change.timeout.ms"),

    DDL_SCHEMA_CHANGE_POLL_INTERVAL_MS("ddl.schema.change.poll.interval.ms"),

    // Issue #1287: MySQL TRUNCATE reaches ClickHouse both as DDL and as a CDC
    // record. The DDL side read this from the lightweight properties; the CDC
    // side (PreparedStatementExecutor) had no way to see it at all, so the
    // table was emptied anyway. Declaring it here puts it on the same
    // ClickHouseSinkConnectorConfig every other setting on that path uses.
    DISABLE_DROP_TRUNCATE("disable.drop.truncate"),

    DATABASE_HOSTNAME("database.hostname");



    private String label;

    ClickHouseSinkConnectorConfigVariables(String s) {
        this.label = s;
    }

    @Override
    public String toString() {
        return this.label;
    }
}


package com.altinity.clickhouse.sink.connector;

public class ClickHouseSinkConnectorConfigVariables {

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

    // Column to store the raw data.
    public static final String STORE_RAW_DATA_COLUMN = "store.raw.data.column";

    // Buffer flush time in seconds.
    public static final String BUFFER_FLUSH_TIME = "buffer.flush.time";

}
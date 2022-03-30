
package com.altinity.clickhouse.sink.connector;

public class ClickHouseSinkConnectorConfigVariables {

    static final String BUFFER_COUNT = "buffer.count";

    static final long BUFFER_COUNT_DEFAULT = 100;
    static final String CLICKHOUSE_TOPICS_TABLES_MAP = "clickhouse.topic2table.map";

    static final String CLICKHOUSE_URL = "clickhouse.server.url";
    static final String CLICKHOUSE_USER = "clickhouse.server.user";
    static final String CLICKHOUSE_PASS = "clickhouse.server.pass";
    static final String CLICKHOUSE_DATABASE = "clickhouse.server.database";

    static final String PROVIDER_CONFIG = "provider";
}
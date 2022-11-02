package com.altinity.clickhouse.sink.connector.common;

public class MetricsConstants {

    public static final String TOPIC = "Topic";
    public static final String PARTITION = "Partition";

    public static final String CLICKHOUSE_SINK_RECORDS = "clickhouse.sink.records";

    public static final String CLICKHOUSE_SINK_BINLOG_POS = "clickhouse_sink_binlog_pos";

    public static final String CLICKHOUSE_SINK_GTID = "clickhouse_sink_gtid";

    public static final String CLICKHOUSE_SINK_PARTITION_OFFSET = "clickhouse_sink_partition_offset";

    public static final String CLICKHOUSE_DB_SINK_LAG = "clickhouse_db_sink_lag";

    public static final String CLICKHOUSE_DEBEZIUM_SINK_LAG = "clickhouse_debezium_sink_lag";

    public static final String CLICKHOUSE_NUM_RECORDS_BY_TOPIC = "clickhouse.topics.num.records";

    public static final String CLICKHOUSE_NUM_ERROR_RECORDS_BY_TOPIC = "clickhouse.topics.error.records";

    static {

    }

}

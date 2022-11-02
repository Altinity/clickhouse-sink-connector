package com.altinity.clickhouse.sink.connector.common;

import java.util.HashMap;
import java.util.Map;

public class MetricsConstants {

    public static final String TOPIC = "Topic";
    public static final String PARTITION = "Partition";

    private static final Map<String, String> metricsToHelp;

    public static final String CLICKHOUSE_SINK_RECORDS = "clickhouse.sink.records";

    public static final String CLICKHOUSE_SINK_BINLOG_POS = "clickhouse_sink_binlog_pos";

    public static final String CLICKHOUSE_SINK_GTID = "clickhouse_sink_gtid";

    public static final String CLICKHOUSE_SINK_PARTITION_OFFSET = "clickhouse_sink_partition_offset";

    public static final String CLICKHOUSE_DB_SINK_LAG = "clickhouse_sink_db_lag";

    public static final String CLICKHOUSE_DEBEZIUM_SINK_LAG = "clickhouse_sink_debezium_lag";

    public static final String CLICKHOUSE_NUM_RECORDS_BY_TOPIC = "clickhouse.sink.topics.num.records";

    public static final String CLICKHOUSE_NUM_ERROR_RECORDS_BY_TOPIC = "clickhouse.sink.topics.error.records";

    public static final String CLICKHOUSE_SINK_CONNECTOR_UPTIME = "clickhouse_sink_connector_uptime";

    static {
        metricsToHelp = new HashMap<String, String>();

        metricsToHelp.put(CLICKHOUSE_SINK_RECORDS, "Number of sink records(Count)");
        metricsToHelp.put(CLICKHOUSE_SINK_BINLOG_POS, "MySQL Bin log position and file");
        metricsToHelp.put(CLICKHOUSE_SINK_GTID, "MySQL GTID");
        metricsToHelp.put(CLICKHOUSE_SINK_PARTITION_OFFSET, "Kafka partition Offset by Topic");

        metricsToHelp.put(CLICKHOUSE_DB_SINK_LAG, "Lag between Source Database and Bulk Insert to CH");
        metricsToHelp.put(CLICKHOUSE_DEBEZIUM_SINK_LAG, "Lag between Debezium(Source) and Bulk Insert to CH");

        metricsToHelp.put(CLICKHOUSE_NUM_RECORDS_BY_TOPIC, "Total number of Sink records by Topic(Count)");
        metricsToHelp.put(CLICKHOUSE_NUM_ERROR_RECORDS_BY_TOPIC, "Total number of Sink Error records by Topic(Count)");

        metricsToHelp.put(CLICKHOUSE_SINK_CONNECTOR_UPTIME, "Connector uptime in milliseconds");

    }

    public static Map<String, String> getMetricsToHelpMap() {
        return metricsToHelp;
    }
}

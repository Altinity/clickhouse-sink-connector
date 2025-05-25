package com.altinity.clickhouse.sink.connector.common;

import java.util.HashMap;
import java.util.Map;

/**
 * This class contains constants related to metrics used in the
 * ClickHouse sink connector.
 * <p>
 * These constants define various metric names and descriptions used for
 * monitoring the performance of the ClickHouse sink connector. The
 * metrics are related to record counts, offsets, lag times, and other
 * performance indicators.
 * </p>
 */
public class MetricsConstants {

    /**
     * Topic name for metrics.
     */
    public static final String TOPIC = "Topic";

    /**
     * Partition name for metrics.
     */
    public static final String PARTITION = "Partition";

    /**
     * Metric name for the number of sink records.
     */
    public static final String CLICKHOUSE_SINK_RECORDS = "clickhouse.sink.records";

    /**
     * Metric name for MySQL bin log position and file.
     */
    public static final String CLICKHOUSE_SINK_BINLOG_POS = "clickhouse_sink_binlog_pos";

    /**
     * Metric name for MySQL GTID (Global Transaction Identifier).
     */
    public static final String CLICKHOUSE_SINK_GTID = "clickhouse_sink_gtid";

    /**
     * Metric name for Kafka partition offset by topic.
     */
    public static final String CLICKHOUSE_SINK_PARTITION_OFFSET = "clickhouse_sink_partition_offset";

    /**
     * Metric name for lag between the source database and bulk insert to ClickHouse.
     */
    public static final String CLICKHOUSE_DB_SINK_LAG = "clickhouse_sink_db_lag";

    /**
     * Metric name for lag between Debezium (source) and bulk insert to ClickHouse.
     */
    public static final String CLICKHOUSE_DEBEZIUM_SINK_LAG = "clickhouse_sink_debezium_lag";

    /**
     * Metric name for the total number of sink records by topic.
     */
    public static final String CLICKHOUSE_NUM_RECORDS_BY_TOPIC = "clickhouse.sink.topics.num.records";

    /**
     * Metric name for DDL (Data Definition Language) statements and execution time.
     */
    public static final String CLICKHOUSE_SINK_DDL = "clickhouse.sink.ddl";

    /**
     * Metric name for the total number of sink error records by topic.
     */
    public static final String CLICKHOUSE_NUM_ERROR_RECORDS_BY_TOPIC = "clickhouse.sink.topics.error.records";

    /**
     * Metric name for the uptime of the ClickHouse sink connector in milliseconds.
     */
    public static final String CLICKHOUSE_SINK_CONNECTOR_UPTIME = "clickhouse_sink_connector_uptime";

    /**
     * A map that stores the descriptions of the metrics.
     */
    private static final Map<String, String> metricsToHelp;

    static {
        metricsToHelp = new HashMap<String, String>();

        // Add metric descriptions
        metricsToHelp.put(CLICKHOUSE_SINK_RECORDS, "Number of sink records(Count)");
        metricsToHelp.put(CLICKHOUSE_SINK_BINLOG_POS, "MySQL Bin log position and file");
        metricsToHelp.put(CLICKHOUSE_SINK_GTID, "MySQL GTID");
        metricsToHelp.put(CLICKHOUSE_SINK_PARTITION_OFFSET, "Kafka partition Offset by Topic");

        metricsToHelp.put(CLICKHOUSE_DB_SINK_LAG, "Lag between Source Database and Bulk Insert to CH");
        metricsToHelp.put(CLICKHOUSE_DEBEZIUM_SINK_LAG, "Lag between Debezium(Source) and Bulk Insert to CH");

        metricsToHelp.put(CLICKHOUSE_NUM_RECORDS_BY_TOPIC, "Total number of Sink records by Topic(Count)");
        metricsToHelp.put(CLICKHOUSE_NUM_ERROR_RECORDS_BY_TOPIC, "Total number of Sink Error records by Topic(Count)");

        metricsToHelp.put(CLICKHOUSE_SINK_CONNECTOR_UPTIME, "Connector uptime in milliseconds");

        metricsToHelp.put(CLICKHOUSE_SINK_DDL, "DDL Statements and execution time");
    }

    /**
     * Returns a map containing metric names and their descriptions.
     *
     * @return a map of metric names to descriptions.
     */
    public static Map<String, String> getMetricsToHelpMap() {
        return metricsToHelp;
    }
}

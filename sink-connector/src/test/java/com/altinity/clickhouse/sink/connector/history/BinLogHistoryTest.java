package com.altinity.clickhouse.sink.connector.history;

import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.junit.Assert.assertEquals;

public class BinLogHistoryTest {

    private BinLogHistory binLogHistory;

    @BeforeEach
    public void setUp() {
        binLogHistory = new BinLogHistory();
    }

    @Test
    public void testCreateHistoryTableSyntax() {
        String result = binLogHistory.createHistoryTableSyntax(
            "test_history",
            "test_db",
            30,
            ZoneId.of("UTC")
        );
        String expected = "CREATE TABLE IF NOT EXISTS `test_db`.`test_history`(`gtid` String,`database` LowCardinality(String),`table` LowCardinality(String),`ddl` String,`before` String,`after` String,`_raw` String,`_time` DateTime64(9, 'UTC'),`is_deleted` UInt8,`_operation` LowCardinality(String),`_version` UInt64,`host` LowCardinality(String),`logfile` LowCardinality(String),`position` UInt64,`primary_host` LowCardinality(String),`server_id` UInt32,`row` UInt32,`sequence` UInt64,`db_time` DateTime MATERIALIZED now()) ENGINE = ReplacingMergeTree(_version, is_deleted) ORDER BY(server_id,logfile,position,sequence,_time) PARTITION BY toDate(`_time`) TTL toDate(`_time`) + toIntervalDay(30);";
       // String expected = "CREATE TABLE IF NOT EXISTS test_db.`test_history`(`gtid` String,`database` LowCardinality(String),`table` LowCardinality(String),`ddl` String,`before` String,`after` String,`_raw` String,`_time` DateTime('UTC'),`is_deleted` UInt8,`_operation` String,`_version` UInt64,`host` LowCardinality(String),`logfile` LowCardinality(String),`position` UInt64,`primary_host` LowCardinality(String),`server_id` UInt32,`row` UInt32,`sequence` UInt64) ENGINE = ReplacingMergeTree(_version, is_deleted) ORDER BY(server_id,logfile,position,sequence,_time) PARTITION BY toDate(`_time`) TTL toDate(`_time`) + toIntervalDay(30);";
       // String expected = "CREATE TABLE IF NOT EXISTS test_db.`test_history`(`gtid` String,`database` LowCardinality(String),`table` LowCardinality(String),`ddl` String,`before` LowCardinality(String),`after` LowCardinality(String),`_raw` String,`_time` DateTime64(3),`is_deleted` UInt8,`operation` String,`_version` UInt64,`host` LowCardinality(String),`logfile` LowCardinality(String),`position` UInt64,`primary_host` LowCardinality(String),`server_id` UInt32,`row` UInt32,`sequence` UInt64) ENGINE = ReplacingMergeTree(_version, is_deleted) ORDER BY(server_id,logfile,position,sequence,_time) PARTITION BY toDate(`_time`) TTL toDate(`_time`) + toIntervalDay(30);";
        assertEquals(expected, result);
    }


    @Test
    public void testCreateHistoryTableSyntaxWithDifferentNames() {
        String result = binLogHistory.createHistoryTableSyntax(
            "user_history",
            "production_db",
            30,
            ZoneId.of("UTC")
        );

        String expected = "CREATE TABLE IF NOT EXISTS `production_db`.`user_history`(`gtid` String,`database` LowCardinality(String),`table` LowCardinality(String),`ddl` String,`before` String,`after` String,`_raw` String,`_time` DateTime64(9, 'UTC'),`is_deleted` UInt8,`_operation` LowCardinality(String),`_version` UInt64,`host` LowCardinality(String),`logfile` LowCardinality(String),`position` UInt64,`primary_host` LowCardinality(String),`server_id` UInt32,`row` UInt32,`sequence` UInt64,`db_time` DateTime MATERIALIZED now()) ENGINE = ReplacingMergeTree(_version, is_deleted) ORDER BY(server_id,logfile,position,sequence,_time) PARTITION BY toDate(`_time`) TTL toDate(`_time`) + toIntervalDay(30);";
        //String expected = "CREATE TABLE IF NOT EXISTS production_db.`user_history`(`gtid` String,`database` LowCardinality(String),`table` LowCardinality(String),`ddl` String,`before` LowCardinality(String),`after` LowCardinality(String),`_raw` String,`_time` DateTime64(3),`is_deleted` UInt8,`operation` String,`_version` UInt64,`host` LowCardinality(String),`logfile` LowCardinality(String),`position` UInt64,`primary_host` LowCardinality(String),`server_id` UInt32,`row` UInt32,`sequence` UInt64) ENGINE = ReplacingMergeTree(_version, is_deleted) ORDER BY(server_id,logfile,position,sequence,_time) PARTITION BY toDate(`_time`) TTL toDate(`_time`) + toIntervalDay(30);";
        Assert.assertTrue(expected.equalsIgnoreCase(result));
        //Assert.assertTrue(result.contains("CREATE TABLE production_db.`user_history`"));
        //Assert.assertTrue(result.contains("ENGINE = MergeTree()"));
    }

    /**
     * Spec 02.01 section 3.5 (d): the history sorting key is
     * {@code (server_id, logfile, position, sequence, _time)}. The Kafka Connect
     * path assigns no sequence number, so every row of a multi-row statement bound
     * the {@code -1} sentinel and collapsed into one row; the row index within the
     * event is unique per (logfile, position) and is bound instead. A record that
     * carries a sequence number (the lightweight engine) binds it unchanged.
     */
    @Test
    public void kafkaPathUsesRowIndexAsSequence() throws Exception {
        java.util.List<java.util.Map<Integer, Object>> batches = new java.util.ArrayList<>();
        final java.util.Map<Integer, Object>[] current = new java.util.Map[] {new java.util.HashMap<Integer, Object>()};
        java.sql.PreparedStatement ps = (java.sql.PreparedStatement) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {java.sql.PreparedStatement.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.startsWith("set") && args != null && args.length == 2) {
                        current[0].put((Integer) args[0], args[1]);
                        return null;
                    }
                    if (name.equals("addBatch")) {
                        batches.add(current[0]);
                        current[0] = new java.util.HashMap<>();
                        return null;
                    }
                    if (name.equals("executeBatch")) {
                        return new int[batches.size()];
                    }
                    return null;
                });
        java.sql.Connection conn = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {java.sql.Connection.class},
                (proxy, method, args) -> method.getName().equals("prepareStatement") ? ps : null);
        java.util.Map<String, String> props = new java.util.HashMap<>();
        com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig.setDefaultValues(props);
        com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig config =
                new com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig(props);

        com.altinity.clickhouse.sink.connector.model.ClickHouseStruct kafkaRow =
                new com.altinity.clickhouse.sink.connector.model.ClickHouseStruct();
        kafkaRow.setTs_ms(1709290200000L);
        kafkaRow.setRow(3);                                    // third row of the event, no sequence number
        com.altinity.clickhouse.sink.connector.model.ClickHouseStruct lightweightRow =
                new com.altinity.clickhouse.sink.connector.model.ClickHouseStruct();
        lightweightRow.setTs_ms(1709290200000L);
        lightweightRow.setRow(0);
        lightweightRow.setSequenceNumber(777L);

        binLogHistory.executeInsertWithStructs(config, conn, "INSERT INTO h.history", "",
                java.util.Arrays.asList(kafkaRow, lightweightRow), "UTC", "UTC");

        int sequenceIndex = new java.util.ArrayList<>(BinLogHistory.HISTORY_COLUMNS.keySet())
                .indexOf(BinLogHistory.SEQUENCE_COLUMN) + 1;
        assertEquals(2, batches.size());
        assertEquals("a Kafka Connect row binds its row index as the sequence, never the -1 sentinel",
                3L, batches.get(0).get(sequenceIndex));
        assertEquals("a row with a sequence number binds it unchanged",
                777L, batches.get(1).get(sequenceIndex));
    }
}

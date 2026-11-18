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
                new com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig(
                        com.altinity.clickhouse.sink.connector.ZoneFixtures.withZones(props));

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

    /** Binds one row through {@code executeInsertWithStructs} and returns its {@code _time} parameter. */
    private Object boundTime(long tsMs, String sourceTimeZone, String serverTimeZone) throws Exception {
        java.util.Map<Integer, Object> params = new java.util.HashMap<>();
        java.sql.PreparedStatement ps = (java.sql.PreparedStatement) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {java.sql.PreparedStatement.class},
                (proxy, method, args) -> {
                    if (method.getName().startsWith("set") && args != null && args.length == 2) {
                        params.put((Integer) args[0], args[1]);
                    }
                    return method.getName().equals("executeBatch") ? new int[1] : null;
                });
        java.sql.Connection conn = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {java.sql.Connection.class},
                (proxy, method, args) -> method.getName().equals("prepareStatement") ? ps : null);
        java.util.Map<String, String> props = new java.util.HashMap<>();
        com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig.setDefaultValues(props);
        com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig config =
                new com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig(
                        com.altinity.clickhouse.sink.connector.ZoneFixtures.withZones(props));
        com.altinity.clickhouse.sink.connector.model.ClickHouseStruct row =
                new com.altinity.clickhouse.sink.connector.model.ClickHouseStruct();
        row.setTs_ms(tsMs);
        binLogHistory.executeInsertWithStructs(config, conn, "INSERT INTO h.history", "",
                java.util.Collections.singletonList(row), sourceTimeZone, serverTimeZone);
        int timeIndex = new java.util.ArrayList<>(BinLogHistory.HISTORY_COLUMNS.keySet())
                .indexOf(BinLogHistory.TIME_COLUMN) + 1;
        return params.get(timeIndex);
    }

    /**
     * Spec 12.04 section 3.3: {@code _time} is the instant of {@code source.ts_ms}, bound as
     * epoch text, for every combination of source and server zone. Equal zones used to
     * bind the instant's UTC digits, which a non-UTC {@code DateTime64} column then read as
     * local time (shifted by the zone offset); different zones used to shift the instant by
     * the source zone's offset.
     */
    @Test
    public void timeColumnBindsTheInstantAsEpochTextForEveryZoneCombination() throws Exception {
        long tsMs = 1709290200123L;
        String[][] zones = {
                {"UTC", "UTC"},
                {"America/Chicago", "America/Chicago"},
                {"UTC", "America/Chicago"},
                {"America/Chicago", "UTC"},
                {"Asia/Kolkata", "America/Chicago"},
        };
        for (String[] z : zones) {
            assertEquals("source=" + z[0] + " server=" + z[1],
                    "1709290200.123000", boundTime(tsMs, z[0], z[1]));
        }
    }

    /** The second 01:30 of a fall-back day exists only as an instant; digits could not store it. */
    @Test
    public void timeColumnKeepsTheSecondOccurrenceOfAnOverlapHour() throws Exception {
        long secondOneThirty = java.time.Instant.parse("2026-11-01T07:30:00Z").toEpochMilli();
        assertEquals("1793518200.000000",
                boundTime(secondOneThirty, "America/Chicago", "America/Chicago"));
    }

    /** The second before the epoch cannot be epoch text; it is bound as digits in the server zone. */
    @Test
    public void timeColumnBindsDigitsInTheSecondBeforeTheEpoch() throws Exception {
        assertEquals("1969-12-31 17:59:59.500", boundTime(-500L, "UTC", "America/Chicago"));
        assertEquals("1969-12-31 23:59:59.999", boundTime(-1L, "UTC", "UTC"));
    }

    /**
     * An empty source zone does not change the bound instant (the source zone is not used for
     * binding). An empty or null SERVER zone is refused: it used to default silently to UTC
     * (spec 07.03 section 3.1.6, B3); the caller now resolves the effective server zone.
     */
    @Test
    public void timeColumnIgnoresEmptySourceZoneAndRefusesEmptyServerZone() throws Exception {
        assertEquals("1709290200.123000", boundTime(1709290200123L, "", "UTC"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> boundTime(1709290200123L, "", null));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> boundTime(1709290200123L, "UTC", ""));
    }
}

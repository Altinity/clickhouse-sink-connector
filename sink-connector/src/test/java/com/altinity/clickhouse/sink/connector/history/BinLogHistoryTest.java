package com.altinity.clickhouse.sink.connector.history;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.HashMap;

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
        String expected = "CREATE TABLE IF NOT EXISTS test_db.`test_history`(`gtid` String,`database` LowCardinality(String),`table` LowCardinality(String),`ddl` String,`before` String,`after` String,`_raw` String,`_time` DateTime64(0, 'UTC'),`is_deleted` UInt8,`_operation` LowCardinality(String),`_version` UInt64,`host` LowCardinality(String),`logfile` LowCardinality(String),`position` UInt64,`primary_host` LowCardinality(String),`server_id` UInt32,`row` UInt32,`sequence` UInt64) ENGINE = ReplacingMergeTree(_version, is_deleted) ORDER BY(server_id,logfile,position,sequence,_time) PARTITION BY toDate(`_time`) TTL toDate(`_time`) + toIntervalDay(30);";
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

        String expected = "CREATE TABLE IF NOT EXISTS production_db.`user_history`(`gtid` String,`database` LowCardinality(String),`table` LowCardinality(String),`ddl` String,`before` String,`after` String,`_raw` String,`_time` DateTime64(0, 'UTC'),`is_deleted` UInt8,`_operation` LowCardinality(String),`_version` UInt64,`host` LowCardinality(String),`logfile` LowCardinality(String),`position` UInt64,`primary_host` LowCardinality(String),`server_id` UInt32,`row` UInt32,`sequence` UInt64) ENGINE = ReplacingMergeTree(_version, is_deleted) ORDER BY(server_id,logfile,position,sequence,_time) PARTITION BY toDate(`_time`) TTL toDate(`_time`) + toIntervalDay(30);";
        //String expected = "CREATE TABLE IF NOT EXISTS production_db.`user_history`(`gtid` String,`database` LowCardinality(String),`table` LowCardinality(String),`ddl` String,`before` LowCardinality(String),`after` LowCardinality(String),`_raw` String,`_time` DateTime64(3),`is_deleted` UInt8,`operation` String,`_version` UInt64,`host` LowCardinality(String),`logfile` LowCardinality(String),`position` UInt64,`primary_host` LowCardinality(String),`server_id` UInt32,`row` UInt32,`sequence` UInt64) ENGINE = ReplacingMergeTree(_version, is_deleted) ORDER BY(server_id,logfile,position,sequence,_time) PARTITION BY toDate(`_time`) TTL toDate(`_time`) + toIntervalDay(30);";
        Assert.assertTrue(expected.equalsIgnoreCase(result));
        //Assert.assertTrue(result.contains("CREATE TABLE production_db.`user_history`"));
        //Assert.assertTrue(result.contains("ENGINE = MergeTree()"));
    }

    @Test
    public void testHistoryColumnValuesAreMappedForUnifiedPreparedStatementPath() {
        ClickHouseStruct record = new ClickHouseStruct();
        record.setTopic("server1.employees.newtable");
        record.setDatabase("employees");
        record.setCdcOperation(ClickHouseConverter.CDC_OPERATION.DELETE);
        record.setTs_ms(1700000000000L);
        record.setGtid(12345L);
        record.setFile("mysql-bin.000001");
        record.setPos(42L);
        record.setServerId(99L);
        record.setRow(2);
        record.setSequenceNumber(7L);

        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(new HashMap<>());

        assertEquals("employees", BinLogHistory.getValueFromStruct(record, BinLogHistory.DATABASE_COLUMN, config));
        assertEquals("newtable", BinLogHistory.getValueFromStruct(record, BinLogHistory.TABLE_COLUMN, config));
        assertEquals(1, BinLogHistory.getValueFromStruct(record, BinLogHistory.IS_DELETED_COLUMN, config));
        assertEquals("mysql-bin.000001", BinLogHistory.getValueFromStruct(record, BinLogHistory.LOGFILE_COLUMN, config));
        assertEquals("CREATE TABLE newtable(id Int32)",
                BinLogHistory.getValueFromStruct(record, BinLogHistory.DDL_COLUMN, config,
                        "CREATE TABLE newtable(id Int32)", "", ""));
    }

}
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
        String expected = "CREATE TABLE IF NOT EXISTS test_db.`test_history`(`gtid` String,`database` LowCardinality(String),`table` LowCardinality(String),`ddl` String,`before` String,`after` String,`_raw` String,`_time` DateTime('UTC'),`is_deleted` UInt8,`_operation` String,`_version` UInt64,`host` LowCardinality(String),`logfile` LowCardinality(String),`position` UInt64,`primary_host` LowCardinality(String),`server_id` UInt32,`row` UInt32,`sequence` UInt64) ENGINE = ReplacingMergeTree(_version, is_deleted) ORDER BY(server_id,logfile,position,sequence,_time) PARTITION BY toDate(`_time`) TTL toDate(`_time`) + toIntervalDay(30);";
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

        String expected = "CREATE TABLE IF NOT EXISTS production_db.`user_history`(`gtid` String,`database` LowCardinality(String),`table` LowCardinality(String),`ddl` String,`before` String,`after` String,`_raw` String,`_time` DateTime('UTC'),`is_deleted` UInt8,`_operation` String,`_version` UInt64,`host` LowCardinality(String),`logfile` LowCardinality(String),`position` UInt64,`primary_host` LowCardinality(String),`server_id` UInt32,`row` UInt32,`sequence` UInt64) ENGINE = ReplacingMergeTree(_version, is_deleted) ORDER BY(server_id,logfile,position,sequence,_time) PARTITION BY toDate(`_time`) TTL toDate(`_time`) + toIntervalDay(30);";
        //String expected = "CREATE TABLE IF NOT EXISTS production_db.`user_history`(`gtid` String,`database` LowCardinality(String),`table` LowCardinality(String),`ddl` String,`before` LowCardinality(String),`after` LowCardinality(String),`_raw` String,`_time` DateTime64(3),`is_deleted` UInt8,`operation` String,`_version` UInt64,`host` LowCardinality(String),`logfile` LowCardinality(String),`position` UInt64,`primary_host` LowCardinality(String),`server_id` UInt32,`row` UInt32,`sequence` UInt64) ENGINE = ReplacingMergeTree(_version, is_deleted) ORDER BY(server_id,logfile,position,sequence,_time) PARTITION BY toDate(`_time`) TTL toDate(`_time`) + toIntervalDay(30);";
        Assert.assertTrue(expected.equalsIgnoreCase(result));
        //Assert.assertTrue(result.contains("CREATE TABLE production_db.`user_history`"));
        //Assert.assertTrue(result.contains("ENGINE = MergeTree()"));
    }


}
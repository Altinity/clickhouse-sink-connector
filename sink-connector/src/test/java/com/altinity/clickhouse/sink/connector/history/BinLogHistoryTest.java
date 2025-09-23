package com.altinity.clickhouse.sink.connector.history;

import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
            30
        );
        String expected = "CREATE TABLE IF NOT EXISTS test_db.`test_history`(`gtid` String,`database` String,`table` String,`ddl` String,`before` String,`after` String,`_raw` String,`_time` UInt64,`is_deleted` UInt8,`operation` String,`_version` UInt64,`host` String,`logfile` String,`position` UInt64,`primary_host` String) ENGINE = MergeTree() ORDER BY `gtid` PARTITION BY toDate(`_time`) TTL toDate(`_time`) + toIntervalDay(30);";
        assertEquals(expected, result);
    }


    @Test
    public void testCreateHistoryTableSyntaxWithDifferentNames() {
        String result = binLogHistory.createHistoryTableSyntax(
            "user_history",
            "production_db",
            30
        );
        String expected = "CREATE TABLE IF NOT EXISTS production_db.`user_history`(`gtid` String,`database` String,`table` String,`ddl` String,`before` String,`after` String,`_raw` String,`_time` UInt64,`is_deleted` UInt8,`operation` String,`_version` UInt64,`host` String,`logfile` String,`position` UInt64,`primary_host` String) ENGINE = MergeTree() ORDER BY `gtid` PARTITION BY toDate(`_time`) TTL toDate(`_time`) + toIntervalDay(30);";
        Assert.assertTrue(expected.equalsIgnoreCase(result));
        //Assert.assertTrue(result.contains("CREATE TABLE production_db.`user_history`"));
        //Assert.assertTrue(result.contains("ENGINE = MergeTree()"));
    }


}
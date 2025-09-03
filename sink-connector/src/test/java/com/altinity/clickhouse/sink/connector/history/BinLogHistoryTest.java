package com.altinity.clickhouse.sink.connector.history;

import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        
        Assert.assertTrue(result.contains("CREATE TABLE test_db.`test_history`"));
        Assert.assertTrue(result.contains("ENGINE = MergeTree()"));
        Assert.assertTrue(result.contains("`database` String"));
        Assert.assertTrue(result.contains("`table` String"));
        Assert.assertTrue(result.contains("`before` String"));
        Assert.assertTrue(result.contains("`after` String"));
        Assert.assertTrue(result.contains("`_raw` String"));
        Assert.assertTrue(result.contains("`_time` UInt64"));
        Assert.assertTrue(result.contains("`is_deleted` UInt8"));
        Assert.assertTrue(result.contains("`operation` String"));
        Assert.assertTrue(result.contains("`_version` UInt64"));
        Assert.assertTrue(result.contains("`host` String"));
        Assert.assertTrue(result.contains("`logfile` String"));
        Assert.assertTrue(result.contains("`position` UInt64"));
        Assert.assertTrue(result.contains("`primary_host` String"));
    }

    @Test
    public void testCreateHistoryTableSyntaxWithDifferentNames() {
        String result = binLogHistory.createHistoryTableSyntax(
            "user_history",
            "production_db",
            30
        );
        
        Assert.assertTrue(result.contains("CREATE TABLE production_db.`user_history`"));
        Assert.assertTrue(result.contains("ENGINE = MergeTree()"));
    }


}
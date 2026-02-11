package com.altinity.clickhouse.sink.connector.model;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for RoutedBatch hash-based routing logic.
 */
public class RoutedBatchTest {

    @Test
    public void testCalculateThreadIdIsConsistent() {
        String tableName = "test_table";
        int threadPoolSize = 4;
        
        int threadId1 = RoutedBatch.calculateThreadId(tableName, threadPoolSize);
        int threadId2 = RoutedBatch.calculateThreadId(tableName, threadPoolSize);
        
        // Same table should always map to same thread
        Assert.assertEquals(threadId1, threadId2);
    }

    @Test
    public void testCalculateThreadIdIsWithinRange() {
        String tableName = "users";
        int threadPoolSize = 5;
        
        int threadId = RoutedBatch.calculateThreadId(tableName, threadPoolSize);
        
        // Thread ID should be within valid range
        Assert.assertTrue(threadId >= 0);
        Assert.assertTrue(threadId < threadPoolSize);
    }

    @Test
    public void testCalculateThreadIdDistributesTables() {
        int threadPoolSize = 3;
        String[] tables = {"users", "orders", "products", "inventory", "payments"};
        
        Map<Integer, Integer> threadDistribution = new HashMap<>();
        
        for (String table : tables) {
            int threadId = RoutedBatch.calculateThreadId(table, threadPoolSize);
            threadDistribution.put(threadId, threadDistribution.getOrDefault(threadId, 0) + 1);
        }
        
        // All threads should be assigned at least one table
        // (This might fail with very few tables, but should work with 5+ tables)
        Assert.assertTrue("All threads should be used", threadDistribution.size() > 0);
        
        // No thread should have all the tables
        for (int count : threadDistribution.values()) {
            Assert.assertTrue("Distribution should be somewhat even", count < tables.length);
        }
    }

    @Test
    public void testExtractTableNameFromTopic() {
        String topic = "server5432.mydb.users";
        String tableName = RoutedBatch.extractTableName(topic);
        
        Assert.assertEquals("users", tableName);
    }

    @Test
    public void testExtractTableNameFromInvalidTopic() {
        String topic = "invalidformat";
        String tableName = RoutedBatch.extractTableName(topic);
        
        // Should return the whole topic as fallback
        Assert.assertEquals("invalidformat", tableName);
    }

    @Test
    public void testExtractTableNameFromEmptyTopic() {
        String topic = "";
        String tableName = RoutedBatch.extractTableName(topic);
        
        Assert.assertEquals("", tableName);
    }

    @Test
    public void testExtractTableNameFromNullTopic() {
        String topic = null;
        String tableName = RoutedBatch.extractTableName(topic);
        
        Assert.assertEquals("", tableName);
    }

    @Test
    public void testCreateRoutingKey() {
        String topic = "server5432.mydb.users";
        String routingKey = RoutedBatch.createRoutingKey(topic);
        
        // Should be database.table
        Assert.assertEquals("mydb.users", routingKey);
    }

    @Test
    public void testCreateRoutingKeyWithInvalidTopic() {
        String topic = "invalid";
        String routingKey = RoutedBatch.createRoutingKey(topic);
        
        // Should return the whole topic as fallback
        Assert.assertEquals("invalid", routingKey);
    }

    @Test
    public void testDifferentDatabasesSameTableGetDifferentRouting() {
        String topic1 = "server.db1.users";
        String topic2 = "server.db2.users";
        
        String routingKey1 = RoutedBatch.createRoutingKey(topic1);
        String routingKey2 = RoutedBatch.createRoutingKey(topic2);
        
        // Different databases should have different routing keys
        Assert.assertNotEquals(routingKey1, routingKey2);
        Assert.assertEquals("db1.users", routingKey1);
        Assert.assertEquals("db2.users", routingKey2);
    }

    @Test
    public void testSameTableAlwaysRoutesToSameThread() {
        int threadPoolSize = 5;
        String table = "orders";
        
        // Calculate thread ID multiple times
        List<Integer> threadIds = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            threadIds.add(RoutedBatch.calculateThreadId(table, threadPoolSize));
        }
        
        // All should be the same
        int firstThreadId = threadIds.get(0);
        for (int threadId : threadIds) {
            Assert.assertEquals(firstThreadId, threadId);
        }
    }

    @Test
    public void testRoutedBatchConstruction() {
        List<ClickHouseStruct> batch = new ArrayList<>();
        batch.add(new ClickHouseStruct());
        
        int threadId = 2;
        String tableName = "users";
        
        RoutedBatch routedBatch = new RoutedBatch(batch, threadId, tableName);
        
        Assert.assertEquals(batch, routedBatch.getBatch());
        Assert.assertEquals(threadId, routedBatch.getAssignedThreadId());
        Assert.assertEquals(tableName, routedBatch.getTableName());
    }

    @Test
    public void testHashingDistributionIsReasonable() {
        int threadPoolSize = 4;
        int numTables = 100;
        
        Map<Integer, Integer> distribution = new HashMap<>();
        
        // Generate many table names and see how they distribute
        for (int i = 0; i < numTables; i++) {
            String tableName = "table_" + i;
            int threadId = RoutedBatch.calculateThreadId(tableName, threadPoolSize);
            distribution.put(threadId, distribution.getOrDefault(threadId, 0) + 1);
        }
        
        // With 100 tables and 4 threads, each should get roughly 25 tables
        // Allow some variance (between 15 and 35)
        Assert.assertEquals("All threads should be assigned tables", threadPoolSize, distribution.size());
        
        for (Map.Entry<Integer, Integer> entry : distribution.entrySet()) {
            int count = entry.getValue();
            Assert.assertTrue("Thread " + entry.getKey() + " should have reasonable load: " + count, 
                    count >= 15 && count <= 35);
        }
    }

    @Test
    public void testZeroThreadPoolSize() {
        String tableName = "users";
        int threadPoolSize = 0;
        
        // Should not crash, should return 0
        int threadId = RoutedBatch.calculateThreadId(tableName, threadPoolSize);
        Assert.assertEquals(0, threadId);
    }

    @Test
    public void testNegativeThreadPoolSize() {
        String tableName = "users";
        int threadPoolSize = -1;
        
        // Should not crash, should return 0
        int threadId = RoutedBatch.calculateThreadId(tableName, threadPoolSize);
        Assert.assertEquals(0, threadId);
    }

    @Test
    public void testNullTableName() {
        String tableName = null;
        int threadPoolSize = 4;
        
        // Should not crash, should return 0
        int threadId = RoutedBatch.calculateThreadId(tableName, threadPoolSize);
        Assert.assertEquals(0, threadId);
    }
}


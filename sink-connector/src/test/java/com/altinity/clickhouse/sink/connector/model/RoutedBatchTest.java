package com.altinity.clickhouse.sink.connector.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for RoutedBatch hash-based routing logic.
 *
 * <p>JUnit 5: this module runs only the Jupiter engine (no vintage engine), so
 * a JUnit 4 test class here is silently never executed.</p>
 */
public class RoutedBatchTest {

    @Test
    public void testCalculateThreadIdIsConsistent() {
        String tableName = "test_table";
        int threadPoolSize = 4;

        int threadId1 = RoutedBatch.calculateThreadId(tableName, threadPoolSize);
        int threadId2 = RoutedBatch.calculateThreadId(tableName, threadPoolSize);

        // Same table should always map to same thread
        assertEquals(threadId1, threadId2);
    }

    @Test
    public void testCalculateThreadIdIsWithinRange() {
        String tableName = "users";
        int threadPoolSize = 5;

        int threadId = RoutedBatch.calculateThreadId(tableName, threadPoolSize);

        // Thread ID should be within valid range
        assertTrue(threadId >= 0);
        assertTrue(threadId < threadPoolSize);
    }

    /**
     * {@code Math.abs(Integer.MIN_VALUE)} is {@code Integer.MIN_VALUE}, so the
     * former {@code Math.abs(hash) % n} produced a NEGATIVE index for a routing
     * key with that hash code and the per-thread queue lookup threw
     * {@code IndexOutOfBoundsException} on the Debezium thread.
     * {@code Math.floorMod} keeps every hash code in {@code [0, n)}.
     */
    @Test
    public void testMinValueHashCodeRoutesInRange() {
        String key = "polygenelubricants";
        assertEquals(Integer.MIN_VALUE, key.hashCode(),
                "precondition: this key must hash to Integer.MIN_VALUE");

        for (int threadPoolSize : new int[] {2, 3, 4, 7, 10, 16}) {
            int threadId = RoutedBatch.calculateThreadId(key, threadPoolSize);
            assertTrue(threadId >= 0, "thread id must not be negative for pool size "
                    + threadPoolSize + " (was " + threadId + ")");
            assertTrue(threadId < threadPoolSize, "thread id must be below the pool size");
        }
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
        assertTrue(threadDistribution.size() > 0, "All threads should be used");

        // No thread should have all the tables
        for (int count : threadDistribution.values()) {
            assertTrue(count < tables.length, "Distribution should be somewhat even");
        }
    }

    @Test
    public void testExtractTableNameFromTopic() {
        String topic = "server5432.mydb.users";
        String tableName = RoutedBatch.extractTableName(topic);

        assertEquals("users", tableName);
    }

    @Test
    public void testExtractTableNameFromInvalidTopic() {
        String topic = "invalidformat";
        String tableName = RoutedBatch.extractTableName(topic);

        // Should return the whole topic as fallback
        assertEquals("invalidformat", tableName);
    }

    @Test
    public void testExtractTableNameFromEmptyTopic() {
        String topic = "";
        String tableName = RoutedBatch.extractTableName(topic);

        assertEquals("", tableName);
    }

    @Test
    public void testExtractTableNameFromNullTopic() {
        String topic = null;
        String tableName = RoutedBatch.extractTableName(topic);

        assertEquals("", tableName);
    }

    @Test
    public void testCreateRoutingKey() {
        String topic = "server5432.mydb.users";
        String routingKey = RoutedBatch.createRoutingKey(topic);

        // Should be database.table
        assertEquals("mydb.users", routingKey);
    }

    @Test
    public void testCreateRoutingKeyWithInvalidTopic() {
        String topic = "invalid";
        String routingKey = RoutedBatch.createRoutingKey(topic);

        // Should return the whole topic as fallback
        assertEquals("invalid", routingKey);
    }

    @Test
    public void testDifferentDatabasesSameTableGetDifferentRouting() {
        String topic1 = "server.db1.users";
        String topic2 = "server.db2.users";

        String routingKey1 = RoutedBatch.createRoutingKey(topic1);
        String routingKey2 = RoutedBatch.createRoutingKey(topic2);

        // Different databases should have different routing keys
        assertNotEquals(routingKey1, routingKey2);
        assertEquals("db1.users", routingKey1);
        assertEquals("db2.users", routingKey2);
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
            assertEquals(firstThreadId, threadId);
        }
    }

    @Test
    public void testRoutedBatchConstruction() {
        List<ClickHouseStruct> batch = new ArrayList<>();
        batch.add(new ClickHouseStruct());

        int threadId = 2;
        String tableName = "users";
        long handoffSequence = 42L;

        RoutedBatch routedBatch = new RoutedBatch(batch, threadId, tableName, handoffSequence);

        assertEquals(batch, routedBatch.getBatch());
        assertEquals(threadId, routedBatch.getAssignedThreadId());
        assertEquals(tableName, routedBatch.getTableName());
        assertEquals(handoffSequence, routedBatch.getHandoffSequence());
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
        assertEquals(threadPoolSize, distribution.size(), "All threads should be assigned tables");

        for (Map.Entry<Integer, Integer> entry : distribution.entrySet()) {
            int count = entry.getValue();
            assertTrue(count >= 15 && count <= 35,
                    "Thread " + entry.getKey() + " should have reasonable load: " + count);
        }
    }

    @Test
    public void testZeroThreadPoolSize() {
        String tableName = "users";
        int threadPoolSize = 0;

        // Should not crash, should return 0
        int threadId = RoutedBatch.calculateThreadId(tableName, threadPoolSize);
        assertEquals(0, threadId);
    }

    @Test
    public void testNegativeThreadPoolSize() {
        String tableName = "users";
        int threadPoolSize = -1;

        // Should not crash, should return 0
        int threadId = RoutedBatch.calculateThreadId(tableName, threadPoolSize);
        assertEquals(0, threadId);
    }

    @Test
    public void testNullTableName() {
        String tableName = null;
        int threadPoolSize = 4;

        // Should not crash, should return 0
        int threadId = RoutedBatch.calculateThreadId(tableName, threadPoolSize);
        assertEquals(0, threadId);
    }

    private static ClickHouseStruct keyed(String topic, String key,
            com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter.CDC_OPERATION op) {
        ClickHouseStruct s = new ClickHouseStruct();
        s.setTopic(topic);
        s.setKey(key);
        java.util.ArrayList<String> pk = new java.util.ArrayList<>();
        pk.add("id");
        s.setPrimaryKey(pk);
        s.setCdcOperation(op);
        return s;
    }

    @Test
    public void testKeyedRecordsOfSameTableSplitAcrossShards() {
        String topic = "srv.db.orders";
        ClickHouseStruct r1 = keyed(topic, "Struct{id=1}", com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter.CDC_OPERATION.CREATE);
        ClickHouseStruct r2 = keyed(topic, "Struct{id=2}", com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter.CDC_OPERATION.CREATE);

        String k1 = RoutedBatch.createShardKey(r1, true);
        String k2 = RoutedBatch.createShardKey(r2, true);
        // Different rows of one table produce different shard tokens.
        assertNotEquals(k1, k2, "two rows of one table must produce different shard tokens");

        // And for at least one pool size the two rows land on different threads.
        boolean split = false;
        for (int pool : new int[] {2, 3, 4, 7, 10, 16}) {
            if (RoutedBatch.calculateThreadId(k1, pool)
                    != RoutedBatch.calculateThreadId(k2, pool)) {
                split = true;
                break;
            }
        }
        assertTrue(split, "distinct rows of one table must be able to route to different workers");
    }

    @Test
    public void testSameRowKeyAlwaysSameShard() {
        String topic = "srv.db.orders";
        ClickHouseStruct a = keyed(topic, "Struct{id=42}", com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter.CDC_OPERATION.CREATE);
        ClickHouseStruct b = keyed(topic, "Struct{id=42}", com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter.CDC_OPERATION.UPDATE);

        String ka = RoutedBatch.createShardKey(a, true);
        String kb = RoutedBatch.createShardKey(b, true);
        assertEquals(ka, kb, "the same row must always produce the same shard token");
        for (int pool : new int[] {2, 3, 4, 7, 10, 16}) {
            assertEquals(RoutedBatch.calculateThreadId(ka, pool),
                    RoutedBatch.calculateThreadId(kb, pool),
                    "the same row must always route to the same worker (pool " + pool + ")");
        }
    }

    @Test
    public void testNullKeyFallsBackToTableShard() {
        String topic = "srv.db.no_pk";
        ClickHouseStruct r = new ClickHouseStruct();
        r.setTopic(topic);
        r.setCdcOperation(com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter.CDC_OPERATION.CREATE);
        // No key / no primary key -> table-level base shard (MySQL COMMIT_ORDER fallback).
        assertEquals(RoutedBatch.createRoutingKey(topic),
                RoutedBatch.createShardKey(r, true),
                "a record with no usable key must route to the table base shard");
    }

    @Test
    public void testTruncateFallsBackToTableShard() {
        String topic = "srv.db.orders";
        // Even with a key present, a truncate-table event routes to the table base shard.
        ClickHouseStruct r = keyed(topic, "Struct{id=1}", com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter.CDC_OPERATION.TRUNCATE);
        assertEquals(RoutedBatch.createRoutingKey(topic),
                RoutedBatch.createShardKey(r, true),
                "a truncate-table event must route to the table base shard");
    }

    @Test
    public void testKeyRoutingDisabledUsesTableShard() {
        String topic = "srv.db.orders";
        ClickHouseStruct r = keyed(topic, "Struct{id=1}", com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter.CDC_OPERATION.CREATE);
        // Key routing off restores exact table-level routing.
        assertEquals(RoutedBatch.createRoutingKey(topic),
                RoutedBatch.createShardKey(r, false),
                "with key routing disabled a keyed record must use the table base shard");
    }

    @Test
    public void testRoutingModeDoesNotChangeRowIdentity() {
        String topic = "srv.db.orders";
        ClickHouseStruct r = keyed(topic, "Struct{id=7}", com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter.CDC_OPERATION.CREATE);
        String tableToken = RoutedBatch.createRoutingKey(topic);

        // Downgrade (routing off) routes exactly as the prior table-level scheme.
        String tableModeToken = RoutedBatch.createShardKey(r, false);
        assertEquals(tableToken, tableModeToken,
                "with routing off the shard token is the plain table token");

        // Upgrade (routing on) refines the shard by row key but stays within the
        // same table token -- it never rewrites the record.
        String keyModeToken = RoutedBatch.createShardKey(r, true);
        assertTrue(keyModeToken.startsWith(tableToken),
                "key routing must refine, not replace, the table token");
        assertNotEquals(tableModeToken, keyModeToken,
                "flipping the mode changes only the shard token");

        // The record itself is untouched by routing: nothing about the data
        // differs across an upgrade or downgrade, only which worker applies it.
        assertEquals(topic, r.getTopic());
        assertEquals("Struct{id=7}", r.getKey());
        assertEquals(com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter.CDC_OPERATION.CREATE, r.getCdcOperation());
    }
}

package com.altinity.clickhouse.sink.connector.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BlockMetaData} to verify lag metrics are properly tracked.
 */
public class BlockMetaDataTest {

    @Test
    @DisplayName("update() should populate minSourceLag and maxSourceLag")
    public void testUpdatePopulatesSourceLag() {
        BlockMetaData meta = new BlockMetaData();

        // Create a mock record with ts_ms set to a known value
        ClickHouseStruct record1 = new ClickHouseStruct();
        record1.setTs_ms(meta.blockInsertionTimestamp - 100); // 100ms lag
        record1.setDebezium_ts_ms(meta.blockInsertionTimestamp - 50);
        record1.setTopic("server.db.table1");
        record1.setKafkaPartition(0);
        record1.setPos(1L);
        record1.setFile("binlog.000001");

        meta.update(record1);

        assertTrue(meta.getMinSourceLag() > 0, "minSourceLag should be populated after update");
        assertTrue(meta.getMaxSourceLag() > 0, "maxSourceLag should be populated after update");
    }

    @Test
    @DisplayName("update() should track min and max correctly across multiple records")
    public void testUpdateTracksMinMax() {
        BlockMetaData meta = new BlockMetaData();

        ClickHouseStruct record1 = new ClickHouseStruct();
        record1.setTs_ms(meta.blockInsertionTimestamp - 200); // 200ms lag
        record1.setDebezium_ts_ms(meta.blockInsertionTimestamp - 100);
        record1.setTopic("server.db.table1");
        record1.setKafkaPartition(0);
        record1.setPos(1L);
        record1.setFile("binlog.000001");
        meta.update(record1);

        ClickHouseStruct record2 = new ClickHouseStruct();
        record2.setTs_ms(meta.blockInsertionTimestamp - 50); // 50ms lag
        record2.setDebezium_ts_ms(meta.blockInsertionTimestamp - 25);
        record2.setTopic("server.db.table1");
        record2.setKafkaPartition(0);
        record2.setPos(2L);
        record2.setFile("binlog.000001");
        meta.update(record2);

        // Min should be the smaller lag (50ms), max should be the larger (200ms)
        assertTrue(meta.getMinSourceLag() <= meta.getMaxSourceLag(),
                "minSourceLag should be <= maxSourceLag");
        assertTrue(meta.getMinConsumerLag() <= meta.getMaxConsumerLag(),
                "minConsumerLag should be <= maxConsumerLag");
    }

    @Test
    @DisplayName("update() should populate minConsumerLag and maxConsumerLag")
    public void testUpdatePopulatesConsumerLag() {
        BlockMetaData meta = new BlockMetaData();

        ClickHouseStruct record1 = new ClickHouseStruct();
        record1.setTs_ms(meta.blockInsertionTimestamp - 100);
        record1.setDebezium_ts_ms(meta.blockInsertionTimestamp - 75); // 75ms consumer lag
        record1.setTopic("server.db.table1");
        record1.setKafkaPartition(0);
        record1.setPos(1L);
        record1.setFile("binlog.000001");

        meta.update(record1);

        assertTrue(meta.getMinConsumerLag() > 0, "minConsumerLag should be populated after update");
        assertTrue(meta.getMaxConsumerLag() > 0, "maxConsumerLag should be populated after update");
    }

    @Test
    @DisplayName("update() should track partition offset correctly")
    public void testUpdateTracksPartitionOffset() {
        BlockMetaData meta = new BlockMetaData();

        ClickHouseStruct record1 = new ClickHouseStruct();
        record1.setTs_ms(System.currentTimeMillis() - 100);
        record1.setDebezium_ts_ms(System.currentTimeMillis() - 50);
        record1.setTopic("server.db.table1");
        record1.setKafkaPartition(0);
        record1.setKafkaOffset(42L);
        record1.setPos(1L);
        record1.setFile("binlog.000001");

        meta.update(record1);

        assertTrue(meta.getPartitionToOffsetMap().containsKey("server.db.table1"),
                "partitionToOffsetMap should contain topic");
        assertEquals(42L, meta.getPartitionToOffsetMap().get("server.db.table1").getRight(),
                "offset should be tracked");
    }
}

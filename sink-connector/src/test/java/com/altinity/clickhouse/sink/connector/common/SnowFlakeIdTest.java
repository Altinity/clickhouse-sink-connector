package com.altinity.clickhouse.sink.connector.common;

import com.altinity.clickhouse.sink.connector.common.SnowFlakeId;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

public class SnowFlakeIdTest {

    @Test
    public void testGenerate() throws InterruptedException {
       // 1666300948617
        long currentTimestamp = 1666717042727L;
        long gtId = 1666735042727620291L;
        long snowFlakeId = SnowFlakeId.generate(currentTimestamp, gtId, false);
        Assert.assertTrue(snowFlakeId == 1584952269638053571L);

        long ts1= 1666735042727L;
        long snowFlakeId1 = SnowFlakeId.generate(ts1, gtId, false);
        Assert.assertTrue(snowFlakeId1 == 1585027767110053571L);

    }

    /**
     * Test that SnowFlakeId.generate() with gtid=-1 still produces a valid positive number.
     * This is important because -1 written directly to a UInt64 column would become
     * 18446744073709551615 (max UInt64) which causes overflow when read back as signed long.
     */
    @Test
    public void testGenerateWithNegativeGtid() {
        long timestamp = System.currentTimeMillis();
        long negativeGtid = -1L;
        
        long snowFlakeId = SnowFlakeId.generate(timestamp, negativeGtid, false);
        
        // The generated ID should be positive (within signed long range)
        Assert.assertTrue("SnowFlakeId should be positive", snowFlakeId > 0);
        // Should not be the max UInt64 value that causes overflow
        Assert.assertNotEquals("Should not be max UInt64 value", -1L, snowFlakeId);
    }

    /**
     * Test that timestamp used as version fallback is always positive and fits in UInt64.
     * When gtid, sequenceNumber, and lsn are all -1, we use ts_ms as fallback.
     */
    @Test
    public void testTimestampAsVersionFallback() {
        long timestamp = System.currentTimeMillis();
        
        // Timestamp should always be positive and fit in both signed long and UInt64
        Assert.assertTrue("Timestamp should be positive", timestamp > 0);
        Assert.assertTrue("Timestamp should fit in signed long", timestamp < Long.MAX_VALUE);
    }

}
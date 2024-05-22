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

}
package com.clickhouse.sink.connector.common;

import com.altinity.clickhouse.sink.connector.common.SnowFlakeId;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

public class SnowFlakeIdTest {

    @Test
    public void testGenerate() throws InterruptedException {
       // 1666300948617
        long currentTimestamp = 1666297437042L;
        long gtId = 600851475143L;
        long snowFlakeId = SnowFlakeId.generate(currentTimestamp, gtId);
        System.out.println("Snowflake ID" + snowFlakeId);

        long ts1 = System.currentTimeMillis();
        long snowFlakeId1 = SnowFlakeId.generate(ts1, gtId);

        Thread.sleep(4000);

        long ts2 = System.currentTimeMillis();
        long snowFlakeId2 = SnowFlakeId.generate(ts2, gtId);


        Assert.assertTrue(snowFlakeId2 > snowFlakeId1);
    }

}
package com.clickhouse.sink.connector.common;

import com.altinity.clickhouse.sink.connector.common.SnowFlakeId;
import org.junit.jupiter.api.Test;

public class SnowFlakeIdTest {

    @Test
    public void testGenerate() {
       // 1666300948617
        long currentTimestamp = 1666297437042L;
        long gtId = 600851475143L;
        long snowFlakeId = SnowFlakeId.generate(currentTimestamp, gtId);
        System.out.println("Snowflake ID" + snowFlakeId);
    }

}
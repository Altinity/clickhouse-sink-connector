package com.altinity.clickhouse.sink.connector.common;

/**
 * Snowflake ID implementation
 * 43 bits(timestamp in milliseconds since epoch)
 * 21 bits(transaction id of GTID)
 * https://en.wikipedia.org/wiki/Snowflake_ID
 */

public class SnowFlakeId {

    private static final int GTID_BITS = 23;

    public static long generate(long timestamp, long gtId) {
        long gtIdMask =  (1L << GTID_BITS) - 1;
        long timestampMask = ((1L << 41) - 1L);
        long timestampMasked =  timestamp & timestampMask ;
        long timestampShifted = timestampMasked << GTID_BITS;
        return timestampShifted | (gtId & gtIdMask);
    }
}
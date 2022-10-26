package com.altinity.clickhouse.sink.connector.common;

import java.util.BitSet;

/**
 * Snowflake ID implementation
 * 43 bits(timestamp in milliseconds since epoch)
 * 21 bits(transaction id of GTID)
 * https://en.wikipedia.org/wiki/Snowflake_ID
 */

public class SnowFlakeId {

    private static final int GTID_BITS = 23;

    private static final long SNOWFLAKE_EPOCH = 1288834974657L;
//    static constexpr long snowflake_epoch = 1288834974657L;

    public static long generate(long timestamp, long gtId) {
        long gtIdMask =  (1L << GTID_BITS) - 1L;

        long timestampMask = ((1L << 41) - 1L);
        long timestampMasked =  timestamp & timestampMask ;
        long timestampShifted = (timestampMasked << GTID_BITS) & 0xFF;

        //long timestampRotated = Long.rotateLeft(timestamp, 7);

        //return (long) timestampShifted | (gtId & gtIdMask);

        // 1. Create bitset with 64 bits
        BitSet result = new BitSet(64);

        // 2. Create bitset from long (timestamp) - 41 bits
        long tsDiff = timestamp - SNOWFLAKE_EPOCH;
        BitSet tsBitSet = BitSet.valueOf(new long[] {tsDiff});

        // 3. Create bitset from Gtid - 22 bits
        BitSet gtIdBitSet = BitSet.valueOf(new long[] {gtId});
        BitSet gtId22Bits = gtIdBitSet.get(0, 22);

        BitSet ts41Bits = tsBitSet.get(0, 41);
        // 4. Set bitset from 


        // Set Gtid in result.
        for(int i = 0; i <= 21; i++) {
            result.set(i, gtId22Bits.get(i));
        }
        int tsIndex = 0;
        for(int j = 22; j <= 62; j++) {
            result.set(j, ts41Bits.get(tsIndex++));
        }
        return result.toLongArray()[0];
    }
}
package com.altinity.clickhouse.sink.connector.common;

import java.util.BitSet;

/**
 * The SnowFlakeId class provides an implementation of the Snowflake ID generation algorithm.
 * <p>
 * Snowflake ID is a unique identifier that consists of several parts:
 * - 43 bits representing the timestamp (in milliseconds since the epoch).
 * - 21 bits representing the transaction ID of GTID (Global Transaction ID).
 * </p>
 * <p>
 * The algorithm is designed to generate unique IDs in a distributed system without the need for a central authority.
 * </p>
 * <p>
 * For more information on Snowflake ID, refer to:
 * https://en.wikipedia.org/wiki/Snowflake_ID
 * </p>
 */
public class SnowFlakeId {

    /**
     * The number of bits used for the GTID (Global Transaction ID).
     */
    private static final int GTID_BITS = 23;

    /**
     * The epoch used for Snowflake ID generation (starting point for the timestamp).
     * This is the timestamp when Twitter's Snowflake was created.
     */
    private static final long SNOWFLAKE_EPOCH = 1288834974657L;

    /**
     * Generates a Snowflake ID using the provided timestamp and GTID.
     * <p>
     * The generated ID consists of:
     * - 41 bits representing the timestamp (in milliseconds since the epoch).
     * - 22 bits representing the transaction ID of GTID.
     * </p>
     * <p>
     * If the {@code ignoreSnowflakeEpoch} flag is true, the epoch will be ignored and the
     * timestamp will be used as is.
     * </p>
     *
     * @param timestamp the timestamp in milliseconds since the epoch.
     * @param gtId the GTID (Global Transaction ID).
     * @param ignoreSnowflakeEpoch flag to ignore the Snowflake epoch.
     * @return the generated Snowflake ID as a long.
     */
    public static long generate(long timestamp, long gtId, boolean ignoreSnowflakeEpoch) {
        // 1. Create a bitset with 64 bits for the result.
        BitSet result = new BitSet(64);

        // 2. Create a bitset from the timestamp (41 bits).
        long tsDiff = timestamp - SNOWFLAKE_EPOCH;
        BitSet tsBitSet = BitSet.valueOf(new long[] {tsDiff});
        if (ignoreSnowflakeEpoch) {
            tsBitSet = BitSet.valueOf(new long[] {timestamp});
        }

        // 3. Create a bitset from the GTID (22 bits).
        BitSet gtIdBitSet = BitSet.valueOf(new long[] {gtId});
        BitSet gtId22Bits = gtIdBitSet.get(0, 22);

        // Get the timestamp's 41 bits.
        BitSet ts41Bits = tsBitSet.get(0, 41);

        // Set the GTID bits in the result.
        for (int i = 0; i <= 21; i++) {
            result.set(i, gtId22Bits.get(i));
        }

        // Set the timestamp bits in the result.
        int tsIndex = 0;
        for (int j = 22; j <= 62; j++) {
            result.set(j, ts41Bits.get(tsIndex++));
        }

        // Return the final Snowflake ID as a long value.
        long[] resultArray = result.toLongArray();
        return resultArray.length > 0 ? resultArray[0] : 0L;
    }
}

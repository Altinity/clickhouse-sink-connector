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
     * This is the timestamp when Twitter's Snowflake was created. Public so that the
     * startup version-floor seeding can decode the timestamp field of a stored
     * snowflake version ({@code (version >>> GTID_FIELD_BITS) + SNOWFLAKE_EPOCH}).
     */
    public static final long SNOWFLAKE_EPOCH = 1288834974657L;

    /**
     * Width of the transaction-number field below the timestamp in a generated id
     * (bits 0..21); the timestamp occupies bits 22..62.
     */
    public static final int GTID_FIELD_BITS = 22;

    /** Mask of the discriminator (GTID / offset) field: the low {@link #GTID_FIELD_BITS} bits. */
    public static final long DISCRIMINATOR_MASK = (1L << GTID_FIELD_BITS) - 1;

    /** One millisecond of the timestamp field, in id units ({@code 2^22}). */
    public static final long MILLISECOND = 1L << GTID_FIELD_BITS;

    /**
     * The id {@link #generate} produces for {@code (timestamp, discriminator)} with the
     * discriminator NOT truncated to {@link #GTID_FIELD_BITS} bits:
     * {@code (timestamp - SNOWFLAKE_EPOCH) * 2^22 + discriminator}.
     *
     * <p>For {@code 0 <= discriminator < 2^22} the result equals
     * {@code generate(timestamp, discriminator, false)} bit for bit, so values stay in
     * the snowflake domain older releases wrote. Above that the high part of the
     * discriminator carries into the timestamp field instead of being discarded: the
     * result is strictly increasing in the discriminator for a fixed timestamp and
     * non-decreasing in the timestamp, so a counter that grows in commit order (the
     * Kafka offset of one partition) never wraps (spec 02.01 section 3.1, path 4;
     * {@code Replication.VersionFloor.Domains.compose_strict_mono}).</p>
     *
     * @param timestamp     milliseconds since the Unix epoch, not before {@link #SNOWFLAKE_EPOCH}
     * @param discriminator a non-negative ordering counter
     * @return the id
     * @throws IllegalArgumentException if {@code timestamp} precedes the epoch or the
     *                                  discriminator is negative
     * @throws ArithmeticException      if the id does not fit in a signed 64-bit value
     */
    public static long compose(long timestamp, long discriminator) {
        if (timestamp < SNOWFLAKE_EPOCH || discriminator < 0) {
            throw new IllegalArgumentException("snowflake id needs a timestamp at or after "
                    + SNOWFLAKE_EPOCH + " and a non-negative discriminator; got timestamp "
                    + timestamp + ", discriminator " + discriminator);
        }
        return Math.addExact(Math.multiplyExact(timestamp - SNOWFLAKE_EPOCH, MILLISECOND), discriminator);
    }

    /**
     * The smallest timestamp {@code t >= minTimestamp} with
     * {@code generate(t, discriminator, false) >= floorVersion}.
     *
     * <p>The version sequence uses it to keep GTID versions in commit order inside one
     * effective millisecond: when the low 22 bits of the next transaction number are not
     * above the previous version's (a wrap of the field, or a different server UUID
     * after a failover) the timestamp field is moved forward by the least amount that
     * restores the order; the encoding itself is unchanged
     * ({@code Replication.VersionFloor.Domains.bumpTs_reaches},
     * {@code Replication.VersionFloor.Domains.bumpTs_least}).</p>
     *
     * @param minTimestamp  the timestamp the record would use without the bump (ms, at
     *                      or after {@link #SNOWFLAKE_EPOCH})
     * @param discriminator the GTID transaction number; only its low 22 bits count
     * @param floorVersion  the version the result must reach
     * @return the timestamp to feed {@link #generate}
     */
    public static long timestampReaching(long minTimestamp, long discriminator, long floorVersion) {
        long low = discriminator & DISCRIMINATOR_MASK;
        if (floorVersion <= low) {
            return minTimestamp;
        }
        // ceil((floorVersion - low) / 2^22) without overflow near Long.MAX_VALUE.
        long needed = SNOWFLAKE_EPOCH + Math.floorDiv(floorVersion - low - 1, MILLISECOND) + 1;
        return Math.max(minTimestamp, needed);
    }

    /**
     * The first millisecond whose ids all exceed {@code version}:
     * {@code floorDiv(version, 2^22) + SNOWFLAKE_EPOCH + 1}. Every id
     * {@code generate(t, d, false) <= version} has {@code t} below it
     * ({@code Replication.VersionFloor.Domains.below_snowflake_floor}).
     *
     * @param version a non-negative id
     * @return the timestamp slot strictly above it
     */
    public static long floorAbove(long version) {
        return Math.floorDiv(version, MILLISECOND) + SNOWFLAKE_EPOCH + 1;
    }

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
        return result.toLongArray()[0];
    }
}

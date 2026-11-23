package com.altinity.clickhouse.sink.connector.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The helpers the version sequence and the Kafka Connect fallback use (spec 02.01 section 3.1). */
public class SnowFlakeIdComposeTest {

    private static final long TS = 1_757_900_000_000L;

    @Test
    @DisplayName("compose equals generate below 2^22 and keeps increasing above it")
    public void composeExtendsGenerateWithoutWrapping() {
        for (long d : new long[] {0L, 1L, 900_000L, SnowFlakeId.DISCRIMINATOR_MASK}) {
            assertEquals(SnowFlakeId.generate(TS, d, false), SnowFlakeId.compose(TS, d));
        }
        assertTrue(SnowFlakeId.compose(TS, 1L << 22) > SnowFlakeId.compose(TS, (1L << 22) - 1));
        assertEquals(SnowFlakeId.compose(TS + 1, 0L), SnowFlakeId.compose(TS, 1L << 22),
                "the high part carries into the timestamp field");
        assertThrows(IllegalArgumentException.class, () -> SnowFlakeId.compose(TS, -1L));
        assertThrows(IllegalArgumentException.class, () -> SnowFlakeId.compose(SnowFlakeId.SNOWFLAKE_EPOCH - 1, 0L));
    }

    @Test
    @DisplayName("timestampReaching returns the least timestamp whose id reaches the floor")
    public void timestampReachingIsTheLeastSufficientTimestamp() {
        long floor = SnowFlakeId.generate(TS, 900_000L, false);
        assertEquals(TS, SnowFlakeId.timestampReaching(TS, 900_001L, floor + 1), "already above: no bump");
        assertEquals(TS, SnowFlakeId.timestampReaching(TS, 900_000L, floor), "a tie is reached");
        long bumped = SnowFlakeId.timestampReaching(TS, 7L, floor + 1);
        assertEquals(TS + 1, bumped, "a lower transaction number moves one millisecond");
        assertTrue(SnowFlakeId.generate(bumped, 7L, false) > floor);
        assertTrue(SnowFlakeId.generate(bumped - 1, 7L, false) <= floor, "one millisecond less is not enough");
        long wrapped = SnowFlakeId.timestampReaching(TS, 1L << 22, SnowFlakeId.generate(TS, (1L << 22) - 1, false) + 1);
        assertEquals(TS + 1, wrapped, "a wrap of the 22-bit field moves one millisecond");
        assertEquals(TS + 50, SnowFlakeId.timestampReaching(TS + 50, 7L, floor + 1), "never below the minimum");
    }

    @Test
    @DisplayName("floorAbove is the first millisecond whose ids all exceed the version")
    public void floorAboveBoundsEveryIdAtOrBelowTheVersion() {
        long version = SnowFlakeId.generate(TS, SnowFlakeId.DISCRIMINATOR_MASK, false);
        assertEquals(TS + 1, SnowFlakeId.floorAbove(version));
        assertEquals(TS + 1, SnowFlakeId.floorAbove(SnowFlakeId.generate(TS, 0L, false)));
        assertTrue(SnowFlakeId.generate(SnowFlakeId.floorAbove(version), 0L, false) > version);
    }
}

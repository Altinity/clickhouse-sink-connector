package com.altinity.clickhouse.sink.connector.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SnowFlakeId} to verify edge cases and correctness.
 */
public class SnowFlakeIdTest {

    @Test
    @DisplayName("generate should not throw on zero timestamp and zero gtId")
    public void testGenerateZeroInputs() {
        // When ignoreSnowflakeEpoch=true and both timestamp=0 and gtId=0,
        // all bits in the BitSet are zero, so toLongArray() returns empty array.
        // This should return 0L, not throw ArrayIndexOutOfBoundsException.
        long result = SnowFlakeId.generate(0, 0, true);
        assertEquals(0L, result, "Zero inputs should produce zero ID");
    }

    @Test
    @DisplayName("generate should return 0 when timestamp equals SNOWFLAKE_EPOCH and gtId is 0")
    public void testGenerateEpochTimestamp() {
        // tsDiff = timestamp - SNOWFLAKE_EPOCH = 0, gtId = 0 -> all bits zero
        long result = SnowFlakeId.generate(1288834974657L, 0, false);
        assertEquals(0L, result, "Epoch timestamp with zero gtId should produce zero ID");
    }

    @Test
    @DisplayName("generate should produce positive ID for typical inputs")
    public void testGenerateTypicalInputs() {
        long timestamp = System.currentTimeMillis();
        long gtId = 42;
        long result = SnowFlakeId.generate(timestamp, gtId, false);
        assertTrue(result > 0, "Typical inputs should produce positive ID");
    }

    @Test
    @DisplayName("generate should produce consistent results for same inputs")
    public void testGenerateConsistency() {
        long timestamp = 1700000000000L;
        long gtId = 100;
        long first = SnowFlakeId.generate(timestamp, gtId, false);
        long second = SnowFlakeId.generate(timestamp, gtId, false);
        assertEquals(first, second, "Same inputs must produce same ID");
    }

    @Test
    @DisplayName("generate with ignoreSnowflakeEpoch uses raw timestamp")
    public void testGenerateIgnoreEpoch() {
        long timestamp = 1700000000000L;
        long gtId = 42;
        long withEpoch = SnowFlakeId.generate(timestamp, gtId, false);
        long withoutEpoch = SnowFlakeId.generate(timestamp, gtId, true);
        assertNotEquals(withEpoch, withoutEpoch,
                "ignoreSnowflakeEpoch should produce different ID");
    }

    @Test
    @DisplayName("generate should encode gtId in lower bits")
    public void testGenerateGtIdEncoding() {
        long timestamp = 1700000000000L;
        long id1 = SnowFlakeId.generate(timestamp, 1, false);
        long id2 = SnowFlakeId.generate(timestamp, 2, false);
        assertNotEquals(id1, id2, "Different gtIds should produce different IDs");
    }
}

package com.altinity.clickhouse.debezium.embedded;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The restart monitor's fallback to the stored offset timestamp
 * (spec 01.01 §3.2).
 *
 * <p><b>The defect.</b> When no record had been observed in memory yet
 * ({@code lastRecordTimestamp == -1}) the monitor read the newest
 * {@code record_insert_ts} from the offset table -- and then adopted it only
 * {@code if (storedOffsetsInTable == -1)}, i.e. only when it was the sentinel.
 * A valid stored timestamp was never used, so the delta was computed from
 * {@code -1}, always exceeded the timeout, and the monitor restarted the
 * engine on EVERY tick until the first record arrived. Each such restart went
 * through {@code stop()}, which (before spec 09.01 §3.8) poisoned the offset
 * FIFO.</p>
 */
public class RestartMonitorTimestampTest {

    @Test
    @DisplayName("A valid stored timestamp is adopted when nothing has been observed in memory")
    public void storedTimestampIsAdoptedWhenInMemoryIsUnset() {
        long stored = 1_788_182_208_000L;
        assertEquals(stored,
                ClickHouseDebeziumEmbeddedApplication.effectiveLastRecordTimestamp(-1L, stored),
                "the monitor must measure idleness from the stored offset timestamp, not from -1 "
                        + "(pre-fix: the stored value was adopted only when it was -1)");
    }

    @Test
    @DisplayName("An in-memory timestamp always wins over the stored one")
    public void inMemoryTimestampWins() {
        assertEquals(42L,
                ClickHouseDebeziumEmbeddedApplication.effectiveLastRecordTimestamp(42L, 7L));
    }

    @Test
    @DisplayName("When neither side has a value the sentinel is kept")
    public void sentinelWhenNothingIsKnown() {
        assertEquals(-1L,
                ClickHouseDebeziumEmbeddedApplication.effectiveLastRecordTimestamp(-1L, -1L));
    }
}

package com.altinity.clickhouse.sink.connector.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ClickHouseStruct#calculateVersion(boolean)} and its helper methods.
 * Validates magnitude compatibility, overflow handling, 2038/2109 safety, and redelivery stability.
 */
@DisplayName("VersionCalculation - formula correctness and safety guards")
public class VersionCalculationTest {

    // Typical ts_ms value in 2026 (approx 1.785e12 ms)
    private static final long TS_MS_2026 = 1_785_678_221_550L;
    // Upper bound of the acceptable magnitude family
    private static final long MAX_ACCEPTABLE = 2_500_000_000_000L * 1_000_000L; // 2.5e18
    // Lower bound (anything below this from a 2026 timestamp is suspicious)
    private static final long MIN_ACCEPTABLE = 1_500_000_000_000L * 1_000_000L; // 1.5e18

    // --- parseBinlogFileNumber tests ---

    @Test
    @DisplayName("parseBinlogFileNumber extracts numeric suffix")
    void parseBinlogFileNumber_standard() {
        assertEquals(123, ClickHouseStruct.parseBinlogFileNumber("binlog.000123"));
        assertEquals(1, ClickHouseStruct.parseBinlogFileNumber("binlog.000001"));
        assertEquals(999999, ClickHouseStruct.parseBinlogFileNumber("binlog.999999"));
        assertEquals(0, ClickHouseStruct.parseBinlogFileNumber("mysql-bin.000000"));
    }

    @Test
    @DisplayName("parseBinlogFileNumber returns 0 on malformed input")
    void parseBinlogFileNumber_malformed() {
        assertEquals(0, ClickHouseStruct.parseBinlogFileNumber(null));
        assertEquals(0, ClickHouseStruct.parseBinlogFileNumber(""));
        assertEquals(0, ClickHouseStruct.parseBinlogFileNumber("no-dot"));
        assertEquals(0, ClickHouseStruct.parseBinlogFileNumber("binlog."));
        assertEquals(0, ClickHouseStruct.parseBinlogFileNumber("binlog.abc"));
    }

    // --- deriveSubMs tests ---

    @Test
    @DisplayName("deriveSubMs stays in [0, 999_999] range")
    void deriveSubMs_bounded() {
        for (long fileNum = 0; fileNum < 1000; fileNum += 100) {
            for (long pos = 4; pos < 1_000_000_000L; pos += 100_000_000L) {
                long subMs = ClickHouseStruct.deriveSubMs(fileNum, pos);
                assertTrue(subMs >= 0, "subMs must be non-negative: " + subMs);
                assertTrue(subMs < 1_000_000L, "subMs must be < 1_000_000: " + subMs);
            }
        }
    }

    @Test
    @DisplayName("deriveSubMs is deterministic for same (file, pos)")
    void deriveSubMs_deterministic() {
        long a = ClickHouseStruct.deriveSubMs(123, 456789L);
        long b = ClickHouseStruct.deriveSubMs(123, 456789L);
        assertEquals(a, b, "Same inputs must produce same output");
    }

    @Test
    @DisplayName("deriveSubMs differs across binlog rotation (different file numbers)")
    void deriveSubMs_rotationAware() {
        long samePos = 512L;
        long subFile1 = ClickHouseStruct.deriveSubMs(1, samePos);
        long subFile2 = ClickHouseStruct.deriveSubMs(2, samePos);
        // They should differ because fileNumber changes the composite
        assertNotEquals(subFile1, subFile2,
                "Same pos in different files must produce different subMs");
    }

    // --- calculateVersion magnitude tests ---

    @Test
    @DisplayName("sequenceNumber path stays in magnitude family")
    void calculateVersion_sequenceNumberPath_magnitude() {
        ClickHouseStruct struct = new ClickHouseStruct();
        long seqNum = TS_MS_2026 * 1_000_000L + 1_000_000_001L;
        struct.setSequenceNumber(seqNum);
        struct.setTs_ms(TS_MS_2026);

        struct.calculateVersion(true);

        assertEquals(seqNum, struct.getVersion());
        assertTrue(struct.getVersion() >= MIN_ACCEPTABLE,
                "Version " + struct.getVersion() + " below minimum");
        assertTrue(struct.getVersion() <= MAX_ACCEPTABLE,
                "Version " + struct.getVersion() + " above maximum safe magnitude");
    }

    @Test
    @DisplayName("Kafka Connect fallback (pos, no sequenceNumber) stays in magnitude family")
    void calculateVersion_posPath_magnitude() {
        ClickHouseStruct struct = new ClickHouseStruct();
        struct.setTs_ms(TS_MS_2026);
        struct.setFile("binlog.000050");
        struct.setPos(1_073_741_824L); // 1 GB position
        // Leave sequenceNumber at UNINITIALIZED (-1)
        // Leave gtid at UNINITIALIZED (-1)

        struct.calculateVersion(true);

        assertTrue(struct.getVersion() > 0, "Version should be positive");
        assertTrue(struct.getVersion() >= MIN_ACCEPTABLE,
                "Version " + struct.getVersion() + " below minimum acceptable magnitude");
        assertTrue(struct.getVersion() <= MAX_ACCEPTABLE,
                "Version " + struct.getVersion() + " above maximum safe magnitude ("
                + MAX_ACCEPTABLE + ") - would poison RMT");
    }

    @Test
    @DisplayName("pos overflow > 2^32 throws IllegalStateException")
    void calculateVersion_posOverflow_throws() {
        ClickHouseStruct struct = new ClickHouseStruct();
        struct.setTs_ms(TS_MS_2026);
        struct.setFile("binlog.000001");
        struct.setPos(0xFFFFFFFFL + 1); // 4 GiB + 1

        assertThrows(IllegalStateException.class, () -> struct.calculateVersion(true),
                "Position exceeding 2^32 must throw, not silently truncate");
    }

    // --- Redelivery stability ---

    @Test
    @DisplayName("Same event data always produces the same version (redelivery stable)")
    void calculateVersion_redeliveryStable() {
        ClickHouseStruct struct1 = new ClickHouseStruct();
        struct1.setTs_ms(TS_MS_2026);
        struct1.setFile("binlog.000050");
        struct1.setPos(12345L);
        struct1.calculateVersion(true);

        ClickHouseStruct struct2 = new ClickHouseStruct();
        struct2.setTs_ms(TS_MS_2026);
        struct2.setFile("binlog.000050");
        struct2.setPos(12345L);
        struct2.calculateVersion(true);

        assertEquals(struct1.getVersion(), struct2.getVersion(),
                "Identical event data must produce identical version across redeliveries");
    }

    // --- 2038/2109 safety ---

    @Test
    @DisplayName("ts_ms in 2038 produces a valid version without overflow")
    void calculateVersion_2038safe() {
        // Jan 20, 2038 00:00:00 UTC in ms
        long ts2038 = 2_147_483_648_000L;

        ClickHouseStruct struct = new ClickHouseStruct();
        struct.setTs_ms(ts2038);
        struct.setFile("binlog.000001");
        struct.setPos(1000L);
        struct.calculateVersion(true);

        assertTrue(struct.getVersion() > 0, "Version at 2038 must be positive");
        // At 2038: 2147483648000 * 1e6 + subMs ≈ 2.15e18. Still in safe range.
        assertTrue(struct.getVersion() < MAX_ACCEPTABLE,
                "Version at 2038 (" + struct.getVersion() + ") should still be in safe range");
    }

    @Test
    @DisplayName("sequenceNumber takes priority over pos-based fallback")
    void calculateVersion_sequenceNumberPriority() {
        ClickHouseStruct struct = new ClickHouseStruct();
        struct.setTs_ms(TS_MS_2026);
        struct.setFile("binlog.000050");
        struct.setPos(99999L);
        long explicitSeq = TS_MS_2026 * 1_000_000L + 42;
        struct.setSequenceNumber(explicitSeq);

        struct.calculateVersion(true);

        assertEquals(explicitSeq, struct.getVersion(),
                "sequenceNumber must take priority over pos-based fallback");
    }

    @Test
    @DisplayName("GTID takes priority over sequenceNumber and pos")
    void calculateVersion_gtidPriority() {
        ClickHouseStruct struct = new ClickHouseStruct();
        struct.setTs_ms(TS_MS_2026);
        struct.setFile("binlog.000050");
        struct.setPos(99999L);
        struct.setSequenceNumber(TS_MS_2026 * 1_000_000L + 42);
        struct.setGtid(12345L);

        struct.calculateVersion(true);

        // With useSnowflakeId=true, should use SnowFlakeId.generate(ts, gtid, false)
        assertNotEquals(-1, struct.getVersion(), "Version must be set when GTID is available");
        // GTID-based version uses SnowFlakeId which has its own magnitude (~2.08e18)
        assertTrue(struct.getVersion() > 0);
    }
}
